package me.kavishdevar.librepods.core.data.apple

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Uncalibrated sensor fields; the same report is carried by SensorDataWX and RemoteHID. */
@Serializable
data class MotionReport(
    @SerialName("timestamp_ns") val timestampNs: Long? = null,
    @SerialName("attitude_q15") val attitudeQ15: List<Int>,
    @SerialName("gyro_raw") val gyroRaw: List<Int>,
    @SerialName("user_acceleration_raw") val userAccelerationRaw: List<Int>,
    @SerialName("gravity_raw") val gravityRaw: List<Int>,
    val counter: Long? = null,
) {
    init {
        require(timestampNs == null || timestampNs >= 0)
        require(counter == null || counter in 0..0xffffffffL)
        require(listOf(attitudeQ15,gyroRaw,userAccelerationRaw,gravityRaw).all { a ->
            a.size == 3 && a.all { it in -32768..32767 } })
    }

    /** Captured on ordinary AirPods 3; do not apply this calibration to another model. */
    fun airPods3Quaternion(): List<Double>? {
        val v = attitudeQ15.map { it / 32768.0 }
        val norm = v.sumOf { it*it }
        if (norm > 1.01) return null
        val q = listOf(sqrt((1.0-norm).coerceAtLeast(0.0))) + v
        val length = sqrt(q.sumOf { it*it })
        return q.map { it/length }
    }

    /** Gravity-referenced rates need a measured lateral axis; they are not an absolute pose. */
    fun airPods3Rates(lateral: List<Double>): List<Double>? {
        if (lateral.size != 3 || lateral.any { !it.isFinite() }) return null
        fun dot(a: List<Double>,b: List<Double>) = a.zip(b).sumOf { (x,y) -> x*y }
        fun normalized(a: List<Double>): List<Double>? {
            val n = sqrt(dot(a,a)); return if (!n.isFinite() || n <= 0.001) null else a.map { it/n }
        }
        val up = normalized(gravityRaw.map(Int::toDouble)) ?: return null
        val projection = dot(lateral,up)
        val side = normalized(lateral.indices.map { lateral[it]-projection*up[it] }) ?: return null
        val forward = listOf(up[1]*side[2]-up[2]*side[1],up[2]*side[0]-up[0]*side[2],up[0]*side[1]-up[1]*side[0])
        val gyro = gyroRaw.map { it*0.122 }
        return listOf(dot(gyro,up),dot(gyro,side),dot(gyro,forward))
    }

    companion object {
        fun parse(bytes: ByteArray, transportTimestampNs: Long? = null): MotionReport? {
            if (bytes.size !in setOf(56,58) || bytes[0] != 1.toByte()) return null
            var time = (0..7).fold(0L) { n,i -> n or ((bytes[1+i].toLong() and 255) shl (8*i)) }
            if (time < 0) return null
            if (time == 0L) time = transportTimestampNs?.takeIf { it > 0 } ?: 0
            fun vector(at: Int) = (0..2).map { i ->
                ((bytes[at+2*i].toInt() and 255) or ((bytes[at+2*i+1].toInt() and 255) shl 8)).toShort().toInt() }
            val counter = if (bytes.size == 58) (0..3).fold(0L) { n,i -> n or ((bytes[54+i].toLong() and 255) shl (8*i)) } else null
            return MotionReport(time.takeIf { it != 0L },vector(20),vector(26),vector(38),vector(44),counter)
        }
    }
}
