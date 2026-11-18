/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class TidalEffectiveBufferRetentionTest {
    private class Track(original: Int = 7230) {
        val trial = TidalEffectiveBufferTrial(original, 4, 1000)
        var size = original
        var underruns = 4
        val writes = mutableListOf<Int>()
        val set: (Int) -> Int = { writes += it; size = it; it }
        val read: () -> Int = { size }
        fun apply() = trial.apply(set, read)
        fun poll(now: Long = 2000, enabled: Boolean = true) = trial.poll(now, enabled, { underruns }, read, set)
        fun restore() = trial.restore(set, read)
    }

    @Test fun compatibleSameTrackConfigurationKeeps100msWithoutAnotherSetter() {
        val f = Track(); f.apply(); val renderer = Any(); val provider = Any()
        val scope = TidalRetainedBufferScope(true, f, renderer, provider, f.trial)
        for (mode in listOf(TidalBufferProbePolicy.Mode.RETAINED, TidalBufferProbePolicy.Mode.DIAGNOSTIC))
            assertTrue(scope.permits(f, renderer, provider, mode, true, true, true))
        f.poll()
        assertEquals(4800, f.size); assertEquals(listOf(4800), f.writes)
    }

    @Test fun anUnmodifiedExact100msTrackCanRetainButASmallerUnknownLimitIsNotPromoted() {
        val renderer = Any(); val provider = Any()
        for (frames in listOf(2400, 4800)) {
            val f = Track(frames); f.apply()
            val scope = TidalRetainedBufferScope(true, f, renderer, provider, f.trial)
            assertEquals(frames == 4800,
                scope.permits(f, renderer, provider, TidalBufferProbePolicy.Mode.RETAINED, true, true, true))
            assertTrue(f.writes.isEmpty()); assertEquals(frames, f.size)
        }
    }

    @Test fun retainedConfigurationCannotInventOwnershipOrLoseItsExactScope() {
        val f = Track(); f.apply(); val renderer = Any(); val provider = Any()
        fun scope(requested: Boolean = true, oldTrack: Any? = f, oldRenderer: Any? = renderer,
            oldProvider: Any? = provider, trial: TidalEffectiveBufferTrial? = f.trial) =
            TidalRetainedBufferScope(requested, oldTrack, oldRenderer, oldProvider, trial)
        fun permits(value: TidalRetainedBufferScope = scope(), actualTrack: Any? = f, actualRenderer: Any? = renderer,
            actualProvider: Any? = provider, mode: TidalBufferProbePolicy.Mode = TidalBufferProbePolicy.Mode.RETAINED,
            compatible: Boolean = true, admitted: Boolean = true, quiet: Boolean = true) =
            value.permits(actualTrack, actualRenderer, actualProvider, mode, compatible, admitted, quiet)
        assertFalse(permits(scope(requested = false))); assertFalse(permits(scope(trial = null)))
        assertFalse(permits(scope(oldTrack = null))); assertFalse(permits(scope(oldRenderer = null)))
        assertFalse(permits(scope(oldProvider = null)))
        assertFalse(permits(actualTrack = Any())); assertFalse(permits(actualRenderer = Any()))
        assertFalse(permits(actualProvider = Any())); assertFalse(permits(actualTrack = null))
        assertFalse(permits(mode = TidalBufferProbePolicy.Mode.NONE))
        assertFalse(permits(compatible = false)); assertFalse(permits(admitted = false)); assertFalse(permits(quiet = false))
        assertEquals(listOf(4800), f.writes)
    }

    @Test fun aNewUnderrunStillRestoresOriginalAndCannotBeRevivedByConfiguration() {
        val f = Track(); f.apply(); val renderer = Any(); val provider = Any()
        val scope = TidalRetainedBufferScope(true, f, renderer, provider, f.trial)
        f.underruns++
        assertEquals(TidalEffectiveBufferTrial.State.RESTORED, f.poll())
        assertFalse(scope.permits(f, renderer, provider, TidalBufferProbePolicy.Mode.RETAINED, true, true, true))
        f.apply(); f.poll(3000)
        assertEquals(7230, f.size); assertEquals(listOf(4800, 7230), f.writes)
    }

    @Test fun teardownAndFailedRestorationRemainTerminal() {
        val renderer = Any(); val provider = Any()
        for (failedRestore in listOf(false, true)) {
            val f = Track(); f.apply()
            val scope = TidalRetainedBufferScope(true, f, renderer, provider, f.trial)
            f.trial.restore(if (failedRestore) { { -2 } } else f.set, f.read)
            assertFalse(scope.permits(f, renderer, provider, TidalBufferProbePolicy.Mode.RETAINED, true, true, true))
            val writes = f.writes.toList(); f.apply(); assertEquals(writes, f.writes)
        }
    }

    @Test fun preservingScopeLetsTheExistingPollPreserveFrameworkGrowth() {
        val f = Track(); f.apply(); val renderer = Any(); val provider = Any()
        val scope = TidalRetainedBufferScope(true, f, renderer, provider, f.trial)
        f.size = 9610
        val admitted = scope.permits(f, renderer, provider, TidalBufferProbePolicy.Mode.RETAINED, true, true, true)
        assertTrue(admitted)
        assertEquals(TidalEffectiveBufferTrial.State.FRAMEWORK_ENLARGED, f.poll(enabled = admitted))
        assertEquals(9610, f.size); assertEquals(listOf(4800), f.writes)
        assertFalse(scope.permits(f, renderer, provider, TidalBufferProbePolicy.Mode.RETAINED, true, true, true))
    }

    @Test fun flushedIdentityCannotBeReclaimedButANewTrackGetsItsOwn100msTrial() {
        val trials = TidalEffectiveBufferTrials()
        val retained = Track()
        assertSame(retained.trial, trials.claim(retained) { retained.trial })
        retained.apply(); trials[retained]!!.restore(retained.set, retained.read)
        assertEquals(7230, retained.size)
        assertNull(trials.claim(retained) { error("a terminal track must never get another trial") })
        assertSame(retained.trial, trials[retained])
        assertEquals(listOf(4800, 7230), retained.writes)
        val replacement = Track()
        assertSame(replacement.trial, trials.claim(replacement) { replacement.trial })
        replacement.apply()
        trials[retained]!!.restore(retained.set, retained.read)
        assertEquals(4800, replacement.size); assertEquals(listOf(4800), replacement.writes)
        assertEquals(TidalEffectiveBufferTrial.State.ACTIVE, trials[replacement]!!.state)
    }
}
