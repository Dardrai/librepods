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
        ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(2),
        ControlCommandIdentifier.PPE_TOGGLE_CONFIG to byteArrayOf(2))
    private fun controls(state: AppleState = AppleState(), settings: AppleSettings = AppleSettings(),
        game: NativeGameModeController.State? = null, metadata: AppleMetadata = this.metadata,
        pending: Set<QuickSetting> = emptySet(), profile: Boolean = false) =
        nativeQuickSettings(metadata, state, settings, game, true, profile, pending).associateBy { it.setting }

    @Test fun unknownAccessoryValuesStayUnknownAndCachedPreferencesDoNotBecomeReceivedProof() {
        val empty = controls()
        assertEquals(8, empty.size)
        assertTrue(empty.filterKeys { it != QuickSetting.EAR_DETECTION }.values.all { it.checked == null && !it.enabled })
        assertEquals(true, empty[QuickSetting.EAR_DETECTION]!!.checked)
        assertTrue(empty[QuickSetting.EAR_DETECTION]!!.enabled)
        assertTrue(empty[QuickSetting.EAR_DETECTION]!!.requested)
        val cachedState = AppleState(controlStates = ordinary)
        val cached = controls(cachedState)
        for (key in listOf(QuickSetting.CONVERSATION_AWARENESS, QuickSetting.PERSONALIZED_VOLUME, QuickSetting.EN352_PROTECTION)) {
            assertEquals(false, cached.getValue(key).checked)
            assertTrue(cached.getValue(key).requested)
        }
        assertTrue(cached[QuickSetting.EAR_DETECTION]!!.requested)
        val unavailable = nativeQuickSettings(metadata, cachedState, AppleSettings(), null, false, false,
            canWrite = { false }).associateBy { it.setting }
        for (key in listOf(QuickSetting.CONVERSATION_AWARENESS, QuickSetting.PERSONALIZED_VOLUME, QuickSetting.EAR_DETECTION, QuickSetting.EN352_PROTECTION)) {
            assertTrue(unavailable.getValue(key).requested)
            assertFalse(unavailable.getValue(key).enabled)
        }
        assertTrue(cachedState.liveControlReports.isEmpty())
        val unknown = controls(AppleState(controlStates = ordinary, liveControlReports = ordinary.keys),
            metadata = metadata.copy(model = AirPodsModel.UNKNOWN), game = NativeGameModeController.State(available = true))
        assertTrue(unknown.values.none { it.enabled })
    }

    @Test fun nativeEarDetectionUsesTheSameReportRequestAndSavedPreferenceOrderAsTheMainScreen() {
        val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
        val savedOn = AppleSettings(earDetectionEnabled = true, savedUnconfirmedChoices = mapOf("desired_ear_detection" to "on"))
        val savedOff = AppleSettings(earDetectionEnabled = true, savedUnconfirmedChoices = mapOf("desired_ear_detection" to "off"))
        val reportedOff = AppleState(controlStates = mapOf(id to byteArrayOf(1)), liveControlReports = setOf(id))
        val cases = listOf(
            Triple(AppleState(), AppleSettings(earDetectionEnabled = false), false),
            Triple(reportedOff, savedOn, false),
            Triple(reportedOff.copy(requestedControls = mapOf(id to byteArrayOf(2))), savedOff, true),
            Triple(AppleState(controlStates = mapOf(id to byteArrayOf(2))), savedOff, false),
            Triple(AppleState(controlStates = mapOf(id to byteArrayOf(1))), AppleSettings(earDetectionEnabled = true), false),
            Triple(reportedOff.copy(requestedControls = mapOf(id to byteArrayOf(0))), savedOn, false))
        for ((state, settings, expected) in cases) {
            val value = controls(state, settings)[QuickSetting.EAR_DETECTION]!!
            assertEquals(expected, value.checked)
            assertTrue(value.enabled)
            assertEquals(id.decodeBoolean(state.controlStates[id]?.takeIf { id in state.liveControlReports }) != expected, value.requested)
            assertFalse(value.pending)
        }
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

    @Test fun protectionUsesItsOwnReportedBooleanAndRequiresAMatchingModelAndWritableTransport() {
        val id = ControlCommandIdentifier.PPE_TOGGLE_CONFIG
        val key = QuickSetting.EN352_PROTECTION
        assertEquals(id, key.control)
        for ((raw, expected) in listOf(1.toByte() to true, 2.toByte() to false)) {
            val state = AppleState(controlStates = mapOf(id to byteArrayOf(raw)), liveControlReports = setOf(id))
            val value = controls(state).getValue(key)
            assertEquals(expected, value.checked)
            assertTrue(value.supported)
            assertTrue(value.enabled)
            assertFalse(value.requested)
            val blocked = nativeQuickSettings(metadata, state, AppleSettings(), null, true, false,
                canWrite = { it != id }).first { it.setting == key }
            assertFalse(blocked.enabled)
            for (model in listOf(AirPodsModel.AIRPODS_PRO_2_USBC, AirPodsModel.UNKNOWN)) {
                val unsupported = controls(state, metadata = metadata.copy(model = model)).getValue(key)
                assertFalse(unsupported.supported)
                assertFalse(unsupported.enabled)
            }
        }
        val requested = AppleState(controlStates = mapOf(id to byteArrayOf(2)), liveControlReports = setOf(id),
            requestedControls = mapOf(id to byteArrayOf(1)))
        assertEquals(true, controls(requested).getValue(key).checked)
        assertTrue(controls(requested).getValue(key).requested)
        assertNull(controls().getValue(key).checked)
        assertFalse(controls().getValue(key).enabled)
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

    @Test fun ordinaryWritesDeferOnlyTypedHearingChangesWhileChoicesAndGameModeStayEditable() {
        val modes = HearingModes.empty()
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 2, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0))
            .withReport(ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 2, 2).copyInto(it) })
        val state = AppleState(controlStates = ordinary, liveControlReports = ordinary.keys, hearingModes = modes,
            loudSoundReductionReadStatus = SettingsReadStatus.AVAILABLE,
            hearingAidData = ByteArray(104).also { byteArrayOf(2, 2, 0x60, 0).copyInto(it) },
            transparencyData = ByteArray(104))
        fun choices(busy: Boolean) = nativeQuickSettings(metadata, state, AppleSettings(),
            NativeGameModeController.State(available = true), true, true, ordinaryBusy = busy).associateBy { it.setting }
        val ready = choices(false)
        assertTrue(ready.values.all { it.enabled })
        val busy = choices(true)
        for (setting in QuickSetting.entries) {
            val expected = ready.getValue(setting).let {
                if (setting in setOf(QuickSetting.LOUD_SOUND_REDUCTION, QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST))
                    it.copy(enabled = false) else it
            }
            assertEquals(setting.name, expected, busy.getValue(setting))
        }
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
