package me.kavishdevar.librepods.dongle

import android.app.Application
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.core.devices.canSetLegacyEndCall
import me.kavishdevar.librepods.core.devices.effectiveControl
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.core.devices.supportedNoiseModeCycleMask
import org.junit.Assert.*
import org.junit.Test
import me.kavishdevar.librepods.data.LiveValueState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleAppleStateTest {
    @Test fun queuedListeningIntentIsVisibleBeforeTheWireWriteWithoutReplacingReceivedReports() {
        val state = DongleState(settings = mapOf(0x0d to DongleProtocol.Setting(3, 0, 1),
            0x2e to DongleProtocol.Setting(42, 0, 1)), requested = mapOf(0x0d to 2L, 0x0a to 2L))
        val ui = state.appleUiState(listeningMode = LiveValueState(3L, requested = 4L),
            adaptiveStrength = LiveValueState(42L, requested = 75L))
        assertArrayEquals(byteArrayOf(4), ui.state.requestedControls[ControlCommandIdentifier.LISTENING_MODE])
        assertArrayEquals(byteArrayOf(75), ui.state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH])
        assertArrayEquals(byteArrayOf(3), ui.state.controlStates[ControlCommandIdentifier.LISTENING_MODE])
        assertArrayEquals(byteArrayOf(42), ui.state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH])
        assertArrayEquals(byteArrayOf(2), ui.state.requestedControls[ControlCommandIdentifier.EAR_DETECTION_CONFIG])
        assertEquals(2L, state.requested[0x0d])
    }

    @Test fun retiringEditorIntentShowsTheNewReceivedModeAndRejectsInvalidIntent() {
        val state = DongleState(settings = mapOf(0x0d to DongleProtocol.Setting(2, 0, 2)))
        val ui = state.appleUiState(listeningMode = LiveValueState(2L),
            adaptiveStrength = LiveValueState(50L, requested = 101L))
        assertFalse(ControlCommandIdentifier.LISTENING_MODE in ui.state.requestedControls)
        assertFalse(ControlCommandIdentifier.AUTO_ANC_STRENGTH in ui.state.requestedControls)
        assertArrayEquals(byteArrayOf(2), ui.state.controlStates[ControlCommandIdentifier.LISTENING_MODE])
    }

    private val metadata = AppleMetadata(name = "Test AirPods", model = AirPodsModel.AIRPODS_PRO_3,
        modelNumber = "A3063", version1 = "90.3431000025000000.6807", version3 = "9442752")
    private fun status() = DongleProtocol.Status(3, listOf(0, 1, 2, 3, 4, 5), 3, 3,
        listOf(91, 90, 65), 3, 511, listOf(1_000, 1_200, 60_000), batteryPresent = 3, charging = 1)

    @Test fun usesTheDonglesMetadataAndTheSharedAdaptiveCapabilityGate() {
        val state = DongleState(connected = true, status = status(), metadata = metadata).appleUiState()
        assertEquals(metadata, state.metadata)
        assertTrue(state.metadata.supportedNoiseModeCycleMask(false) and 8 != 0)
        assertTrue(state.state.isLocallyConnected)
        assertTrue(state.vendorIdHook)
    }

    @Test fun missingMetadataDoesNotInventAModelFirmwareOrAdaptiveSupport() {
        val state = DongleState(connected = true, status = status().copy(capabilities = 2)).appleUiState()
        assertEquals(AirPodsModel.UNKNOWN, state.metadata.model)
        assertNull(state.metadata.firmwareMajor)
        assertEquals(0, state.metadata.supportedNoiseModeCycleMask(null))
        assertFalse(state.vendorIdHook)
        assertTrue(state.state.controlStates.isEmpty())
    }

    @Test fun preservesOpaqueReceiptsAndKeepsThemReadOnly() {
        val state = DongleState(connected = true, status = status(), metadata = metadata,
            settings = mapOf(0x24 to DongleProtocol.Setting(0x00022301, 0, 1),
                0x16 to DongleProtocol.Setting(0x05010505, 0, 1)))
        val ui = state.appleUiState()
        assertArrayEquals(byteArrayOf(1, 0x23, 2), ui.state.controlStates[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG])
        assertArrayEquals(byteArrayOf(5, 5, 1, 5), ui.state.controlStates[ControlCommandIdentifier.CLICK_HOLD_MODE])
        assertFalse(ui.state.canSetLegacyEndCall())
        assertFalse(state.canWriteControl(ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG))
    }

    @Test fun savedChoicesStaySeparateAndAReceivedReportWinsOverTheSavedFallback() {
        val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
        val saved = DongleState(savedChoices = mapOf(0x0a to 2L))
        val first = saved.appleUiState()
        assertTrue(first.state.controlStates.isEmpty())
        assertFalse(id in first.state.liveControlReports)
        assertArrayEquals(byteArrayOf(2), first.state.effectiveControl(id, first.settings))
        val received = saved.copy(settings = mapOf(0x0a to DongleProtocol.Setting(1, 0, 4))).appleUiState()
        assertArrayEquals(byteArrayOf(1), received.state.effectiveControl(id, received.settings))
        val pending = saved.copy(settings = mapOf(0x0a to DongleProtocol.Setting(1, 0, 4)), requested = mapOf(0x0a to 2L)).appleUiState()
        assertArrayEquals(byteArrayOf(2), pending.state.effectiveControl(id, pending.settings))
    }

    @Test fun holdActionsUseRightLowByteAndLeftNextByteIgnoringPreviousActions() {
        val state = DongleState(settings = mapOf(0x16 to DongleProtocol.Setting(0x01050501, 0, 1)))
        val ui = state.appleUiState()
        assertEquals(StemAction.DIGITAL_ASSISTANT, ui.settings.rightLongPressAction)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, ui.settings.leftLongPressAction)
        assertArrayEquals(byteArrayOf(1, 5, 5, 1), ui.state.controlStates[ControlCommandIdentifier.CLICK_HOLD_MODE])
    }

    @Test fun absentCaseKeepsItsPercentageAndAgeWithoutClaimingItIsPresentOrCharging() {
        val ui = DongleState(status = status()).appleUiState(now = 1_000_000)
        val case = ui.state.battery.single { it.component == BatteryComponent.CASE }
        assertEquals(65, case.level)
        assertEquals(940_000L, case.observedAt)
        assertEquals(BatteryStatus.DISCONNECTED, case.status)
        assertFalse(case.present)
        assertEquals(BatteryStatus.CHARGING, ui.state.battery.single { it.component == BatteryComponent.LEFT }.status)
    }

    @Test fun silentAllowOffKeepsSavedIntentWithoutReplacingAReceivedReport() {
        val saved = DongleState(metadata = metadata, savedChoices = mapOf(0x34 to 1L))
        val first = saved.appleUiState()
        assertEquals(true, first.state.effectiveAllowOff(first.settings, metadata.modelNumber))
        assertTrue(first.state.controlStates.isEmpty())
        val received = saved.copy(settings = mapOf(0x34 to DongleProtocol.Setting(2, 0, 1))).appleUiState()
        assertEquals(false, received.state.effectiveAllowOff(received.settings, metadata.modelNumber))
        val opaque = saved.copy(settings = mapOf(0x34 to DongleProtocol.Setting(0x0101, 0, 2))).appleUiState()
        assertNull(opaque.state.effectiveAllowOff(opaque.settings, metadata.modelNumber))
    }

    @Test fun projectsReadStatusesAndExactSavedRecordsWithoutSynthesizingAnAudiogram() {
        val bytes = ByteArray(104) { it.toByte() }
        val ui = DongleState(hearingAidData = bytes, transparencyData = byteArrayOf(1, 2),
            hearingAidReadStatus = SettingsReadStatus.AVAILABLE,
            transparencyReadStatus = SettingsReadStatus.UNAVAILABLE).appleUiState()
        assertArrayEquals(bytes, ui.state.hearingAidData)
        assertEquals(SettingsReadStatus.AVAILABLE, ui.state.hearingAidReadStatus)
        assertEquals(SettingsReadStatus.UNAVAILABLE, ui.state.transparencyReadStatus)
        assertNull(ui.state.accommodationsAudiogramSource)
    }
}
