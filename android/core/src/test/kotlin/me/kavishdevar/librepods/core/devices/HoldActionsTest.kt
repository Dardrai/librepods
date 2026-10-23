package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.*
import org.junit.Test

class HoldActionsTest {
    private val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
    private fun state(mode: Int, mask: Int? = null, off: Int? = null) = AppleState(controlStates = buildMap {
        put(ControlCommandIdentifier.LISTENING_MODE, byteArrayOf(mode.toByte()))
        mask?.let { put(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, byteArrayOf(it.toByte())) }
        off?.let { put(ControlCommandIdentifier.ALLOW_OFF_OPTION, byteArrayOf(it.toByte())) }
    })

    @Test fun missingAndroidCycleReportsUseTheSupportedModesWithoutEnablingOff() {
        assertEquals(3, state(2).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(4, state(3).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(2, state(4).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(1, state(4, off = 1).nextHoldListeningMode(AppleSettings(), metadata))
    }

    @Test fun reportedCycleAndAllowOffChoiceAreRespected() {
        assertEquals(4, state(2, mask = 10).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(2, state(4, mask = 10).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(3, state(2, mask = 3, off = 2).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(1, state(2, mask = 3, off = 1).nextHoldListeningMode(AppleSettings(), metadata))
    }

    @Test fun absentOrInvalidCurrentModeDoesNotInventAChange() {
        assertNull(AppleState().nextHoldListeningMode(AppleSettings(), metadata))
        for (mode in listOf(0, 5, 255)) assertNull(state(mode).nextHoldListeningMode(AppleSettings(), metadata))
    }

    @Test fun delegatedHoldsUsePendingAndSavedCompleteCyclesWithoutWaitingForAnEcho() {
        val id = ControlCommandIdentifier.LISTENING_MODE_CONFIGS
        val pending = state(2, mask = 6, off = 1).copy(requestedControls = mapOf(id to byteArrayOf(11)))
        assertEquals(4, pending.nextHoldListeningMode(AppleSettings(), metadata))
        val saved = AppleSettings(savedUnconfirmedChoices = mapOf("desired_noise_modes_cycle" to "0b"))
        assertEquals(4, state(2, mask = 6, off = 1).nextHoldListeningMode(saved, metadata))
        assertEquals(1, state(4, off = 1).nextHoldListeningMode(saved, metadata))
    }

    @Test fun delegatedFallbackCannotInventAdaptiveForLegacyOrUnknownModels() {
        val pro1 = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_1, modelNumber = "A2084", version1 = "6A326")
        assertEquals(1, state(3).nextHoldListeningMode(AppleSettings(), pro1))
        assertEquals(1, state(3, mask = 10).nextHoldListeningMode(AppleSettings(), pro1))
        assertNull(state(2).nextHoldListeningMode(AppleSettings(), AppleMetadata()))
        val unknownFirmware = metadata.copy(version1 = "")
        assertEquals(3, state(2).nextHoldListeningMode(AppleSettings(), unknownFirmware))
        assertEquals(2, state(3).nextHoldListeningMode(AppleSettings(), unknownFirmware))
        val pro2Old = metadata.copy(model = AirPodsModel.AIRPODS_PRO_2_USBC, modelNumber = "A3047", version1 = "5A377")
        assertEquals(1, state(3).nextHoldListeningMode(AppleSettings(), pro2Old))
    }

    @Test fun freshReadbackWinsOverSavedIntentAndAnExplicitNewRequestWinsUntilReadback() {
        val id = ControlCommandIdentifier.LISTENING_MODE_CONFIGS
        val saved = AppleSettings(savedUnconfirmedChoices = mapOf("desired_noise_modes_cycle" to "0b"))
        val fresh = state(2, mask = 6, off = 1).copy(liveControlReports = setOf(id))
        assertEquals(3, fresh.nextHoldListeningMode(saved, metadata))
        assertEquals(4, fresh.copy(requestedControls = mapOf(id to byteArrayOf(11, 0, 0, 0)))
            .nextHoldListeningMode(saved, metadata))
    }

    @Test fun losingOffKeepsTheOtherSelectedModesInsteadOfAddingAdaptive() {
        val id = ControlCommandIdentifier.LISTENING_MODE_CONFIGS
        val requested = state(2, off = 2).copy(requestedControls = mapOf(id to byteArrayOf(7)))
        assertEquals(3, requested.nextHoldListeningMode(AppleSettings(), metadata))
        val saved = AppleSettings(savedUnconfirmedChoices = mapOf("desired_noise_modes_cycle" to "07"))
        assertEquals(2, state(3, off = 2).nextHoldListeningMode(saved, metadata))
    }

    @Test fun aCurrentModeOutsideTheSelectedOrAllowedCycleStartsAtItsFirstSupportedMode() {
        assertEquals(2, state(4, mask = 6).nextHoldListeningMode(AppleSettings(), metadata))
        assertEquals(2, state(1, mask = 6, off = 2).nextHoldListeningMode(AppleSettings(), metadata))
    }
}
