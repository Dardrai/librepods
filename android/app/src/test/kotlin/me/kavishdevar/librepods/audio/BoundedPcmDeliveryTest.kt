package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedPcmDeliveryTest {
    @Test fun deliversImmediatelyWithPhysicalClockAndUnchangedSamples() {
        val samples = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1)
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failures = AtomicInteger()
        val closes = AtomicInteger()
        var receivedFormat: PcmFormat? = null
        var pcm: ByteArray? = null
        var timestamp = -1L
        val delivery = BoundedPcmDelivery(object : LivePcmConsumer {
            override fun onFormat(format: PcmFormat) { receivedFormat = format }
            override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
                pcm = bytes
                timestamp = presentationTimeUs
                received.countDown()
            }
            override fun close() { closes.incrementAndGet(); closed.countDown() }
        }, { true }, { failures.incrementAndGet() })
        try {
            assertTrue(delivery.offer(samples, 7_500))
            assertTrue("Must drain without a startup fill", received.await(1, TimeUnit.SECONDS))
            assertEquals(PcmFormat(64_000, 1), receivedFormat)
            assertArrayEquals(samples, pcm)
            assertEquals(7_500L, timestamp)
            assertNotSame(samples, pcm)
        } finally { delivery.close(); delivery.close() }
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertEquals(1, closes.get())
        assertEquals(0, failures.get())
        assertFalse(delivery.offer(samples, 15_000))
    }

    @Test fun aBlockedConsumerCannotBlockDecoderAndOverflowStopsInsteadOfGrowingLatency() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failures = AtomicInteger()
        val calls = AtomicInteger()
        val delivery = BoundedPcmDelivery(object : LivePcmConsumer {
            override fun onFormat(format: PcmFormat) = Unit
            override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
                calls.incrementAndGet()
                entered.countDown()
                release.await()
            }
            override fun close() { closed.countDown() }
        }, { true }, { failures.incrementAndGet() })
        try {
            val pcm = ByteArray(480 * 2)
            assertTrue(delivery.offer(pcm, 0))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(16) { assertTrue(delivery.offer(pcm, (it + 1) * 7_500L)) }
            assertFalse(delivery.offer(pcm, 17 * 7_500L))
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
            assertEquals(1, calls.get())
        } finally { release.countDown(); delivery.close() }
    }

    @Test fun byteBudgetAlsoBoundsBatchedDecoderOutput() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = CountDownLatch(1)
        val delivery = BoundedPcmDelivery(object : LivePcmConsumer {
            override fun onFormat(format: PcmFormat) = Unit
            override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) { entered.countDown(); CountDownLatch(1).await() }
            override fun close() { closed.countDown() }
        }, { true }, { failure.countDown() })
        try {
            assertTrue(delivery.offer(ByteArray(960), 0))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(delivery.offer(ByteArray(960 * 16), 7_500))
            assertFalse(delivery.offer(ByteArray(2), 127_500))
            assertTrue(failure.await(1, TimeUnit.SECONDS))
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        } finally { delivery.close() }
    }

    @Test fun sinkFailureAndStaleOwnerDiscardQueuedAudioAndCloseOnce() {
        for (throws in listOf(false, true)) {
            val current = AtomicBoolean(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val calls = AtomicInteger()
            val failures = AtomicInteger()
            val closes = AtomicInteger()
            val delivery = BoundedPcmDelivery(object : LivePcmConsumer {
                override fun onFormat(format: PcmFormat) = Unit
                override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
                    calls.incrementAndGet()
                    entered.countDown()
                    release.await()
                    if (throws) error("sink failed")
                }
                override fun close() { closes.incrementAndGet(); closed.countDown() }
            }, current::get, { failures.incrementAndGet() })
            try {
                assertTrue(delivery.offer(ByteArray(960), 0))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertTrue(delivery.offer(ByteArray(960), 7_500))
                if (!throws) current.set(false)
                release.countDown()
                assertTrue(closed.await(1, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
                assertEquals(1, closes.get())
                assertEquals(if (throws) 1 else 0, failures.get())
            } finally { release.countDown(); delivery.close() }
        }
    }
}
