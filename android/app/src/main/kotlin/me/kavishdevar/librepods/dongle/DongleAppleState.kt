package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.AccommodationsAudiogram
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.savedControlChoice
import me.kavishdevar.librepods.core.devices.savedControlKey
import me.kavishdevar.librepods.data.LiveValueState
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState

/** The transport changes; the Apple screens still consume received state and separate intent. */
internal fun DongleState.appleUiState(now: Long = System.currentTimeMillis(),
    listeningMode: LiveValueState<Long>? = null, adaptiveStrength: LiveValueState<Long>? = null): AppleUiState {
    fun controls(values: Map<Int, Long>) = values.mapNotNull { (number, value) ->
        ControlCommandIdentifier.fromByte(number.toByte())?.let { it to appleControlBytes(value) }
    }.toMap()
    val received = controls(settings.mapValues { it.value.value })
    val liveRequested = buildMap {
        listeningMode?.requested?.takeIf { validLiveSetting(0x0d, it) }?.let { put(0x0d, it) }
        adaptiveStrength?.requested?.takeIf { validLiveSetting(0x2e, it) }?.let { put(0x2e, it) }
    }
    val sent = controls(requested + liveRequested)
    val savedAllowOff = if (0x34 in settings) null else when (savedChoices[0x34]) { 1L -> true; 2L -> false; else -> null }
    val saved = savedChoices.mapNotNull { (number, value) ->
        val id = ControlCommandIdentifier.fromByte(number.toByte()) ?: return@mapNotNull null
        val key = savedControlKey(id) ?: return@mapNotNull null
        savedControlChoice(id, appleControlBytes(value))?.let { key to it }
    }.toMap() + (savedAllowOff?.let { mapOf("desired_allow_off" to if (it) "on" else "off") }.orEmpty())
    val hold = (requested[0x16] ?: settings[0x16]?.value ?: savedChoices[0x16])
        ?.let { currentDongleSetting(0x16, it) }?.takeIf { validLiveSetting(0x16, it) }
    fun action(shift: Int): StemAction = when (hold?.shr(shift)?.and(255)) {
        1L -> StemAction.DIGITAL_ASSISTANT
        else -> StemAction.CYCLE_NOISE_CONTROL_MODES
    }
    val batteries = status?.let { report ->
        listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE).mapIndexedNotNull { index, component ->
            val level = report.batteries.getOrNull(index) ?: return@mapIndexedNotNull null
            val age = report.batteryAgeMs.getOrNull(index)
            val live = report.batteryLive(index)
            Battery(component, level, when {
                !live -> BatteryStatus.DISCONNECTED
                report.batteryCharging(index) -> BatteryStatus.CHARGING
                else -> BatteryStatus.NOT_CHARGING
            }, observedAt = age?.takeIf { it <= now }?.let { now - it }, present = live)
        }.toSet()
    }.orEmpty()
    val actualMetadata = metadata ?: AppleMetadata(name = "AirPods")
    return AppleUiState(
        state = AppleState(
            isLocallyConnected = connected && status?.controlsReady == true,
            battery = batteries,
            controlStates = received,
            requestedControls = sent,
            liveControlReports = received.keys,
            controlSession = controlSession,
            hearingModes = hearingModes,
            transparencyData = transparencyData,
            hearingAidData = hearingAidData,
            transparencyReadStatus = transparencyReadStatus,
            hearingAidReadStatus = hearingAidReadStatus,
            accommodationsRequest = accommodationsRequest,
            accommodationsAudiogramSource = AccommodationsAudiogram.parse(hearingAidData, controlSession),
        ),
        settings = AppleSettings(
            hostAutoPause = automaticPause,
            allowOffChoice = savedAllowOff,
            leftLongPressAction = action(8),
            rightLongPressAction = action(0),
            savedUnconfirmedChoices = saved,
        ),
        metadata = actualMetadata.copy(name = actualMetadata.name.ifBlank { actualMetadata.modelName.ifBlank { "AirPods" } }),
        // ATT is owned by the dongle, so this does not depend on the phone's identity hook.
        vendorIdHook = status?.capabilities?.and(8 or 32) == (8 or 32),
    )
}

internal fun DongleState.canWriteControl(id: ControlCommandIdentifier): Boolean = canEditSetting(id.value.toInt() and 255)

internal fun DongleController.setAppleControl(id: ControlCommandIdentifier, value: Int) {
    val raw = if (id == ControlCommandIdentifier.CHIME_VOLUME) 0x5000L or value.toLong() else value.toLong()
    setSetting(id.value.toInt() and 255, raw)
}

internal fun DongleController.setAppleControl(id: ControlCommandIdentifier, value: Boolean) =
    setSetting(id.value.toInt() and 255, id.encodeBoolean(value).toLong() and 255)

internal fun appleControlBytes(value: Long): ByteArray {
    val trimmed = ByteArray(4) { (value ushr (it * 8)).toByte() }.dropLastWhile { it == 0.toByte() }.toByteArray()
    return if (trimmed.isEmpty()) byteArrayOf(0) else trimmed
}
