/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosDecoderHandoffTest {
    private val old = AtmosRootDecoderBinding(21, 31, 41, 51)
    private val player = AtmosPlaybackWitness(10001, 123, 17, 19, 7, "02:11:22:33:44:55")
    private val captured = AtmosHandoffCapture(old, player, "1".repeat(32))
    private val loss = 2_000_000_000L
    private fun wire(phase: Long = 1) = longArrayOf(1, phase, loss, loss + 500_000_000, loss + 5_000_000_000, 21, 31, 41, 51)
    private fun marker() = Bundle().apply {
        putString("nonce", captured.nonce); putLong("buffer_issued_ms", 100)
        putInt("process_pid", player.pid); putInt("audio_session_id", player.session)
        putLong("ownership_epoch", 1); putLong("event_boottime_ns", loss - 10_000_000)
    }

    @Test fun markerMustMatchCapturedClaimPlayerAndUnrenewedWindow() {
        val state = requireNotNull(AtmosDecoderHandoff.from(wire()))
        fun accepts(value: Bundle) = captured.accepts(value, state, 100, loss + 100_000_000)
        assertTrue(accepts(marker()))
        assertFalse(accepts(marker().apply { putString("nonce", "2".repeat(32)) }))
        assertFalse(accepts(marker().apply { putLong("buffer_issued_ms", 101) }))
        assertFalse(accepts(marker().apply { putInt("process_pid", 124) }))
        assertFalse(accepts(marker().apply { putInt("audio_session_id", 20) }))
        assertFalse(accepts(marker().apply { putLong("ownership_epoch", 0) }))
        assertFalse(accepts(marker().apply { putLong("event_boottime_ns", loss - 500_000_001) }))
        assertFalse(accepts(marker().apply { putLong("event_boottime_ns", loss + 100_000_001) }))
        assertFalse(captured.accepts(marker(), state, 100, state.markerDeadlineNs))
        assertFalse(captured.accepts(marker(), state.copy(old = old.copy(control = 22)), 100, loss + 1))
        assertFalse(captured.accepts(marker(), state.copy(phase = 2), 100, loss + 1))
    }

    @Test fun newPlayerNeedsFreshNativeBindingSameProcessAndRoute() {
        val next = player.copy(player = 18)
        val ready = old.copy(control = 22, revision = 52)
        assertTrue(captured.newPlayerReady(next, ready))
        assertFalse(captured.newPlayerReady(player, ready))
        assertFalse(captured.newPlayerReady(next, old))
        assertFalse(captured.newPlayerReady(next.copy(pid = 124), ready))
        assertFalse(captured.newPlayerReady(next.copy(uid = 10002), ready))
        assertFalse(captured.newPlayerReady(next.copy(routeId = 8), ready))
        assertFalse(captured.newPlayerReady(next.copy(address = "02:11:22:33:44:56"), ready))
        assertFalse(captured.newPlayerReady(null, ready))
    }

    @Test fun quiescentStatusNeedsStrictHandoffEnvelopeAndNeverYieldsActiveBinding() {
        val status = LongArray(20).apply { this[0] = 1; this[1] = 7; this[19] = 99
            this[4] = 21; this[5] = 31; this[6] = 41; this[7] = 51 }
        assertFalse(AtmosTrackingContract.validStatus(status, 99))
        assertTrue(AtmosTrackingContract.validStatus(status, 99, wire()))
        assertNull(AtmosRootDecoderBinding.from(status))
        assertFalse(AtmosTrackingContract.validStatus(status, 99, wire(0)))
        assertFalse(AtmosTrackingContract.validStatus(status, 98, wire()))
        assertNull(AtmosDecoderHandoff.from(wire().copyOf(8)))
        assertNull(AtmosDecoderHandoff.from(wire().apply { this[3]++ }))
        assertNull(AtmosDecoderHandoff.from(wire().apply { this[5] = 0 }))
        assertNull(AtmosDecoderHandoff.from(wire(3).apply { this[4] = loss }))
    }

    @Test fun coherentSnapshotsSurviveQuiescentToNormalBoundaryWithoutMixedRecords() {
        val oldStatus = LongArray(20).apply { this[0] = 1; this[1] = 7; this[19] = 99 }
        val newStatus = LongArray(20).apply { this[0] = 1; this[1] = 2; this[19] = 99
            this[4] = 22; this[5] = 32; this[6] = 42; this[7] = 52 }
        val normal = LongArray(9).apply { this[0] = 1 }
        // Separate getters straddling completion would incorrectly reject this mixed view.
        assertNull(AtmosNativeState.from(oldStatus + normal, 99))
        val snapshots = listOf(oldStatus + wire(), newStatus + normal)
        val first = requireNotNull(AtmosNativeState.from(snapshots[0], 99))
        val second = requireNotNull(AtmosNativeState.from(snapshots[1], 99))
        assertEquals(1, first.transition.phase); assertNull(AtmosRootDecoderBinding.from(first.status))
        assertEquals(0, second.transition.phase); assertEquals(22L, AtmosRootDecoderBinding.from(second.status)!!.control)
    }

    @Test fun pausedFlushNeedsFreshEventAndResumeDoesNotReuseOldPlayerAuthority() {
        val paused = requireNotNull(AtmosDecoderHandoff.from(wire(5)))
        val later = loss + 60_000_000_000
        assertFalse(captured.accepts(marker(), paused, 100, later))
        assertTrue(captured.accepts(marker().apply { putLong("event_boottime_ns", later - 10_000_000) }, paused, 100, later))
        val resume = captured.copy(samePlayerResume = true)
        assertTrue(resume.newPlayerReady(player, old.copy(control = 22)))
        assertFalse(resume.newPlayerReady(player.copy(player = 18), old.copy(control = 22)))
        assertFalse(resume.newPlayerReady(player, old))
        assertTrue(captured.copy(pausedFlushResume = true).newPlayerReady(player.copy(player = 18), old.copy(control = 22)))
        assertNotNull(AtmosDecoderHandoff.from(wire(3).apply { this[4] = later + 5_000_000_000 }))
    }

    @Test fun zeroTargetPauseHistoryIsStructuralAndNeverAnActiveBinding() {
        val preparing = LongArray(20).apply { this[0] = 1; this[1] = 1; this[4] = 22; this[19] = 99 }
        assertEquals(AtmosRootDecoderBinding(22, 0, 0, 0), AtmosRootDecoderBinding.preparing(preparing))
        assertNull(AtmosRootDecoderBinding.from(preparing))
        assertNull(AtmosRootDecoderBinding.preparing(preparing.copyOf().apply { this[9] = 1 }))
        for (phase in listOf(1L, 2L, 3L, 5L, 6L, 7L)) {
            val zero = wire(phase).apply { this[5] = 22; this[6] = 0; this[7] = 0; this[8] = 0 }
            assertNotNull(AtmosDecoderHandoff.from(zero))
            assertNull(AtmosDecoderHandoff.from(zero.copyOf().apply { this[6] = 1 }))
        }
    }

    @Test fun sourceExpiryNeedsCompleteOldBindingAndTheOriginalCleanupWindow() {
        val status = LongArray(20).apply { this[0] = 1; this[1] = 7; this[4] = old.control; this[19] = 99 }
        for (phase in 8L..9L) {
            val recovery = wire(phase).apply { this[4] = 0 }
            val parsed = requireNotNull(AtmosDecoderHandoff.from(recovery))
            assertEquals(old, parsed.old)
            assertTrue(AtmosTrackingContract.validStatus(status, 99, recovery))
            assertNotNull(AtmosNativeState.from(status + recovery, 99))
            assertNull(AtmosRootDecoderBinding.from(status))
            assertFalse(captured.accepts(marker(), parsed, 100, loss + 1))
            for ((index, value) in listOf(2 to 0L, 3 to loss, 3 to loss + 500_000_001,
                4 to 1L, 5 to 0L, 6 to 0L, 7 to 0L, 8 to 0L)) {
                val invalid = recovery.copyOf().apply { this[index] = value }
                assertNull(AtmosDecoderHandoff.from(invalid))
                assertFalse(AtmosTrackingContract.validStatus(status, 99, invalid))
                assertNull(AtmosNativeState.from(status + invalid, 99))
            }
            assertFalse(AtmosTrackingContract.validStatus(status, 98, recovery))
        }
        assertNull(AtmosDecoderHandoff.from(wire(10)))
    }

    @Test fun sourceRecoveryOnlyRebindsTheSamePlayerAndDecoderWithANewerTag() {
        val recovery = captured.copy(samePlayerResume = true, sourceRecovery = true)
        val ready = old.copy(control = 22, revision = 52)
        assertTrue(recovery.newPlayerReady(player, ready))
        assertFalse(recovery.newPlayerReady(player.copy(player = 18), ready))
        assertFalse(recovery.newPlayerReady(player.copy(session = 20), ready))
        for (changed in listOf(old, ready.copy(instance = 32), ready.copy(epoch = 42),
            ready.copy(revision = old.revision), ready.copy(revision = old.revision - 1)))
            assertFalse(recovery.newPlayerReady(player, changed))
        assertFalse(captured.copy(sourceRecovery = true).newPlayerReady(player, ready))
    }
}
