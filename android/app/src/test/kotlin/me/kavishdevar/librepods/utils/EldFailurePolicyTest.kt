/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils
import org.junit.Assert.*
import org.junit.Test

class EldFailurePolicyTest {
    private val peer = listOf(1, 2, 3, 4, 5, 6)
    private var health: EldEncoderHealth? = null
    private val policy = EldFailurePolicy { health }
    private fun select() {
        policy.connected(peer); policy.selected(peer, "target"); policy.codec(peer, EldCodecIdentity.MODULE_CODEC_ID)
    }

    @Test fun halFailureBeforeAudioStartedCanBeDiscoveredFromTheOwnedPendingCandidate() {
        select()
        assertNotNull(policy.candidate()); assertNull(policy.owner)
        health = EldEncoderHealth(peer, 1, true); policy.changed()
        val owner = checkNotNull(policy.owner)
        val result = policy.tick(owner)
        assertEquals(owner, result.fallback); assertTrue(policy.current(owner))
        policy.changed()
        assertNull(policy.owner); assertNull(policy.candidate())
    }
    @Test fun unknownPeerAndZeroGenerationCannotProduceAFallback() {
        health = EldEncoderHealth(List(6) { 0 }, 0, true); select()
        assertNull(policy.owner)
        health = EldEncoderHealth(peer.reversed(), 2, true); policy.changed()
        assertNull(policy.owner)
        policy.retire(checkNotNull(policy.candidate()))
        assertNull(policy.candidate())
    }
    @Test fun staleOwnerCannotAffectAReplacementPeerOrStockCodec() {
        health = EldEncoderHealth(peer, 1, true); select()
        val old = checkNotNull(policy.owner)
        policy.selected(peer.reversed(), "new target")
        assertNull(policy.tick(old).fallback); assertFalse(policy.current(old))
        policy.selected(peer, "target"); policy.codec(peer, 1)
        assertNull(policy.candidate()); assertNull(policy.owner)
    }
    @Test fun healthyPauseHasNoFallbackAndEachFailedGenerationHasAtMostOneAttempt() {
        health = EldEncoderHealth(peer, 1, false); select()
        assertTrue(policy.tick(checkNotNull(policy.owner)).keepPolling)
        health = health!!.copy(failed = true)
        assertNotNull(policy.tick(checkNotNull(policy.owner)).fallback)
        policy.changed(); assertNull(policy.owner)
        health = EldEncoderHealth(peer, 2, true); policy.changed()
        assertNotNull(policy.tick(checkNotNull(policy.owner)).fallback)
    }
    @Test fun disconnectAndCleanupRetireBothPendingAndVerifiedTargets() {
        health = EldEncoderHealth(peer, 1, true); select()
        val owner = checkNotNull(policy.owner)
        policy.disconnected(peer)
        assertNull(policy.candidate()); assertFalse(policy.current(owner)); assertNull(policy.tick(owner).fallback)
        select(); policy.clear(); assertNull(policy.candidate()); assertNull(policy.owner)
    }
}
