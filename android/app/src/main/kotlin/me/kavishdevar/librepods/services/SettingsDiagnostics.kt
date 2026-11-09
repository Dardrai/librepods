/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.services

import java.io.PrintWriter
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.core.devices.effectiveControl
import me.kavishdevar.librepods.core.devices.effectiveEarDetection

/** Explicit dumpsys output contains fixed diagnostic fields, never accessory identities or fitting data. */
internal fun dumpNativeSettings(writer: PrintWriter, index: Int, device: AppleDevice) {
    val state = device.state.value
    val settings = device.settings.value
    fun flag(value: Boolean?): String = when (value) { true -> "on"; false -> "off"; null -> "unknown" }
    fun mode(value: ByteArray?): String = value?.takeIf { it.size in 1..4 && it.drop(1).all { byte -> byte == 0.toByte() } }
        ?.firstOrNull()?.toInt()?.takeIf { it in 1..4 }?.toString() ?: "unknown"
    writer.println("settings device_index=$index connection=${device.connectionState.value.name} session=${state.controlSession} " +
        "audio_socket_ready=${device.aacp.captureAudioSocket() != null} settings_socket_ready=${device.settingsSocketReady} " +
        "settings_operation_pending=${device.settingsOperationPending} microphone_active=${state.microphoneState.isActive} " +
        "microphone_requested=${device.aacp.isMicrophoneRequested()} recording=${state.recordingState.isRecording} " +
        "call=${state.audioSource.type == AudioSourceType.CALL}")
    val listening = ControlCommandIdentifier.LISTENING_MODE
    writer.println("settings_control device_index=$index control=LISTENING_MODE received=${mode(state.controlStates[listening])} " +
        "live_report=${listening in state.liveControlReports} requested=${mode(state.requestedControls[listening])}")
    for (id in listOf(ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG, ControlCommandIdentifier.EAR_DETECTION_CONFIG,
        ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG)) {
        val preferred = if (id == ControlCommandIdentifier.EAR_DETECTION_CONFIG) state.effectiveEarDetection(settings)
            else id.decodeBoolean(state.effectiveControl(id, settings))
        writer.println("settings_control device_index=$index control=${id.name} received=${flag(id.decodeBoolean(state.controlStates[id]))} " +
            "live_report=${id in state.liveControlReports} requested=${flag(id.decodeBoolean(state.requestedControls[id]))} preferred=${flag(preferred)}")
    }
    val modes = state.hearingModes
    val report = modes.accommodationsReport(state.controlSession)
    writer.println("settings_hearing device_index=$index profile_read=${state.hearingAidReadStatus.name} transparency_read=${state.transparencyReadStatus.name} " +
        "loud_sound_read=${state.loudSoundReductionReadStatus.name} loud_sound_value=${flag(state.loudSoundReductionEnabled.takeIf { state.loudSoundReductionReadStatus == SettingsReadStatus.AVAILABLE })} " +
        "loud_sound_writable=${device.loudSoundReductionWritable} transparency_transport_ready=${device.transparencyTransportAvailable} " +
        "fitting_writable=${hearingAidSettingsWritable(state.hearingAidData)} transparency_enabled=${flag(parseTransparencySettingsResponse(state.transparencyData)?.enabled)} " +
        "enrolled=${flag(modes.enrolled)} clinical_reports=${modes.clinicalReportsKnown} hearing_aid=${flag(modes.hearingAidEnabled)} media_assist=${flag(modes.mediaAssistEnabled)} " +
        "pending=${modes.pending?.name ?: "none"} uncertain=${modes.uncertain?.name ?: "none"} " +
        "accommodations_pending=${state.accommodationsRequest.pending} accommodations_result=${state.accommodationsRequest.result?.name ?: "none"} " +
        "accommodations_phone=${flag(report?.phoneEnabled)} accommodations_media=${flag(report?.mediaEnabled)}")
}
