package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.*
import org.junit.Test

class HoldActionsTest {
    private fun state(mode: Int, mask: Int? = null, off: Int? = null) = AppleState(controlStates = buildMap {
        put(ControlCommandIdentifier.LISTENING_MODE, byteArrayOf(mode.toByte()))
        mask?.let { put(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, byteArrayOf(it.toByte())) }
        off?.let { put(ControlCommandIdentifier.ALLOW_OFF_OPTION, byteArrayOf(it.toByte())) }
    })

    @Test fun missingAndroidCycleReportsUseTheSupportedModesWithoutEnablingOff() {
        assertEquals(3, state(2).nextHoldListeningMode(AppleSettings(), "A3063"))
        assertEquals(4, state(3).nextHoldListeningMode(AppleSettings(), "A3063"))
        assertEquals(2, state(4).nextHoldListeningMode(AppleSettings(), "A3063"))
        assertEquals(1, state(4, off = 1).nextHoldListeningMode(AppleSettings(), "A3063"))
    }

    @Test fun reportedCycleAndAllowOffChoiceAreRespected() {
        assertEquals(4, state(2, mask = 10).nextHoldListeningMode(AppleSettings(), "A3063"))
        assertEquals(2, state(4, mask = 10).nextHoldListeningMode(AppleSettings(), "A3063"))
        assertEquals(3, state(2, mask = 3, off = 2).nextHoldListeningMode(AppleSettings(), "A3063"))
        assertEquals(1, state(2, mask = 3, off = 1).nextHoldListeningMode(AppleSettings(), "A3063"))
    }

    @Test fun absentOrInvalidCurrentModeDoesNotInventAChange() {
        assertNull(AppleState().nextHoldListeningMode(AppleSettings(), "A3063"))
        for (mode in listOf(0, 5, 255)) assertNull(state(mode).nextHoldListeningMode(AppleSettings(), "A3063"))
    }
}
