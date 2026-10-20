package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud

internal fun holdActions(value: ByteArray?): ByteArray? {
    if (value == null || value.size !in 2..4 || value.take(2).any { it != 1.toByte() && it != 5.toByte() }) return null
    // Reports can include the previous actions in the upper bytes; writes use only the current pair.
    return value.copyOfRange(0, 2)
}

fun AppleState.effectiveLongPressAction(side: StemPressBud): StemAction? {
    val actions = holdActions(requestedControls[ControlCommandIdentifier.CLICK_HOLD_MODE])
        ?: holdActions(controlStates[ControlCommandIdentifier.CLICK_HOLD_MODE])
        ?: return null
    return if (actions[if (side == StemPressBud.RIGHT) 0 else 1] == 5.toByte()) StemAction.DIGITAL_ASSISTANT
        else StemAction.CYCLE_NOISE_CONTROL_MODES
}
