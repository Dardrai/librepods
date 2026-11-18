/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class TidalEffectiveBufferTrialTest {
    private class Fixture(original: Int = 7230, initialUnderruns: Int = 4) {
        val trial = TidalEffectiveBufferTrial(original, initialUnderruns, 1000)
        var size = original
        var underruns = initialUnderruns
        var sizeReads = 0
        var underrunReads = 0
        val writes = mutableListOf<Int>()
        val set: (Int) -> Int = { writes += it; size = it; it }
        val read: () -> Int = { sizeReads++; size }
        fun apply() = trial.apply(set, read)
        fun poll(now: Long = 2000, enabled: Boolean = true) = trial.poll(now, enabled,
            { underrunReads++; underruns }, read, set)
        fun restore() = trial.restore(set, read)
    }

    @Test fun exactAcceptedLimitAppliesOnlyOnceWithoutDependingOnCapacity() {
        val f = Fixture()
        assertEquals(TidalEffectiveBufferTrial.State.ACTIVE, f.apply())
        assertEquals(4800, f.size)
        assertEquals(listOf(4800), f.writes)
        val reads = f.sizeReads
        assertEquals(TidalEffectiveBufferTrial.State.ACTIVE, f.apply())
        assertEquals(reads, f.sizeReads)
        assertEquals(listOf(4800), f.writes)
    }

    @Test fun smallerOriginalAndInvalidInputsNeverTouchTheTrack() {
        for (original in listOf(1, 4799, 4800)) {
            val f = Fixture(original)
            assertEquals(TidalEffectiveBufferTrial.State.UNCHANGED, f.apply())
            f.poll(); f.restore(); f.apply()
            assertTrue(f.writes.isEmpty()); assertEquals(0, f.sizeReads)
        }
        for ((original, underruns, now) in listOf(Triple(0, 0, 0L), Triple(-1, 0, 0L),
            Triple(7230, -1, 0L), Triple(7230, 0, -1L))) {
            val trial = TidalEffectiveBufferTrial(original, underruns, now)
            assertEquals(TidalEffectiveBufferTrial.State.DECLINED, trial.apply({ error("write") }, { error("read") }))
        }
    }

    @Test fun refusalAndThrowWithoutMutationDeclineWithoutRepeatedRequests() {
        for (throws in listOf(false, true)) {
            val f = Fixture(); var calls = 0
            val set: (Int) -> Int = { calls++; if (throws) error("refused") else -2 }
            assertEquals(TidalEffectiveBufferTrial.State.DECLINED, f.trial.apply(set, f.read))
            f.trial.apply(set, f.read); f.poll(); f.restore()
            assertEquals(1, calls); assertEquals(7230, f.size)
        }
    }

    @Test fun clampedOrPartiallyMutatedRequestsRestoreOnce() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            val set: (Int) -> Int = { requested ->
                f.writes += requested
                f.size = if (requested == 4800) 6000 else requested
                if (requested == 4800 && throws) error("mutated before failure") else f.size
            }
            assertEquals(TidalEffectiveBufferTrial.State.RESTORED, f.trial.apply(set, f.read))
            assertEquals(7230, f.size); assertEquals(listOf(4800, 7230), f.writes)
            f.trial.apply(set, f.read); f.poll(); f.trial.restore(set, f.read)
            assertEquals(listOf(4800, 7230), f.writes)
        }
    }

    @Test fun returnedSuccessWithoutMatchingReadbackIsNotActive() {
        val f = Fixture()
        assertEquals(TidalEffectiveBufferTrial.State.DECLINED, f.trial.apply({ 4800 }, f.read))
        assertEquals(7230, f.size)
    }

    @Test fun failureAfterReachingTargetStillRestoresInsteadOfClaimingSuccess() {
        val f = Fixture()
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, f.trial.apply({ requested ->
            f.writes += requested; f.size = requested; -2
        }, f.read))
        assertEquals(listOf(4800, 7230), f.writes); assertEquals(7230, f.size)
    }

    @Test fun preexistingUnderrunsAreFineAndPollingIsThrottled() {
        val f = Fixture(); f.apply()
        assertEquals(TidalEffectiveBufferTrial.State.ACTIVE, f.poll(1999))
        assertEquals(0, f.underrunReads); assertEquals(1, f.sizeReads)
        assertEquals(TidalEffectiveBufferTrial.State.ACTIVE, f.poll(2000))
        assertEquals(1, f.underrunReads); assertEquals(2, f.sizeReads)
        f.poll(2001); f.poll(2999)
        assertEquals(1, f.underrunReads); assertEquals(2, f.sizeReads)
        f.poll(3000)
        assertEquals(2, f.underrunReads); assertEquals(3, f.sizeReads)
    }

    @Test fun firstAddedUnderrunRestoresAndNeverShrinksAgain() {
        val f = Fixture(); f.apply(); f.underruns++
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, f.poll())
        assertEquals(7230, f.size); assertEquals(listOf(4800, 7230), f.writes)
        val reads = f.sizeReads
        f.poll(3000); f.apply(); f.restore()
        assertEquals(reads, f.sizeReads); assertEquals(listOf(4800, 7230), f.writes)
    }

    @Test fun revocationRestoresImmediatelyEvenBeforeNextPollInterval() {
        val f = Fixture(); f.apply()
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, f.poll(1001, enabled = false))
        assertEquals(0, f.underrunReads); assertEquals(7230, f.size)
    }

    @Test fun changedSizeRestoresUnlessTheFrameworkAlreadyEnlargedIt() {
        val smaller = Fixture(); smaller.apply(); smaller.size = 5500
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, smaller.poll())
        assertEquals(listOf(4800, 7230), smaller.writes)
        for (actual in listOf(7230, 9610)) {
            val f = Fixture(); f.apply(); f.size = actual
            val expected = if (actual == 7230) TidalEffectiveBufferTrial.State.RESTORED
                else TidalEffectiveBufferTrial.State.FRAMEWORK_ENLARGED
            assertEquals(expected, f.poll()); assertEquals(actual, f.size)
            assertEquals(listOf(4800), f.writes)
        }
    }

    @Test fun frameworkGrowthDuringApplyOrRestoreIsPreserved() {
        val applying = Fixture()
        assertEquals(TidalEffectiveBufferTrial.State.FRAMEWORK_ENLARGED,
            applying.trial.apply({ applying.writes += it; applying.size = 9610; 9610 }, applying.read))
        assertEquals(9610, applying.size)
        assertEquals(listOf(4800), applying.writes)
        val restoring = Fixture(); restoring.apply()
        assertEquals(TidalEffectiveBufferTrial.State.FRAMEWORK_ENLARGED,
            restoring.trial.restore({ restoring.size = 9610; 9610 }, restoring.read))
        assertEquals(9610, restoring.size)
    }

    @Test fun teardownRestorationAndFailureAreBothTerminal() {
        val restored = Fixture(); restored.apply()
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, restored.restore())
        assertEquals(listOf(4800, 7230), restored.writes)
        for (throws in listOf(false, true)) {
            val f = Fixture(); f.apply(); var calls = 0
            val set: (Int) -> Int = { calls++; if (throws) error("cannot restore") else -2 }
            assertEquals(TidalEffectiveBufferTrial.State.RESTORE_FAILED, f.trial.restore(set, f.read))
            f.trial.restore(set, f.read); f.poll(); f.apply()
            assertEquals(1, calls); assertEquals(4800, f.size)
        }
    }

    @Test fun unreadableSizeNeverBlindlyShrinksAPossiblyEnlargedBuffer() {
        val f = Fixture(); var writes = 0
        assertEquals(TidalEffectiveBufferTrial.State.RESTORE_FAILED,
            f.trial.apply({ writes++; 4800 }, { error("no readback") }))
        assertEquals(1, writes)
        val active = Fixture(); active.apply()
        assertEquals(TidalEffectiveBufferTrial.State.RESTORE_FAILED,
            active.trial.restore({ error("must not write") }, { error("unknown current size") }))
    }

    @Test fun unreadableOrResetUnderrunsAndBackwardClockRestore() {
        val unreadable = Fixture(); unreadable.apply()
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, unreadable.trial.poll(2000, true,
            { error("no underrun count") }, unreadable.read, unreadable.set))
        val reset = Fixture(); reset.apply(); reset.underruns = 0
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, reset.poll())
        val clock = Fixture(); clock.apply()
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, clock.poll(999))
    }

}
