/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class AtmosFreshSourceRecoveryTest {
    private val binding = AtmosRootDecoderBinding(21, 31, 41, 51)
    private val player = AtmosPlaybackWitness(10001, 123, 17, 19, 7, "02:11:22:33:44:55")
    private val captured = AtmosHandoffCapture(binding, player, "1".repeat(32))
    private val loss = 1_000_000_000L
    private val overall = loss + 600_000_000
    private fun transition(phase: Int = 9) = AtmosDecoderHandoff(phase, loss,
        loss + AtmosDecoderHandoff.MARKER_WINDOW_NS, 0, binding)

    @Test fun cleanupAndPreLossGuardCannotAuthorizeButFreshGuardIssuesOnlyOnce() {
        val recovery = AtmosFreshSourceRecovery()
        val calls = mutableListOf<AtmosRootDecoderBinding>()
        val authorize = { old: AtmosRootDecoderBinding -> calls += old; true }
        recovery.observe(transition(8), captured, player, loss, loss + 10, overall, {}, authorize)
        recovery.observe(transition(), captured, player, loss - 1, loss + 20, overall, {}, authorize)
        assertTrue(calls.isEmpty())
        val resume = recovery.observe(transition(), captured, player, loss + 1, loss + 30, overall, {}, authorize)
        recovery.observe(transition(), captured, player, loss + 40, loss + 50, overall, {}, authorize)
        assertEquals(listOf(binding), calls)
        assertTrue(resume.samePlayerResume); assertTrue(resume.sourceRecovery)
        assertEquals(captured.nonce, resume.nonce); assertEquals(player, resume.player)
    }

    @Test fun changedPlayerOrAnyOldDecoderWordCannotAuthorize() {
        var calls = 0
        val authorize = { _: AtmosRootDecoderBinding -> calls++; true }
        val wrongPlayers = listOf(null, player.copy(uid = 10002), player.copy(pid = 124),
            player.copy(player = 18), player.copy(session = 20), player.copy(routeId = 8),
            player.copy(address = "02:11:22:33:44:56"))
        for (observed in wrongPlayers) assertThrows(IllegalStateException::class.java) {
            AtmosFreshSourceRecovery().observe(transition(), captured, observed, loss, loss + 1, overall, {}, authorize)
        }
        val wrongBindings = listOf(binding.copy(control = 22), binding.copy(instance = 32),
            binding.copy(epoch = 42), binding.copy(revision = 52))
        for (old in wrongBindings) assertThrows(IllegalStateException::class.java) {
            AtmosFreshSourceRecovery().observe(transition(), captured.copy(binding = old), player,
                loss, loss + 1, overall, {}, authorize)
        }
        assertEquals(0, calls)
    }

    @Test fun deadlinesAndStaleOrFutureGuardsFailClosedIncludingAfterAuthorization() {
        val recovery = AtmosFreshSourceRecovery()
        var calls = 0
        val authorize = { _: AtmosRootDecoderBinding -> calls++; true }
        val window = transition()
        recovery.observe(window, captured, player, loss, loss + 1, overall, {}, authorize)
        assertThrows(IllegalStateException::class.java) {
            recovery.observe(window, captured, player, loss + 2, window.markerDeadlineNs, overall, {}, authorize)
        }
        for ((guard, now, deadline) in listOf(Triple(loss, loss - 1, overall),
            Triple(loss + 2, loss + 1, overall), Triple(1L, loss + 1, overall),
            Triple(loss, loss + 1, window.markerDeadlineNs - 1))) {
            assertThrows(IllegalStateException::class.java) {
                AtmosFreshSourceRecovery().observe(window, captured, player, guard, now, deadline, {}, authorize)
            }
        }
        assertEquals(1, calls)
    }

    @Test fun repeatedRecoveryCapturesCurrentBindingEvenIfTheGuardMissedNormalState() {
        val recovery = AtmosFreshSourceRecovery()
        val calls = mutableListOf<AtmosRootDecoderBinding>()
        val authorize = { old: AtmosRootDecoderBinding -> calls += old; true }
        val previous = recovery.observe(transition(), captured, player, loss, loss + 1, overall, {}, authorize)
        val nextBinding = binding.copy(control = 22, revision = 52)
        val next = captured.copy(binding = nextBinding, nonce = "2".repeat(32))
        val nextLoss = loss + 100_000_000
        val nextWindow = transition().copy(firstLossNs = nextLoss,
            markerDeadlineNs = nextLoss + AtmosDecoderHandoff.MARKER_WINDOW_NS, old = nextBinding)
        val resume = recovery.observe(nextWindow, next, player, nextLoss, nextLoss + 1, overall, {}, authorize)
        assertEquals(listOf(binding, nextBinding), calls)
        assertNotEquals(previous.binding, resume.binding)
        assertEquals(next.nonce, resume.nonce)
        assertTrue(resume.samePlayerResume); assertTrue(resume.sourceRecovery)
    }

    @Test fun rejectedPrivateAuthorizationIsNeverReportedAsSuccessful() {
        val error = assertThrows(IllegalStateException::class.java) {
            AtmosFreshSourceRecovery().observe(transition(), captured, player, loss, loss + 1, overall, {}) { false }
        }
        assertEquals("fresh_source_authorization_rejected", error.message)
    }

    @Test fun preparationSeesResumeProvenanceBeforeAuthorizationCanAdvanceNativeState() {
        var published: AtmosHandoffCapture? = null
        var nativePhase = 9
        var authorized = false
        AtmosFreshSourceRecovery().observe(transition(), captured, player, loss, loss + 1, overall,
            { published = it }) {
            nativePhase = 3
            val preparation = checkNotNull(published)
            assertTrue(preparation.sourceRecovery); assertTrue(preparation.samePlayerResume)
            assertEquals(captured.nonce, preparation.nonce)
            assertEquals(player.pid, preparation.player.pid); assertEquals(player.session, preparation.player.session)
            authorized = true
            true
        }
        assertEquals(3, nativePhase); assertTrue(authorized)
    }
}
