package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSource
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.PacketDestination

data class AudioSourcePacket(
    val audioSource: AudioSource,
    override val payload: ByteArray,
): AACPPacket {
    override val destination: PacketDestination = PacketDestination.HOST

    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.AUDIO_SOURCE

    companion object {
        fun parse(
            packet: ByteArray,
        ): AudioSourcePacket {
            require(packet.size == 13 && packet.copyOfRange(0, 6).contentEquals(byteArrayOf(4, 0, 4, 0, 0x0e, 0))) { "Invalid audio-source report" }

            val payload = packet.copyOfRange(6, packet.size)
            val address = payload.copyOfRange(0, 6).reversedArray()
            val macAddress = MacAddress(address.toHexString(HexFormat{ upperCase = true }).chunked(2).joinToString(":"))

            val typeByte = payload[6]
            val type = AudioSourceType.fromByte(typeByte)
            require(type != AudioSourceType.UNKNOWN && (type == AudioSourceType.NONE || validRoutingAddress(address))) { "Invalid audio-source identity or type" }

            val audioSource = AudioSource(macAddress, type)

            return AudioSourcePacket(audioSource, payload)
        }
    }
}
