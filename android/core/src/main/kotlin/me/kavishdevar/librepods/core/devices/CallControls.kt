package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

/** Stable navigation values; old saved routes contained the translated label. */
enum class CallControlAction(val route: String) {
    MUTE_UNMUTE("mute_unmute"), HANG_UP("hang_up")
}

fun resolveCallControlAction(route: String, legacyMuteLabel: String, legacyHangUpLabel: String): CallControlAction? =
    when (route) {
        CallControlAction.MUTE_UNMUTE.route, legacyMuteLabel -> CallControlAction.MUTE_UNMUTE
        CallControlAction.HANG_UP.route, legacyHangUpLabel -> CallControlAction.HANG_UP
        else -> null // A stale translated label must never select the opposite action.
    }

/** The supported legacy 0x24 layout selects the end-call press count, not proven mute behavior. */
fun legacyEndCallSinglePress(value: ByteArray?): Boolean? {
    if (value == null || value.size !in 2..4 || value[0] != 0.toByte() || value.drop(2).any { it != 0.toByte() }) return null
    return when (value[1].toInt()) { 2 -> true; 3 -> false; else -> null }
}

fun legacyEndCallValue(singlePress: Boolean): ByteArray = byteArrayOf(0, if (singlePress) 2 else 3)

fun AppleState.legacyEndCallSinglePress(): Boolean? =
    legacyEndCallSinglePress(requestedControls[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG])
        ?: legacyEndCallSinglePress(controlStates[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG])

/** Only a fresh legacy report can establish a safe baseline; preserve other versions/status fields. */
fun AppleState.canSetLegacyEndCall(): Boolean {
    val id = ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG
    return id in liveControlReports && legacyEndCallSinglePress(controlStates[id]) != null &&
        requestedControls[id].let { it == null || legacyEndCallSinglePress(it) != null }
}

/** Native calling still works on older models; this gates only the configurable end-call preference. */
fun AppleMetadata.supportsCallControls(): Boolean =
    model !in setOf(AirPodsModel.UNKNOWN, AirPodsModel.AIRPODS_1, AirPodsModel.AIRPODS_2)
