package me.kavishdevar.librepods.dongle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DongleVolumeAuthorityTest {
    private fun snapshot(flags: Int = 17, confirmed: Int = 64, desired: Int = 255, token: Long = 0,
        remaining: Long = 0, usbGeneration: Long = 3) = ByteArray(24).also {
        it[0] = 1; it[1] = flags.toByte(); it[2] = confirmed.toByte(); it[3] = desired.toByte()
        DongleProtocol.put32(it, 4, 7); DongleProtocol.put32(it, 8, token)
        DongleProtocol.put32(it, 12, remaining); DongleProtocol.put32(it, 16, usbGeneration)
    }
    private fun rejected(data: ByteArray) {
        try { DongleProtocol.physicalVolume(data); fail("Malformed volume snapshot was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun physicalReadbackIsSeparateFromPendingSetAndOtherClientAuthority() {
        val report = DongleProtocol.physicalVolume(snapshot(flags = 31, desired = 90, token = 12, remaining = 4_000))
        assertEquals(64, report.confirmed); assertEquals(90, report.desired)
        assertTrue(report.pending); assertTrue(report.inflight); assertTrue(report.authorityActive)
        assertEquals(12L, report.ownToken); assertEquals(7L, report.observationRevision)
        assertNull(DongleProtocol.physicalVolume(snapshot(flags = 25, remaining = 5000)).ownToken)
        assertNull(DongleProtocol.physicalVolume(snapshot(flags = 0, confirmed = 255)).confirmed)
    }

    @Test fun malformedOrInconsistentSnapshotsFailClosed() {
        rejected(snapshot().copyOf(23))
        rejected(snapshot().also { it[0] = 2 })
        rejected(snapshot().also { it[20] = 1 })
        rejected(snapshot(flags = 49))
        rejected(snapshot(confirmed = 128))
        rejected(snapshot(flags = 16))
        rejected(snapshot(flags = 19))
        rejected(snapshot(desired = 12))
        rejected(snapshot(token = 3))
        rejected(snapshot(flags = 25, token = 3, remaining = 0))
        rejected(snapshot(flags = 25, token = 3, remaining = 5001))
        rejected(snapshot(flags = 9, token = 3, remaining = 5000))
        rejected(snapshot(flags = 25, token = 3, remaining = 5000, usbGeneration = 2))
    }

    @Test fun requestEncodingHasOnlyTheDocumentedActionsAndPhysicalRange() {
        assertArrayEquals(byteArrayOf(17, 0, 0, 0, 0, 0, 0), DongleProtocol.volumeAuthorityRequest(0))
        assertArrayEquals(byteArrayOf(17, 3, 0x78, 0x56, 0x34, 0x12, 127),
            DongleProtocol.volumeAuthorityRequest(3, 0x12345678, 127))
        for ((action, token, level) in listOf(Triple(4, 1L, 0), Triple(0, 1L, 0), Triple(1, 0L, 0),
            Triple(1, 1L, 1), Triple(3, 1L, 128))) {
            try { DongleProtocol.volumeAuthorityRequest(action, token, level); fail("Invalid request was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun oldFirmwareNeverReceivesTheNewFeature() = runBlocking {
        var writes = 0
        val client = DongleSession({ operation, id, _ ->
            if (operation == 2) writes++
            assertEquals(2, id)
            DongleProtocol.header(2)
        })
        try { client.readPhysicalVolume(); fail("Missing capability was accepted") }
        catch (_: IllegalStateException) { }
        assertEquals(0, writes)
    }

    @Test fun rfcommCannotAcquireUsbAuthority() = runBlocking {
        var exchanges = 0
        val client = DongleSession({ _, _, _ -> exchanges++; error("No transport request expected") })
        try { client.volumeAuthority(0, 3); fail("Bluetooth acquired USB authority") }
        catch (_: IllegalStateException) { }
        assertEquals(0, exchanges)
    }
}
