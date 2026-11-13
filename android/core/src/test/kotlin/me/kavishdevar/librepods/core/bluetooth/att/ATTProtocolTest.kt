package me.kavishdevar.librepods.core.bluetooth.att

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ATTProtocolTest {
    private fun page(declaration: Int, handle: Int, properties: Int = 0x0a) =
        byteArrayOf(9, 21, declaration.toByte(), (declaration ushr 8).toByte(), properties.toByte(), handle.toByte(), (handle ushr 8).toByte()) +
            ATTHandle.TRANSPARENCY.uuid!!.attBytes()

    @Test fun loudSoundReductionDiscoveryUsesItsUuidAndAdvertisedWriteMethod() {
        for ((properties, opcode) in listOf(2 to null, 6 to 0x52, 10 to 0x12)) {
            var index = 0
            val characteristics = discoverATTSettings { _, _ -> when (index++) {
                0 -> byteArrayOf(7, 0x20, 1, 0x40, 1)
                1 -> byteArrayOf(9, 21, 0x30, 1, properties.toByte(), 0x35, 1) +
                    UUID.fromString("a48fec08-3921-43db-82aa-afbce8ebb4fb").attBytes()
                else -> byteArrayOf(1, 8, 0x40, 1, 0x0a)
            } }
            assertEquals(ATTCharacteristic(0x135, opcode), characteristics[ATTHandle.LOUD_SOUND_REDUCTION])
            assertEquals(1, characteristics.size)
        }
    }

    @Test fun discoveryRejectsInvalidRangesDuplicateUnreadableAndTruncatedDeclarations() {
        for (response in listOf(byteArrayOf(7, 0, 0, 0x40, 0), byteArrayOf(7, 0x40, 0, 0x20, 0), byteArrayOf(7, 1))) {
            assertThrows(ATTFailure::class.java) { discoverATTSettings { _, _ -> response } }
        }
        val invalid = listOf(page(0x20, 0x20), page(0x20, 0x41), page(0x20, 0x21, 8), page(0x20, 0x21).dropLast(1).toByteArray(),
            page(0x20, 0x21) + page(0x22, 0x23).copyOfRange(2, 23))
        for (page in invalid) {
            var index = 0
            assertThrows(ATTFailure::class.java) { discoverATTSettings { _, _ -> if (index++ == 0) byteArrayOf(7, 0x20, 0, 0x40, 0) else page } }
        }
    }

    @Test fun discoveryNoAttributeMustMatchRequestAndStayWithinRange() {
        val request = byteArrayOf(8, 0x20, 0, 0x40, 0, 3, 0x28)
        assertTrue(noATTAttribute(byteArrayOf(1, 8, 0x40, 0, 0x0a), request))
        assertFalse(noATTAttribute(byteArrayOf(1, 6, 0x20, 0, 0x0a), request))
        assertFalse(noATTAttribute(byteArrayOf(1, 8, 0x41, 0, 0x0a), request))
        assertFalse(noATTAttribute(byteArrayOf(1, 8, 0x20, 0, 0x0e), request))
    }

    @Test fun timeoutRetiresBearerAndLateReplyCannotCompleteAnotherRead() {
        var closed = false
        var sends = 0
        val bearer = ATTBearer(256, { sends++ }, { _, _ -> closed = true }, { true })
        assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x0a, 0x35, 0), 0x0b, 1) }
        assertTrue(closed)
        bearer.receive(byteArrayOf(0x0b, 1))
        assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x0a, 0x36, 0), 0x0b, 1) }
        assertEquals(1, sends)
    }

    @Test fun mismatchedErrorAndMalformedAcknowledgementRetireBearer() {
        for (reply in listOf(byteArrayOf(1, 0x12, 0x36, 0, 0x0e), byteArrayOf(0x13, 0))) {
            var closed = false
            lateinit var bearer: ATTBearer
            bearer = ATTBearer(256, { bearer.receive(reply) }, { _, _ -> closed = true }, { true })
            assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x12, 0x35, 0, 1), 0x13) }
            assertTrue(closed)
        }
    }

    @Test fun idleIndicationsConfirmReceiptWithoutTrustingTheirHandleOrValue() {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        val bearer = ATTBearer(48, { sent += it }, { _, _ -> closed = true }, { true })
        val captured = byteArrayOf(0x1d, 0x51, 0, 1, 0, 0x17, 0, 2, 0, 1, 0, 1)
        for (indication in listOf(captured, byteArrayOf(0x1d, 0, 0), byteArrayOf(0x1d, -1, -1),
                ByteArray(48).also { it[0] = 0x1d; it[1] = 0x51 })) {
            bearer.receive(indication)
        }
        assertFalse(closed)
        assertTrue(bearer.isCurrent)
        assertEquals(4, sent.size)
        sent.forEach { assertArrayEquals(byteArrayOf(0x1e), it) }
    }

    @Test fun indicationsDoNotCompleteOrRetirePendingReadsAndWrites() {
        for ((request, opcode, response) in listOf(
                Triple(byteArrayOf(0x0a, 0x35, 0), 0x0b, byteArrayOf(0x0b, 7)),
                Triple(byteArrayOf(0x12, 0x35, 0, 1), 0x13, byteArrayOf(0x13)))) {
            val sent = mutableListOf<ByteArray>()
            var closed = false
            lateinit var bearer: ATTBearer
            bearer = ATTBearer(48, { pdu ->
                sent += pdu
                if (pdu[0] == request[0]) {
                    bearer.receive(byteArrayOf(0x1d, 0x51, 0, 1))
                    assertFalse(closed)
                    assertEquals(2, sent.size)
                    bearer.receive(response)
                }
            }, { _, _ -> closed = true }, { true })
            assertArrayEquals(response, bearer.request(request, opcode))
            assertFalse(closed)
            assertArrayEquals(byteArrayOf(0x1e), sent[1])
        }
    }

    @Test fun indicationConfirmationDoesNotChangeNotificationHandling() {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        val bearer = ATTBearer(48, { sent += it }, { _, _ -> closed = true }, { true })
        bearer.receive(byteArrayOf(0x1b, 0x51, 0, 1))
        assertTrue(sent.isEmpty())
        assertFalse(closed)
    }

    @Test fun malformedIndicationEnvelopesRetireWithoutConfirmation() {
        for (pdu in listOf(byteArrayOf(), byteArrayOf(0x1d), byteArrayOf(0x1d, 0x51),
                ByteArray(49).also { it[0] = 0x1d })) {
            val sent = mutableListOf<ByteArray>()
            var closed = false
            val bearer = ATTBearer(48, { sent += it }, { _, _ -> closed = true }, { true })
            bearer.receive(pdu)
            assertTrue(closed)
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun retiredOrReplacedOwnersCannotConfirmIndications() {
        for (retire in listOf(false, true)) {
            val sent = mutableListOf<ByteArray>()
            var current = true
            val bearer = ATTBearer(48, { sent += it }, { _, _ -> }, { current })
            if (retire) bearer.retire() else current = false
            bearer.receive(byteArrayOf(0x1d, 0x51, 0, 1))
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun aFailedConfirmationRetiresTheBearerAndReleasesItsPendingRead() {
        var closed = false
        var retryable = false
        lateinit var bearer: ATTBearer
        bearer = ATTBearer(48, { pdu ->
            if (pdu[0] == 0x0a.toByte()) bearer.receive(byteArrayOf(0x1d, 0x51, 0, 1))
            else throw IOException("write failed")
        }, { retry, _ -> closed = true; retryable = retry }, { true })
        assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x0a, 0x35, 0), 0x0b) }
        assertTrue(closed)
        assertTrue(retryable)
        assertFalse(bearer.isCurrent)
    }

    @Test fun requestAndCommandWritesSerializeWithConcurrentConfirmations() {
        for (request in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val acknowledged = CountDownLatch(1)
            val receiving = CountDownLatch(1)
            val writing = AtomicBoolean()
            val workers = Executors.newFixedThreadPool(2)
            val bearer = ATTBearer(48, { pdu ->
                assertTrue("Concurrent ATT writes", writing.compareAndSet(false, true))
                try {
                    if (pdu[0] == 0x1e.toByte()) acknowledged.countDown()
                    else { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                } finally { writing.set(false) }
            }, { _, _ -> release.countDown() }, { true })
            try {
                val writer = workers.submit<Boolean> {
                    if (request) bearer.request(byteArrayOf(0x0a, 0x35, 0), 0x0b).contentEquals(byteArrayOf(0x0b, 7))
                    else bearer.command(byteArrayOf(0x52, 0x35, 0, 1)) { true }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val reader = workers.submit {
                    receiving.countDown()
                    bearer.receive(byteArrayOf(0x1d, 0x51, 0, 1))
                }
                assertTrue(receiving.await(2, TimeUnit.SECONDS))
                assertFalse(acknowledged.await(50, TimeUnit.MILLISECONDS))
                release.countDown()
                assertTrue(acknowledged.await(2, TimeUnit.SECONDS))
                reader.get(2, TimeUnit.SECONDS)
                if (request) bearer.receive(byteArrayOf(0x0b, 7))
                assertTrue(writer.get(2, TimeUnit.SECONDS))
                assertTrue(bearer.isCurrent)
            } finally { bearer.retire(); release.countDown(); workers.shutdownNow() }
        }
    }

    @Test fun retiringCanCloseAndUnblockAConfirmationWrite() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val bearer = ATTBearer(48, {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }, { _, _ -> closed.countDown(); release.countDown() }, { true })
        try {
            val reader = workers.submit { bearer.receive(byteArrayOf(0x1d, 0x51, 0, 1)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            workers.submit { bearer.retire() }.get(1, TimeUnit.SECONDS)
            assertEquals(0L, closed.count)
            reader.get(1, TimeUnit.SECONDS)
            assertFalse(bearer.isCurrent)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun queuedConfirmationRechecksTheOwnerBeforeWriting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receiving = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val acknowledged = AtomicBoolean()
        val workers = Executors.newFixedThreadPool(2)
        val bearer = ATTBearer(48, { pdu ->
            if (pdu[0] == 0x1e.toByte()) acknowledged.set(true)
            else { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        }, { _, _ -> release.countDown() }, current::get)
        try {
            val writer = workers.submit { bearer.command(byteArrayOf(0x52, 0x35, 0, 1)) { true } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val reader = workers.submit {
                receiving.countDown()
                bearer.receive(byteArrayOf(0x1d, 0x51, 0, 1))
            }
            assertTrue(receiving.await(2, TimeUnit.SECONDS))
            current.set(false)
            release.countDown()
            reader.get(2, TimeUnit.SECONDS)
            writer.get(2, TimeUnit.SECONDS)
            assertFalse(acknowledged.get())
        } finally { bearer.retire(); release.countDown(); workers.shutdownNow() }
    }
}
