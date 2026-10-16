package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ATTBearerTest {
    private val readTransparency = byteArrayOf(0x0A, 0x18, 0x00)
    private val readHearing = byteArrayOf(0x0A, 0x2A, 0x00)

    @Test fun concurrentReadsAreSerializedAndKeepTheirOwnReplies() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val bearer = ATTBearer({ sent.add(it) }, {}, { true })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<ByteArray?> { bearer.request(readTransparency, 0x0B, 2000) }
            assertArrayEquals(readTransparency, sent.poll(1, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val second = workers.submit<ByteArray?> {
                entered.countDown()
                bearer.request(readHearing, 0x0B, 2000)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(sent.poll(50, TimeUnit.MILLISECONDS))
            bearer.receive(byteArrayOf(0x0B, 0x11))
            assertArrayEquals(byteArrayOf(0x0B, 0x11), first.get(1, TimeUnit.SECONDS))
            assertArrayEquals(readHearing, sent.poll(1, TimeUnit.SECONDS))
            bearer.receive(byteArrayOf(0x0B, 0x22))
            assertArrayEquals(byteArrayOf(0x0B, 0x22), second.get(1, TimeUnit.SECONDS))
        } finally { bearer.retire(); workers.shutdownNow() }
    }

    @Test fun timeoutRetiresBearerAndLateReplyCannotSatisfyAnotherRead() {
        val closes = AtomicInteger()
        val writes = AtomicInteger()
        val old = ATTBearer({ writes.incrementAndGet() }, { closes.incrementAndGet() }, { true })
        assertNull(old.request(readTransparency, 0x0B, 1))
        assertFalse(old.isCurrent)
        old.receive(byteArrayOf(0x0B, 0x55))
        assertNull(old.request(readHearing, 0x0B, 10))
        old.retire()
        assertEquals(1, closes.get())
        assertEquals(1, writes.get())
        lateinit var replacement: ATTBearer
        replacement = ATTBearer({ replacement.receive(byteArrayOf(0x0B, 0x66)) }, {}, { true })
        assertArrayEquals(byteArrayOf(0x0B, 0x66), replacement.request(readHearing, 0x0B, 100))
    }

    @Test fun onlyErrorForCurrentRequestCompletesItAndNextRequestStillWorks() {
        var turn = 0
        lateinit var bearer: ATTBearer
        bearer = ATTBearer({
            if (turn++ == 0) {
                bearer.receive(byteArrayOf(0x01, 0x12, 0x18, 0x00, 0x05))
                bearer.receive(byteArrayOf(0x01, 0x0A, 0x2A, 0x00, 0x05))
                bearer.receive(byteArrayOf(0x01, 0x0A, 0x18, 0x00, 0x05))
            } else bearer.receive(byteArrayOf(0x0B, 0x44))
        }, {}, { true })
        assertNull(bearer.request(readTransparency, 0x0B, 100))
        assertTrue(bearer.isCurrent)
        assertArrayEquals(byteArrayOf(0x0B, 0x44), bearer.request(readHearing, 0x0B, 100))
    }

    @Test fun wrongErrorsAndUnsolicitedPacketsAreNeverStoredForLater() {
        lateinit var bearer: ATTBearer
        bearer = ATTBearer({
            bearer.receive(byteArrayOf(0x01, 0x0A, 0x2A, 0x00, 0x05))
            repeat(10000) { bearer.receive(byteArrayOf(0x1B, 0x18, 0x00, 0x77)) }
            bearer.receive(byteArrayOf(0x0B, 0x33))
        }, {}, { true })
        repeat(10000) {
            bearer.receive(byteArrayOf(0x0B, 0x55))
            bearer.receive(byteArrayOf(0x1B, 0x18, 0x00, 0x77))
        }
        assertArrayEquals(byteArrayOf(0x0B, 0x33), bearer.request(readTransparency, 0x0B, 100))
    }

    @Test fun disconnectWakesWaiterAndCannotRetireReplacement() {
        val sent = CountDownLatch(1)
        val closes = AtomicInteger()
        val old = ATTBearer({ sent.countDown() }, { closes.incrementAndGet() }, { true })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<ByteArray?> { old.request(readTransparency, 0x0B, 10000) }
            assertTrue(sent.await(1, TimeUnit.SECONDS))
            old.retire()
            assertNull(pending.get(1, TimeUnit.SECONDS))
            val replacementCloses = AtomicInteger()
            val replacement = ATTBearer({}, { replacementCloses.incrementAndGet() }, { true })
            old.retire()
            assertEquals(1, closes.get())
            assertEquals(0, replacementCloses.get())
            assertTrue(replacement.isCurrent)
        } finally { old.retire(); worker.shutdownNow() }
    }

    @Test fun retiredOrCancelledQueuedRequestNeverWrites() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val bearer = ATTBearer({ sent.add(it) }, {}, { true })
        val allowed = AtomicBoolean(true)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<ByteArray?> { bearer.request(readTransparency, 0x0B, 2000) }
            assertNotNull(sent.poll(1, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val queued = workers.submit<ByteArray?> {
                entered.countDown()
                bearer.request(byteArrayOf(0x12, 0x2A, 0x00, 0x77), 0x13, 2000) { allowed.get() }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            allowed.set(false)
            bearer.receive(byteArrayOf(0x0B, 0x11))
            assertNotNull(first.get(1, TimeUnit.SECONDS))
            assertNull(queued.get(1, TimeUnit.SECONDS))
            assertTrue(sent.isEmpty())
            assertTrue(bearer.isCurrent)
        } finally { bearer.retire(); workers.shutdownNow() }
    }

    @Test fun failedWriteRetiresBearer() {
        val closes = AtomicInteger()
        val bearer = ATTBearer({ throw IllegalStateException("closed") }, { closes.incrementAndGet() }, { true })
        assertNull(bearer.request(readTransparency, 0x0B, 100))
        assertFalse(bearer.isCurrent)
        assertEquals(1, closes.get())
    }

    @Test fun changedTransportCannotAcceptResponseOrStartRequest() {
        val active = AtomicBoolean(false)
        val writes = AtomicInteger()
        val bearer = ATTBearer({ writes.incrementAndGet() }, {}, { active.get() })
        assertNull(bearer.request(readTransparency, 0x0B, 100))
        assertEquals(0, writes.get())
    }
}
