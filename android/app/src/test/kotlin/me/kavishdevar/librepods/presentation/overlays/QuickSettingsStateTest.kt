package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.utils.NativeGameModeController
import org.junit.Assert.*
import org.junit.Test

class QuickSettingsStateTest {
    private val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
    private val ordinary = mapOf(ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(2),
        ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG to byteArrayOf(2),
        ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(2),
        ControlCommandIdentifier.PPE_TOGGLE_CONFIG to byteArrayOf(2))
    private fun controls(state: AppleState = AppleState(), settings: AppleSettings = AppleSettings(),
        game: NativeGameModeController.State? = null, metadata: AppleMetadata = this.metadata,
        pending: Set<QuickSetting> = emptySet(), profile: Boolean = false, protectedAccess: Boolean = true) =
        nativeQuickSettings(metadata, state, settings, game, true, profile, pending,
            protectedSettingsSupported = protectedAccess).associateBy { it.setting }

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
        val state = NativeGameModeController.State(desiredOn = true, available = true, active = true,
            remoteRestorePending = true, hostSupported = true)
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
            NativeGameModeController.State(available = true, hostSupported = true), true, true, ordinaryBusy = busy,
            protectedSettingsSupported = true).associateBy { it.setting }
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

    @Test fun protectedControlsNeedExplicitHostSupportEvenWhenTheirRecordsArePresent() {
        val state = fittedState()
        val unavailable = nativeQuickSettings(metadata, state, AppleSettings(), null, true, true)
            .associateBy { it.setting }
        for (setting in listOf(QuickSetting.LOUD_SOUND_REDUCTION, QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST)) {
            assertFalse(setting.name, unavailable.getValue(setting).supported)
            assertFalse(setting.name, unavailable.getValue(setting).enabled)
            assertTrue(setting.name, controls(state, profile = true).getValue(setting).supported)
            assertTrue(setting.name, controls(state, profile = true).getValue(setting).enabled)
        }
        assertTrue(unavailable.getValue(QuickSetting.EAR_DETECTION).supported)
        assertTrue(unavailable.getValue(QuickSetting.EN352_PROTECTION).supported)
    }

    @Test fun unsupportedModelsCannotGainProtectedControlsFromHostAccessOrStaleRecords() {
        for (model in listOf(AirPodsModel.AIRPODS_PRO_1, AirPodsModel.AIRPODS_4_ANC, AirPodsModel.UNKNOWN)) {
            val values = controls(fittedState(on = true), profile = true, metadata = metadata.copy(model = model))
            for (setting in listOf(QuickSetting.LOUD_SOUND_REDUCTION, QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST))
                assertFalse("$model $setting", values.getValue(setting).supported)
        }
    }

    @Test fun supportedControlsRemainPresentWhileRecordsReloadOrAnotherWriteIsPending() {
        val protectedSettings = setOf(QuickSetting.LOUD_SOUND_REDUCTION, QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST)
        for (read in SettingsReadStatus.entries) {
            val state = AppleState(hearingAidReadStatus = read, transparencyReadStatus = read,
                loudSoundReductionReadStatus = read)
            val values = nativeQuickSettings(metadata, state, AppleSettings(), null, false, false,
                pending = setOf(QuickSetting.HEARING_AID), protectedSettingsSupported = true)
                .associateBy { it.setting }
            for (setting in protectedSettings) {
                assertTrue("$read $setting", values.getValue(setting).supported)
                assertFalse("$read $setting", values.getValue(setting).enabled)
            }
        }
        val disconnected = nativeQuickSettings(metadata, fittedState(), AppleSettings(), null, true, true,
            connected = false, protectedSettingsSupported = true).associateBy { it.setting }
        for (setting in protectedSettings) {
            assertTrue(disconnected.getValue(setting).supported)
            assertFalse(disconnected.getValue(setting).enabled)
        }
    }

    @Test fun losingHostSupportKeepsExistingOnAndUncertainModesAvailableForTurningOff() {
        val on = controls(fittedState(on = true), profile = true, protectedAccess = false)
        for (setting in listOf(QuickSetting.LOUD_SOUND_REDUCTION, QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST)) {
            assertTrue(setting.name, on.getValue(setting).supported)
            assertEquals(true, on.getValue(setting).checked)
            assertTrue(setting.name, on.getValue(setting).enabled)
        }
        for (mode in HearingMode.entries) {
            val state = fittedState().let { it.copy(hearingModes = it.hearingModes.withRequestState(null, mode)) }
            val setting = if (mode == HearingMode.HEARING_AID) QuickSetting.HEARING_AID else QuickSetting.MEDIA_ASSIST
            val value = controls(state, protectedAccess = false).getValue(setting)
            assertTrue(value.supported)
            assertTrue(value.recoveryOff)
            assertTrue(value.enabled)
        }
    }

    @Test fun retainedPresentationCannotBringBackAControlWhoseSupportWasRemoved() {
        val setting = QuickSetting.HEARING_AID
        val previous = ListeningModeSnapshot(metadata, AppleState(), AppleSettings(),
            listOf(QuickSettingState(setting, true, true, true)))
        val current = previous.copy(quickSettings = listOf(QuickSettingState(setting, null, false, false)))
        assertEquals(current, retainQuickPresentation(previous, current, ownedRefresh = true))
    }

    @Test fun gameModeSupportDoesNotDisappearWhileItsNativeRouteIsTemporarilyUnready() {
        val host = NativeGameModeController.State(hostSupported = true)
        val waiting = controls(game = host).getValue(QuickSetting.GAME_MODE)
        assertTrue(waiting.supported)
        assertEquals(false, waiting.checked)
        assertFalse(waiting.enabled)
        assertTrue(controls(game = host.copy(available = true)).getValue(QuickSetting.GAME_MODE).enabled)
        val off = controls(settings = AppleSettings(nativeGameModeEnabled = true), game = NativeGameModeController.State())
            .getValue(QuickSetting.GAME_MODE)
        assertTrue(off.supported)
        assertTrue(off.enabled)
        for (other in listOf(metadata.copy(model = AirPodsModel.AIRPODS_PRO_2_USBC), metadata.copy(version1 = "7A294"),
                metadata.copy(version1 = ""))) {
            val unsupported = controls(metadata = other, settings = AppleSettings(nativeGameModeEnabled = true),
                game = host.copy(available = true)).getValue(QuickSetting.GAME_MODE)
            assertFalse(unsupported.supported)
            assertFalse(unsupported.enabled)
        }
        assertFalse(controls(game = null).getValue(QuickSetting.GAME_MODE).supported)
        assertFalse(controls(game = NativeGameModeController.State()).getValue(QuickSetting.GAME_MODE).supported)
    }

    @Test fun dongleProtectedSupportComesFromTheDongleAndSurvivesTemporaryUnreadyStatus() {
        val fitting = fittedState()
        val status = DongleProtocol.Status(1, listOf(1, 2, 3, 4, 5, 6), 3, 2,
            listOf(80, 80, 80), 3, 8 or 32)
        val dongle = DongleState(connected = true, status = status, metadata = metadata,
            hearingModes = fitting.hearingModes, hearingAidData = fitting.hearingAidData,
            transparencyData = fitting.transparencyData)
        for (ready in listOf(true, false)) {
            val values = dongleQuickSettings(dongle.copy(status = status.copy(flags = if (ready) 3 else 1)))
                .associateBy { it.setting }
            for (setting in listOf(QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST)) {
                assertTrue(values.getValue(setting).supported)
                assertEquals(ready, values.getValue(setting).enabled)
            }
        }
        val unsupported = dongleQuickSettings(dongle.copy(status = status.copy(capabilities = 0))).associateBy { it.setting }
        assertFalse(unsupported.getValue(QuickSetting.HEARING_AID).supported)
        assertFalse(unsupported.getValue(QuickSetting.MEDIA_ASSIST).supported)
        assertFalse(unsupported.getValue(QuickSetting.LOUD_SOUND_REDUCTION).supported)
    }

    private fun fittedState(on: Boolean = false): AppleState = AppleState(
        loudSoundReductionEnabled = on, loudSoundReductionReadStatus = SettingsReadStatus.AVAILABLE,
        hearingModes = HearingModes.empty()
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, if (on) 1 else 2, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, if (on) 1 else 2, 0, 0, 0))
            .withReport(ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1,
                if (on) 1 else 2, if (on) 1 else 2).copyInto(it) }),
        hearingAidData = ByteArray(104).also { byteArrayOf(2, 2, 0x60, 0).copyInto(it) },
        transparencyData = ByteArray(104))
}
