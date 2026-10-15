/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

/** Key payloads and microphone audio must never enter packet broadcasts or history. */
object PrivateAacpPacket {
    fun isPrivate(packet: ByteArray): Boolean = packet.getOrNull(4)?.toInt() in listOf(0x31, 0x58)

    fun isPrivateLog(line: String): Boolean = line.substringAfter(": ", line).trim()
        .split(Regex("\\s+")).getOrNull(4)?.toIntOrNull(16) in listOf(0x31, 0x58)
}

object ProximityKeyPacket {
    fun parse(packet: ByteArray): Map<Int, ByteArray> {
        require(packet.size in 7..4096 && HEADER.indices.all { packet[it] == HEADER[it] }) { "Invalid key response" }
        val count = packet[6].toInt() and 0xff
        require(count <= 32) { "Too many key records" }
        val keys = mutableMapOf<Int, ByteArray>()
        var offset = 7
        repeat(count) {
            require(packet.size - offset >= 4) { "Truncated key record" }
            val type = (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
            val length = (packet[offset + 2].toInt() and 0xff) or ((packet[offset + 3].toInt() and 0xff) shl 8)
            offset += 4
            require(length <= packet.size - offset) { "Truncated key value" }
            if (type == 1 || type == 4) {
                require(length == 16 && type !in keys) { "Invalid or duplicate proximity key" }
                val key = packet.copyOfRange(offset, offset + length)
                require(key.any { it != 0.toByte() }) { "Invalid zero proximity key" }
                keys[type] = key
            }
            offset += length
        }
        require(offset == packet.size) { "Trailing key response data" }
        return keys
    }

    private val HEADER = byteArrayOf(4, 0, 4, 0, 0x31, 0)
}
