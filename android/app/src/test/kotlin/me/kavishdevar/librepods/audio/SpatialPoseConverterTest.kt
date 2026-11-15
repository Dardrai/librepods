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
        vector(listOf(0.0, 0.0, 0.0), still.rotationVector.map(Float::toDouble), 0.0003)
        assertEquals(7, still.discontinuity); assertEquals(tick, still.intervalNs)
    }

    @Test fun sessionRelativeHeadAndIntrinsicRatesRemainCorrectWithNontrivialMounts() {
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
                vector((neutral.inverse() * head).rotvec(), pose.rotationVector.map(Float::toDouble), 0.0004)
                // The half-turn mount leaves raw w≈0.2: Q15 reconstruction's two-frame
                // angular-rate error is bounded by ~0.03 rad/s at a 20 ms interval.
                vector(wantedRate, pose.angularVelocity.map(Float::toDouble), 0.03)
                assertEquals(time, pose.timestampNs); assertEquals(19, pose.discontinuity)
            }
        }
    }

    @Test fun canonicalWholeQuaternionSignFlipsAcrossRawPiKeepTheSessionPoseContinuous() {
        val identity = checkNotNull(SpatialPoseProfile.fromBasis(listOf(
            listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))))
        val converter = SpatialPoseConverter(identity, 23)
        val direction = listOf(1.0, 2.0, 3.0).map { it / sqrt(14.0) }
        val angles = (160..178 step 2).map(Int::toDouble) + listOf(179.0, 181.0) + (182..200 step 2).map(Int::toDouble)
        var time = 1_000_000_000L
        assertNull(converter.accept(report(axis(1.0, 2.0, 3.0, angles.first()), time)))
        for ((before, angle) in angles.zipWithNext()) {
            time += tick
            val pose = checkNotNull(converter.accept(report(axis(1.0, 2.0, 3.0, angle), time)))
            vector(direction.map { it * Math.toRadians(angle - angles.first()) }, pose.rotationVector.map(Float::toDouble), 0.003)
            // Q15 xyz reconstructs w near zero with ~0.12 degree error here; the two-frame
            // rate error is ~0.21 rad/s across179/181 degrees at20ms, without a branch jump.
            vector(direction.map { it * Math.toRadians(angle - before) / (tick / 1e9) },
                pose.angularVelocity.map(Float::toDouble), 0.25)
            assertEquals(23, pose.discontinuity); assertFalse(converter.invalidated)
        }
    }

    @Test fun anArbitraryInitialWorldPoseIsCenteredWithoutChangingMountedBodyRates() {
        val rawFromHead = axis(1.0, 2.0, -1.0, 57.0)
        val converter = SpatialPoseConverter(checkNotNull(calibrate(rawFromHead)), 8)
        val origin = axis(1.0, -2.0, 3.0, 147.0)
        var head = origin
        var time = 1_000_000_000L
        assertNull(converter.accept(report(head * rawFromHead.inverse(), time)))
        time += tick
        val centered = checkNotNull(converter.accept(report(head * rawFromHead.inverse(), time)))
        vector(listOf(0.0, 0.0, 0.0), centered.rotationVector.map(Float::toDouble), 0.0001)
        for ((delta, wantedRate) in listOf(
            axis(1.0, 0.0, 0.0, Math.toDegrees(0.01)) to listOf(0.5, 0.0, 0.0),
            axis(0.0, 1.0, 0.0, Math.toDegrees(-0.012)) to listOf(0.0, -0.6, 0.0),
            axis(0.0, 0.0, 1.0, Math.toDegrees(0.008)) to listOf(0.0, 0.0, 0.4))) {
            head *= delta; time += tick
            val pose = checkNotNull(converter.accept(report(head * rawFromHead.inverse(), time)))
            // This world/mount combination has raw |w|≈0.05: the two-frame Q15
            // orientation-error bound is≈0.00212 rad near the raw half-turn.
            vector((origin.inverse() * head).rotvec(), pose.rotationVector.map(Float::toDouble), 0.0022)
            vector(wantedRate, pose.angularVelocity.map(Float::toDouble), 0.03)
        }
    }

    @Test fun calibrationHoldMeansAlignCanonicalHemispheresAcrossTheRawHalfTurn() {
        var time = 100_000_000L
        fun hold() = List(10) { i -> report(axis(0.0, 0.0, 1.0, if (i % 2 == 0) 179.0 else 181.0), time).also { time += tick } }
        val neutral = hold()
        val up = List(10) {
            report(axis(0.0, 0.0, 1.0, 180.0) * axis(1.0, 0.0, 0.0, 25.0), time).also { time += tick }
        }
        val result = SpatialPoseProfile.calibrateResult(neutral, up, hold())
        assertNotNull(result.profile); assertNull(result.failure)
        assertNotNull(SpatialPoseProfile.fromBasis(checkNotNull(result.profile).headFromRaw))
    }

    @Test fun fullSpherePosesRemainFiniteAndPrincipalWhileNativePiContinuityIsNotClaimed() {
        val profile = checkNotNull(SpatialPoseProfile.fromBasis(listOf(
            listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))))
        val converter = SpatialPoseConverter(profile, 0)
        var time = 1_000_000_000L
        assertNull(converter.accept(report(axis(0.0, 0.0, 1.0, 37.0), time)))
        for (relative in 15..345 step 15) {
            time += tick
            val pose = checkNotNull(converter.accept(report(axis(0.0, 0.0, 1.0, 37.0 + relative), time)))
            val rv = pose.rotationVector.map(Float::toDouble)
            val norm = sqrt(rv.sumOf { it * it })
            assertTrue(norm.isFinite() && norm <= Math.PI + Math.ulp(Math.PI.toFloat()))
            val reconstructed = axis(rv[0], rv[1], rv[2], Math.toDegrees(norm))
            assertEquals(1.0, kotlin.math.abs(reconstructed.dot(axis(0.0, 0.0, 1.0, relative.toDouble()))), 0.00001)
            assertFalse(converter.invalidated)
        }
        val boundary = SpatialPoseConverter(profile, 1)
        val halfTurn = report(axis(1.0, 0.0, 0.0, 180.0), time).copy(attitudeQ15 = listOf(-32768, 0, 0))
        assertNull(boundary.accept(halfTurn))
        val still = checkNotNull(boundary.accept(halfTurn.copy(timestampNs = time + tick, counter = halfTurn.counter!! + 4)))
        vector(listOf(0.0, 0.0, 0.0), still.rotationVector.map(Float::toDouble), 0.00001)
    }

    @Test fun anEstablishedReferenceCanPassThroughExactZeroXyzAsARealIdentityPose() {
        val profile = checkNotNull(SpatialPoseProfile.fromBasis(listOf(
            listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))))
        val converter = SpatialPoseConverter(profile, 11)
        var time = 1_000_000_000L
        assertNull(converter.accept(report(axis(0.0, 0.0, 1.0, -2.0), time)))
        var before = -2.0
        for (angle in listOf(-1.0, -0.2, 0.0, 0.2, 1.0, 2.0)) {
            time += tick
            val raw = report(axis(0.0, 0.0, 1.0, angle), time)
            if (angle == 0.0) assertEquals(listOf(0, 0, 0), raw.attitudeQ15)
            val pose = checkNotNull(converter.accept(raw))
            vector(listOf(0.0, 0.0, Math.toRadians(angle + 2.0)), pose.rotationVector.map(Float::toDouble), 0.0001)
            vector(listOf(0.0, 0.0, Math.toRadians(angle - before) / (tick / 1e9)),
                pose.angularVelocity.map(Float::toDouble), 0.004)
            assertFalse(converter.invalidated); before = angle
        }
    }

    @Test fun calibrationAllowsIdentitySamplesInEstablishedNeutralAndReturnHolds() {
        var time = 100_000_000L
        fun capture(head: TQ) = report(head, time).also { time += tick }
        val start = List(10) { i -> capture(axis(0.0, 0.0, 1.0, if (i == 0) 1.0 else 0.0)) }
        val up = List(10) { capture(axis(1.0, 0.0, 0.0, 25.0)) }
        val finish = List(10) { capture(axis(0.0, 0.0, 1.0, 0.0)) }
        val result = SpatialPoseProfile.calibrateResult(start, up, finish)
        assertNotNull(result.profile); assertNull(result.failure)
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

    @Test fun fasterReportsKeepTheSamePhysicalAngularRateAndSavedCalibration() {
        val profile = checkNotNull(calibrate())
        for (interval in listOf(5_000_000L, 10_000_000L, 20_000_000L)) {
            val converter = SpatialPoseConverter(profile, 7)
            var head = neutral
            var time = 1_000_000_000L
            assertNull(converter.accept(report(head * mount.inverse(), time)))
            repeat(40) {
                head *= axis(1.0, 0.0, 0.0, Math.toDegrees(interval / 1e9))
                time += interval
                val pose = checkNotNull(converter.accept(report(head * mount.inverse(), time)
                    .copy(counter = time / 5_000_000L)))
                vector(listOf(1.0, 0.0, 0.0), pose.angularVelocity.map(Float::toDouble), 0.05)
                assertEquals(interval, pose.intervalNs)
                assertEquals(7, pose.discontinuity)
            }
            assertFalse(converter.invalidated)
        }
    }

    @Test fun calibrationRejectsInvalidAttitudeAndReferenceClocks() {
        val phases = phases()
        for (bad in listOf(
            phases.second[0].copy(attitudeQ15 = listOf(0, 0, 0)),
            phases.second[0].copy(attitudeQ15 = listOf(32767, 32767, 0)),
            phases.second[0].copy(timestampNs = null),
            phases.second[0].copy(timestampNs = phases.first.last().timestampNs),
            phases.second[0].copy(counter = 0))) {
            assertNull(SpatialPoseProfile.calibrate(phases.first, listOf(bad) + phases.second.drop(1), phases.third))
        }
    }

    @Test fun calibrationResultPreservesSuccessAndIdentifiesInputContinuityFailures() {
        val p = phases()
        val success = SpatialPoseProfile.calibrateResult(p.first, p.second, p.third)
        assertNull(success.failure)
        assertEquals(checkNotNull(calibrate()).headFromRaw, checkNotNull(success.profile).headFromRaw)
        rejected(SpatialCalibrationFailure.INSUFFICIENT_SAMPLES, p.copy(first = p.first.take(4)))
        rejected(SpatialCalibrationFailure.SENSOR_TIME_CHANGED,
            p.copy(second = listOf(p.second.first().copy(timestampNs = p.first.last().timestampNs)) + p.second.drop(1)))
        rejected(SpatialCalibrationFailure.SENSOR_COUNTER_CHANGED,
            p.copy(second = listOf(p.second.first().copy(counter = 0)) + p.second.drop(1)))
        rejected(SpatialCalibrationFailure.UNSUPPORTED_ATTITUDE,
            p.copy(second = listOf(p.second.first().copy(attitudeQ15 = listOf(32767, 32767, 0))) + p.second.drop(1)))
    }

    @Test fun calibrationResultDistinguishesHoldReturnGravityAndUpFailures() {
        val p = phases()
        fun moved(first: MotionReport) = report((neutral * axis(1.0, 0.0, 0.0, 8.0)) * mount.inverse(), first.timestampNs!!)
        rejected(SpatialCalibrationFailure.NEUTRAL_UNSTEADY,
            p.copy(first = listOf(moved(p.first.first())) + p.first.drop(1)))
        rejected(SpatialCalibrationFailure.RETURN_UNSTEADY,
            p.copy(third = listOf(moved(p.third.first())) + p.third.drop(1)))
        rejected(SpatialCalibrationFailure.RETURN_NOT_NEUTRAL,
            phases(returnHead = neutral * axis(1.0, 0.0, 0.0, 10.0)))
        rejected(SpatialCalibrationFailure.NEUTRAL_GRAVITY_INVALID,
            p.copy(first = p.first.map { it.copy(gravityRaw = listOf(0, 0, 0)) }))
        rejected(SpatialCalibrationFailure.RETURN_GRAVITY_INVALID,
            p.copy(third = p.third.map { it.copy(gravityRaw = listOf(0, 0, 0)) }))
        rejected(SpatialCalibrationFailure.UP_TOO_SMALL,
            phases(upHead = neutral * axis(1.0, 0.0, 0.0, 7.0)))
        rejected(SpatialCalibrationFailure.UP_AXIS_NOT_HORIZONTAL,
            phases(upHead = neutral * axis(0.0, 0.0, 1.0, 25.0)))
        val sideways = report((neutral * axis(0.0, 1.0, 0.0, 25.0)) * mount.inverse(), p.second.first().timestampNs!!)
        rejected(SpatialCalibrationFailure.UP_AXIS_INCONSISTENT, p.copy(second = listOf(sideways) + p.second.drop(1)))
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

    @Test fun badTimesCounterResetsAndInvalidAttitudesLatchUntilANewConverter() {
        val first = report(neutral * mount.inverse(), 1_000_000_000)
        val next = report(neutral * mount.inverse(), 1_000_000_000 + tick)
        for (bad in listOf(
            next.copy(timestampNs = null), next.copy(timestampNs = 0),
            next.copy(timestampNs = first.timestampNs), next.copy(timestampNs = first.timestampNs!! - 1),
            next.copy(timestampNs = first.timestampNs!! + 250_000_000),
            next.copy(counter = first.counter), next.copy(counter = 0), next.copy(counter = null),
            next.copy(attitudeQ15 = listOf(0, 0, 0)),
            next.copy(attitudeQ15 = listOf(32767, 32767, 0)),
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

    @Test fun forwardShortGapsReprimeRateWithoutChangingSessionReferenceOrDiscontinuity() {
        val first = report(neutral * mount.inverse(), 1_000_000_000)
        val raised = neutral * axis(1.0, 0.0, 0.0, 20.0)
        val after = raised * axis(1.0, 0.0, 0.0, Math.toDegrees(0.01))
        for (gap in listOf(100_000_001L, 119_563_000L, 249_999_999L)) {
            val converter = SpatialPoseConverter(checkNotNull(calibrate()), 17)
            assertNull(converter.accept(first))
            val gapReport = report(raised * mount.inverse(), first.timestampNs!! + gap).copy(counter = first.counter!! + 24)
            assertNull(converter.accept(gapReport)); assertFalse(converter.invalidated)
            val next = report(after * mount.inverse(), gapReport.timestampNs!! + tick).copy(counter = gapReport.counter!! + 4)
            val pose = checkNotNull(converter.accept(next))
            assertEquals(17, pose.discontinuity); assertEquals(tick, pose.intervalNs)
            vector((neutral.inverse() * after).rotvec(), pose.rotationVector.map(Float::toDouble), 0.0004)
            vector(listOf(0.5, 0.0, 0.0), pose.angularVelocity.map(Float::toDouble), 0.02)
        }
    }

    @Test fun gapRecoveryNeverAcceptsClockCounterOrBranchResetsAndLongGapsRemainLatched() {
        val first = report(neutral * mount.inverse(), 1_000_000_000)
        val gap = first.copy(timestampNs = first.timestampNs!! + 119_563_000, counter = first.counter!! + 24)
        val cases = listOf(
            gap.copy(timestampNs = first.timestampNs) to "sensor_time_changed",
            gap.copy(counter = first.counter) to "sensor_counter_changed",
            gap.copy(counter = 0) to "sensor_counter_changed",
            gap.copy(counter = null) to "sensor_counter_changed",
            gap.copy(attitudeQ15 = listOf(32767, 32767, 0)) to "unsupported_attitude",
            gap.copy(timestampNs = first.timestampNs!! + 250_000_000) to "sensor_gap")
        for ((bad, reason) in cases) {
            val converter = SpatialPoseConverter(checkNotNull(calibrate()), 0)
            assertNull(converter.accept(first)); assertNull(converter.accept(bad))
            assertEquals(reason, converter.invalidReason)
            assertNull(converter.accept(gap.copy(timestampNs = gap.timestampNs!! + tick, counter = gap.counter!! + 4)))
        }
        val withoutCounters = SpatialPoseConverter(checkNotNull(SpatialPoseProfile.fromBasis(checkNotNull(calibrate()).headFromRaw)), 0)
        assertNull(withoutCounters.accept(first.copy(counter = null)))
        assertNull(withoutCounters.accept(gap.copy(counter = null)))
        assertEquals("sensor_gap", withoutCounters.invalidReason)
    }

    @Test fun clockCorroboratedSixteenBitWrapWorksInLiveCalibrationAndReferenceHandoff() {
        val profile = checkNotNull(SpatialPoseProfile.fromBasis(checkNotNull(calibrate()).headFromRaw))
        for ((before, after, dt) in listOf(Triple(65534L, 0L, 10_000_000L), Triple(65535L, 1L, 10_000_000L),
            Triple(65532L, 0L, tick), Triple(65524L, 12L, 120_000_000L))) {
            val converter = SpatialPoseConverter(profile, 7)
            val first = report(neutral * mount.inverse(), 1_000_000_000).copy(counter = before)
            assertNull(converter.accept(first))
            val crossing = first.copy(timestampNs = first.timestampNs!! + dt, counter = after)
            if (dt <= 100_000_000) assertNotNull(converter.accept(crossing))
            else assertNull(converter.accept(crossing)) // Existing short-gap reprime remains intact.
            assertFalse(converter.invalidated)
            assertNotNull(converter.accept(crossing.copy(timestampNs = crossing.timestampNs!! + tick, counter = after + 4)))
        }
        val phases = phases()
        val crossing = (phases.first + phases.second + phases.third).mapIndexed { i, value ->
            value.copy(counter = (65516L + i * 4) and 0xffff)
        }
        assertNotNull(SpatialPoseProfile.calibrate(crossing.take(5), crossing.drop(5).take(5), crossing.takeLast(5)))
        val endingAtBoundary = crossing.mapIndexed { i, value -> value.copy(counter = 65478L + i * 4) }
        val referenceProfile = checkNotNull(SpatialPoseProfile.calibrate(endingAtBoundary.take(5),
            endingAtBoundary.drop(5).take(5), endingAtBoundary.takeLast(5)))
        val last = endingAtBoundary.last()
        assertEquals(65534L, last.counter)
        val handoff = SpatialPoseConverter(referenceProfile, 9)
        val first = last.copy(timestampNs = last.timestampNs!! + 10_000_000, counter = 0)
        assertNull(handoff.accept(first)); assertFalse(handoff.invalidated)
        assertNotNull(handoff.accept(first.copy(timestampNs = first.timestampNs!! + 10_000_000, counter = 2)))
        val bad = crossing.toMutableList().also { it[5] = it[5].copy(timestampNs = it[4].timestampNs!! + 250_000_000) }
        assertEquals(SpatialCalibrationFailure.SENSOR_COUNTER_CHANGED,
            SpatialPoseProfile.calibrateResult(bad.take(5), bad.drop(5).take(5), bad.takeLast(5)).failure)
    }

    @Test fun aSixteenBitLookingResetStillRequiresPlausibleTimeAndPreservesFailureLatching() {
        val profile = checkNotNull(SpatialPoseProfile.fromBasis(checkNotNull(calibrate()).headFromRaw))
        for ((before, after, dt) in listOf(Triple(30000L, 0L, tick), Triple(65534L, 0L, 50_000_000L),
            Triple(65534L, 0L, 250_000_000L), Triple(0x1fffeL, 0L, 10_000_000L),
            Triple(65534L, 0L, -10_000_000L))) {
            val converter = SpatialPoseConverter(profile, 0)
            val first = report(neutral * mount.inverse(), 1_000_000_000).copy(counter = before)
            assertNull(converter.accept(first))
            assertNull(converter.accept(first.copy(timestampNs = first.timestampNs!! + dt, counter = after)))
            assertTrue(converter.invalidated)
            assertNull(converter.accept(first.copy(timestampNs = first.timestampNs!! + tick, counter = before + 1)))
        }
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
    private fun rejected(reason: SpatialCalibrationFailure,
        p: Triple<List<MotionReport>, List<MotionReport>, List<MotionReport>>) {
        val result = SpatialPoseProfile.calibrateResult(p.first, p.second, p.third)
        assertEquals(reason, result.failure); assertNull(result.profile)
        assertNull(SpatialPoseProfile.calibrate(p.first, p.second, p.third))
    }
    private fun phases(rawFromHead: TQ = mount, upHead: TQ = neutral * axis(1.0, 0.0, 0.0, 25.0),
        returnHead: TQ = neutral): Triple<List<MotionReport>, List<MotionReport>, List<MotionReport>> {
        var time = 100_000_000L
        fun hold(head: TQ) = List(5) { report(head * rawFromHead.inverse(), time).also { time += tick } }
        return Triple(hold(neutral), hold(upHead).map { it.copy(gravityRaw = listOf(-400, 700, -600)) }, hold(returnHead))
    }
    private fun report(q: TQ, time: Long, worldGravity: List<Double> = listOf(0.0, 0.0, -1.0)) = MotionReport(time,
        listOf(q.x, q.y, q.z).map { (it * if (q.w < 0) -32768 else 32768).roundToInt().coerceIn(-32768, 32767) },
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
        fun dot(b: TQ) = w*b.w + x*b.x + y*b.y + z*b.z
        fun rotate(v: List<Double>): List<Double> = (this * TQ(0.0, v[0], v[1], v[2]) * inverse())
            .let { listOf(it.x, it.y, it.z) }
        fun rotvec(): List<Double> {
            val sign = if (w < 0) -1.0 else 1.0
            val n = sqrt(x*x+y*y+z*z); val scale = sign * if (n < 1e-10) 2.0 else 2 * atan2(n, w * sign) / n
            return listOf(x*scale, y*scale, z*scale)
        }
    }
}
