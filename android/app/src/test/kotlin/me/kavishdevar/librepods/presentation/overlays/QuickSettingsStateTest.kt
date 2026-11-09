package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.utils.NativeGameModeController
import org.junit.Assert.*
import org.junit.Test

class QuickSettingsStateTest {
    private val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063")
    private val ordinary = mapOf(ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(2),
        ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG to byteArrayOf(2),
        ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(2))
    private fun controls(state: AppleState = AppleState(), settings: AppleSettings = AppleSettings(),
        game: NativeGameModeController.State? = null, metadata: AppleMetadata = this.metadata,
        pending: Set<QuickSetting> = emptySet(), profile: Boolean = false) =
        nativeQuickSettings(metadata, state, settings, game, true, profile, pending).associateBy { it.setting }

    @Test fun unknownOrCachedValuesNeverBecomeAnOffSelectionOrAWritableToggle() {
        val empty = controls()
        assertEquals(7, empty.size)
        assertTrue(empty.values.all { it.checked == null && !it.enabled })
        val cached = controls(AppleState(controlStates = ordinary))
        assertTrue(cached.values.all { it.checked == null && !it.enabled })
        val unknown = controls(AppleState(controlStates = ordinary, liveControlReports = ordinary.keys),
            metadata = metadata.copy(model = AirPodsModel.UNKNOWN), game = NativeGameModeController.State(available = true))
        assertTrue(unknown.values.none { it.enabled })
    }

    @Test fun liveBooleanReportsKeepEarDetectionEncodingAndPendingIntentSeparate() {
        val state = AppleState(controlStates = ordinary, liveControlReports = ordinary.keys,
            requestedControls = mapOf(ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG to byteArrayOf(1)))
        val values = controls(state)
        assertEquals(true, values[QuickSetting.EAR_DETECTION]!!.checked)
        assertEquals(true, values[QuickSetting.CONVERSATION_AWARENESS]!!.checked)
        assertTrue(values[QuickSetting.CONVERSATION_AWARENESS]!!.requested)
        assertFalse(values[QuickSetting.CONVERSATION_AWARENESS]!!.pending)
        assertEquals(false, values[QuickSetting.PERSONALIZED_VOLUME]!!.checked)
        assertEquals(2.toByte(), state.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]!!.single())
        assertTrue(values.filterKeys { it.control != null }.values.all { it.enabled })
        assertTrue(controls(state, pending = setOf(QuickSetting.HEARING_AID)).values.none { it.enabled })
    }

    @Test fun loudSoundReductionDistinguishesReadingFromUnavailableAndNeedsLiveProof() {
        for (status in SettingsReadStatus.entries) {
            val control = controls(AppleState(loudSoundReductionEnabled = true, loudSoundReductionReadStatus = status))[QuickSetting.LOUD_SOUND_REDUCTION]!!
            assertEquals(status == SettingsReadStatus.READING, control.reading)
            assertEquals(if (status == SettingsReadStatus.AVAILABLE) true else null, control.checked)
        }
    }

    @Test fun activeGameModeRestorationObligationDoesNotLookLikeAnUnfinishedToggle() {
        val state = NativeGameModeController.State(desiredOn = true, available = true, active = true, remoteRestorePending = true)
        val control = controls(settings = AppleSettings(nativeGameModeEnabled = true), game = state)[QuickSetting.GAME_MODE]!!
        assertEquals(true, control.checked)
        assertTrue(control.enabled)
        assertFalse(control.pending)
        val waiting = controls(settings = AppleSettings(nativeGameModeEnabled = true),
            game = state.copy(active = false, issue = NativeGameModeController.Issue.WAITING_FOR_AUDIO))[QuickSetting.GAME_MODE]!!
        assertTrue(waiting.pending)
    }

    @Test fun clinicalModesRequireExistingFittingAndInactiveCustomTransparencyToEnableButCanTurnOffWithoutThem() {
        fun modes(on: Boolean) = HearingModes.empty()
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, if (on) 1 else 2, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, if (on) 1 else 2, 0, 0, 0))
            .withReport(ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 2, 2).copyInto(it) })
        val off = AppleState(hearingModes = modes(false))
        assertFalse(controls(off)[QuickSetting.HEARING_AID]!!.enabled)
        val fitted = off.copy(hearingAidData = ByteArray(104).also { byteArrayOf(2, 2, 0x60, 0).copyInto(it) },
            transparencyData = ByteArray(104))
        assertTrue(controls(fitted, profile = true)[QuickSetting.HEARING_AID]!!.enabled)
        assertTrue(controls(fitted, profile = true)[QuickSetting.MEDIA_ASSIST]!!.enabled)
        assertTrue(controls(AppleState(hearingModes = modes(true)))[QuickSetting.HEARING_AID]!!.enabled)
        assertFalse(controls(fitted.copy(transparencyData = ByteArray(104).also { it[3] = 0x3f; it[2] = 0x80.toByte() }),
            profile = true)[QuickSetting.HEARING_AID]!!.enabled)
    }
}
