package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.core.data.apple.MotionReport
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class SpatialPoseConverterTest {
    private val neutral = axis(0.0, 0.0, 1.0, 23.0)
    private val mount = axis(0.0, 1.0, 0.0, 90.0)
    private val tick = 20_000_000L

    @Test fun knownUpCalibratesRightFrontUpWithoutUsingDelayedGravityOrRawGyroScale() {
        val profile = checkNotNull(calibrate())
        val expected = listOf(listOf(0.0, 0.0, -1.0), listOf(0.0, 1.0, 0.0), listOf(1.0, 0.0, 0.0))
        profile.headFromRaw.zip(expected).forEach { (actual, wanted) -> vector(wanted, actual, 0.0003) }
        val converter = SpatialPoseConverter(profile, 7)
        assertNull(converter.accept(report(neutral * mount.inverse(), 1_000_000_000)))
        val still = checkNotNull(converter.accept(report(neutral * mount.inverse(), 1_000_000_000 + tick)))
        vector(listOf(0.0, 0.0, 0.0), still.angularVelocity.map(Float::toDouble), 0.0001)
        vector(listOf(0.0, 0.0, Math.toRadians(23.0)), still.rotationVector.map(Float::toDouble), 0.0003)
        assertEquals(7, still.discontinuity); assertEquals(tick, still.intervalNs)
    }

    @Test fun composedWorldFromHeadAndIntrinsicRatesRemainCorrectWithNontrivialMounts() {
        for (rawFromHead in listOf(mount, axis(1.0, 2.0, -1.0, 57.0), axis(0.0, 0.0, 1.0, 180.0))) {
            val converter = SpatialPoseConverter(checkNotNull(calibrate(rawFromHead)), 19)
            var head = neutral
            var time = 1_000_000_000L
            assertNull(converter.accept(report(head * rawFromHead.inverse(), time)))
            for ((delta, wantedRate) in listOf(
                axis(1.0, 0.0, 0.0, Math.toDegrees(0.012)) to listOf(0.6, 0.0, 0.0),
                axis(0.0, 1.0, 0.0, Math.toDegrees(-0.008)) to listOf(0.0, -0.4, 0.0),
                axis(0.0, 0.0, 1.0, Math.toDegrees(0.010)) to listOf(0.0, 0.0, 0.5))) {
                head *= delta; time += tick
                val pose = checkNotNull(converter.accept(report(head * rawFromHead.inverse(), time)))
                vector(head.rotvec(), pose.rotationVector.map(Float::toDouble), 0.0004)
                // The half-turn mount leaves raw w≈0.2: Q15 reconstruction's two-frame
                // angular-rate error is bounded by ~0.03 rad/s at a 20 ms interval.
                vector(wantedRate, pose.angularVelocity.map(Float::toDouble), 0.03)
                assertEquals(time, pose.timestampNs); assertEquals(19, pose.discontinuity)
            }
        }
    }

    @Test fun calibrationRejectsWeakMixedOrUnreturnedExcursionsAndUnstableHolds() {
        assertNull(calibrate(upHead = neutral * axis(1.0, 0.0, 0.0, 7.0)))
        assertNull(calibrate(upHead = neutral * axis(0.0, 0.0, 1.0, 25.0)))
        assertNull(calibrate(upHead = neutral * axis(1.0, 0.0, 1.0, 25.0)))
        assertNull(calibrate(returnHead = neutral * axis(1.0, 0.0, 0.0, 10.0)))
        val phases = phases()
        assertNull(SpatialPoseProfile.calibrate(phases.first.take(4), phases.second, phases.third))
        val unstable = phases.first.toMutableList()
        unstable[0] = report((neutral * axis(1.0, 0.0, 0.0, 8.0)) * mount.inverse(), unstable[0].timestampNs!!)
        assertNull(SpatialPoseProfile.calibrate(unstable, phases.second, phases.third))
        val mixed = phases.second.toMutableList()
        mixed[0] = report((neutral * axis(0.0, 1.0, 0.0, 25.0)) * mount.inverse(), mixed[0].timestampNs!!)
        assertNull(SpatialPoseProfile.calibrate(phases.first, mixed, phases.third))
    }

    @Test fun calibrationRejectsInvalidAttitudeAndReferenceClocks() {
        val phases = phases()
        for (bad in listOf(
            phases.second[0].copy(attitudeQ15 = listOf(0, 0, 0)),
            phases.second[0].copy(attitudeQ15 = listOf(32767, 0, 0)),
            phases.second[0].copy(timestampNs = null),
            phases.second[0].copy(timestampNs = phases.first.last().timestampNs),
            phases.second[0].copy(counter = 0))) {
            assertNull(SpatialPoseProfile.calibrate(phases.first, listOf(bad) + phases.second.drop(1), phases.third))
        }
    }

    @Test fun calibrationRejectsReversedTiltedZeroOrUnstableNeutralAndReturnGravity() {
        val phases = phases()
        for (direction in listOf(listOf(0.0, 0.0, 1.0),
            listOf(sin(Math.toRadians(10.0)), 0.0, -cos(Math.toRadians(10.0))), listOf(0.0, 0.0, 0.0))) {
            val bad = report(neutral * mount.inverse(), 100_000_000, direction).gravityRaw
            assertNull(SpatialPoseProfile.calibrate(phases.first.map { it.copy(gravityRaw = bad) }, phases.second, phases.third))
            assertNull(SpatialPoseProfile.calibrate(phases.first, phases.second, phases.third.map { it.copy(gravityRaw = bad) }))
        }
        val unstable = phases.first.toMutableList()
        unstable[0] = unstable[0].copy(gravityRaw = report(neutral * mount.inverse(), 100_000_000,
            listOf(sin(Math.toRadians(20.0)), 0.0, -cos(Math.toRadians(20.0)))).gravityRaw)
        assertNull(SpatialPoseProfile.calibrate(unstable, phases.second, phases.third))
    }

    @Test fun calibrationUsesSettledHoldTailsWithoutAcceptingBadFinalGravity() {
        var time = 100_000_000L
        val raw = neutral * mount.inverse()
        val tilted = listOf(sin(Math.toRadians(20.0)), 0.0, -cos(Math.toRadians(20.0)))
        fun hold() = List(100) { i ->
            report(raw, time, if (i < 60) tilted else listOf(0.0, 0.0, -1.0)).also { time += tick }
        }
        val start = hold()
        val up = List(5) { report((neutral * axis(1.0, 0.0, 0.0, 25.0)) * mount.inverse(), time).also { time += tick } }
        val finish = hold()
        assertNotNull(SpatialPoseProfile.calibrate(start, up, finish))
        val disturbed = finish.dropLast(1) + finish.last().copy(gravityRaw = report(raw, time, tilted).gravityRaw)
        assertNull(SpatialPoseProfile.calibrate(start, up, disturbed))
    }

    @Test fun profileRejectsNonfiniteScaledSkewedAndReflectedBases() {
        val identity = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
        assertNotNull(SpatialPoseProfile.fromBasis(identity))
        for (bad in listOf(
            emptyList(), identity.take(2),
            listOf(listOf(Double.NaN, 0.0, 0.0)) + identity.drop(1),
            listOf(listOf(2.0, 0.0, 0.0)) + identity.drop(1),
            listOf(listOf(-1.0, 0.0, 0.0)) + identity.drop(1),
            listOf(identity[0], listOf(0.6, 0.8, 0.0), identity[2]))) {
            assertNull(SpatialPoseProfile.fromBasis(bad))
        }
    }

    @Test fun startupZerosDoNotPrimeTheConverterAndFirstGoodReportCannotInventARate() {
        val converter = SpatialPoseConverter(checkNotNull(calibrate()), 0)
        val good = report(neutral * mount.inverse(), 1_000_000_000)
        assertNull(converter.accept(good.copy(attitudeQ15 = listOf(0, 0, 0), timestampNs = 1)))
        assertFalse(converter.invalidated)
        assertNull(converter.accept(good)); assertFalse(converter.invalidated)
        assertNotNull(converter.accept(good.copy(timestampNs = good.timestampNs!! + tick, counter = good.counter!! + 4)))
        val phases = phases()
        assertNotNull(SpatialPoseProfile.calibrate(
            listOf(good.copy(attitudeQ15 = listOf(0, 0, 0), timestampNs = 1)) + phases.first,
            phases.second, phases.third))
    }

    @Test fun badTimesCounterResetsAndUnsupportedBranchLatchUntilANewConverter() {
        val first = report(neutral * mount.inverse(), 1_000_000_000)
        val next = report(neutral * mount.inverse(), 1_000_000_000 + tick)
        for (bad in listOf(
            next.copy(timestampNs = null), next.copy(timestampNs = 0),
            next.copy(timestampNs = first.timestampNs), next.copy(timestampNs = first.timestampNs!! - 1),
            next.copy(timestampNs = first.timestampNs!! + 100_000_001),
            next.copy(counter = first.counter), next.copy(counter = 0), next.copy(counter = null),
            next.copy(attitudeQ15 = listOf(0, 0, 0)),
            next.copy(attitudeQ15 = listOf(32767, 0, 0)), next.copy(attitudeQ15 = listOf(32767, 32767, 0)),
            report((neutral * axis(1.0, 0.0, 0.0, 80.0)) * mount.inverse(), next.timestampNs!!))) {
            val converter = SpatialPoseConverter(checkNotNull(calibrate()), 0)
            assertNull(converter.accept(first)); assertNull(converter.accept(bad))
            assertTrue(converter.invalidated); assertNotNull(converter.invalidReason); assertNull(converter.accept(next))
            val reason = converter.invalidReason
            converter.invalidate(); assertEquals(reason, converter.invalidReason)
        }
        val fresh = SpatialPoseConverter(checkNotNull(calibrate()), 1)
        assertNull(fresh.accept(first)); assertNotNull(fresh.accept(next))
    }

    @Test fun naturalCounterWrapIsValidButExplicitRoleOrReferenceRetirementCannotRestart() {
        val phases = phases()
        val shifted = (phases.first + phases.second + phases.third).mapIndexed { i, report ->
            report.copy(counter = 0xffffffc0L + i * 4)
        }
        val wrapProfile = SpatialPoseProfile.calibrate(shifted.take(5), shifted.drop(5).take(5), shifted.takeLast(5))
        val converter = SpatialPoseConverter(checkNotNull(wrapProfile), 255)
        val first = report(neutral * mount.inverse(), 1_000_000_000).copy(counter = 0xfffffffcL)
        assertNull(converter.accept(first))
        val next = first.copy(timestampNs = first.timestampNs!! + tick, counter = 0)
        assertNotNull(converter.accept(next))
        converter.invalidate()
        assertNull(converter.accept(next.copy(timestampNs = next.timestampNs!! + tick, counter = 4)))
        assertTrue(converter.invalidated); assertEquals("session_changed", converter.invalidReason)
        assertThrows(IllegalArgumentException::class.java) { SpatialPoseConverter(checkNotNull(calibrate()), -1) }
        assertThrows(IllegalArgumentException::class.java) { SpatialPoseConverter(checkNotNull(calibrate()), 256) }
    }

    @Test fun calibrationToRuntimeHandoffIgnoresTheCachedLastFrameAndRejectsAReferenceReset() {
        val phases = phases(); val profile = checkNotNull(calibrate())
        val cached = phases.third.last()
        val converter = SpatialPoseConverter(profile, 0)
        assertNull(converter.accept(cached)); assertFalse(converter.invalidated)
        val fresh = cached.copy(timestampNs = cached.timestampNs!! + tick, counter = cached.counter!! + 4)
        assertNull(converter.accept(fresh)); assertFalse(converter.invalidated)
        assertNotNull(converter.accept(fresh.copy(timestampNs = fresh.timestampNs!! + tick, counter = fresh.counter!! + 4)))
        val clockReset = SpatialPoseConverter(profile, 0)
        assertNull(clockReset.accept(cached.copy(timestampNs = 1, counter = 1)))
        assertEquals("sensor_time_changed", clockReset.invalidReason)
        val counterReset = SpatialPoseConverter(profile, 0)
        assertNull(counterReset.accept(cached.copy(timestampNs = cached.timestampNs!! + tick, counter = 0)))
        assertEquals("sensor_counter_changed", counterReset.invalidReason)
    }

    private fun calibrate(rawFromHead: TQ = mount, upHead: TQ = neutral * axis(1.0, 0.0, 0.0, 25.0),
        returnHead: TQ = neutral): SpatialPoseProfile? {
        val p = phases(rawFromHead, upHead, returnHead)
        return SpatialPoseProfile.calibrate(p.first, p.second, p.third)
    }
    private fun phases(rawFromHead: TQ = mount, upHead: TQ = neutral * axis(1.0, 0.0, 0.0, 25.0),
        returnHead: TQ = neutral): Triple<List<MotionReport>, List<MotionReport>, List<MotionReport>> {
        var time = 100_000_000L
        fun hold(head: TQ) = List(5) { report(head * rawFromHead.inverse(), time).also { time += tick } }
        return Triple(hold(neutral), hold(upHead).map { it.copy(gravityRaw = listOf(-400, 700, -600)) }, hold(returnHead))
    }
    private fun report(q: TQ, time: Long, worldGravity: List<Double> = listOf(0.0, 0.0, -1.0)) = MotionReport(time,
        listOf(q.x, q.y, q.z).map { (it * if (q.w < 0) -32768 else 32768).roundToInt() },
        listOf(30000, -31000, 22000), listOf(0, 0, 0),
        q.inverse().rotate(worldGravity).map { (it * 1000).roundToInt() }, time / tick * 4)
    private fun vector(expected: List<Double>, actual: List<Double>, tolerance: Double) {
        expected.zip(actual).forEachIndexed { i, (a, b) -> assertEquals("axis $i", a, b, tolerance) }
        assertEquals(expected.size, actual.size)
    }
    private fun axis(x: Double, y: Double, z: Double, degrees: Double): TQ {
        val half = Math.toRadians(degrees) / 2; val n = sqrt(x*x + y*y + z*z)
        return TQ(cos(half), x/n*sin(half), y/n*sin(half), z/n*sin(half))
    }
    private data class TQ(val w: Double, val x: Double, val y: Double, val z: Double) {
        operator fun times(b: TQ) = TQ(w*b.w-x*b.x-y*b.y-z*b.z, w*b.x+x*b.w+y*b.z-z*b.y,
            w*b.y-x*b.z+y*b.w+z*b.x, w*b.z+x*b.y-y*b.x+z*b.w)
        fun inverse() = TQ(w, -x, -y, -z)
        fun rotate(v: List<Double>): List<Double> = (this * TQ(0.0, v[0], v[1], v[2]) * inverse())
            .let { listOf(it.x, it.y, it.z) }
        fun rotvec(): List<Double> {
            val n = sqrt(x*x+y*y+z*z); val scale = 2 * atan2(n, w) / n
            return listOf(x*scale, y*scale, z*scale)
        }
    }
}
