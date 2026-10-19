package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

/** Keep host automation and the displayed choice in agreement without inventing a device report. */
fun AppleState.effectiveEarDetection(settings: AppleSettings): Boolean {
    fun choice(value: ByteArray?): Boolean? {
        if (value == null || value.isEmpty() || value.size > 4 || value.drop(1).any { it != 0.toByte() }) return null
        return when (value[0].toInt()) { 1 -> true; 2 -> false; else -> null }
    }
    val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
    return choice(requestedControls[id]) ?: choice(controlStates[id]) ?: settings.earDetectionEnabled
}
