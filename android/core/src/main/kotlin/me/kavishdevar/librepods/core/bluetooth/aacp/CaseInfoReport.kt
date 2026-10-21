package me.kavishdevar.librepods.core.bluetooth.aacp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Bud-side metadata only; never authorization to connect to a nearby BLE case. Not persisted. */
data class CaseInfoReport(val vendor: Int, val product: UInt, val vendorSource: Int, val color: Int,
    val version: ULong, val reserved: UInt, val name: String)

internal fun parseCaseInfoReport(packet: ByteArray): CaseInfoReport? {
    if (packet.size != 31 || packet.take(7) != listOf<Byte>(4, 0, 4, 0, 0x23, 0, 3)) return null
    val body = packet.copyOfRange(6, 31)
    val extension = 14 + (body[12].toInt() and 255)
    if (extension + 10 != body.size || body[extension - 1] != 0.toByte()) return null
    val nameBytes = body.copyOfRange(13, extension - 1)
    if (nameBytes.any { it == 0.toByte() }) return null
    val name = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(nameBytes)).toString()
    } catch (_: Exception) { return null }
    val data = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
    val versionBytes = body.copyOfRange(10, 12) + body.copyOfRange(extension, extension + 6)
    return CaseInfoReport(data.getShort(1).toInt() and 65535, data.getInt(3).toUInt(),
        data.getShort(7).toInt() and 65535, body[9].toInt() and 255,
        ByteBuffer.wrap(versionBytes).order(ByteOrder.LITTLE_ENDIAN).long.toULong(), data.getInt(extension + 6).toUInt(), name)
}
