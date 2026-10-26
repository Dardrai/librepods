/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The call route alone uses framing: a mute edge must never retag queued speech as new audio. */
internal class CallPcmConsumer internal constructor(private val output: LivePcmConsumer,
    private val stopOutput: () -> Unit) : LivePcmConsumer {
    private val lock = Any()
    private var epoch = 0L
    private var muted = true
    private var floor = 0L
    private var lastFrame = -1L
    private var formatted = false
    private var closed = false
    private var stopped = false

    fun updateEpoch(epoch: Long, muted: Boolean, receivedSampleFloor: Long) = synchronized(lock) {
        require(epoch > 0 && epoch >= this.epoch && receivedSampleFloor >= 0)
        if (epoch == this.epoch) {
            require(this.muted == muted) { "A mute change requires a new epoch" }
            floor = maxOf(floor, receivedSampleFloor)
        } else {
            this.epoch = epoch
            this.muted = muted
            floor = maxOf(floor, receivedSampleFloor)
        }
    }

    override fun onFormat(format: PcmFormat) = synchronized(lock) {
        check(!closed && format == BoundedPcmDelivery.FORMAT)
        output.onFormat(format)
        formatted = true
    }

    override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 16 * 480 * 2 &&
            presentationTimeUs >= 0 && presentationTimeUs <= (Long.MAX_VALUE - 999) / 64)
        // Inverse of floor(frames * 1e6 / 64000), with rounding up to the actual sample boundary.
        val firstFrame = (presentationTimeUs * 64 + 999) / 1000
        synchronized(lock) {
            check(!closed && formatted)
            require(firstFrame >= lastFrame) { "Microphone time moved backwards" }
            lastFrame = firstFrame
        }
        var offset = 0
        while (offset < bytes.size / 2) {
            val frame = synchronized(lock) {
                if (closed || stopped || muted || epoch == 0L) return
                val skip = (floor - (firstFrame + offset)).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                offset += minOf(skip, bytes.size / 2 - offset)
                if (offset >= bytes.size / 2) return
                val count = minOf(480, bytes.size / 2 - offset)
                ByteBuffer.allocate(32 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(MAGIC); putInt(1); putInt(count * 2); putInt(0)
                    putLong(epoch); putLong(firstFrame + offset)
                    put(bytes, offset * 2, count * 2)
                }.array().also { offset += count }
            }
            // A racing mute may leave an old-epoch frame in flight. The reader discards it;
            // finish the frame so stream framing cannot become ambiguous after a partial write.
            output.onPcm(frame, presentationTimeUs)
        }
    }

    fun stopSending() { synchronized(lock) { stopped = true }; stopOutput() }
    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; output.close() }
    }

    companion object {
        internal const val MAGIC = 0x4d43504c
        fun open(): Pair<ParcelFileDescriptor, CallPcmConsumer> {
            val (input, pipe) = PipePcmConsumer.open()
            return input to CallPcmConsumer(pipe, pipe::stopSending)
        }
    }
}
