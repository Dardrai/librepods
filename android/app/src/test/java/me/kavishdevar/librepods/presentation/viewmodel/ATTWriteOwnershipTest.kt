package me.kavishdevar.librepods.presentation.viewmodel

import org.junit.Assert.*
import org.junit.Test
import me.kavishdevar.librepods.bluetooth.ATTBearer
import me.kavishdevar.librepods.bluetooth.ATTHandles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ATTWriteOwnershipTest {
    @Test fun writesRequireOriginalSessionAndEveryDeviceIdentity() {
        val original = Any()
        assertTrue(attWriteMatches("A", "A", "A", "A", "A", original, original))
        assertFalse(attWriteMatches("A", "B", "A", "A", "A", original, original))
        assertFalse(attWriteMatches("A", "A", "B", "A", "A", original, original))
        assertFalse(attWriteMatches("A", "A", "A", "B", "A", original, original))
        assertFalse(attWriteMatches("A", "A", "A", "A", "B", original, original))
        assertFalse(attWriteMatches("A", "A", "A", "A", "A", original, Any()))
        assertFalse(attWriteMatches("A", "A", "A", "A", "A", original, null))
        assertFalse(attWriteMatches("A", "A", "A", "A", "A", null, null))
    }

    @Test fun olderReportPreservesNewerFullPendingProfile() {
        val first = byteArrayOf(1, 10, 20)
        val latest = byteArrayOf(1, 10, 30)
        val initial = AirPodsUiState(requestedATT = mapOf(ATTHandles.HEARING_AID to latest))
        val oldReport = initial.withATTReport(ATTHandles.HEARING_AID, first)
        assertArrayEquals(first, oldReport.hearingAidData)
        assertArrayEquals(latest, oldReport.requestedATT[ATTHandles.HEARING_AID])
        val confirmed = oldReport.withATTReport(ATTHandles.HEARING_AID, latest.copyOf())
        assertArrayEquals(latest, confirmed.hearingAidData)
        assertTrue(confirmed.requestedATT.isEmpty())
    }

    @Test fun queuedWriteCannotReachNewPairOrReconnectedSession() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val bearer = ATTBearer({ sent.add(it) }, {}, { true })
        val session = Any()
        val currentSession = AtomicReference<Any>(session)
        val selected = AtomicReference("A")
        val workers = Executors.newFixedThreadPool(2)
        try {
            val read = workers.submit<ByteArray?> { bearer.request(byteArrayOf(0x0A, 0x18, 0), 0x0B, 2000) }
            assertNotNull(sent.poll(1, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val write = workers.submit<ByteArray?> {
                entered.countDown()
                bearer.request(byteArrayOf(0x12, 0x18, 0, 0x55), 0x13, 2000) {
                    attWriteMatches("A", selected.get(), selected.get(), selected.get(), selected.get(), session, currentSession.get())
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            selected.set("B")
            currentSession.set(Any())
            bearer.receive(byteArrayOf(0x0B, 0x11))
            assertNotNull(read.get(1, TimeUnit.SECONDS))
            assertNull(write.get(1, TimeUnit.SECONDS))
            assertTrue(sent.isEmpty())
        } finally { bearer.retire(); workers.shutdownNow() }
    }
}
