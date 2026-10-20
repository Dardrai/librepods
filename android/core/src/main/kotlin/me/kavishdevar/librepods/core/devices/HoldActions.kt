package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

internal fun AppleState.nextHoldListeningMode(settings: AppleSettings, model: String): Int? {
    val current = controlStates[ControlCommandIdentifier.LISTENING_MODE]?.firstOrNull()?.toInt() ?: return null
    if (current !in 1..4) return null
    val allowOff = effectiveAllowOff(settings, model) == true
    val available = listOf(1, 2, 3, 4).filter { it != 1 || allowOff }
    val mask = controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS]?.firstOrNull()?.toInt()
    val configured = available.filter { mode -> mask != null && mask and (1 shl (mode - 1)) != 0 }
    val modes = configured.takeIf { it.size >= 2 } ?: available
    return modes[(modes.indexOf(current) + 1) % modes.size]
}
