package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

/** Keep host automation and the displayed choice in agreement without inventing a device report. */
fun AppleState.effectiveEarDetection(settings: AppleSettings): Boolean {
    val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
    return id.decodeBoolean(requestedControls[id]) ?: id.decodeBoolean(controlStates[id]?.takeIf { id in liveControlReports })
        ?: id.decodeBoolean(savedControlValue(id, settings)) ?: id.decodeBoolean(controlStates[id]) ?: settings.earDetectionEnabled
}
