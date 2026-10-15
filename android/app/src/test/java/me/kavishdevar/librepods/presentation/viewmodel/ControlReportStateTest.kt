package me.kavishdevar.librepods.presentation.viewmodel

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import org.junit.Assert.*
import org.junit.Test

class ControlReportStateTest {
    @Test fun coarseAdaptiveReportsDoNotConfirmOrEraseTheRequestedStrength() {
        val id = ControlCommandIdentifiers.AUTO_ANC_STRENGTH
        val requested = AirPodsUiState(requestedControls = mapOf(id to byteArrayOf(69)))
        val reported = requested.withControlReport(id, byteArrayOf(50))
        assertArrayEquals(byteArrayOf(50), reported.controlStates[id])
        assertArrayEquals(byteArrayOf(69), reported.requestedControls[id])
        val confirmed = reported.withControlReport(id, byteArrayOf(69, 0, 0, 0))
        assertFalse(id in confirmed.requestedControls)
        assertArrayEquals(byteArrayOf(69), confirmed.controlStates[id])
    }

    @Test fun aReportCanOnlyClearItsMatchingRequest() {
        val allow = ControlCommandIdentifiers.ALLOW_OFF_OPTION
        val strength = ControlCommandIdentifiers.AUTO_ANC_STRENGTH
        val requested = AirPodsUiState(requestedControls = mapOf(allow to byteArrayOf(1), strength to byteArrayOf(80)))
        val result = requested.withControlReport(allow, byteArrayOf(1))
        assertFalse(allow in result.requestedControls)
        assertArrayEquals(byteArrayOf(80), result.requestedControls[strength])
    }

    @Test fun modernAdaptiveDevicesNeedAReportOrTheirOwnRequestForOff() {
        val modern = AirPodsUiState(capabilities = setOf(Capability.LISTENING_MODE, Capability.ADAPTIVE_AUDIO), version3 = "9A348")
        assertFalse(offListeningModeAllowed(modern))
        assertTrue(offListeningModeAllowed(modern.copy(requestedControls = mapOf(ControlCommandIdentifiers.ALLOW_OFF_OPTION to byteArrayOf(1)))))
        assertFalse(offListeningModeAllowed(modern.copy(controlStates = mapOf(ControlCommandIdentifiers.ALLOW_OFF_OPTION to byteArrayOf(2)))))
        assertTrue(offListeningModeAllowed(modern.copy(controlStates = mapOf(ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(1)))))
        assertTrue(offListeningModeAllowed(modern.copy(version3 = "6F21")))
        assertFalse(offListeningModeAllowed(AirPodsUiState()))
    }

    @Test fun invalidReportsCannotEraseValidControlsOrRequests() {
        val id = ControlCommandIdentifiers.AUTO_ANC_STRENGTH
        val state = AirPodsUiState(controlStates = mapOf(id to byteArrayOf(50)), requestedControls = mapOf(id to byteArrayOf(70)))
        assertSame(state, state.withControlReport(id, byteArrayOf()))
        assertSame(state, state.withControlReport(id, byteArrayOf(101)))
        assertSame(state, state.withControlReport(id, ByteArray(5)))
    }

    @Test fun olderEqualizerReportsDoNotConfirmOrEraseANewerRequest() {
        val desired = CustomEq(2, 80, 20, 65)
        val older = CustomEq(2, 60, 50, 50)
        val requested = AirPodsUiState(requestedCustomEq = desired)
        val reported = requested.withCustomEqReport(older)
        assertEquals(older, reported.customEq)
        assertEquals(desired, reported.requestedCustomEq)
        val confirmed = reported.withCustomEqReport(desired)
        assertEquals(desired, confirmed.customEq)
        assertNull(confirmed.requestedCustomEq)
    }

    @Test fun controlWritesRequireAllSelectedAndTransportIdentitiesToAgree() {
        val a = "12:34:56:78:9A:BC"
        val b = "12:34:56:78:9A:BD"
        assertTrue(controlDeviceMatches(a, a, a, a))
        assertFalse(controlDeviceMatches(a, a, b, a))
        assertFalse(controlDeviceMatches(b, b, b, a))
        assertFalse(controlDeviceMatches(a, b, a, a))
        assertFalse(controlDeviceMatches(null, null, null, null))
    }
}
