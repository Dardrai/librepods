package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.CustomEqPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CustomEq
import org.junit.Assert.*
import org.junit.Test

class ControlReportsTest {
    private val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG

    @Test fun requestsRemainSeparateUntilAMatchingDeviceReportArrives() {
        val id = ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG
        val original = AppleState().withControlReport(id, byteArrayOf(2, 0, 0, 0))
        val requested = original.withControlRequest(id, byteArrayOf(1), original.controlStates[id])
        assertArrayEquals(byteArrayOf(2), requested.controlStates[id])
        assertArrayEquals(byteArrayOf(1), requested.requestedControls[id])
        val different = requested.withControlReport(id, byteArrayOf(2))
        assertTrue(id in different.requestedControls)
        val confirmed = different.withControlReport(id, byteArrayOf(1, 0, 0, 0))
        assertArrayEquals(byteArrayOf(1), confirmed.controlStates[id])
        assertFalse(id in confirmed.requestedControls)
    }

    @Test fun freshConversationReportsRetireSilentChoicesEvenWhenTheyDisagree() {
        for (reported in listOf(1, 2)) {
            val original = AppleState().withControlReport(id, byteArrayOf(reported.toByte()))
            val sameChoice = original.withControlRequest(id, byteArrayOf(reported.toByte()), original.controlStates[id])
            assertArrayEquals(byteArrayOf(reported.toByte()), sameChoice.requestedControls[id])
            val requested = original.withControlRequest(id, byteArrayOf((3 - reported).toByte()), original.controlStates[id])
            val report = requested.withControlReport(id, byteArrayOf(reported.toByte(), 0, 0, 0))
            assertArrayEquals(byteArrayOf(reported.toByte()), report.controlStates[id])
            assertFalse(id in report.requestedControls)
            assertSame(report, report.withControlRequest(id, byteArrayOf((3 - reported).toByte()), original.controlStates[id]))
        }
    }

    @Test fun reportReceivedDuringAWriteCannotBeOverwrittenByItsCompletion() {
        val original = AppleState().withControlReport(id, byteArrayOf(2))
        val beforeWrite = original.controlStates[id]
        val reported = original.withControlReport(id, byteArrayOf(1))
        assertSame(reported, reported.withControlRequest(id, byteArrayOf(2), beforeWrite))
        assertTrue(reported.requestedControls.isEmpty())
    }

    @Test fun validAdaptiveStrengthReportsRetireRequestsEvenWhenRounded() {
        val strength = ControlCommandIdentifier.AUTO_ANC_STRENGTH
        val original = AppleState().withControlReport(strength, byteArrayOf(50))
        val beforeWrite = original.controlStates[strength]
        val requested = original.withControlRequest(strength, byteArrayOf(25), beforeWrite)
        for (reportedValue in listOf(0, 50, 100)) {
            val report = requested.withControlReport(strength, byteArrayOf(reportedValue.toByte(), 0, 0, 0))
            assertArrayEquals(byteArrayOf(reportedValue.toByte()), report.controlStates[strength])
            assertFalse(strength in report.requestedControls)
            assertSame("A report received during the write remains authoritative", report,
                report.withControlRequest(strength, byteArrayOf(25), beforeWrite))
        }
    }

    @Test fun invalidAdaptiveStrengthReportsPreserveConfirmedAndPendingValues() {
        val strength = ControlCommandIdentifier.AUTO_ANC_STRENGTH
        val original = AppleState().withControlReport(strength, byteArrayOf(50))
        val requested = original.withControlRequest(strength, byteArrayOf(25), original.controlStates[strength])
        for (invalid in listOf(byteArrayOf(), byteArrayOf(101), byteArrayOf(50, 1), byteArrayOf(0xff.toByte()),
            byteArrayOf(50, 0, 0, 0, 0))) {
            assertSame(original, original.withControlReport(strength, invalid))
            assertSame(requested, requested.withControlReport(strength, invalid))
        }
    }

    @Test fun invalidBooleanReportsPreserveConfirmedValuesWithAndWithoutAPendingChoice() {
        for (id in listOf(ControlCommandIdentifier.ALLOW_OFF_OPTION, ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifier.EAR_DETECTION_CONFIG)) {
            val original = AppleState().withControlReport(id, byteArrayOf(1))
            val requested = original.withControlRequest(id, byteArrayOf(2), original.controlStates[id])
            for (invalid in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(3), byteArrayOf(1, 1),
                byteArrayOf(2, 0, 0, 0, 0), byteArrayOf(0xff.toByte()))) {
                assertSame(original, original.withControlReport(id, invalid))
                assertSame(requested, requested.withControlReport(id, invalid))
            }
            for (valid in listOf(1, 2)) {
                val confirmed = requested.withControlReport(id, byteArrayOf(valid.toByte(), 0, 0, 0))
                assertArrayEquals(byteArrayOf(valid.toByte()), confirmed.controlStates[id])
                val retainsRequest = id == ControlCommandIdentifier.EAR_DETECTION_CONFIG && valid != 2
                assertEquals(retainsRequest, id in confirmed.requestedControls)
                if (retainsRequest) assertArrayEquals(byteArrayOf(2), confirmed.requestedControls[id])
            }
        }
    }

    @Test fun controlAndEqPacketsRejectMalformedLengthsModesAndOversizedValues() {
        for (size in listOf(0, 1, 4, 6)) assertThrows(IllegalArgumentException::class.java) {
            ControlCommand.fromAACPPayload(ByteArray(size))
        }
        assertThrows(IllegalArgumentException::class.java) { ControlCommand(id, ByteArray(5)).toAACPPayload() }
        assertThrows(IllegalArgumentException::class.java) { CustomEq(0, 50, 50, 50) }
        val eq = CustomEq(2, 80, 30, 65)
        val packet = CustomEqPacket.create(eq).rawPacket
        assertEquals(0x63, packet[4].toInt())
        assertEquals(eq, CustomEqPacket.parse(packet).customEq)
        for (offset in listOf(4, 5, 6, 7, 8)) assertThrows(IllegalArgumentException::class.java) {
            CustomEqPacket.parse(packet.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() })
        }
    }
}
