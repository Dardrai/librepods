package me.kavishdevar.librepods.core.bluetooth.aacp

import org.junit.Assert.*
import org.junit.Test

class PacketSafetyTest {
    @Test fun onlyCompleteKnownSuccessfulServiceFourResponsesAuthorizeSettings() {
        val accepted = byteArrayOf(1, 0, 4, 0, 0, 0, 1, 0, 3, 0) + ByteArray(8) { 0xff.toByte() }
        assertTrue(successfulSettingsConnectResponse(accepted))
        assertTrue(successfulSettingsConnectResponse(accepted.copyOf().also { it[8] = 2 }))
        for (length in (0..17) + 19) assertFalse(successfulSettingsConnectResponse(accepted.copyOf(length)))
        for ((offset, value) in listOf(0 to 4, 1 to 1, 2 to 0, 3 to 1, 4 to 0x85, 5 to 1, 6 to 2, 7 to 1, 8 to 4, 9 to 1))
            assertFalse(successfulSettingsConnectResponse(accepted.copyOf().also { it[offset] = value.toByte() }))
    }

    @Test fun rawWritesAndReceivedMessagesRejectShortOrMalformedHeadersWithoutIndexingThem() {
        val message = byteArrayOf(4, 0, 4, 0, 9, 0)
        for (length in 0..5) {
            assertFalse(validRawAacpPacket(message.copyOf(length)))
            assertFalse(validAacpMessage(message.copyOf(length)))
        }
        assertTrue(validRawAacpPacket(message)); assertTrue(validAacpMessage(message))
        for (index in listOf(1, 3, 5)) assertFalse(validRawAacpPacket(message.copyOf().also { it[index] = 1 }))
        for (index in listOf(0, 2)) assertFalse(validAacpMessage(message.copyOf().also { it[index] = 1 }))
        assertFalse(validRawAacpPacket(ByteArray(65_536)))
    }
}
