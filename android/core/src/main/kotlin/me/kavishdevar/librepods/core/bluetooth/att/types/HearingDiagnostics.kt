package me.kavishdevar.librepods.core.bluetooth.att.types

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Saved device records, never a current diagnosis or proof that a new self-check completed. */
data class SavedHearingDiagnostics(val left: SavedEarMeasurement, val right: SavedEarMeasurement,
    val rawAnc: List<Int>, val opaqueTail: List<Int>)

data class SavedEarMeasurement(val timestamp: ULong, val rawResult: UInt, val firstUse: UInt,
    val componentFlags: Map<String, Boolean>?, val ancScores: List<Int>?) {
    val generalStatus: Int get() = (rawResult and 7u).toInt()
    val measurementStatus: Int get() = ((rawResult shr 3) and 7u).toInt()
    val completed: Boolean get() = timestamp != 0uL && measurementStatus in 0..1
    val ancSelector: Boolean get() = rawResult and 0x8000u != 0u
    val uninterpretedBits: UInt get() = rawResult and 0xffff0040u
}

fun parseSavedHearingDiagnostics(bytes: ByteArray, product: Int): SavedHearingDiagnostics? {
    if (bytes.size != 71 || bytes[0] != 8.toByte()) return null
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun ear(right: Boolean): SavedEarMeasurement {
        val timestamp = data.getLong(if (right) 9 else 1).toULong()
        val result = data.getInt(if (right) 21 else 17).toUInt()
        val completed = timestamp != 0uL && ((result shr 3) and 7u) <= 1u
        val components = if (completed && product in setOf(0x2014, 0x2024, 0x2027))
            listOf("thd", "frequency", "speaker", "rearVent", "frontVent", "bottomMic", "topMic", "innerMic")
                .mapIndexed { index, label -> label to (result and (1u shl (index + 7)) != 0u) }.toMap() else null
        val scores = if (completed && product == 0x201b)
            listOf(27, 29, 31).map { bytes[it + if (right) 1 else 0].toInt() and 255 } else null
        return SavedEarMeasurement(timestamp, result, data.getInt(if (right) 37 else 33).toUInt(), components, scores)
    }
    return SavedHearingDiagnostics(ear(false), ear(true), bytes.sliceArray(25..32).map { it.toInt() and 255 },
        bytes.sliceArray(41..70).map { it.toInt() and 255 })
}

data class HearingSelfCheckBaseline(val left: ULong, val right: ULong) {
    fun hasTwoFreshCompletedResults(record: SavedHearingDiagnostics): Boolean =
        record.left.completed && record.right.completed && record.left.timestamp != left && record.right.timestamp != right
}

object HearingDiagnosticUuids {
    val savedResults: UUID = UUID.fromString("a4120002-95c5-4d6f-9098-0f0b41457e0a")
    val selfCheck: UUID = UUID.fromString("a4120004-95c5-4d6f-9098-0f0b41457e0a")
    val fitting: UUID = UUID.fromString("a4120005-95c5-4d6f-9098-0f0b41457e0a")
    val sensitivity: UUID = UUID.fromString("82f6bc0a-1bca-4873-afc9-ec5890e3469a")
    val volumeCurve: UUID = UUID.fromString("d5f96afa-2f2c-41bb-a7e6-f54abe6235b4")
}

fun parseHearingSensitivity(bytes: ByteArray): Float? = parseHearingMetadata(bytes, 1)?.single()
fun parseHearingVolumeCurve(bytes: ByteArray): List<Float>? = parseHearingMetadata(bytes, 17)

private fun parseHearingMetadata(bytes: ByteArray, count: Int): List<Float>? {
    if (bytes.size != count * 4) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return List(count) { buffer.float }.takeIf { it.all(Float::isFinite) }
}
