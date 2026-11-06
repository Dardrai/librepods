package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import java.nio.ByteBuffer
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode

internal fun validRoutingAddress(address: ByteArray): Boolean = address.size == 6 &&
    address.any { it != 0.toByte() } && address.any { it != 0xff.toByte() }

internal data class RoutingRequest(val sender: MacAddress, val reverseTapped: Boolean)

internal fun routingRequestPacket(target: String, reverse: Boolean): AACPPacket {
    val address = MacAddress(target).value.split(':').map { it.toInt(16).toByte() }.toByteArray()
    require(validRoutingAddress(address))
    val buffer = ByteBuffer.allocate(if (reverse) 97 else 106)
    buffer.put(address.reversedArray())
    buffer.put(byteArrayOf(if (reverse) 0x59 else 0x62, 0))
    if (reverse) {
        buffer.put(byteArrayOf(1, 0xe3.toByte(), 0x5f))
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(byteArrayOf(1, 0x59))
        buffer.put("audioRoutingShowReverseUI".toByteArray())
        buffer.put(byteArrayOf(1, 0x46))
        buffer.put("reason".toByteArray())
        buffer.put(0x53)
        buffer.put("ReverseBannerTapped".toByteArray())
    } else {
        buffer.put(byteArrayOf(1, 0xe5.toByte(), 0x4a))
        buffer.put("localscore".toByteArray())
        buffer.put(byteArrayOf(0x30, 0x64, 0x46))
        buffer.put("reason".toByteArray())
        buffer.put(0x48)
        buffer.put("Hijackv2".toByteArray())
        buffer.put(0x51)
        buffer.put("audioRoutingScore".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2d, 1, 0x5f))
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(byteArrayOf(1, 0x4b))
        buffer.put("remotescore".toByteArray())
        buffer.put(0xa5.toByte())
    }
    check(!buffer.hasRemaining())
    return AACPPacket.createUnknownPacket(MessageOpcode.SMART_ROUTING, buffer.array())
}

/** Unknown relay dictionaries stay passive until their wire schema is established. */
internal fun parseRoutingRequest(packet: ByteArray, connected: Set<MacAddress>): RoutingRequest? {
    if (packet.size !in 14..144 || !packet.copyOfRange(0, 6).contentEquals(byteArrayOf(4, 0, 4, 0, 0x11, 0)) ||
        (packet[12].toInt() and 255 or ((packet[13].toInt() and 255) shl 8)) != packet.size - 14) return null
    val address = packet.copyOfRange(6, 12).reversedArray()
    if (!validRoutingAddress(address)) return null
    val sender = MacAddress(address.joinToString(":") { "%02X".format(it) })
    if (sender !in connected) return null
    for (reverse in listOf(false, true)) {
        val expected = routingRequestPacket(sender.value, reverse).rawPacket
        if (packet.size == expected.size && packet.copyOfRange(14, packet.size).contentEquals(expected.copyOfRange(14, expected.size)))
            return RoutingRequest(sender, reverse)
    }
    return null
}
