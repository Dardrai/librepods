package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

internal fun normalizedControl(value: ByteArray): ByteArray =
    value.dropLastWhile { it == 0.toByte() }.toByteArray().takeIf { it.isNotEmpty() } ?: byteArrayOf(0)

internal fun AppleState.withControlReport(id: ControlCommandIdentifier, rawValue: ByteArray): AppleState {
    val value = normalizedControl(rawValue)
    return copy(controlStates = controlStates + (id to value), requestedControls =
        if (requestedControls[id]?.contentEquals(value) == true) requestedControls - id else requestedControls)
}

internal fun AppleState.withControlRequest(id: ControlCommandIdentifier, value: ByteArray,
    previousReport: ByteArray?): AppleState {
    // Reports received while a write was in progress remain authoritative.
    if (controlStates[id] !== previousReport) return this
    val requested = normalizedControl(value)
    return copy(requestedControls = if (controlStates[id]?.contentEquals(requested) == true)
        requestedControls - id else requestedControls + (id to requested))
}
