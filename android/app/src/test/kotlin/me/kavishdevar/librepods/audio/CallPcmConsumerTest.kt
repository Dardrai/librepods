package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CallPcmConsumerTest {
    private class Output : LivePcmConsumer {
        val frames = mutableListOf<ByteArray>()
        var closed = 0
        override fun onFormat(format: PcmFormat) {}
        override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) { frames += bytes.copyOf() }
        override fun close() { closed++ }
    }
    private fun header(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun framesBoundedPcmWithoutChangingItsSamples() {
        val sink = Output(); val consumer = CallPcmConsumer(sink) {}
        consumer.onFormat(PcmFormat(64000, 1)); consumer.updateEpoch(1, false, 0)
        val pcm = ByteArray(1000) { it.toByte() }
        consumer.onPcm(pcm, 0)
        assertEquals(listOf(992, 72), sink.frames.map { it.size })
        val first = header(sink.frames[0])
        assertEquals(CallPcmConsumer.MAGIC, first.int); assertEquals(1, first.int)
        assertEquals(960, first.int); assertEquals(0, first.int); assertEquals(1L, first.long); assertEquals(0L, first.long)
        assertArrayEquals(pcm, sink.frames.flatMap { it.drop(32) }.toByteArray())
        assertEquals(480L, header(sink.frames[1]).getLong(24))
    }

    @Test fun unmuteDropsQueuedSpeechAndTrimsTheCrossingBuffer() {
        val sink = Output(); val consumer = CallPcmConsumer(sink) {}
        consumer.onFormat(PcmFormat(64000, 1)); consumer.updateEpoch(1, false, 0)
        consumer.updateEpoch(2, true, 480)
        consumer.onPcm(ByteArray(960) { 1 }, 0)
        consumer.updateEpoch(3, false, 500)
        consumer.onPcm(ByteArray(960) { 2 }, 0) // Queue from before mute; cannot acquire epoch3.
        val crossing = ByteArray(960) { it.toByte() }
        consumer.onPcm(crossing, 7500)
        assertEquals(1, sink.frames.size)
        val frame = sink.frames.single()
        assertEquals(3L, header(frame).getLong(16)); assertEquals(500L, header(frame).getLong(24))
        assertArrayEquals(crossing.copyOfRange(40, 960), frame.copyOfRange(32, frame.size))
    }

    @Test fun aMuteDuringAnInFlightFrameKeepsItsOldEpochAndDropsTheRest() {
        val frames = mutableListOf<ByteArray>(); lateinit var consumer: CallPcmConsumer
        val output = object : LivePcmConsumer {
            override fun onFormat(format: PcmFormat) {}
            override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
                frames += bytes.copyOf(); consumer.updateEpoch(2, true, 960)
            }
            override fun close() {}
        }
        consumer = CallPcmConsumer(output) {}
        consumer.onFormat(PcmFormat(64000, 1)); consumer.updateEpoch(1, false, 0)
        consumer.onPcm(ByteArray(1920), 0)
        assertEquals(1, frames.size); assertEquals(1L, header(frames.single()).getLong(16))
    }

    @Test fun rejectsEpochReversalAndStopsWithoutClosingRootInputEarly() {
        val sink = Output(); var stopped = 0; val consumer = CallPcmConsumer(sink) { stopped++ }
        consumer.onFormat(PcmFormat(64000, 1)); consumer.updateEpoch(3, false, 0)
        assertThrows(IllegalArgumentException::class.java) { consumer.updateEpoch(2, false, 0) }
        assertThrows(IllegalArgumentException::class.java) { consumer.updateEpoch(3, true, 0) }
        consumer.stopSending(); consumer.onPcm(ByteArray(960), 0)
        assertEquals(1, stopped); assertEquals(0, sink.closed); assertTrue(sink.frames.isEmpty())
        consumer.close(); consumer.close(); assertEquals(1, sink.closed)
    }
}
