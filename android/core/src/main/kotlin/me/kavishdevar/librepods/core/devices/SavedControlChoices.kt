package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

/** Saved intent is a fallback only, never a received report or an instruction to the accessory. */
fun AppleState.effectiveControl(id: ControlCommandIdentifier, settings: AppleSettings): ByteArray? =
    requestedControls[id] ?: controlStates[id]?.takeIf { id in liveControlReports } ?: savedControlValue(id, settings) ?: controlStates[id]

fun AppleState.hasSavedControlFallback(settings: AppleSettings): Boolean = ControlCommandIdentifier.entries.any {
    savedControlKey(it) != null && it !in liveControlReports && requestedControls[it] == null && savedControlValue(it, settings) != null
}

fun savedControlKey(id: ControlCommandIdentifier): String? = when (id) {
    ControlCommandIdentifier.MIC_MODE -> "desired_microphone_side"
    ControlCommandIdentifier.EAR_DETECTION_CONFIG -> "desired_ear_detection"
    ControlCommandIdentifier.LISTENING_MODE_CONFIGS -> "desired_noise_modes_cycle"
    ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL -> "desired_swipe_duration"
    ControlCommandIdentifier.IN_CASE_TONE_CONFIG -> "desired_case_sounds"
    ControlCommandIdentifier.IN_CASE_TONE_VOLUME -> "desired_case_volume"
    else -> null
}

fun savedControlValue(id: ControlCommandIdentifier, settings: AppleSettings): ByteArray? {
    val value = settings.savedUnconfirmedChoices[savedControlKey(id)] ?: return null
    val number = when (id) {
        ControlCommandIdentifier.MIC_MODE -> when (value) { "auto" -> 0; "right" -> 1; "left" -> 2; else -> null }
        ControlCommandIdentifier.EAR_DETECTION_CONFIG -> when (value) { "on" -> 2; "off" -> 1; else -> null }
        ControlCommandIdentifier.IN_CASE_TONE_CONFIG -> when (value) { "on" -> 1; "off" -> 2; else -> null }
        ControlCommandIdentifier.LISTENING_MODE_CONFIGS -> value.toIntOrNull(16)?.takeIf { it in 1..15 && it.countOneBits() >= 2 }
        ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL -> value.toIntOrNull()?.takeIf { it in 0..2 }?.plus(1)
        ControlCommandIdentifier.IN_CASE_TONE_VOLUME -> value.toIntOrNull()?.takeIf { it in 0..100 }
        else -> null
    } ?: return null
    return byteArrayOf(number.toByte())
}

internal fun savedControlChoice(id: ControlCommandIdentifier, raw: ByteArray): String? {
    if (raw.isEmpty() || raw.size > 4 || raw.drop(1).any { it != 0.toByte() }) return null
    val number = raw[0].toInt() and 255
    return when (id) {
        ControlCommandIdentifier.MIC_MODE -> when (number) { 0 -> "auto"; 1 -> "right"; 2 -> "left"; else -> null }
        ControlCommandIdentifier.EAR_DETECTION_CONFIG -> when (number) { 2 -> "on"; 1 -> "off"; else -> null }
        ControlCommandIdentifier.IN_CASE_TONE_CONFIG -> when (number) { 1 -> "on"; 2 -> "off"; else -> null }
        ControlCommandIdentifier.LISTENING_MODE_CONFIGS -> number.takeIf { it in 1..15 && it.countOneBits() >= 2 }?.toString(16)?.padStart(2, '0')
        ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL -> number.takeIf { it in 1..3 }?.minus(1)?.toString()
        ControlCommandIdentifier.IN_CASE_TONE_VOLUME -> number.takeIf { it in 0..100 }?.toString()
        else -> null
    }
}
