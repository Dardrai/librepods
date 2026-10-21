package me.kavishdevar.librepods.bluetooth.cases

import java.util.UUID

internal enum class CaseSoundRoute(val label: String, val service: UUID, val characteristic: UUID) {
    DULT("DULT", UUID.fromString("15190001-12f4-c226-88ed-2ac5579f2a85"), UUID.fromString("8e0c0001-1d68-fb92-bf61-48377421680e")),
    HAWKEYE("Hawkeye configuration", UUID.fromString("0000fd44-0000-1000-8000-00805f9b34fb"), UUID.fromString("4f860002-943b-49ef-bed4-2f730304427a")),
    NON_OWNER("Hawkeye non-owner", HAWKEYE.service, UUID.fromString("4f860003-943b-49ef-bed4-2f730304427a"));

    fun command(stop: Boolean): ByteArray = when (this) {
        DULT -> byteArrayOf(if (stop) 1 else 0, 3)
        HAWKEYE -> byteArrayOf(1, if (stop) 1 else 0, 2)
        NON_OWNER -> byteArrayOf(1, if (stop) 1 else 0, 3)
    }

    fun response(bytes: ByteArray): CaseSoundResponse? = when (this) {
        DULT -> when {
            bytes.contentEquals(byteArrayOf(3, 3)) -> CaseSoundResponse.Completed
            bytes.size == 6 && bytes[0] == 2.toByte() && bytes[1] == 3.toByte() &&
                bytes[3] == 3.toByte() && bytes[2].toInt() in 0..1 ->
                CaseSoundResponse.Acknowledged(bytes[2] == 1.toByte(), bytes.u16(4))
            else -> null
        }
        HAWKEYE -> when {
            bytes.contentEquals(byteArrayOf(1, 13, 2)) -> CaseSoundResponse.Completed
            bytes.size == 7 && bytes.take(3) == listOf<Byte>(1, 11, 2) &&
                bytes[4] == 2.toByte() && bytes[3].toInt() in 0..1 ->
                CaseSoundResponse.Acknowledged(bytes[3] == 1.toByte(), bytes.u16(5))
            else -> null
        }
        // The non-owner reply framing has not been established. Do not borrow configuration replies.
        NON_OWNER -> null
    }
}

internal sealed interface CaseSoundResponse {
    data class Acknowledged(val stop: Boolean, val status: Int): CaseSoundResponse
    data object Completed: CaseSoundResponse
}

internal val CASE_PNP_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
internal val CASE_PNP_CHARACTERISTIC: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
internal val CASE_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)

// Only the Pro 3 case identity has been checked; this does not establish ownership or a bud-to-case link.
internal fun verifiedPro3Case(pnp: ByteArray): Boolean = pnp.size == 7 &&
    pnp[0] == 1.toByte() && pnp.u16(1) == 0x004c && pnp.u16(3) == 0x2029 && pnp.u16(5) == 1

internal fun validCaseCccd(bytes: ByteArray): Boolean = bytes.size == 2 && bytes.u16(0) in 0..3
