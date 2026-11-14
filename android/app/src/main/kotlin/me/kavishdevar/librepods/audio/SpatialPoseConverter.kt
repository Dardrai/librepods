/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.core.data.apple.MotionReport
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Explicit, session-specific anatomical axes. Rows map raw-body vectors to right/front/up. */
internal class SpatialPoseProfile private constructor(val headFromRaw: List<List<Double>>,
    internal val referenceTimestampNs: Long? = null, internal val referenceCounter: Long? = null) {
    companion object {
        fun fromBasis(rows: List<List<Double>>): SpatialPoseProfile? {
            if (rows.size != 3 || rows.any { it.size != 3 || it.any { n -> !n.isFinite() } }) return null
            val axes = rows.map { V.of(it) }
            if (axes.any { abs(it.norm() - 1) > 1e-5 } ||
                abs(axes[0].dot(axes[1])) > 1e-5 || abs(axes[0].dot(axes[2])) > 1e-5 ||
                abs(axes[1].dot(axes[2])) > 1e-5 || abs(axes[0].cross(axes[1]).dot(axes[2]) - 1) > 1e-5) return null
            return SpatialPoseProfile(rows.map { it.toList() })
        }

        /** The caller must identify a level neutral hold, positive nose-UP-only excursion, and return.
         * The settled final 500 ms of neutral/return validate world +Z as up in this sensor session.
         * UP gravity is intentionally unused: it can lag the attitude during movement.
         */
        fun calibrate(neutral: List<MotionReport>, up: List<MotionReport>, returned: List<MotionReport>): SpatialPoseProfile? {
            val ready = neutral.dropWhile { it.attitudeQ15.all { n -> n == 0 } }
            if (ready.size < 5 || returned.size < 5 || up.size < 3) return null
            val all = ready + up + returned
            if (all.zipWithNext().any { (a, b) ->
                    val before = a.timestampNs; val after = b.timestampNs
                    before == null || after == null || before <= 0 || after <= before || !counterFollows(a.counter, b.counter)
                }) return null
            val quaternions = all.map { attitude(it) ?: return null }
            val neutralTail = settledTail(ready); val returnTail = settledTail(returned)
            if (neutralTail.size < 5 || returnTail.size < 5) return null
            val base = meanHold(neutralTail.map { checkNotNull(attitude(it)) }) ?: return null
            val finish = meanHold(returnTail.map { checkNotNull(attitude(it)) }) ?: return null
            if ((base.inverse() * finish).rotationVector().norm() > radians(5.0)) return null
            if (!levelGravity(neutralTail, base) || !levelGravity(returnTail, finish)) return null
            val z = base.inverse().rotate(V(0.0, 0.0, 1.0))
            val axes = quaternions.drop(ready.size).take(up.size).mapNotNull { q ->
                val delta = (base.inverse() * q).rotationVector()
                val angle = delta.norm()
                if (angle < radians(8.0)) return@mapNotNull null
                val horizontal = delta - z * delta.dot(z)
                if (horizontal.norm() / angle < cos(radians(10.0))) return null
                horizontal.unit()
            }
            if (axes.size < 3) return null
            val x = axes.reduce(V::plus).unit()
            if (axes.any { angleBetween(it, x) > radians(5.0) }) return null
            val y = z.cross(x).unit()
            val basis = fromBasis(listOf(x.list(), y.list(), z.list())) ?: return null
            return SpatialPoseProfile(basis.headFromRaw, returned.last().timestampNs, returned.last().counter)
        }

        private fun meanHold(values: List<Q>): Q? {
            val mean = values.reduce(Q::plus).unit()
            return mean.takeIf { q -> values.all { (q.inverse() * it).rotationVector().norm() <= radians(2.0) } }
        }

        private fun settledTail(reports: List<MotionReport>): List<MotionReport> {
            val start = checkNotNull(reports.last().timestampNs) - 500_000_000L
            return reports.takeLastWhile { checkNotNull(it.timestampNs) >= start }
        }

        private fun levelGravity(reports: List<MotionReport>, attitude: Q): Boolean {
            val gravity = reports.map {
                val v = V.of(it.gravityRaw.map(Int::toDouble))
                if (v.norm() < 0.001) return false
                v.unit()
            }
            val mean = gravity.reduce(V::plus)
            if (mean.norm() < 0.001) return false
            val direction = mean.unit()
            return gravity.all { angleBetween(it, direction) <= radians(5.0) } &&
                angleBetween(attitude.rotate(direction), V(0.0, 0.0, -1.0)) <= radians(5.0)
        }
    }
}

internal data class SpatialPose(val timestampNs: Long, val discontinuity: Int,
    val rotationVector: List<Float>, val angularVelocity: List<Float>, val intervalNs: Long)

/** Pure positive-w conversion for a caller-validated session; no stream, device, or audio ownership.
 * The caller retires this instance on role, transport or known reference changes. Invalid input after
 * startup also retires it. Rates are head-body finite differences over intervalNs (normally ~20 ms),
 * not instantaneous raw-gyro rates. No prediction or latency compensation is applied.
 */
internal class SpatialPoseConverter(private val profile: SpatialPoseProfile, private val discontinuity: Int) {
    private val rawFromHead = Q.fromMatrix(List(3) { r -> List(3) { c -> profile.headFromRaw[c][r] } })
    private var previous: Q? = null
    private var previousTime = 0L
    private var previousCounter: Long? = null
    var invalidReason: String? = null
        private set
    val invalidated get() = invalidReason != null

    init { require(discontinuity in 0..255) }

    fun invalidate() { if (!invalidated) invalidReason = "session_changed" }

    fun accept(report: MotionReport): SpatialPose? {
        if (invalidated) return null
        // Sensor startup uses zero-filled attitude; it cannot establish a reference or fresh rate.
        if (previous == null && report.attitudeQ15.all { it == 0 }) return null
        val time = report.timestampNs
        val raw = attitude(report)
        if (time == null || time <= 0) return retire("sensor_time_unavailable")
        if (raw == null) return retire("unsupported_attitude")
        val head = (raw * rawFromHead).unit()
        val old = previous
        if (old == null) {
            profile.referenceTimestampNs?.let { reference ->
                if (time == reference && report.counter == profile.referenceCounter) return null
                if (time <= reference) return retire("sensor_time_changed")
                if (!counterFollows(profile.referenceCounter, report.counter)) return retire("sensor_counter_changed")
            }
            previous = head; previousTime = time; previousCounter = report.counter
            return null
        }
        val dt = time - previousTime
        if (dt !in 1..100_000_000L) return retire("sensor_time_changed")
        if (!counterFollows(previousCounter, report.counter)) return retire("sensor_counter_changed")
        val omega = (old.inverse() * head).rotationVector() * (1e9 / dt)
        if (!omega.norm().isFinite() || omega.norm() > 32.0) return retire("reference_discontinuity")
        val rotation = head.rotationVector()
        if (!rotation.norm().isFinite()) return retire("unsupported_attitude")
        previous = head; previousTime = time; previousCounter = report.counter
        return SpatialPose(time, discontinuity, rotation.floats(), omega.floats(), dt)
    }

    private fun retire(reason: String): SpatialPose? { invalidReason = reason; return null }
}

private fun attitude(report: MotionReport): Q? {
    if (report.attitudeQ15.all { it == 0 }) return null
    val q = report.quaternionQ15PositiveW()?.let { Q(it[0], it[1], it[2], it[3]) } ?: return null
    // Near w=0 the untransmitted sign cannot safely identify the continuing quaternion branch.
    return q.takeIf { it.w > 0.1 }
}

private fun counterFollows(before: Long?, after: Long?): Boolean = before == null || after != null &&
    ((after - before) and 0xffffffffL) in 1..0x7fffffffL
private fun radians(degrees: Double) = degrees * Math.PI / 180
private fun angleBetween(a: V, b: V) = acos(a.dot(b).coerceIn(-1.0, 1.0))

private data class V(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: V) = V(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: V) = V(x - b.x, y - b.y, z - b.z)
    operator fun times(n: Double) = V(x * n, y * n, z * n)
    fun dot(b: V) = x * b.x + y * b.y + z * b.z
    fun cross(b: V) = V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun norm() = sqrt(dot(this))
    fun unit() = this * (1 / norm())
    fun list() = listOf(x, y, z)
    fun floats() = listOf(x.toFloat(), y.toFloat(), z.toFloat())
    companion object { fun of(v: List<Double>) = V(v[0], v[1], v[2]) }
}

private data class Q(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: Q) = Q(w + b.w, x + b.x, y + b.y, z + b.z)
    operator fun times(b: Q) = Q(w*b.w - x*b.x - y*b.y - z*b.z,
        w*b.x + x*b.w + y*b.z - z*b.y, w*b.y - x*b.z + y*b.w + z*b.x,
        w*b.z + x*b.y - y*b.x + z*b.w)
    fun inverse() = Q(w, -x, -y, -z)
    fun unit(): Q {
        val n = sqrt(w*w + x*x + y*y + z*z)
        return Q(w/n, x/n, y/n, z/n)
    }
    fun rotate(v: V): V = (this * Q(0.0, v.x, v.y, v.z) * inverse()).let { V(it.x, it.y, it.z) }
    fun rotationVector(): V {
        val sign = if (w < 0) -1.0 else 1.0
        val v = V(x * sign, y * sign, z * sign)
        val n = v.norm()
        return v * if (n < 1e-10) 2.0 else 2 * atan2(n, w * sign) / n
    }
    companion object {
        fun fromMatrix(m: List<List<Double>>): Q {
            val trace = m[0][0] + m[1][1] + m[2][2]
            if (trace > 0) {
                val s = sqrt(trace + 1) * 2
                return Q(s/4, (m[2][1]-m[1][2])/s, (m[0][2]-m[2][0])/s, (m[1][0]-m[0][1])/s).unit()
            }
            val i = (0..2).maxBy { m[it][it] }; val j = (i+1)%3; val k = (i+2)%3
            val s = sqrt(1 + m[i][i] - m[j][j] - m[k][k]) * 2
            val v = DoubleArray(3)
            v[i] = s/4; v[j] = (m[i][j]+m[j][i])/s; v[k] = (m[i][k]+m[k][i])/s
            return Q((m[k][j]-m[j][k])/s, v[0], v[1], v[2]).unit()
        }
    }
}
