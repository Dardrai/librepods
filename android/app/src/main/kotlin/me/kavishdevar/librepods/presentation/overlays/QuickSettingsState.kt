/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.data.LiveValueState
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.dongle.appleUiState
import me.kavishdevar.librepods.dongle.appleControlBytes
import me.kavishdevar.librepods.dongle.canWriteControl
import me.kavishdevar.librepods.utils.NativeGameModeController

internal enum class QuickSetting {
    CONVERSATION_AWARENESS, EAR_DETECTION, GAME_MODE, PERSONALIZED_VOLUME,
    LOUD_SOUND_REDUCTION, HEARING_AID, MEDIA_ASSIST;

    val control: ControlCommandIdentifier? get() = when (this) {
        CONVERSATION_AWARENESS -> ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        EAR_DETECTION -> ControlCommandIdentifier.EAR_DETECTION_CONFIG
        PERSONALIZED_VOLUME -> ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG
        else -> null
    }
}

internal data class QuickSettingState(val setting: QuickSetting, val checked: Boolean?,
    val supported: Boolean, val enabled: Boolean, val pending: Boolean = false, val reading: Boolean = false, val requested: Boolean = false, val recoveryOff: Boolean = false, val retained: Boolean = false)

internal fun nativeQuickSettings(metadata: AppleMetadata, state: AppleState, settings: AppleSettings,
    game: NativeGameModeController.State?, loudSoundWritable: Boolean, profileAvailable: Boolean,
    pending: Set<QuickSetting> = emptySet(), connected: Boolean = true,
    canWrite: (ControlCommandIdentifier) -> Boolean = { true }, ordinaryBusy: Boolean = false): List<QuickSettingState> {
    val idle = connected && !state.microphoneState.isActive && !state.recordingState.isRecording && state.audioSource.type != AudioSourceType.CALL
    val gameSupported = metadata.model != AirPodsModel.UNKNOWN && game != null && (game.available || settings.nativeGameModeEnabled)
    val gameToggle = QuickSettingState(QuickSetting.GAME_MODE, settings.nativeGameModeEnabled.takeIf { gameSupported },
        gameSupported, gameSupported && idle && pending.isEmpty(), game?.restoring == true || game?.let { it.desiredOn && !it.active && it.issue in setOf(
                NativeGameModeController.Issue.WAITING_FOR_AUDIO, NativeGameModeController.Issue.WAITING_FOR_TIMING) } == true)
    val loudSupported = metadata.model != AirPodsModel.UNKNOWN && BaseCapability.LOUD_SOUND_REDUCTION in AirPodsSpecs.getSpec(metadata.model).baseCapabilities
    val loud = QuickSettingState(QuickSetting.LOUD_SOUND_REDUCTION,
        state.loudSoundReductionEnabled.takeIf { state.loudSoundReductionReadStatus == SettingsReadStatus.AVAILABLE },
        loudSupported, loudSupported && idle && loudSoundWritable && state.loudSoundReductionReadStatus == SettingsReadStatus.AVAILABLE && pending.isEmpty(), QuickSetting.LOUD_SOUND_REDUCTION in pending,
        state.loudSoundReductionReadStatus == SettingsReadStatus.READING)
    val earId = ControlCommandIdentifier.EAR_DETECTION_CONFIG
    val earChoice = state.effectiveEarDetection(settings)
    val reportedEar = earId.decodeBoolean(state.controlStates[earId]?.takeIf { earId in state.liveControlReports })
    return quickSettings(metadata, state, canWrite, gameToggle, loud, profileAvailable, idle, pending).map { toggle ->
        val id = toggle.setting.control
        when {
            toggle.setting == QuickSetting.EAR_DETECTION -> toggle.copy(checked = if (toggle.supported) earChoice else null,
                enabled = toggle.supported && idle && pending.isEmpty() && canWrite(earId), requested = toggle.supported && reportedEar != earChoice)
            id != null -> {
                val choice = id.decodeBoolean(state.effectiveControl(id, settings))
                val report = id.decodeBoolean(state.controlStates[id]?.takeIf { id in state.liveControlReports })
                toggle.copy(checked = choice, enabled = toggle.supported && choice != null && idle && pending.isEmpty() && canWrite(id),
                    requested = choice != null && report != choice)
            }
            toggle.setting == QuickSetting.GAME_MODE -> toggle
            else -> toggle.copy(enabled = toggle.enabled && !ordinaryBusy)
        }
    }
}

/** Keep an owned clinical reconnect readable without turning remembered presentation into live proof. */
internal fun retainQuickPresentation(previous: ListeningModeSnapshot?, current: ListeningModeSnapshot,
    ownedRefresh: Boolean, reading: Boolean = true): ListeningModeSnapshot {
    if (!ownedRefresh || previous == null) return current
    return current.copy(quickSettings = current.quickSettings.map { value ->
        val known = previous.quickSettings.firstOrNull { it.setting == value.setting }
        if (value.recoveryOff || value.checked != null || known?.checked == null) value else value.copy(checked = known.checked,
            supported = value.supported || known.supported, enabled = false, reading = reading, requested = false, retained = true)
    })
}

internal fun dongleQuickSettings(dongle: DongleState, edits: Map<Int, LiveValueState<Long>> = emptyMap(),
    game: LiveValueState<Boolean>? = null): List<QuickSettingState> {
    val ui = dongle.appleUiState()
    val state = ui.state.copy(requestedControls = ui.state.requestedControls + edits.mapNotNull { (id, edit) ->
        val control = ControlCommandIdentifier.fromByte(id.toByte()) ?: return@mapNotNull null
        edit.requested?.let { control to appleControlBytes(it) }
    }.toMap())
    val idle = dongle.connected && dongle.status?.controlsReady == true && dongle.status.microphoneActive == false && !dongle.busy
    val requested = game?.displayed ?: dongle.audio?.gameRequested
    val supported = ui.metadata.model != AirPodsModel.UNKNOWN && dongle.audio != null && (dongle.audio.flags and 8 == 8 || requested == true)
    val gameToggle = QuickSettingState(QuickSetting.GAME_MODE, requested, supported,
        idle && supported && requested != null, game?.pending == true)
    return quickSettings(ui.metadata, state, dongle::canWriteControl, gameToggle,
        QuickSettingState(QuickSetting.LOUD_SOUND_REDUCTION, null, false, false), ui.vendorIdHook, idle, emptySet(),
        QuickSetting.entries.filter { setting -> setting.control?.let { edits[it.value.toInt() and 255]?.pending } == true }.toSet())
}

private fun quickSettings(metadata: AppleMetadata, state: AppleState,
    canWrite: (ControlCommandIdentifier) -> Boolean, game: QuickSettingState, loud: QuickSettingState,
    profileAvailable: Boolean, idle: Boolean, pending: Set<QuickSetting>, ordinaryPending: Set<QuickSetting> = emptySet()): List<QuickSettingState> {
    val knownModel = metadata.model != AirPodsModel.UNKNOWN
    val capabilities = if (knownModel) AirPodsSpecs.getSpec(metadata.model).baseCapabilities else emptySet()
    fun ordinary(setting: QuickSetting, supported: Boolean): QuickSettingState {
        val id = checkNotNull(setting.control)
        val received = id.decodeBoolean(state.controlStates[id]?.takeIf { id in state.liveControlReports })
        val requested = id.decodeBoolean(state.requestedControls[id])
        return QuickSettingState(setting, (requested ?: received).takeIf { received != null }, supported,
            supported && received != null && idle && pending.isEmpty() && canWrite(id),
            pending = setting in ordinaryPending, requested = requested != null && requested != received)
    }
    val canReadProfile = profileAvailable && hearingAidSettingsWritable(state.hearingAidData) &&
        parseTransparencySettingsResponse(state.transparencyData)?.enabled == false
    fun hearing(setting: QuickSetting, mode: HearingMode): QuickSettingState {
        val reported = if (mode == HearingMode.HEARING_AID) state.hearingModes.hearingAidEnabled else state.hearingModes.mediaAssistEnabled
        val supported = BaseCapability.HEARING_AID in capabilities
        if (state.hearingModes.uncertain == mode) return QuickSettingState(setting, null, supported,
            supported && idle && pending.isEmpty() && state.hearingModes.canChange(mode, false),
            pending = state.hearingModes.pending == mode || setting in pending, recoveryOff = true)
        return QuickSettingState(setting, reported, supported, supported && idle && pending.isEmpty() && reported != null &&
            state.hearingModes.canChange(mode, !reported) && (reported || canReadProfile),
            state.hearingModes.pending == mode || setting in pending)
    }
    return listOf(ordinary(QuickSetting.CONVERSATION_AWARENESS, BaseCapability.CONVERSATION_AWARENESS in capabilities),
        ordinary(QuickSetting.EAR_DETECTION, knownModel), game,
        ordinary(QuickSetting.PERSONALIZED_VOLUME, BaseCapability.ADAPTIVE_VOLUME in capabilities), loud,
        hearing(QuickSetting.HEARING_AID, HearingMode.HEARING_AID), hearing(QuickSetting.MEDIA_ASSIST, HearingMode.MEDIA_ASSIST))
}
