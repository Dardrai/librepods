/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class HeadMotionGapRecoveryTest {
    private val ms = 1_000_000L
    private val start = 1_000 * ms
    private val basis = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    private val profile = checkNotNull(SpatialPoseProfile.fromBasis(basis))
    private val key = SpatialPoseProfileKey("00:11:22:33:44:55", listOf(1, 0), "A3063", "test")

    private fun healthy(value: HeadMotionGapRecovery, from: Long, durationMs: Long): Long {
        for (elapsed in 0L..durationMs step 100) value.submitted(from + elapsed * ms, from + elapsed * ms)
        if (durationMs % 100 != 0L) value.submitted(from + durationMs * ms, from + durationMs * ms)
        return from + durationMs * ms
    }

    @Test fun exactlyOneAutomaticRetryAndEarlySecondGapLatches() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true)); assertFalse(value.latched)
        assertFalse(value.failed(true)); assertTrue(value.latched)
        healthy(value, start, 60_000)
        assertFalse(value.failed(true)); assertTrue(value.latched)
    }

    @Test fun malformedFaultLatchesEvenWithAnUnusedBudget() {
        val value = HeadMotionGapRecovery()
        assertFalse(value.failed(false)); assertTrue(value.latched)
        assertFalse(value.failed(true))
    }

    @Test fun twentyNinePointNineNineNineSecondsDoesNotReplenish() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true))
        healthy(value, start, 29_999)
        assertFalse(value.failed(true))
    }

    @Test fun thirtySecondsOfSuccessfulFreshSubmissionsReplenishesOnce() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true))
        healthy(value, start, 30_000)
        assertTrue(value.failed(true)); assertFalse(value.latched)
        assertFalse(value.failed(true))
    }

    @Test fun repeatedLongHealthyPeriodsCanRecoverLaterIndependentGaps() {
        val value = HeadMotionGapRecovery()
        var at = start
        repeat(3) {
            assertTrue(value.failed(true))
            at = healthy(value, at, 30_000) + 100 * ms
        }
        assertTrue(value.failed(true))
    }

    @Test fun wallTimeAndPausedInputCannotReplenish() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true))
        healthy(value, start, 29_900)
        value.submitted(start + 60_000 * ms, start + 60_000 * ms)
        assertFalse(value.failed(true))
    }

    @Test fun briefWithheldPlaybackBreaksHealthyRunWithoutLatchingOrRefunding() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true))
        val at = healthy(value, start, 29_900)
        value.withheld()
        assertFalse(value.latched)
        value.submitted(at + 100 * ms, at + 100 * ms)
        assertFalse(value.failed(true))
        value.withheld(); assertTrue(value.latched)
        val unused = HeadMotionGapRecovery()
        unused.withheld(); assertTrue(unused.failed(true))
    }

    @Test fun invalidStaleReversedAndNonIncreasingInputBreaksHealthyRun() {
        val at = start + 29_900 * ms
        val invalid = listOf(
            0L to at + 100 * ms,
            at + 100 * ms to 0L,
            at + 101 * ms to at + 100 * ms,
            at + 100 * ms to at + 351 * ms,
            at to at + 100 * ms,
            at - ms to at + 100 * ms,
            at + ms to at,
        )
        for ((arrival, now) in invalid) {
            val value = HeadMotionGapRecovery()
            assertTrue(value.failed(true)); healthy(value, start, 29_900)
            value.submitted(arrival, now)
            value.submitted(at + 200 * ms, at + 200 * ms)
            assertFalse("arrival=$arrival now=$now", value.failed(true))
        }
    }

    @Test fun exactQuarterSecondArrivalOrSubmissionGapBreaksContinuity() {
        for ((arrival, now) in listOf(250L to 250L, 100L to 250L)) {
            val value = HeadMotionGapRecovery()
            assertTrue(value.failed(true))
            val at = healthy(value, start, 29_900)
            value.submitted(at + arrival * ms, at + now * ms)
            assertFalse(value.failed(true))
        }
    }

    @Test fun freshDelayedSubmissionsCanReplenishWithoutInventingArrivalTime() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true))
        for (elapsed in 0L..30_000L step 100)
            value.submitted(start + elapsed * ms, start + (elapsed + 250) * ms)
        assertTrue(value.failed(true))
    }

    @Test fun reversedHostClockBreaksContinuityEvenWithIncreasingFreshArrivals() {
        val value = HeadMotionGapRecovery()
        assertTrue(value.failed(true))
        for (elapsed in 0L..29_900L step 100)
            value.submitted(start + elapsed * ms, start + (elapsed + 200) * ms)
        val arrival = start + 29_900 * ms
        value.submitted(arrival + 50 * ms, arrival + 199 * ms)
        value.submitted(arrival + 100 * ms, arrival + 300 * ms)
        assertFalse(value.failed(true))
    }

    @Test fun automaticReacquisitionAndEqualProfileObjectsKeepSpentBudget() {
        val scopes = HeadMotionGapRecovery.Scopes(); val socket = Any()
        val first = scopes.forSource(socket, key, profile, 1)
        assertTrue(first.failed(true))
        val again = scopes.forSource(socket, key.copy(), checkNotNull(SpatialPoseProfile.fromBasis(basis)), 1)
        assertSame(first, again)
        assertFalse(again.failed(true))
        assertSame(again, scopes.forSource(socket, key, profile, 1))
    }

    @Test fun explicitSocketIdentityKeyBasisReferenceOrChoiceChangesStartNewBudget() {
        fun changed(check: (HeadMotionGapRecovery.Scopes, Any) -> HeadMotionGapRecovery) {
            val scopes = HeadMotionGapRecovery.Scopes(); val socket = Any()
            val old = scopes.forSource(socket, key, profile, 1)
            assertFalse(old.failed(false))
            val next = check(scopes, socket)
            assertNotSame(old, next); assertTrue(next.failed(true))
        }
        changed { scopes, _ -> scopes.forSource(Any(), key, profile, 1) }
        changed { scopes, socket -> scopes.forSource(socket, key.copy(rawRole = listOf(2, 0)), profile, 1) }
        changed { scopes, socket -> scopes.forSource(socket, key.copy(firmware = "other"), profile, 1) }
        changed { scopes, socket -> scopes.forSource(socket, key.copy(macAddress = "00:11:22:33:44:66"), profile, 1) }
        changed { scopes, socket -> scopes.forSource(socket, key, profile, 2) }
        val rotated = checkNotNull(SpatialPoseProfile.fromBasis(
            listOf(listOf(0.0, -1.0, 0.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0))))
        changed { scopes, socket -> scopes.forSource(socket, key, rotated, 1) }
        // Create reference-bearing profiles without exercising unrelated calibration UI/geometry.
        val constructor = SpatialPoseProfile::class.java.getDeclaredConstructor(List::class.java,
            java.lang.Long::class.java, java.lang.Long::class.java).apply { isAccessible = true }
        changed { scopes, socket -> scopes.forSource(socket, key, constructor.newInstance(basis, 10L, null), 1) }
        changed { scopes, socket -> scopes.forSource(socket, key, constructor.newInstance(basis, null, 20L), 1) }
    }

    @Test fun changingAwayAndBackRetainsOnlyOneScope() {
        val scopes = HeadMotionGapRecovery.Scopes(); val socket = Any()
        val old = scopes.forSource(socket, key, profile, 1)
        assertTrue(old.failed(true))
        scopes.forSource(socket, key, profile, 2)
        val returned = scopes.forSource(socket, key, profile, 1)
        assertNotSame(old, returned); assertTrue(returned.failed(true))
    }

    @Test fun socketEqualityCannotSubstituteForIdentityAndRoleIsSnapshotted() {
        data class EqualSocket(val id: Int)
        val scopes = HeadMotionGapRecovery.Scopes()
        val firstSocket = EqualSocket(1)
        val first = scopes.forSource(firstSocket, key, profile, 1)
        assertTrue(first.failed(true))
        val distinctSocket = scopes.forSource(EqualSocket(1), key, profile, 1)
        assertNotSame(first, distinctSocket); assertTrue(distinctSocket.failed(true))
        val roles = mutableListOf(1, 0)
        val mutableKey = key.copy(rawRole = roles)
        val before = scopes.forSource(firstSocket, mutableKey, profile, 1)
        assertTrue(before.failed(true))
        roles[0] = 2
        val after = scopes.forSource(firstSocket, mutableKey, profile, 1)
        assertNotSame(before, after); assertTrue(after.failed(true))
    }
}
