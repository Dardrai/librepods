/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class HeadTrackerUhidProtocolTest {
    @Test fun halHandshakeNeedsBothSuccessfulFeatureReads() {
        val codec = HeadTrackerUhidProtocol("AA:BB:CC:DD:EE:FF")
        assertFalse(codec.halHandshake)
        codec.handle(event(HeadTrackerUhidProtocol.GET_REPORT, number = 2).apply { this[9] = 1 })
        assertFalse(codec.halHandshake)
        codec.handle(event(HeadTrackerUhidProtocol.GET_REPORT, number = 2))
        assertFalse(codec.halHandshake)
        codec.handle(event(HeadTrackerUhidProtocol.GET_REPORT, number = 1))
        assertTrue(codec.halHandshake)
        assertFalse(codec.reporting)
    }
    private val address = "12:34:56:78:9A:BC"
    private val start = 10_000_000_000L
    private fun event(type: Int, number: Int = 1, controls: Int = 3) = HeadTrackerUhidProtocol.event(type).apply {
        HeadTrackerUhidProtocol.little(this).putInt(4, 123).putShort(10, 2)
        this[8] = number.toByte(); this[9] = 0; this[12] = 1; this[13] = controls.toByte()
    }
    private fun frame(timestamp: Long, discontinuity: Int = 0, vararg values: Float) = ByteArray(40).also {
        val bytes = HeadTrackerUhidProtocol.little(it)
        bytes.putInt(HeadTrackerUhidProtocol.FRAME_MAGIC).putInt(discontinuity).putLong(timestamp)
        repeat(6) { index -> bytes.putFloat(values.getOrElse(index) { 0f }) }
    }
    private fun pose(timestamp: Long, value: Float = 0f) = HeadTrackerUhidProtocol.decodePose(frame(timestamp, 0, value), timestamp)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Accepted invalid input") } catch (_: IllegalArgumentException) { }
    }
    private fun expired(action: () -> Unit) {
        try { action(); fail("Accepted expired input") } catch (_: IllegalStateException) { }
    }

    @Test fun packedCreateMatchesTheDeviceProvenDescriptor() {
        val create = HeadTrackerUhidProtocol(address).create()
        val bytes = HeadTrackerUhidProtocol.little(create)
        assertEquals(4380, create.size); assertEquals(11, bytes.getInt(0))
        assertEquals(149, bytes.getShort(260).toInt()); assertEquals(6, bytes.getShort(262).toInt())
        assertEquals(1, bytes.getInt(272)); assertEquals(0, bytes.getInt(276))
        val descriptor = create.copyOfRange(280, 429)
        val hash = MessageDigest.getInstance("SHA-256").digest(descriptor).joinToString("") { "%02x".format(it) }
        assertEquals("f38a19371c8296ff971f624657220c37cb966fe79513673f1bb55560de7f190c", hash)
        rejected { HeadTrackerUhidProtocol("00:00:00:00:00:00") }
        rejected { HeadTrackerUhidProtocol("12:34:56:78:9A:BC;bad") }
    }

    @Test fun featureRepliesUseExactIdsSizesIdentityAndHostControls() {
        val codec = HeadTrackerUhidProtocol(address)
        codec.handle(event(HeadTrackerUhidProtocol.START).apply { HeadTrackerUhidProtocol.little(this).putLong(4, 5) })
        codec.handle(event(HeadTrackerUhidProtocol.OPEN))
        val set = codec.handle(event(HeadTrackerUhidProtocol.SET_REPORT))!!
        assertEquals(14, HeadTrackerUhidProtocol.little(set).getInt(0))
        assertEquals(123, HeadTrackerUhidProtocol.little(set).getInt(4))
        assertEquals(0, HeadTrackerUhidProtocol.little(set).getShort(8).toInt())
        assertFalse(codec.reporting)
        val identity = codec.handle(event(HeadTrackerUhidProtocol.GET_REPORT, number = 2))!!
        val bytes = HeadTrackerUhidProtocol.little(identity)
        assertEquals(10, bytes.getInt(0)); assertEquals(40, bytes.getShort(10).toInt())
        assertEquals(2, identity[12].toInt())
        assertEquals("#AndroidHeadTracker#1.0", String(identity, 13, 23, Charsets.US_ASCII))
        assertArrayEquals(ByteArray(8), identity.copyOfRange(36, 44))
        assertEquals("BT", String(identity, 44, 2, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0x12,0x34,0x56,0x78,0x9a.toByte(),0xbc.toByte()), identity.copyOfRange(46, 52))
        assertTrue(codec.reporting)
        for (controls in 0..255) {
            codec.handle(event(HeadTrackerUhidProtocol.SET_REPORT, controls = controls))
            assertEquals(controls and 3 == 3, codec.reporting)
            assertEquals(10_000_000L + (controls shr 2) * 90_000_000L / 63, codec.intervalNanos)
            val read = codec.handle(event(HeadTrackerUhidProtocol.GET_REPORT))!!
            assertEquals(2, HeadTrackerUhidProtocol.little(read).getShort(10).toInt())
            assertEquals(controls, read[13].toInt() and 255)
        }
        val denied = codec.handle(event(HeadTrackerUhidProtocol.SET_REPORT, number = 2))!!
        assertEquals(5, HeadTrackerUhidProtocol.little(denied).getShort(8).toInt())
        codec.handle(event(HeadTrackerUhidProtocol.CLOSE)); assertFalse(codec.reporting)
        codec.handle(event(HeadTrackerUhidProtocol.OPEN)); assertTrue(codec.reporting)
        codec.handle(event(HeadTrackerUhidProtocol.STOP)); assertFalse(codec.reporting)
        rejected { codec.handle(ByteArray(1)) }
    }

    @Test fun standardPoseValidationAndFixedPointConversionAreBounded() {
        val decoded = HeadTrackerUhidProtocol.decodePose(frame(start, 255, 0.1f, -0.2f, 0.3f, 1f, -2f, 3f), start)
        val input = HeadTrackerUhidProtocol.input(decoded)
        val bytes = HeadTrackerUhidProtocol.little(input)
        assertEquals(12, bytes.getInt(0)); assertEquals(14, bytes.getShort(4).toInt())
        assertEquals(1, input[6].toInt()); assertEquals(255, input[19].toInt() and 255)
        decoded.values.forEachIndexed { index, value ->
            val maximum = if (index < 3) 3.14159265 else 32.0
            assertEquals(value.toDouble(), bytes.getShort(7 + index * 2) * maximum / 32767, maximum / 65534 + 1e-7)
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 4f))
            rejected { HeadTrackerUhidProtocol.decodePose(frame(start, 0, invalid), start) }
        rejected { HeadTrackerUhidProtocol.decodePose(frame(start, 0, 0f, 0f, 0f, 33f), start) }
        rejected { HeadTrackerUhidProtocol.decodePose(frame(start, 0, 2.3f, 2.3f), start) }
        rejected { HeadTrackerUhidProtocol.decodePose(frame(start, 256), start) }
        rejected { HeadTrackerUhidProtocol.decodePose(frame(start + 1), start) }
        rejected { HeadTrackerUhidProtocol.decodePose(frame(0), start) }
        rejected { HeadTrackerUhidProtocol.decodePose(frame(start).apply { this[0] = 0 }, start) }
        rejected { HeadTrackerUhidProtocol.decodePose(ByteArray(39), start) }
    }

    @Test fun roundedPiAxesEncodeAtTheLogicalBoundaryButFurtherRotationIsRejected() {
        val pi = Math.PI.toFloat()
        val beyond = Math.nextUp(pi)
        assertTrue(beyond.toDouble() > Math.PI + Math.ulp(pi))
        for (axis in 0..2) for (sign in listOf(1f, -1f)) {
            val values = FloatArray(6).apply { this[axis] = sign * pi }
            val decoded = HeadTrackerUhidProtocol.decodePose(frame(start, 0, *values), start)
            val input = HeadTrackerUhidProtocol.little(HeadTrackerUhidProtocol.input(decoded))
            assertEquals((sign * 32767).toInt(), input.getShort(7 + axis * 2).toInt())
            values[axis] = sign * beyond
            rejected { HeadTrackerUhidProtocol.decodePose(frame(start, 0, *values), start) }
        }
        for (sign in listOf(1f, -1f)) {
            val gyro = HeadTrackerUhidProtocol.decodePose(frame(start, 0, 0f, 0f, 0f, sign * 32f), start)
            val input = HeadTrackerUhidProtocol.little(HeadTrackerUhidProtocol.input(gyro))
            assertEquals((sign * 32767).toInt(), input.getShort(13).toInt())
            rejected { HeadTrackerUhidProtocol.decodePose(frame(start, 0, 0f, 0f, 0f, sign * Math.nextUp(32f)), start) }
        }
    }

    @Test fun idleHostClearsPoseAndHasNoFreshnessTimeout() {
        val gate = HeadTrackerPoseGate()
        gate.accept(pose(start), start)
        gate.checkFresh(start + 100_000_000_000L)
        assertFalse(gate.hasPose); assertNull(gate.input(start))
        gate.reporting(true, start + 1)
        gate.accept(pose(start + 2, 0.1f), start + 2)
        assertNotNull(gate.input(start + 2))
        gate.reporting(false, start + 3)
        gate.checkFresh(start + 100_000_000_000L)
        assertFalse(gate.hasPose); assertNull(gate.input(start + 4))
    }

    @Test fun everyEnableRequiresFreshPostEnableInputWithinColdStartGrace() {
        val gate = HeadTrackerPoseGate()
        gate.reporting(true, start)
        assertNull(gate.input(start + HeadTrackerUhidProtocol.STARTUP_NANOS))
        expired { gate.checkFresh(start + HeadTrackerUhidProtocol.STARTUP_NANOS + 1) }
        gate.reporting(false, start + 6_000_000_000L)
        gate.reporting(true, start + 7_000_000_000L)
        gate.accept(pose(start + 6_500_000_000L), start + 7_000_000_000L)
        assertFalse(gate.hasPose)
        gate.accept(pose(start + 7_000_000_001L), start + 7_000_000_001L)
        assertNotNull(gate.input(start + 7_000_000_001L + HeadTrackerUhidProtocol.FRESH_NANOS))
        expired { gate.checkFresh(start + 7_000_000_002L + HeadTrackerUhidProtocol.FRESH_NANOS) }
    }

    @Test fun onlyTheLatestPoseIsRetainedAndTimestampReplayIsRejected() {
        val gate = HeadTrackerPoseGate()
        gate.reporting(true, start)
        gate.accept(pose(start + 1, 0.1f), start + 1)
        gate.accept(pose(start + 2, 0.2f), start + 2)
        val input = HeadTrackerUhidProtocol.little(gate.input(start + 2)!!)
        assertEquals(0.2, input.getShort(7) * 3.14159265 / 32767, 0.00005)
        rejected { gate.accept(pose(start + 2), start + 2) }
        rejected { gate.accept(pose(start + 3), start + HeadTrackerUhidProtocol.FRESH_NANOS + 4) }
    }

    @Test fun lateInputCannotReopenAnExpiredActiveWindowButHostOffCanClearIt() {
        val startup = HeadTrackerPoseGate()
        startup.reporting(true, start)
        val late = start + HeadTrackerUhidProtocol.STARTUP_NANOS + 1
        expired { startup.accept(pose(late), late) }
        val active = HeadTrackerPoseGate()
        active.reporting(true, start)
        active.accept(pose(start + 1), start + 1)
        val expired = start + HeadTrackerUhidProtocol.FRESH_NANOS + 2
        expired { active.accept(pose(expired), expired) }
        active.reporting(false, expired)
        active.checkFresh(expired + 60_000_000_000L)
        assertNull(active.input(expired + 60_000_000_000L))
    }
}
