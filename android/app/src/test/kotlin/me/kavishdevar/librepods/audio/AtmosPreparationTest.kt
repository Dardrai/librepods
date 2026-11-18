/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosPreparationTest {
    private fun status(phase: Long) = LongArray(20).apply {
        this[0] = 1; this[1] = phase; this[4] = 11; this[5] = 22; this[6] = 33; this[7] = 44
    }
    private fun state(phase: Long) = Bundle().apply {
        putBoolean("live", true); putLongArray("native_status", status(phase))
        putBoolean("can_stream", true); putBoolean("playback_verified", true)
    }

    @Test fun preparingWaitsForUserPlaybackWithoutStartingPoseOrUsingNonceTimeout() = runBlocking {
        var time = 1_000L; var reads = 0; var producerStarted = false
        val binding = awaitAtmosDecoder(60_000, { true }, { true }, {
            reads++; state(if (reads < 4) 1 else 2)
        }, { time }, {
            assertFalse(producerStarted)
            time += 10_000 // User playback starts well after the separate 5-second nonce lookup.
        })
        producerStarted = true
        assertTrue(producerStarted); assertEquals(4, reads); assertEquals(31_000L, time)
        assertTrue(binding.current(status(2))); assertTrue(binding.current(status(3))); assertTrue(binding.current(status(4)))
    }

    @Test fun ownerOrPermitLossWhilePreparingFailsBeforeProducerStart() = runBlocking {
        for (loseOwner in listOf(true, false)) {
            var owner = true; var permit = true; var waits = 0
            val failure = runCatching {
                awaitAtmosDecoder(60_000, { owner }, { permit }, { state(1) }, { 1000 }, {
                    waits++; if (loseOwner) owner = false else permit = false
                })
            }.exceptionOrNull()
            assertEquals("preparation_retired", failure?.message); assertEquals(1, waits)
        }
    }

    @Test fun nativeReadyStillWaitsForRootObservedPlayerRoute() = runBlocking {
        var verified = false; var waits = 0
        val bound = awaitAtmosDecoder(2000, { true }, { true }, {
            state(2).apply { putBoolean("can_stream", verified); putBoolean("playback_verified", verified) }
        }, { 1000 }, { waits++; verified = true })
        assertEquals(1, waits); assertTrue(bound.current(status(2)))
    }

    @Test fun deadlineTerminalStateAndChangedDecoderNeverBecomeReady() = runBlocking {
        assertEquals("preparation_retired", runCatching {
            awaitAtmosDecoder(1000, { true }, { true }, { state(1) }, { 1000 })
        }.exceptionOrNull()?.message)
        for (phase in listOf(0L, 5L, 6L)) assertEquals("native_not_ready", runCatching {
            awaitAtmosDecoder(2000, { true }, { true }, { state(phase) }, { 1000 })
        }.exceptionOrNull()?.message)
        val bound = awaitAtmosDecoder(2000, { true }, { true }, { state(2) }, { 1000 })
        for (index in 4..7) assertFalse(bound.current(status(3).apply { this[index]++ }))
        assertFalse(bound.current(status(5))); assertFalse(bound.current(null))
    }

    @Test fun handoffKeepsWaitingForCoherentFreshBindingButNotPastOriginalDeadline() = runBlocking {
        var reads = 0; var now = 100L
        val bound = awaitAtmosDecoder(1000, { true }, { true }, {
            reads++
            if (reads == 1) Bundle().apply { putBoolean("live", true); putBoolean("handoff_pending", true) }
            else state(4)
        }, { now }, { now += 100 }, streamAlreadyRunning = true)
        assertEquals(2, reads); assertTrue(bound.current(status(4)))
        assertEquals("preparation_retired", runCatching {
            awaitAtmosDecoder(300, { true }, { true }, {
                Bundle().apply { putBoolean("live", true); putBoolean("handoff_pending", true) }
            }, { now }, { now += 100 }, streamAlreadyRunning = true)
        }.exceptionOrNull()?.message)
    }

    @Test fun handoffRequiresExactOldBindingAndNeverTurnsTerminalIntoRetry() {
        val old = AtmosDecoderBinding(11, 22, 33, 44)
        val pending = longArrayOf(1, 1, 100, 600, 0, 11, 22, 33, 44)
        assertNotNull(atmosHandoffFor(pending, old))
        assertEquals("handoff_binding_changed", runCatching { atmosHandoffFor(pending, old.copy(epoch = 34)) }.exceptionOrNull()?.message)
        assertEquals("handoff_failed", runCatching { atmosHandoffFor(pending.copyOf().apply { this[1] = 4 }, old) }.exceptionOrNull()?.message)
        assertNull(atmosHandoffFor(pending.copyOf().apply { this[1] = 3 }, old.copy(control = 12, instance = 23), authorizedControl = 11))
        for (phase in 5L..7L) assertNotNull(atmosHandoffFor(pending.copyOf().apply { this[1] = phase }, old))
        assertNull(atmosHandoffFor(longArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0), old))
        assertEquals("invalid_handoff_state", runCatching {
            atmosHandoffFor(pending.copyOf().apply { this[1] = 0 }, old)
        }.exceptionOrNull()?.message)
    }

    @Test fun pauseBeforeFirstBeginKeepsKnownPreparedControlAndOriginalDeadline() = runBlocking {
        val paused = longArrayOf(1, 5, 100, 600, 0, 11, 0, 0, 0)
        assertNotNull(atmosHandoffFor(paused, null, preparedControl = 11))
        for (phase in 1L..2L) {
            val pending = paused.copyOf().apply { this[1] = phase }
            assertNotNull(atmosHandoffFor(pending, null, preparedControl = 11))
            assertEquals("handoff_binding_changed", runCatching {
                atmosHandoffFor(pending, AtmosDecoderBinding(11, 22, 33, 44), preparedControl = 11)
            }.exceptionOrNull()?.message)
        }
        assertEquals("handoff_binding_changed", runCatching {
            atmosHandoffFor(paused, null, preparedControl = 12)
        }.exceptionOrNull()?.message)
        assertNotNull(atmosHandoffFor(paused.copyOf().apply { this[1] = 3 }, null,
            preparedControl = 12, authorizedControl = 11))
        var reads = 0; var transitions = 0; var clock = 100L
        val ready = awaitAtmosDecoder(10_000, { true }, { true }, {
            reads++
            if (reads == 1) state(7).apply {
                putBoolean("can_stream", false); putBoolean("playback_verified", false)
                putBoolean("handoff_pending", true); putLongArray("native_handoff_state", paused)
            } else state(2)
        }, { clock }, { clock += 6000 }, handleTransition = { transitions++ })
        assertEquals(2, transitions); assertEquals(6100L, clock); assertTrue(ready.current(status(2)))
        assertEquals("preparation_retired", runCatching {
            awaitAtmosDecoder(7000, { true }, { true }, {
                state(7).apply { putBoolean("handoff_pending", true); putLongArray("native_handoff_state", paused) }
            }, { clock }, { clock += 1000 }, handleTransition = {})
        }.exceptionOrNull()?.message)
    }

    @Test fun rejectedMarkerOnlyWaitsForConcurrentExactKnownPauseCleanup() {
        for (target in listOf(longArrayOf(22, 33, 44), longArrayOf(0, 0, 0))) {
            val before = longArrayOf(1, 1, 100, 600, 0, 11, target[0], target[1], target[2])
            val cleanup = Bundle().apply {
                putBoolean("live", true)
                putLongArray("native_handoff_state", before.copyOf().apply { this[1] = 7 })
            }
            assertTrue(atmosPauseCleanupRacedAuthorization(cleanup, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(cleanup).apply { putBoolean("live", false) }, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(cleanup).apply { putBoolean("can_stream", true) }, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(cleanup).apply { putBoolean("handoff_authorizable", true) }, before))
            for (phase in listOf(1L, 2L, 3L, 4L, 6L)) assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(cleanup).apply {
                putLongArray("native_handoff_state", before.copyOf().apply { this[1] = phase })
            }, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(cleanup, before.copyOf().apply { this[5]++ }))
            val completed = Bundle(cleanup).apply {
                putBoolean("handoff_authorizable", true)
                putLongArray("native_handoff_state", before.copyOf().apply { this[1] = 5 })
            }
            assertTrue(atmosPauseCleanupRacedAuthorization(completed, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(completed, before.copyOf().apply { this[1] = 5 }))
            assertFalse(atmosPauseCleanupRacedAuthorization(completed, before.copyOf().apply { this[5]++ }))
            assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(completed).apply { putBoolean("live", false) }, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(completed).apply { putBoolean("playback_verified", true) }, before))
            assertFalse(atmosPauseCleanupRacedAuthorization(Bundle(completed).apply { putBoolean("can_stream", true) }, before))
        }
    }

    @Test fun sourceRecoveryKeepsExistingPipeAliveUntilAFreshVerifiedBinding() = runBlocking<Unit> {
        val old = AtmosDecoderBinding(11, 22, 33, 44)
        val cleanup = longArrayOf(1, 8, 100, 500_000_100, 0, 11, 22, 33, 44)
        var reads = 0
        var transitions = 0
        var time = 100L
        val bound = awaitAtmosDecoder(1000, { true }, { true }, {
            val phase = listOf(8L, 9L, 3L, 0L)[reads++]
            if (phase in 8L..9L) state(7).apply {
                putBoolean("can_stream", false); putBoolean("playback_verified", false)
                putBoolean("handoff_pending", true)
                putLongArray("native_handoff_state", cleanup.copyOf().apply { this[1] = phase })
            } else state(if (phase == 3L) 1 else 2).apply {
                putLongArray("native_status", status(if (phase == 3L) 1 else 2).apply { this[4] = 12; this[7] = 45 })
                putBoolean("can_stream", phase == 0L); putBoolean("playback_verified", phase == 0L)
                putLongArray("native_handoff_state", if (phase == 0L) longArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
                    else cleanup.copyOf().apply { this[1] = 3; this[4] = 5_000_000_100 })
            }
        }, { time }, { time += 25 }, streamAlreadyRunning = true, handleTransition = { state ->
            transitions++
            val transition = atmosHandoffFor(state.getLongArray("native_handoff_state"), old)
            if (transition != null && transition[1] in 8L..9L) assertFalse(state.getBoolean("handoff_authorizable"))
        })
        assertEquals(4, reads); assertEquals(4, transitions)
        assertEquals(AtmosDecoderBinding(12, 22, 33, 45), bound)
        assertNull(atmosHandoffFor(cleanup.copyOf().apply { this[1] = 3; this[4] = 5_000_000_100 },
            bound, preparedControl = 12, authorizedControl = 11))
    }

    @Test fun sourceRecoveryRejectsPartialOrUnboundedStateAndNeverOutlivesThePermit() = runBlocking<Unit> {
        val old = AtmosDecoderBinding(11, 22, 33, 44)
        for (phase in 8L..9L) {
            val wire = longArrayOf(1, phase, 100, 500_000_100, 0, 11, 22, 33, 44)
            assertNotNull(atmosHandoffFor(wire, old))
            for (index in listOf(2, 3, 5, 6, 7, 8)) assertEquals("invalid_handoff_state", runCatching {
                atmosHandoffFor(wire.copyOf().apply { this[index] = 0 }, old)
            }.exceptionOrNull()?.message)
            assertEquals("invalid_handoff_state", runCatching {
                atmosHandoffFor(wire.copyOf().apply { this[3]++ }, old)
            }.exceptionOrNull()?.message)
            assertEquals("handoff_binding_changed", runCatching {
                atmosHandoffFor(wire, old.copy(epoch = 34))
            }.exceptionOrNull()?.message)
            var owner = true
            var reads = 0
            assertEquals("preparation_retired", runCatching {
                awaitAtmosDecoder(1000, { owner }, { true }, {
                    reads++; state(7).apply { putLongArray("native_handoff_state", wire); putBoolean("handoff_pending", true) }
                }, { 100 }, { owner = false }, streamAlreadyRunning = true, handleTransition = {})
            }.exceptionOrNull()?.message)
            assertEquals(1, reads)
        }
    }
}
