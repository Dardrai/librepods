package me.kavishdevar.librepods.core.devices

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse

/** Native legacy Audiogram normalization, explicitly seeded from a validated stored fitting. */
class AccommodationsAudiogram private constructor(val session: Long, private val source: ByteArray,
    private val normalized: FloatArray) {
    internal fun sameSource(other: AccommodationsAudiogram?): Boolean =
        other != null && session == other.session && source.contentEquals(other.source)

    internal fun matchesPme(packet: ByteArray): Boolean = validLegacyAccommodationsPacket(packet) &&
        packet.copyOfRange(12, 140).contentEquals(curves())

    internal fun curves(): ByteArray = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(4) { normalized.forEach { value -> putFloat(value) } }
    }.array()

    internal fun transparency(baseline: ByteArray): ByteArray? {
        if (parseTransparencySettingsResponse(baseline) == null || baseline.size != 104) return null
        return baseline.copyOf().also { result ->
            val target = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in intArrayOf(4, 52)) normalized.forEachIndexed { index, value ->
                target.putFloat(offset + index * 4, if (value <= 0f) 0f else value.coerceAtMost(80f))
            }
        }
    }

    companion object {
        fun parse(source: ByteArray, session: Long): AccommodationsAudiogram? {
            val snapshot = source.copyOf()
            if (session <= 0 || !hearingAidSettingsWritable(snapshot)) return null
            val data = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
            val left = DoubleArray(8) { data.getFloat(4 + it * 4).toDouble() }
            val right = DoubleArray(8) { data.getFloat(52 + it * 4).toDouble() }
            val pta = intArrayOf(1, 2, 3, 5)
            val leftPta = pta.sumOf { left[it] } / 4.0
            val rightPta = pta.sumOf { right[it] } / 4.0
            val common = if (abs(leftPta - rightPta) < 15.0)
                DoubleArray(8) { Math.fma(0.5, left[it], 0.5 * right[it]) }
            else if (leftPta < rightPta) left else right
            // The native dictionary uses zero as missing above 250 Hz; 10 kHz uses its upper endpoint.
            val normalized = FloatArray(8) { index ->
                (if (index in 1..6 && common[index] == 0.0) common[7] else common[index]).toFloat()
            }
            return AccommodationsAudiogram(session, snapshot, normalized)
        }
    }
}
