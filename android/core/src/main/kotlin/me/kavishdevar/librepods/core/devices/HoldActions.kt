package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

internal fun AppleState.nextHoldListeningMode(settings: AppleSettings, metadata: AppleMetadata): Int? {
    val current = controlStates[ControlCommandIdentifier.LISTENING_MODE]?.firstOrNull()?.toInt() ?: return null
    if (current !in 1..4) return null
    val supported = metadata.supportedNoiseModeCycleMask(effectiveAllowOff(settings, metadata.modelNumber))
    val available = listOf(1, 2, 3, 4).filter { supported and (1 shl (it - 1)) != 0 }
    if (available.size < 2) return null
    val mask = noiseModeCycleValue(effectiveControl(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, settings))
    // Retain selected modes after a restriction when at least two remain supported.
    val configured = available.filter { mode -> mask != null && mask and (1 shl (mode - 1)) != 0 }
    val modes = configured.takeIf { it.size >= 2 } ?: available
    return modes[(modes.indexOf(current) + 1) % modes.size]
}
