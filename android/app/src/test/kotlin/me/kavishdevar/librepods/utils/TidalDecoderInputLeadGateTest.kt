/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class TidalDecoderInputLeadGateTest {
    @Test fun anUnderrunRetiresOnlyItsAudioTrackEvenAfterAFlush() {
        val reserve = TidalLateRenderReserve()
        val first = Any()
        val next = Any()
        val trips = mutableListOf<Int>()
        assertTrue(reserve.check(first, 0, { TidalLateRenderReserve.Sample(0, 4800) }, trips::add))
        assertFalse(reserve.check(first, 25, { TidalLateRenderReserve.Sample(1, 4800) }, trips::add))
        assertTrue(reserve.failed(first))
        reserve.flush(first)
        assertFalse(reserve.check(first, 26, { error("Tripped track was sampled") }, trips::add))
        assertFalse(reserve.check(next, 27, { TidalLateRenderReserve.Sample(0, 3300) }, trips::add))
        assertTrue(reserve.check(next, 52, { TidalLateRenderReserve.Sample(0, 4800) }, trips::add))
        assertFalse(reserve.check(first, 53, { error("Older tripped track was sampled") }, trips::add))
        assertEquals(listOf(1), trips)
    }

    @Test fun refreshBeforeYieldingKeepsEnoughAudioQueuedAndRespectsTheSameFloor() {
        val reserve = TidalLateRenderReserve()
        val track = Any()
        var reads = 0
        var queued = 4320L
        fun check(atMs: Long) = reserve.check(track, atMs, { reads++; TidalLateRenderReserve.Sample(0, queued) }) {
            error("Unexpected underrun")
        }
        assertTrue(check(0))
        assertTrue(check(10))
        assertEquals(1, reads)
        assertTrue(check(11))
        assertEquals(2, reads)
        queued = 3839
        assertFalse(check(22))
        assertFalse(check(23))
        assertEquals(3, reads)
    }

    private class Fixture {
        val gate = TidalDecoderInputLeadGate()
        var owner = Any(); var track = Any(); var claim = Any()
        var now = 1000L; var position = 1_000_000_000_000L; var offset = position
        var playingHead: Long? = 0; var reads = 0; var originalCalls = 0
        fun begin(started: Boolean = true, outputOffset: Long = offset) = gate.begin(owner, track, claim,
            offset, outputOffset, position, now, started) { reads++; playingHead }
        fun progressed(): TidalDecoderInputLeadGate.Window? {
            begin(); now += 100; position += 100_000; playingHead = 4800
            return begin()
        }
        fun feed(window: TidalDecoderInputLeadGate.Window?, lead: Long = 500_000,
            current: Boolean = true, control: Boolean = false) = gate.feed(window, position + lead, now,
            current, control) { originalCalls++; true }
    }

    @Test fun startupNeedsTwoActualHeadReadbacksWithProgressDespiteLargeLead() {
        val f = Fixture()
        assertTrue(f.feed(f.begin(), 900_000))
        f.now += 100
        assertTrue(f.feed(f.begin(), 900_000)) // PLAYING with a stationary head is not startup proof.
        f.now += 100; f.playingHead = 1
        assertFalse(f.feed(f.begin()))
        assertEquals(2, f.originalCalls)
        assertEquals(1L, f.gate.blocked)
    }

    @Test fun blocksBeforeOriginalOnlyAtBudgetAndUsesCurrentRenderPosition() {
        val f = Fixture(); val first = f.progressed()
        assertFalse(f.feed(first))
        assertEquals(0, f.originalCalls)
        f.now += 10; f.position += 10_000
        assertTrue(f.feed(f.begin(), 499_999))
        assertEquals(1, f.originalCalls)
        assertEquals(499_999L, f.gate.lastLeadUs)
        assertEquals(500_000L, f.gate.maxLeadUs)
        assertEquals(2, f.reads) // No AudioTrack query on every render/feed invocation.
    }

    @Test fun shorterExperimentBudgetOnlyBlocksWhenOutputHasReserve() {
        val f = Fixture()
        val window = f.progressed()
        val budget = TidalDecoderInputLeadGate.LATE_RENDER_BUDGET_US
        fun feed(lead: Long, enough: Boolean) = f.gate.feed(window, f.position + lead, f.now,
            current = true, controlPending = false, budgetUs = budget, hasReserve = { enough }) {
            f.originalCalls++
            true
        }
        assertTrue(feed(budget - 1, true))
        assertTrue(feed(budget, false))
        assertEquals("low_output_reserve", f.gate.reason)
        assertFalse(feed(budget, true))
        assertEquals(2, f.originalCalls)
    }

    @Test fun stalledPlaybackReleasesBackpressureAndRequiresNewProgress() {
        val f = Fixture(); f.progressed()
        f.now += 251
        assertTrue(f.feed(f.begin())) // Rebuffer cannot deadlock behind this input gate.
        assertEquals("no_head_progress", f.gate.reason)
        f.now += 100; f.playingHead = 9600
        assertFalse(f.feed(f.begin()))
    }

    @Test fun oldRenderScopeUnknownTimestampAndMismatchedOffsetsCannotBlock() {
        val f = Fixture(); val old = f.progressed(); f.now += 51
        assertTrue(f.feed(old))
        assertEquals("stale_position", f.gate.reason)
        assertTrue(f.feed(null))
        assertTrue(f.gate.feed(f.begin(), -9223372036854775807L, f.now, true, false) { f.originalCalls++; true })
        assertEquals("unknown_input_time", f.gate.reason)
        assertNull(f.begin(outputOffset = f.offset + 1))
        f.offset = -9223372036854775807L
        assertNull(f.begin())
    }

    @Test fun drainEosAndLostPermitPassThroughOnceAndInvalidateOldWindow() {
        for (control in listOf(true, false)) {
            val f = Fixture(); val ready = f.progressed()
            assertTrue(f.feed(ready, current = control, control = control))
            assertEquals(1, f.originalCalls)
            assertTrue(f.feed(ready))
            assertEquals(2, f.originalCalls)
            assertEquals(0L, f.gate.blocked)
        }
    }

    @Test fun ownerTrackClaimSeekAndFlushEachRequireFreshHeadProgress() {
        val changes: List<(Fixture) -> Unit> = listOf({ it.owner = Any() }, { it.track = Any() },
            { it.claim = Any() }, { it.offset += 1 }, { it.position -= 1 }, { it.gate.reset("flush") })
        for (change in changes) {
            val f = Fixture(); val old = f.progressed(); change(f)
            assertTrue(f.feed(f.begin()))
            assertTrue(f.feed(old))
            assertEquals(0L, f.gate.blocked)
        }
    }

    @Test fun pauseAndPlaybackHeadResetNeverReuseOldStartupProof() {
        val f = Fixture(); f.progressed()
        assertNull(f.begin(started = false))
        assertTrue(f.feed(f.begin()))
        f.now += 100; f.playingHead = 0
        assertNull(f.begin())
        assertTrue(f.feed(f.begin()))
    }

    @Test fun originalExceptionsPropagateWithoutRetryAndInputTimeResetPassesThrough() {
        val f = Fixture(); val ready = f.progressed()
        val original = IllegalStateException("codec failure")
        assertSame(original, assertThrows(IllegalStateException::class.java) {
            f.gate.feed(ready, f.position + 499_999, f.now, true, false) { f.originalCalls++; throw original }
        })
        assertEquals(1, f.originalCalls)
        assertTrue(f.feed(ready, 400_000))
        assertEquals("input_time_reset", f.gate.reason)
    }
}
