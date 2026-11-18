/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Standard Android pose values only. This codec never interprets an earbud's raw sensor units. */
internal class HeadTrackerUhidProtocol(address: String) {
    private var started = false
    private var opened = false
    private var identityRead = false
    private var controlsRead = false
    private var controls = 0
    val reporting get() = started && opened && identityRead && controls and 3 == 3
    val halHandshake get() = identityRead && controlsRead
    val intervalNanos get() = 10_000_000L + (controls shr 2) * 90_000_000L / 63
    private val identity = ByteArray(40).apply {
        this[0] = 2
        "#AndroidHeadTracker#1.0".toByteArray(Charsets.US_ASCII).copyInto(this, 1)
        this[32] = 'B'.code.toByte(); this[33] = 'T'.code.toByte()
        A2dpRecoveryGuard(address).address.split(':').forEachIndexed { index, octet -> this[34 + index] = octet.toInt(16).toByte() }
    }

    fun create(): ByteArray = event(CREATE2).apply {
        "LibrePods head tracker".toByteArray(Charsets.US_ASCII).copyInto(this, 4)
        "librepods/head-tracker".toByteArray(Charsets.US_ASCII).copyInto(this, 132)
        little(this).putShort(260, descriptor.size.toShort()).putShort(262, 6).putInt(272, 1)
        descriptor.copyInto(this, 280)
    }

    fun handle(bytes: ByteArray): ByteArray? {
        require(bytes.size == EVENT_BYTES) { "short_uhid_event" }
        val input = little(bytes)
        return when (input.getInt(0)) {
            START -> {
                check(input.getLong(4) and 5L == 5L) { "unnumbered_uhid_reports" }
                started = true; null
            }
            STOP -> { started = false; null }
            OPEN -> { opened = true; null }
            CLOSE -> { opened = false; null }
            GET_REPORT -> {
                val number = bytes[8].toInt() and 255
                val feature = bytes[9].toInt() == 0
                val payload = when {
                    feature && number == 1 -> byteArrayOf(1, controls.toByte()).also { controlsRead = true }
                    feature && number == 2 -> identity.also { identityRead = true }
                    else -> null
                }
                event(GET_REPORT_REPLY).apply {
                    little(this).putInt(4, input.getInt(4)).putShort(8, (if (payload == null) 5 else 0).toShort())
                        .putShort(10, (payload?.size ?: 0).toShort())
                    payload?.copyInto(this, 12)
                }
            }
            SET_REPORT -> {
                val valid = bytes[8].toInt() == 1 && bytes[9].toInt() == 0 &&
                    input.getShort(10).toInt() == 2 && bytes[12].toInt() == 1
                if (valid) controls = bytes[13].toInt() and 255
                event(SET_REPORT_REPLY).apply {
                    little(this).putInt(4, input.getInt(4)).putShort(8, (if (valid) 0 else 5).toShort())
                }
            }
            else -> null
        }
    }

    companion object {
        const val EVENT_BYTES = 4380
        const val FRAME_BYTES = 40
        const val FRAME_MAGIC = 0x31505448 // ASCII HTP1, little endian.
        const val FRESH_NANOS = 250_000_000L
        // shortcut: bridge short bursts with constant velocity; revisit when transport delivers steadily.
        const val MAX_EXTRAPOLATION_NANOS = 40_000_000L
        const val STARTUP_NANOS = 5_000_000_000L
        const val START = 2; const val STOP = 3; const val OPEN = 4; const val CLOSE = 5
        const val GET_REPORT = 9; const val GET_REPORT_REPLY = 10; const val CREATE2 = 11
        const val INPUT2 = 12; const val SET_REPORT = 13; const val SET_REPORT_REPLY = 14
        private const val ROTATION_MAX = 3.14159265
        // Float components can round a pi-length vector slightly above pi.
        private val ROTATION_TOLERANCE = Math.ulp(Math.PI.toFloat()).toDouble()

        data class Pose(val timestampNanos: Long, val discontinuity: Int, val values: FloatArray)

        fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun event(type: Int): ByteArray = ByteArray(EVENT_BYTES).also { little(it).putInt(0, type) }

        fun decodePose(bytes: ByteArray, now: Long): Pose {
            require(bytes.size == FRAME_BYTES) { "pose_frame_size" }
            val input = little(bytes)
            require(input.int == FRAME_MAGIC) { "pose_frame_version" }
            val discontinuity = input.int
            val timestamp = input.long
            val values = FloatArray(6) { input.float }
            require(discontinuity in 0..255 && timestamp > 0 && timestamp <= now) { "pose_frame_metadata" }
            require(values.all { it.isFinite() }) { "nonfinite_pose" }
            fun norm(first: Int) = sqrt((first until first + 3).sumOf { values[it].toDouble() * values[it] })
            require(norm(0) <= Math.PI + ROTATION_TOLERANCE && norm(3) <= 32.0) { "pose_out_of_range" }
            return Pose(timestamp, discontinuity, values)
        }

        /** Extrapolate only elapsed host age, preserving the measurement's original freshness. */
        fun poseAtHostTime(pose: Pose, atNs: Long): Pose? {
            if (atNs < pose.timestampNanos) return null
            val age = atNs - pose.timestampNanos
            if (age > MAX_EXTRAPOLATION_NANOS) return null
            val values = pose.values
            if (age == 0L || (3..5).all { values[it] == 0f }) return pose
            fun quaternion(x: Double, y: Double, z: Double): DoubleArray {
                val angle = sqrt(x*x + y*y + z*z)
                val scale = if (angle < 1e-12) 0.5 else sin(angle / 2) / angle
                return doubleArrayOf(cos(angle / 2), x*scale, y*scale, z*scale)
            }
            val q = quaternion(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
            val seconds = age / 1e9
            val d = quaternion(values[3]*seconds, values[4]*seconds, values[5]*seconds)
            // Body angular velocity is intrinsic: right-multiply the measured orientation.
            val product = doubleArrayOf(
                q[0]*d[0] - q[1]*d[1] - q[2]*d[2] - q[3]*d[3],
                q[0]*d[1] + q[1]*d[0] + q[2]*d[3] - q[3]*d[2],
                q[0]*d[2] - q[1]*d[3] + q[2]*d[0] + q[3]*d[1],
                q[0]*d[3] + q[1]*d[2] - q[2]*d[1] + q[3]*d[0],
            )
            val norm = sqrt(product.sumOf { it*it })
            val hemisphere = if (product[0] < 0) -1.0 else 1.0
            for (index in product.indices) product[index] *= hemisphere / norm
            val vectorNorm = sqrt((1..3).sumOf { product[it]*product[it] })
            val scale = if (vectorNorm < 1e-12) 2.0 else 2 * atan2(vectorNorm, product[0]) / vectorNorm
            val advanced = values.copyOf()
            for (index in 0..2) advanced[index] = (product[index + 1] * scale).toFloat()
            if ((0..2).all { advanced[it] == values[it] }) return pose
            return Pose(pose.timestampNanos, pose.discontinuity, advanced)
        }

        fun input(pose: Pose, destination: ByteArray = event(INPUT2)): ByteArray = destination.apply {
            require(size == EVENT_BYTES)
            little(this).putInt(0, INPUT2)
            val output = little(this).putShort(4, 14)
            this[6] = 1
            pose.values.forEachIndexed { index, value ->
                val scale = if (index < 3) ROTATION_MAX else 32.0
                val encoded = (value.toDouble() * 32767 / scale).roundToInt().coerceIn(-32767, 32767)
                output.putShort(7 + index * 2, encoded.toShort())
            }
            this[19] = pose.discontinuity.toByte()
        }

        // Byte-for-byte Android v1 descriptor already proven by the standalone synthetic probe.
        private val descriptor = intArrayOf(
            0x05,0x20,0x09,0xe1,0xa1,0x01,0x85,0x02,
            0x0a,0x08,0x03,0x15,0x00,0x26,0xff,0x00,0x75,0x08,0x95,0x17,0xb1,0x03,
            0x0a,0x02,0x03,0x95,0x10,0xb1,0x03,0x85,0x01,
            0x0a,0x16,0x03,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x01,0xa1,0x02,
            0x0a,0x40,0x08,0x0a,0x41,0x08,0xb1,0x00,0xc0,
            0x0a,0x19,0x03,0xa1,0x02,0x0a,0x55,0x08,0x0a,0x51,0x08,0xb1,0x00,0xc0,
            0x0a,0x0e,0x03,0x15,0x00,0x25,0x3f,0x35,0x0a,0x45,0x64,0x75,0x06,0x95,0x01,
            0x66,0x01,0x10,0x55,0x0d,0xb1,0x02,
            0x0a,0x44,0x05,0x16,0x01,0x80,0x26,0xff,0x7f,0x37,0x5f,0x4f,0x46,0xed,
            0x47,0xa1,0xb0,0xb9,0x12,0x65,0x00,0x55,0x08,0x75,0x10,0x95,0x03,0x81,0x02,
            0x0a,0x45,0x05,0x35,0xe0,0x45,0x20,0x55,0x00,0x81,0x02,
            0x0a,0x46,0x05,0x16,0x00,0x00,0x26,0xff,0x00,0x35,0x00,0x45,0x00,
            0x75,0x08,0x95,0x01,0x81,0x02,0xc0,
        ).map(Int::toByte).toByteArray()
    }
}

/** Idle hosts have no freshness deadline. An enabled host must obtain a new, post-enable pose. */
internal class HeadTrackerPoseGate {
    private var enabled = false
    private var enabledAt = 0L
    private var lastTimestamp = 0L
    private var latest: HeadTrackerUhidProtocol.Companion.Pose? = null
    val hasPose get() = latest != null
    val deadline get() = if (!enabled) Long.MAX_VALUE else latest?.timestampNanos?.plus(ACTIVE_GAP_NANOS)
        ?: (enabledAt + HeadTrackerUhidProtocol.STARTUP_NANOS)

    fun reporting(value: Boolean, now: Long) {
        if (!value || value != enabled) { latest = null; enabledAt = now }
        enabled = value
    }

    fun accept(pose: HeadTrackerUhidProtocol.Companion.Pose, now: Long): Boolean {
        require(pose.timestampNanos > lastTimestamp) { "nonincreasing_pose_timestamp" }
        lastTimestamp = pose.timestampNanos
        if (!enabled || pose.timestampNanos < enabledAt) return false
        require(now - pose.timestampNanos <= HeadTrackerUhidProtocol.FRESH_NANOS) { "stale_pose" }
        checkFresh(now)
        latest = pose
        return true
    }

    fun checkFresh(now: Long) { check(now <= deadline) { if (latest == null) "pose_startup_timeout" else "pose_stale" } }
    fun pose(now: Long): HeadTrackerUhidProtocol.Companion.Pose? {
        checkFresh(now)
        return latest?.takeIf { now - it.timestampNanos <= HeadTrackerUhidProtocol.FRESH_NANOS }
    }
    fun input(now: Long): ByteArray? = pose(now)?.let { HeadTrackerUhidProtocol.input(it) }

    companion object {
        // A delayed delivery can resume the same sensor without extending the accepted pose age.
        const val ACTIVE_GAP_NANOS = 400_000_000L
    }
}

/** Keep the host's periodic cadence without accumulating each poll's rounding delay or catch-up bursts. */
internal class HeadTrackerReportPacer {
    private var enabled = false
    private var interval = 10_000_000L
    private var lastSlot: Long? = null
    var deadline = Long.MAX_VALUE
        private set

    fun reporting(value: Boolean, intervalNanos: Long, now: Long) {
        require(intervalNanos in 10_000_000L..100_000_000L)
        if (!value) { lastSlot = null; deadline = Long.MAX_VALUE }
        else if (!enabled) deadline = now
        else if (interval != intervalNanos) deadline = lastSlot?.let { it + intervalNanos } ?: now
        enabled = value; interval = intervalNanos
    }

    fun advance(now: Long) {
        check(enabled && now >= deadline)
        val next = deadline + interval
        deadline = if (next <= now) now + interval else next
        lastSlot = now
    }
}

/** Created only for a cached stats read, never for an individual pose. */
internal data class HeadTrackerTimingRange(
    val count: Long = 0, val minNanos: Long = 0, val meanNanos: Double = 0.0, val maxNanos: Long = 0,
)

internal data class HeadTrackerSinkStats(
    val acceptedPoses: Long = 0, val hidInputs: Long = 0, val heldRepeats: Long = 0,
    val intervalCount: Long = 0, val intervalMinNanos: Long = 0, val intervalMeanNanos: Double = 0.0,
    val intervalMaxNanos: Long = 0, val sourceAgeCount: Long = 0, val sourceAgeMinNanos: Long = 0,
    val sourceAgeMeanNanos: Double = 0.0, val sourceAgeMaxNanos: Long = 0,
    val sourceReuses: Long = 0, val extrapolatedInputs: Long = 0, val skippedInputs: Long = 0,
    val maxHidGapAtNanos: Long = 0, val sourceAgeAtMaxHidGapNanos: Long = 0,
    val previousSourceTimestampAtMaxHidGapNanos: Long = 0, val sourceTimestampAtMaxHidGapNanos: Long = 0,
    val newSourceAge: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val reusedSourceAge: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val pipeFrameHostAge: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val deadlineLateness: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val overdueSlots: Long = 0, val missedPeriods: Long = 0, val timingClockAnomalies: Long = 0,
)

/** Constant-space host timing only; intervals exclude host-disabled windows. */
internal class HeadTrackerSinkMetrics {
    private var accepted = 0L
    private var inputs = 0L
    private var repeats = 0L
    private var reuses = 0L
    private var extrapolated = 0L
    private var skipped = 0L
    private var lastPoseTimestamp: Long? = null
    private var lastInput: Long? = null
    private var maxGapAt = 0L
    private var sourceAgeAtMaxGap = 0L
    private var previousSourceAtMaxGap = 0L
    private var sourceAtMaxGap = 0L
    private var overdue = 0L
    private var missed = 0L
    private var clockAnomalies = 0L
    private class Range {
        var count = 0L; var min = 0L; var mean = 0.0; var max = 0L
        fun add(value: Long) {
            require(value >= 0)
            if (count++ == 0L) { min = value; max = value } else { min = minOf(min, value); max = maxOf(max, value) }
            mean += (value.toDouble() - mean) / count
        }
        fun snapshot() = HeadTrackerTimingRange(count, min, mean, max)
    }
    private val intervals = Range()
    private val ages = Range()
    private val newAges = Range()
    private val reusedAges = Range()
    private val pipeAges = Range()
    private val lateness = Range()

    private fun validHostAge(timestamp: Long, now: Long): Boolean {
        if (timestamp > 0 && now >= timestamp) return true
        ++clockAnomalies
        return false
    }

    private fun scheduled(deadline: Long, now: Long, interval: Long) {
        if (deadline == 0L && interval == 0L) return // Existing callers need not supply scheduling data.
        if (interval !in 10_000_000L..100_000_000L || !validHostAge(deadline, now)) {
            if (interval !in 10_000_000L..100_000_000L) ++clockAnomalies
            return
        }
        val delay = now - deadline
        lateness.add(delay)
        val periods = delay / interval
        if (periods > 0) { ++overdue; missed += periods }
    }

    @Synchronized fun accepted(poseTimestamp: Long = 0, receivedAt: Long = 0) {
        ++accepted
        // This is parsed-callback timestamp to pipe receipt, not socket or sensor transit time.
        if (poseTimestamp == 0L && receivedAt == 0L) return
        if (validHostAge(poseTimestamp, receivedAt)) pipeAges.add(receivedAt - poseTimestamp)
    }
    @Synchronized fun skipped(deadline: Long = 0, now: Long = 0, interval: Long = 0) {
        ++skipped
        if (lastInput != null) scheduled(deadline, now, interval)
    }
    @Synchronized fun idle() { lastInput = null }
    @Synchronized fun input(poseTimestamp: Long, now: Long, extrapolated: Boolean = false,
        deadline: Long = 0, interval: Long = 0) {
        require(poseTimestamp <= now)
        // Before the first fresh input, the pacer deadline still names HAL enable/startup.
        if (lastInput != null) scheduled(deadline, now, interval)
        if (validHostAge(poseTimestamp, now)) {
            (if (lastPoseTimestamp == poseTimestamp) reusedAges else newAges).add(now - poseTimestamp)
        }
        lastInput?.let {
            val gap = now - it
            val maximum = intervals.count == 0L || gap > intervals.max
            intervals.add(gap)
            if (maximum) {
                maxGapAt = now
                sourceAgeAtMaxGap = now - poseTimestamp
                previousSourceAtMaxGap = checkNotNull(lastPoseTimestamp)
                sourceAtMaxGap = poseTimestamp
            }
        }
        ages.add(now - poseTimestamp)
        if (lastPoseTimestamp == poseTimestamp) {
            ++reuses
            if (!extrapolated) ++repeats
        }
        if (extrapolated) ++this.extrapolated
        ++inputs; lastInput = now; lastPoseTimestamp = poseTimestamp
    }
    @Synchronized fun snapshot() = HeadTrackerSinkStats(accepted, inputs, repeats,
        intervals.count, intervals.min, intervals.mean, intervals.max,
        ages.count, ages.min, ages.mean, ages.max, reuses, extrapolated, skipped,
        maxGapAt, sourceAgeAtMaxGap, previousSourceAtMaxGap, sourceAtMaxGap,
        newAges.snapshot(), reusedAges.snapshot(), pipeAges.snapshot(), lateness.snapshot(), overdue, missed, clockAnomalies)
}

/** Confirmation belongs to one exact selected address; a stuck native read cannot renew it. */
internal class HeadTrackerRouteCache(private val address: String, private val now: () -> Long) {
    @Volatile private var checkedAt = Long.MIN_VALUE
    fun confirm() { checkedAt = now() }
    fun clear() { checkedAt = Long.MIN_VALUE }
    fun current(selected: String): Boolean {
        val checked = checkedAt
        return selected == address && checked != Long.MIN_VALUE && now() - checked in 0..500
    }
}
