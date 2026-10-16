/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

/** Check the known characteristic before choosing an ATT write method, then verify its value. */
internal fun ATTBearer.writeVerifiedSetting(handle: ATTHandles, value: ByteArray, timeoutMillis: Long,
    canSend: () -> Boolean): Boolean {
    val writeOpcode = if (handle == ATTHandles.LOUD_SOUND_REDUCTION) 0x12 else {
        val declaration = handle.value - 1
        val response = request(byteArrayOf(0x08, declaration.toByte(), 0, handle.value.toByte(), 0, 0x03, 0x28),
            0x09, timeoutMillis, canSend) ?: return false
        val expectedUuid = when (handle) {
            ATTHandles.TRANSPARENCY -> "90534d74af9e0394db45abf7c11c62d5"
            ATTHandles.HEARING_AID -> "0a7e45410b0f98906f4dc595050012a4"
            else -> return false
        }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (response.size != 23 || response[1] != 21.toByte() ||
            response[2] != declaration.toByte() || response[3] != 0.toByte() ||
            response[5] != handle.value.toByte() || response[6] != 0.toByte() ||
            !response.copyOfRange(7, 23).contentEquals(expectedUuid)) return false
        val properties = response[4].toInt() and 255
        if (properties and 0x02 == 0) return false
        when {
            properties and 0x08 != 0 -> 0x12
            properties and 0x04 != 0 -> 0x52
            else -> return false
        }
    }
    val pdu = byteArrayOf(writeOpcode.toByte(), handle.value.toByte(), 0) + value
    val sent = if (writeOpcode == 0x12) request(pdu, 0x13, timeoutMillis, canSend) != null
        else command(pdu, canSend)
    if (!sent) return false
    val readback = request(byteArrayOf(0x0A, handle.value.toByte(), 0), 0x0B, timeoutMillis, canSend)
        ?: return false
    return readback.copyOfRange(1, readback.size).contentEquals(value)
}
