/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Session-owned, gain-neutral PCM16 LE at the AirPods' physical 64 kHz mono clock.
 * Callbacks run serially off the decoder thread and must respond to interruption.
 * A successful start transfers ownership; close is called exactly once, including on failure.
 */
internal interface LivePcmConsumer : AutoCloseable {
    fun onFormat(format: PcmFormat)
    fun onPcm(bytes: ByteArray, presentationTimeUs: Long)
}

/** Capacity is a stall limit, never a fill target: each buffer is delivered immediately. */
internal class BoundedPcmDelivery(
    private val consumer: LivePcmConsumer,
    private val isCurrent: () -> Boolean,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private data class Buffer(val bytes: ByteArray, val presentationTimeUs: Long)
    private val queue = ArrayBlockingQueue<Buffer>(16)
    private val queuedBytes = AtomicInteger()
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val worker = Thread(::deliver, "AirPods live PCM").apply { isDaemon = true }

    init { worker.start() }

    fun offer(bytes: ByteArray, presentationTimeUs: Long): Boolean {
        if (closed.get() || !isCurrent()) return false
        if (bytes.isEmpty() || bytes.size % FORMAT.frameSize != 0 || presentationTimeUs < 0 || bytes.size > MAX_BYTES) {
            fail("Invalid live microphone PCM")
            return false
        }
        val size = bytes.size
        if (queuedBytes.addAndGet(size) > MAX_BYTES) {
            queuedBytes.addAndGet(-size)
            fail("Live microphone consumer could not keep up")
            return false
        }
        if (!queue.offer(Buffer(bytes.copyOf(), presentationTimeUs))) {
            queuedBytes.addAndGet(-size)
            fail("Live microphone consumer could not keep up")
            return false
        }
        // Close may race the producer after its first check.
        if (closed.get()) clearQueue()
        return !closed.get()
    }

    private fun deliver() {
        try {
            var announced = false
            while (!closed.get() && isCurrent()) {
                val buffer = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                queuedBytes.addAndGet(-buffer.bytes.size)
                if (closed.get() || !isCurrent()) break
                if (!announced) {
                    consumer.onFormat(FORMAT)
                    announced = true
                }
                if (!closed.get() && isCurrent()) consumer.onPcm(buffer.bytes, buffer.presentationTimeUs)
            }
        } catch (_: InterruptedException) {
            if (!closed.get()) fail("Live microphone consumer was interrupted")
        } catch (_: Exception) {
            fail("Live microphone consumer failed")
        } finally {
            closed.set(true)
            clearQueue()
            try { consumer.close() } catch (_: Exception) { fail("Live microphone consumer did not close cleanly") }
        }
    }

    private fun fail(message: String) {
        close()
        if (failed.compareAndSet(false, true)) onFailure(message)
    }

    private fun clearQueue() {
        while (true) queuedBytes.addAndGet(-(queue.poll() ?: return).bytes.size)
    }

    override fun close() {
        closed.set(true)
        clearQueue()
        worker.interrupt()
    }

    companion object {
        val FORMAT = PcmFormat(AirPodsMicrophoneClock.PHYSICAL_RATE, 1)
        // 16 access units, at most 120 ms queued, with one buffer being consumed.
        private const val MAX_BYTES = 16 * AirPodsMicrophoneClock.SAMPLES_PER_UNIT * 2
    }
}
