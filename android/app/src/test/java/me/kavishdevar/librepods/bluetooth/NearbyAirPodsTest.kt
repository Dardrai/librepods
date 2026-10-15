package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class NearbyAirPodsTest {
    private fun report(model: Int = 0x2720, open: Boolean = true): ByteArray = ByteArray(27).apply {
        this[0] = 7
        this[1] = 25
        this[2] = 1
        this[3] = (model shr 8).toByte()
        this[4] = model.toByte()
        this[5] = 0x35
        this[8] = if (open) 0x31 else 0x38
    }

    @Test fun acceptsOnlyCompleteKnownLidReports() {
        val good = report()
        assertEquals(NearbyAirPods.Event(0x2720, true), NearbyAirPods.lidEvent(good))
        assertEquals(NearbyAirPods.Event(0x2720, false), NearbyAirPods.lidEvent(report(open = false)))
        for (size in 0 until good.size) assertNull(NearbyAirPods.lidEvent(good.copyOf(size)))
        for ((offset, value) in listOf(1 to 24, 1 to 26, 2 to 0, 2 to 7, 5 to 0x20)) {
            assertNull(NearbyAirPods.lidEvent(good.copyOf().also { it[offset] = value.toByte() }))
        }
        assertNull(NearbyAirPods.lidEvent(report(0x0a20)))
        assertNull(NearbyAirPods.lidEvent(report(0xffff)))
        assertNull(NearbyAirPods.lidEvent(ByteArray(255)))
    }

    @Test fun validatesWholeCompoundAdvertisementAndRejectsDuplicateRecords() {
        val compound = byteArrayOf(0x10, 1, 0x42) + report() + byteArrayOf(0x15, 0)
        assertNotNull(NearbyAirPods.lidEvent(compound))
        assertNull(NearbyAirPods.lidEvent(compound + byteArrayOf(0)))
        assertNull(NearbyAirPods.lidEvent(report() + report()))
    }

    @Test fun siblingsDoNotRepeatInvitationsAndLidCloseRearmsThem() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        val closed = open.copy(lidOpen = false)
        assertEquals(open, visits.observe(open, 0, false))
        assertNull(visits.observe(open, 100, false))
        assertEquals(closed, visits.observe(closed, 200, false))
        assertNull(visits.observe(closed, 201, false))
        assertEquals(open, visits.observe(open, 300, false))
        assertNull(visits.observe(open, 400, false))
        assertEquals(listOf(closed), visits.expire(30_400))
        assertEquals(emptyList<NearbyAirPods.Event>(), visits.expire(30_500))
        assertEquals(open, visits.observe(open, 30_600, false))
    }

    @Test fun trustedBondSuppressesInvitationWithoutSuppressingOtherModels() {
        val visits = NearbyAirPods.Visits()
        val ours = NearbyAirPods.Event(0x2720, true)
        assertNull(visits.observe(ours, 0, true))
        assertEquals(NearbyAirPods.Event(0x1920, true), visits.observe(NearbyAirPods.Event(0x1920, true), 1, false))
        visits.observe(ours.copy(lidOpen = false), 2, true)
        assertEquals(ours, visits.observe(ours, 3, false))
    }

    @Test fun closingOneModelDoesNotCancelANewerInvitation() {
        val state = NearbyAirPods.Session()
        val first = state.open(0x2720)
        val second = state.open(0x1920)
        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
        assertFalse(state.close(0x2720))
        assertTrue(state.isCurrent(second))
        assertTrue(state.close(0x1920))
        assertFalse(state.isCurrent(second))
        state.open(0x1920)
        assertFalse(state.isCurrent(second))
    }

    @Test fun namesAreOnlyCandidateHintsAndExcludeUnrelatedDeviceFamilies() {
        assertTrue(NearbyAirPods.matchesName(0x2720, "AirPods Pro"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "AirPods Pro 3"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Dylan’s AirPods Pro"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "AirPods 4"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "AirPods 4"))
        assertFalse(NearbyAirPods.matchesName(0x1920, "AirPods Pro"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "AirPods Max"))
        assertFalse(NearbyAirPods.matchesName(0xffff, "AirPods"))
        assertFalse(NearbyAirPods.matchesName(0x2720, null))
        assertFalse(NearbyAirPods.matchesName(0x2720, "Pixel Buds Pro"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "Proctor’s AirPods"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "Proctor’s AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "Maxwell’s AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Maxwell’s AirPods Pro"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "AirPods Pro Max"))
    }

    @Test fun onlyASoleStableClassicIdentityCanPairAutomatically() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        assertFalse(discovery.observe("unrelated", "Pixel Buds Pro", 0))
        assertNull(discovery.automatic(10_000))
        assertTrue(discovery.observe("AA:BB:CC:DD:EE:01", "AirPods Pro", 100))
        assertNull(discovery.automatic(2_099))
        discovery.observe("AA:BB:CC:DD:EE:01", "AirPods Pro", 1_000)
        assertNull(discovery.automatic(15_000))
        discovery.finish()
        assertEquals("AA:BB:CC:DD:EE:01", discovery.automatic(15_000))
        assertFalse(discovery.observe("AA:BB:CC:DD:EE:02", "AirPods Pro", 15_001))
    }

    @Test fun aSecondCandidateAfterTwoSecondsPreventsAutomaticPairing() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        discovery.observe("AA:BB:CC:DD:EE:01", "AirPods Pro", 100)
        assertNull(discovery.automatic(2_100))
        discovery.observe("AA:BB:CC:DD:EE:02", "AirPods Pro", 10_000)
        discovery.finish()
        assertNull(discovery.automatic(20_000))
    }

    @Test fun twoClassicAddressesStayAmbiguousRegardlessOfOrderOrRepeatedNames() {
        for (addresses in listOf(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01"))) {
            val discovery = NearbyAirPods.Discovery(0x2720)
            for (index in 0..12) {
                discovery.observe(addresses[index % 2], "AirPods Pro", index * 1_000L)
            }
            discovery.finish()
            assertNull(discovery.automatic(20_000))
        }
    }

    @Test fun reopeningTheSameModelInvalidatesPendingDiscoveryAndBondCallbacks() {
        val session = NearbyAirPods.Session()
        val oldToken = session.open(0x2720)
        session.close(0x2720)
        val newToken = session.open(0x2720)
        assertFalse(session.isCurrent(oldToken))
        assertTrue(session.isCurrent(newToken))
        val replacedToken = session.open(0x2720)
        assertFalse(session.isCurrent(newToken))
        assertTrue(session.isCurrent(replacedToken))
        session.close()
        assertFalse(session.isCurrent(replacedToken))
    }

    @Test fun freshPresenceKeepsAnInvitationFromExpiringAtTheOldDeadline() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        assertEquals(open, visits.observe(open, 0, false))
        assertNull(visits.observe(open, 29_999, false))
        assertTrue(visits.expire(30_000).isEmpty())
        assertTrue(visits.expire(59_998).isEmpty())
        assertEquals(listOf(open.copy(lidOpen = false)), visits.expire(59_999))
    }
}
