package me.kavishdevar.librepods.core.bluetooth.aacp

internal fun validRawAacpPacket(data: ByteArray): Boolean = data.size in 6..65_535 &&
    data[1] == 0.toByte() && data[3] == 0.toByte() && data[5] == 0.toByte()

internal fun validAacpMessage(data: ByteArray): Boolean = validRawAacpPacket(data) &&
    data[0] == 4.toByte() && data[2] == 4.toByte()

/** Captured service-4 ConnectResponse: status zero, version 1.2 or 1.3, eight feature bytes. */
internal fun successfulSettingsConnectResponse(data: ByteArray): Boolean = data.size == 18 &&
    data.copyOfRange(0, 8).contentEquals(byteArrayOf(1, 0, 4, 0, 0, 0, 1, 0)) &&
    data[8] in byteArrayOf(2, 3) && data[9] == 0.toByte()
