/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.atomic.AtomicLong

/** Finishes one buffer on the existing delivery worker, without another queue or sample conversion. */
internal class PipePcmConsumer(
    private val write: (ByteArray, Int, Int) -> Int,
    private val release: () -> Unit,
    private val awaitWritable: (Int) -> Unit = { Thread.sleep(it.toLong()) },
    private val nowNs: () -> Long = System::nanoTime,
) : LivePcmConsumer {
    private val lock = Any()
    private var closed = false
    private var formatted = false
    @Volatile private var sending = true
    private var lastTimestampUs = -1L
    private val bytes = AtomicLong()
    private val partials = AtomicLong()
    private val zeroes = AtomicLong()
    private val wouldBlocks = AtomicLong()
    private val maxDelay = AtomicLong()
    val writtenBytes: Long get() = bytes.get()
    val partialWrites: Long get() = partials.get()
    val zeroWrites: Long get() = zeroes.get()
    val wouldBlockWrites: Long get() = wouldBlocks.get()
    val maxWriteDelayMs: Double get() = maxDelay.get() / 1_000_000.0
    @Volatile var lastFailure: String? = null
        private set

    override fun onFormat(format: PcmFormat) = synchronized(lock) {
        check(!closed && format == BoundedPcmDelivery.FORMAT) { "Unexpected live PCM format" }
        formatted = true
    }

    override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
        synchronized(lock) {
            check(!closed && formatted) { "Live PCM output is not ready" }
            require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 16 * 480 * 2 &&
                presentationTimeUs >= 0 && presentationTimeUs >= lastTimestampUs) { "Invalid live PCM buffer" }
            if (!sending) return
        }
        val started = nowNs()
        var offset = 0
        try {
            while (offset < bytes.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("PCM pipe write interrupted")
                val elapsed = nowNs() - started
                check(elapsed in 0 until MAX_WRITE_NS) { "PCM pipe write exceeded 120 ms" }
                val accepted = synchronized(lock) {
                    if (closed || !sending) return
                    val remaining = bytes.size - offset
                    val count = try { write(bytes, offset, remaining) } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EAGAIN) throw error
                        wouldBlocks.incrementAndGet()
                        0
                    }
                    check(count in 0..remaining) { "PCM pipe rejected write" }
                    if (count == 0) zeroes.incrementAndGet()
                    else if (count < remaining) partials.incrementAndGet()
                    this.bytes.addAndGet(count.toLong())
                    count
                }
                offset += accepted
                if (accepted == 0) {
                    val remainingNs = MAX_WRITE_NS - (nowNs() - started)
                    check(remainingNs > 0) { "PCM pipe write exceeded 120 ms" }
                    // Poll outside the lock: owned stop/close never waits for a blocked writer.
                    awaitWritable(((remainingNs + 999_999) / 1_000_000).coerceAtMost(10).toInt())
                }
            }
            synchronized(lock) { if (!closed && sending) lastTimestampUs = presentationTimeUs }
        } catch (failure: Exception) {
            synchronized(lock) {
                if (closed || !sending) return
                lastFailure = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
            }
            throw failure
        } finally {
            val elapsed = (nowNs() - started).coerceAtLeast(0)
            maxDelay.accumulateAndGet(elapsed) { previous, value -> maxOf(previous, value) }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; release() }
    }

    /** End delivery before the root consumer closes, without signalling unexpected EOF to it. */
    fun stopSending() {
        sending = false
        // Wait only for an already-entered nonblocking write, never for poll or its deadline.
        synchronized(lock) { }
    }

    companion object {
        // Match the existing 16-AU delivery stall capacity; no extra queue is introduced.
        private const val MAX_WRITE_NS = 120_000_000L
        /** A one-way Unix socket pair gives public, verifiable kernel buffer limits. */
        fun open(): Pair<ParcelFileDescriptor, PipePcmConsumer> {
            val pair = ParcelFileDescriptor.createSocketPair()
            try {
                val getOption = Os::class.java.getMethod("getsockoptInt", java.io.FileDescriptor::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                for (endpoint in pair) {
                    val fd = endpoint.fileDescriptor
                    for (option in intArrayOf(OsConstants.SO_SNDBUF, OsConstants.SO_RCVBUF)) {
                        Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, option, 4096)
                        check(getOption.invoke(null, fd, OsConstants.SOL_SOCKET, option) as Int in 1..8192) {
                            "Live PCM socket buffer is too large"
                        }
                    }
                    Os.fcntlInt(fd, OsConstants.F_SETFL,
                        Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                    check(Os.fcntlInt(fd, OsConstants.F_GETFL, 0) and OsConstants.O_NONBLOCK != 0)
                }
                val output = pair[1]
                val poll = StructPollfd().apply {
                    fd = output.fileDescriptor
                    events = OsConstants.POLLOUT.toShort()
                }
                val polls = arrayOf(poll)
                return pair[0] to PipePcmConsumer(
                    { data, offset, count -> Os.write(output.fileDescriptor, data, offset, count) },
                    { output.close() },
                    { timeoutMs ->
                        poll.revents = 0
                        try { Os.poll(polls, timeoutMs) } catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EINTR) throw error
                        }
                        check(poll.revents.toInt() and
                            (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) == 0) {
                            "PCM pipe consumer disconnected"
                        }
                    })
            } catch (failure: Exception) {
                pair.forEach { runCatching { it.close() } }
                throw failure
            }
        }
    }
}
