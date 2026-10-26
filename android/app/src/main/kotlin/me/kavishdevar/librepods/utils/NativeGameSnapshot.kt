/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Version1 fixed scalar layout shared with fluoride_hooks.cpp; contains no native addresses. */
internal class NativeGameSnapshot private constructor(private val values: LongArray) {
    val supported get() = values[1] == 1L
    val busy get() = values[2] == 1L
    val phase get() = values[3].toInt()
    val operation get() = values[4].toInt()
    val serial get() = values[5]
    val leaseOwner get() = values[6]
    val error get() = values[7].toInt()
    val baselineKnown get() = values[9] == 1L
    val baselineSlots get() = values[10].toInt()
    val currentKnown get() = values[11] == 1L
    val currentSlots get() = values[12].toInt()
    val restorePending get() = values[13] == 1L
    val cancelled get() = values[14] == 1L
    val requestMediaGeneration get() = values[15]
    val requestEncoderGeneration get() = values[16]
    val requestAclHandle get() = values[17].toInt()
    val requestPeer get() = ByteArray(6) { values[18 + it].toByte() }
    val submittedSerial get() = values[42]
    val mediaGeneration get() = values[24]
    val encoderGeneration get() = values[25]
    val startedMs get() = values[26] / 1_000_000
    val delayReceivedMs get() = values[28] / 1_000_000
    val observedMs get() = values[29] / 1_000_000
    val aclHandle get() = values[31].toInt()
    val peer get() = ByteArray(6) { values[36 + it].toByte() }
    val hostFlags get() = values[35].toInt()
    val routeValid get() = hostFlags and 1 != 0
    val streaming get() = hostFlags and 2 != 0
    val encoderReady get() = hostFlags and 4 != 0
    val reporting get() = hostFlags and 8 != 0
    val delayValid get() = hostFlags and 16 != 0
    val nativeSco get() = hostFlags and 32 != 0
    val delayTenths get() = values[32].toInt()
    val delaySerial get() = values[27]
    val delayReceivedNs get() = values[28]
    val lastOpcode get() = values[53].toInt()
    val controllerStatus get() = values[54].toInt()
    val completedHandle get() = values[55].toInt()
    // Capability comes from the trusted encoder interface; owned completion below
    // remains unavailable until an exact Begin request receives its own serial.
    val clockAvailable get() = values[43] != 0L
    val holding get() = values[56] == 1L
    val clockPending get() = values[57] == 1L
    val leaseDeadlineNs get() = values[58]
    val leaseDeadlineMs get() = values[58] / 1_000_000
    val clockRequestSerial get() = values[59]
    val clockObservedNs get() = values[60]
    val clockPhase get() = values[62].toInt()
    val clockSerial get() = values[63]
    val clockEncoderGeneration get() = values[64]
    val clockPeer get() = ByteArray(6) { values[65 + it].toByte() }
    val clockRequestedInterval get() = values[71].toInt()
    val clockInterval get() = values[72].toInt()
    val clockDriftMs get() = values[73].toInt()
    val restored get() = !holding && !busy && !restorePending && !clockPending && clockInterval == 10 && baselineKnown && currentKnown &&
        baselineSlots == currentSlots
    // Runtime resets each accepted Prepare, checks its owner before the first HCI read,
    // and records a captured baseline plus restore debt before any controller write.
    val noControllerMutation get() = supported && operation == 3 && phase == 9 && error == 3 &&
        serial > 0 && leaseOwner > 0 && !busy && !holding && !baselineKnown && !currentKnown &&
        !restorePending && lastOpcode == 0 && controllerStatus == 255 && completedHandle == 65535 &&
        clockRequestSerial == 0L && !clockPending && clockPhase == 0 && clockSerial == 0L && clockInterval == 10

    fun sameHost(peer: ByteArray) = this.peer.contentEquals(peer) && routeValid &&
        mediaGeneration > 0 && encoderGeneration > 0 && aclHandle in 0..0xeff

    // Deliberately excludes peer identifiers and raw payloads from diagnostic logs.
    fun summary() = "supported=$supported busy=$busy phase=$phase serial=$serial error=$error " +
        "route=$routeValid streaming=$streaming delay=$delayTenths delaySerial=$delaySerial " +
        "hostFlags=$hostFlags reporting=$reporting delayValid=$delayValid encoderReady=$encoderReady " +
        "clockAvailable=$clockAvailable mediaGeneration=$mediaGeneration encoderGeneration=$encoderGeneration " +
        "baselineKnown=$baselineKnown baseline=$baselineSlots currentKnown=$currentKnown " +
        "current=$currentSlots restorePending=$restorePending restored=$restored noControllerMutation=$noControllerMutation " +
        "opcode=$lastOpcode controllerStatus=$controllerStatus holding=$holding " +
        "clockRequest=$clockRequestSerial clockPending=$clockPending clockPhase=$clockPhase " +
        "clockSerial=$clockSerial clockInterval=$clockInterval clockDrift=$clockDriftMs " +
        "clockActualFrames=${values[74]} clockNominalFrames=${values[75]} clockProgressFrames=${values[76]}"

    companion object {
        fun parse(input: LongArray): NativeGameSnapshot? {
            if (input.size != 79 || input[0] != 1L || input[61] != 1L) return null
            if (input.indices.any { it != 47 && it != 76 && input[it] < 0 }) return null
            if (listOf(1, 2, 9, 11, 13, 14, 56, 57).any { input[it] !in 0L..1L } ||
                input[3] !in 0L..11L || input[4] !in 1L..3L || input[7] !in 0L..7L ||
                input[10] !in 0L..2047L || input[12] !in 0L..2047L ||
                input[17] !in 0L..65535L || input[31] !in 0L..65535L ||
                input[30] !in 0L..65535L || input[32] !in 0L..65535L ||
                input[33] !in 0L..255L || input[34] !in 0L..255L || input[35] !in 0L..63L ||
                input[43] !in 0L..6L || input[45] !in 9L..11L || input[46] !in 0L..500L ||
                input[53] !in 0L..65535L || input[54] !in 0L..255L || input[55] !in 0L..65535L ||
                input[62] !in 0L..6L || input[71] !in 9L..11L || input[72] !in 9L..11L ||
                input[73] !in 0L..500L ||
                ((18..23) + (36..41) + (65..70)).any { input[it] !in 0L..255L }) return null
            return NativeGameSnapshot(input.copyOf())
        }
    }
}
