/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

/** AACP 0x58 uplink; timestamps are device counters with an unverified clock. */
object MicrophonePacket {
    data class AccessUnit(val timestamp: Long, val bytes: ByteArray)

    fun isMicrophoneMessage(packet: ByteArray): Boolean = packet.size >= 6 &&
        packet[0] == 4.toByte() && packet[1] == 0.toByte() &&
        packet[2] == 4.toByte() && packet[3] == 0.toByte() &&
        packet[4] == 0x58.toByte() && packet[5] == 0.toByte()

    fun isAudio(packet: ByteArray): Boolean = isMicrophoneMessage(packet) &&
        packet.size >= 8 && packet[6] == 1.toByte() && packet[7] == 0.toByte()

    /** No AU escapes validation when a later record in the SDU is truncated. */
    fun parse(packet: ByteArray): List<AccessUnit> {
        require(isAudio(packet) && packet.size > 22) { "Invalid microphone SDU header" }
        var offset = 22
        while (offset < packet.size) {
            require(packet.size - offset >= 5) { "Truncated microphone record" }
            val length = packet[offset + 4].toInt() and 0xff
            require(length > 0 && length <= packet.size - offset - 5) {
                "Invalid microphone access unit length"
            }
            offset += 5 + length
        }
        val units = mutableListOf<AccessUnit>()
        offset = 22
        while (offset < packet.size) {
            val length = packet[offset + 4].toInt() and 0xff
            var timestamp = 0L
            for (i in 0..3) timestamp = timestamp or
                ((packet[offset + i].toLong() and 0xff) shl (8 * i))
            units += AccessUnit(timestamp, packet.copyOfRange(offset + 5, offset + 5 + length))
            offset += 5 + length
        }
        return units
    }
}
