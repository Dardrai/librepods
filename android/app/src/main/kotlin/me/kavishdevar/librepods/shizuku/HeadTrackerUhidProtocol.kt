/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
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

        fun input(pose: Pose): ByteArray = event(INPUT2).apply {
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
    val deadline get() = if (!enabled) Long.MAX_VALUE else latest?.timestampNanos?.plus(HeadTrackerUhidProtocol.FRESH_NANOS)
        ?: (enabledAt + HeadTrackerUhidProtocol.STARTUP_NANOS)

    fun reporting(value: Boolean, now: Long) {
        if (!value || value != enabled) { latest = null; enabledAt = now }
        enabled = value
    }

    fun accept(pose: HeadTrackerUhidProtocol.Companion.Pose, now: Long) {
        require(pose.timestampNanos > lastTimestamp) { "nonincreasing_pose_timestamp" }
        lastTimestamp = pose.timestampNanos
        if (!enabled || pose.timestampNanos < enabledAt) return
        require(now - pose.timestampNanos <= HeadTrackerUhidProtocol.FRESH_NANOS) { "stale_pose" }
        checkFresh(now)
        latest = pose
    }

    fun checkFresh(now: Long) { check(now <= deadline) { if (latest == null) "pose_startup_timeout" else "pose_stale" } }
    fun input(now: Long): ByteArray? { checkFresh(now); return latest?.let { HeadTrackerUhidProtocol.input(it) } }
}
