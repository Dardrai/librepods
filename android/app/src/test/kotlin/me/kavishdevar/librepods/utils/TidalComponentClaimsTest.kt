/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class TidalComponentClaimsTest {
    private fun permit(nonce: Char) = TidalComponentClaimPermit(nonce.toString().repeat(32), 10, 1000, 1, 42, 1)
    private class Owner(val name: String)
    private class Fixture {
        val queue = ArrayDeque<() -> Unit>()
        val sent = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        var valid = true
        var sending: (() -> Unit)? = null
        val claims = TidalComponentClaims<Owner>(post = { _, action -> queue.add(action); true },
            valid = { _, _ -> valid }, send = { owner, nonce -> sent.add("${owner.name}:$nonce"); sending?.invoke() },
            report = {}, accepted = { _, grant -> accepted.add(grant.nonce) })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    @Test fun freshNonceCanArriveAfterCodecConfigureWithoutReconfiguringAudio() {
        val f = Fixture(); val owner = Owner("new")
        f.claims.owner(owner); f.drain(); assertTrue(f.sent.isEmpty())
        f.claims.permit(permit('a')); f.drain()
        assertEquals(listOf("new:${permit('a').nonce}"), f.sent)
        assertEquals(permit('a'), f.claims.granted(owner))
    }

    @Test fun revocationAndFreshClaimAreOrderedOnSamePlayerQueue() {
        val f = Fixture(); val owner = Owner("codec")
        f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
        f.claims.owner(null); f.claims.owner(owner); f.claims.permit(permit('b')); f.drain()
        assertEquals(listOf("codec:${permit('a').nonce}", "codec:${TidalComponentClaimPermit.CLEAR}", "codec:${permit('b').nonce}"), f.sent)
    }

    @Test fun queuedOldCodecAndRetiredNonceCannotClaimReplacement() {
        val f = Fixture(); val old = Owner("old"); val next = Owner("next")
        f.claims.permit(permit('a')); f.claims.owner(old); f.claims.owner(next); f.drain()
        assertEquals(listOf("next:${permit('a').nonce}"), f.sent)
        f.claims.owner(null); f.claims.owner(Owner("third")); f.drain()
        assertEquals(2, f.sent.size); assertTrue(f.sent.last().endsWith(TidalComponentClaimPermit.CLEAR))
        f.claims.permit(null); f.claims.permit(permit('a')); f.drain()
        assertEquals(2, f.sent.size)
    }

    @Test fun replacementCodecOnRetainedFloatTrackReceivesOnlyItsFreshClaim() {
        val f = Fixture(); val old = Owner("old codec"); val replacement = Owner("new codec")
        f.claims.owner(old); f.claims.permit(permit('a')); f.drain()
        val retained = TidalBufferProbePolicy.Format("c2.pixel.eac3.float.decoder", "audio/raw",
            4, 2, 48000, 0, true, true, false)
        assertTrue(TidalBufferProbePolicy.supportedComponent(37, "2.219.0", 10023, retained))
        val selected = f.claims.selectOwner(replacement) { before, after -> before === after }
        f.claims.permit(permit('b')); f.drain()
        assertSame(replacement, selected)
        assertNull(f.claims.granted(old)); assertEquals(permit('b'), f.claims.granted(replacement))
        assertEquals(listOf("old codec:${permit('a').nonce}", "old codec:${TidalComponentClaimPermit.CLEAR}",
            "new codec:${permit('b').nonce}"), f.sent)
    }

    @Test fun retirementDuringSendCannotPublishAcceptedWitnessOrAffectLaterSession() {
        val f = Fixture(); val owner = Owner("codec")
        f.sending = { f.sending = null; f.claims.end() }
        f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
        assertNull(f.claims.granted(owner)); assertTrue(f.accepted.isEmpty())
        assertEquals(2, f.sent.size)
        f.claims.owner(Owner("new")); f.claims.permit(permit('b')); f.drain()
        assertEquals(2, f.sent.size)
    }

    @Test fun ownershipValidationFailureNeverCallsCodec() {
        val f = Fixture(); f.valid = false
        f.claims.owner(Owner("codec")); f.claims.permit(permit('a')); f.drain()
        assertTrue(f.sent.isEmpty()); assertTrue(f.accepted.isEmpty())
    }

    @Test fun claimBudgetIsFiniteAndEndedSessionDoesNotResurrect() {
        val f = Fixture(); val owner = Owner("codec")
        f.claims.owner(owner)
        repeat(65) { index ->
            f.claims.permit(permit('a').copy(nonce = (index + 1).toString(16).padStart(32, '0')))
            f.drain()
        }
        assertEquals(64, f.accepted.size)
        assertNull(f.claims.granted(owner))
        f.claims.permit(permit('b')); f.drain()
        assertEquals(64, f.accepted.size)
    }

    @Test fun freshRootGrantCanRearmTheAlreadyPlayingOwnerAfterRetirement() {
        val f = Fixture(); val owner = Owner("existing")
        f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
        f.claims.end(); f.drain()
        f.claims.renew(owner)
        f.claims.permit(permit('b')); f.drain()
        assertEquals(listOf("existing:${permit('a').nonce}", "existing:${TidalComponentClaimPermit.CLEAR}",
            "existing:${permit('b').nonce}"), f.sent)
        assertEquals(permit('b'), f.claims.granted(owner))
    }

    @Test fun renewalFencesQueuedOldNonceAndStartsAnotherBoundedBudget() {
        val f = Fixture(); val owner = Owner("same")
        f.claims.owner(owner); f.claims.permit(permit('a'))
        f.claims.renew(owner); f.claims.permit(permit('b')); f.drain()
        assertEquals(listOf("same:${permit('b').nonce}"), f.sent)
        f.claims.end(); f.claims.renew(owner)
        repeat(65) { index ->
            f.claims.permit(permit('c').copy(nonce = (index + 100).toString(16).padStart(32, '0')))
            f.drain()
        }
        assertEquals(65, f.accepted.size)
        assertNull(f.claims.granted(owner))
    }

    @Test fun newBufferWaitsForItsClaimWithoutRetiringThePlayingCodec() {
        val f = Fixture(); val owner = Owner("playing")
        val old = permit('a')
        f.claims.owner(owner); f.claims.permit(old); f.drain()
        val buffer = TidalBufferProbePolicy.Permit(100, 1000, 1, 7, "02:00:00:00:00:01")
        val fresh = permit('b').copy(issuedMs = 110, bufferIssuedMs = buffer.issuedMs)
        f.claims.renew(owner)
        // A listener observes the new root buffer before its matching claim commit.
        f.claims.permit(tidalComponentClaimForWindow(old, buffer, 200, 1, 42)); f.drain()
        assertNull(f.claims.granted(owner))
        assertEquals(listOf("playing:${old.nonce}", "playing:${TidalComponentClaimPermit.CLEAR}"), f.sent)
        f.claims.permit(tidalComponentClaimForWindow(fresh, buffer, 200, 1, 42)); f.drain()
        assertEquals(fresh, f.claims.granted(owner))
        assertEquals("playing:${fresh.nonce}", f.sent.last())
    }

    @Test fun queuedOldWindowClaimCannotReachTheCodecAfterRenewal() {
        val f = Fixture(); val owner = Owner("same")
        val old = permit('a')
        f.claims.owner(owner); f.claims.permit(old)
        val buffer = TidalBufferProbePolicy.Permit(100, 1000, 1, 7, "02:00:00:00:00:01")
        val fresh = permit('b').copy(issuedMs = 110, bufferIssuedMs = 100)
        f.claims.renew(owner)
        f.claims.permit(tidalComponentClaimForWindow(old, buffer, 200, 1, 42))
        f.claims.permit(tidalComponentClaimForWindow(fresh, buffer, 200, 1, 42)); f.drain()
        assertEquals(listOf("same:${fresh.nonce}"), f.sent)
    }

    @Test fun matchingWindowStillRejectsExpiredForeignUidBootOrMalformedClaims() {
        val buffer = TidalBufferProbePolicy.Permit(100, 1000, 1, 7, "02:00:00:00:00:01")
        val fresh = permit('b').copy(issuedMs = 110, bufferIssuedMs = 100)
        for (invalid in listOf(fresh.copy(deadlineMs = 200), fresh.copy(targetUid = 99),
                fresh.copy(boot = 2), fresh.copy(nonce = TidalComponentClaimPermit.CLEAR),
                fresh.copy(issuedMs = 99), fresh.copy(resumeFromNonce = permit('a').nonce))) {
            assertThrows(IllegalStateException::class.java) {
                tidalComponentClaimForWindow(invalid, buffer, 200, 1, 42)
            }
        }
        assertNull(tidalComponentClaimForWindow(null, buffer, 200, 1, 42))
        assertEquals(fresh, tidalComponentClaimForWindow(fresh, buffer, 200, 1, 42))
    }

    @Test fun repeatedSameConcreteOwnerKeepsGrantAndScopedResumeWithoutReclaimingOldNonce() {
        val f = Fixture(); val original = Owner("same concrete identity")
        f.claims.owner(original); f.claims.permit(permit('a')); f.drain()
        val selected = f.claims.selectOwner(Owner(original.name)) { before, after -> before.name == after.name }
        assertSame(original, selected); assertEquals(permit('a'), f.claims.granted(selected))
        f.drain(); assertEquals(1, f.sent.size)
        f.claims.permit(permit('b')); f.drain()
        assertEquals(permit('b'), f.claims.granted(original))
        assertEquals(3, f.sent.size)
        val different = f.claims.selectOwner(Owner("different")) { before, after -> before.name == after.name }
        assertNotSame(original, different); f.drain()
        assertNull(f.claims.granted(different)); assertEquals(4, f.sent.size)
    }

    @Test fun verifiedSamePlayerResumeRetagsTheExistingOwnerWithoutATeardownMarker() {
        val owner = Any()
        val queue = ArrayDeque<() -> Unit>()
        val sent = mutableListOf<String>()
        var last: TidalComponentClaimPermit? = null
        val claims = TidalComponentClaims<Any>(post = { _, action -> queue.add(action); true },
            valid = { captured, grant -> captured === owner && (grant.resumeFromNonce == null ||
                grant.matchesResume(last, 123, 456, captured === owner)) },
            send = { _, nonce -> sent += nonce }, report = {}, accepted = { _, grant -> last = grant })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        val first = permit('a')
        claims.owner(owner); claims.permit(first); drain()
        val resumed = permit('b').copy(resumeFromNonce = first.nonce, resumePid = 123, resumeSession = 456)
        claims.permit(resumed); drain()
        assertEquals(resumed, claims.granted(owner))
        assertEquals(listOf(first.nonce, TidalComponentClaimPermit.CLEAR, resumed.nonce), sent)
        claims.permit(permit('c').copy(resumeFromNonce = first.nonce, resumePid = 123, resumeSession = 456)); drain()
        assertNull(claims.granted(owner))
        assertEquals(TidalComponentClaimPermit.CLEAR, sent.last())
        assertEquals(4, sent.size)
    }

    @Test fun seekFlushRestoresSameCodecForFreshClaimWithoutReusingTheOldNonce() {
        val f = Fixture(); val owner = Owner("same codec")
        val installed = java.util.concurrent.atomic.AtomicReference<Owner?>(owner)
        f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
        assertTrue(installed.compareAndSet(owner, null)); f.claims.owner(null)
        assertTrue(f.claims.restoreOwner(owner) { installed.compareAndSet(null, owner) })
        f.drain()
        assertNull(f.claims.granted(owner))
        assertEquals(listOf("same codec:${permit('a').nonce}", "same codec:${TidalComponentClaimPermit.CLEAR}"), f.sent)
        f.claims.permit(permit('b')); f.drain()
        assertSame(owner, installed.get()); assertEquals(permit('b'), f.claims.granted(owner))
        assertEquals("same codec:${permit('b').nonce}", f.sent.last())
        assertEquals(3, f.sent.size)
    }

    @Test fun freshClaimCanArriveWhileSeekHasNoOwnerAndIsAppliedOnlyAfterRestoration() {
        val f = Fixture(); val owner = Owner("same codec")
        f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
        f.claims.owner(null); f.claims.permit(permit('b')); f.drain()
        assertEquals(2, f.sent.size)
        assertTrue(f.claims.restoreOwner(owner) { true }); f.drain()
        assertEquals(permit('b'), f.claims.granted(owner)); assertEquals(3, f.sent.size)
    }

    @Test fun seekRestorationCannotReplaceAnotherOwnerOrReviveInvalidOrRetiredCodec() {
        for (retired in listOf(false, true)) {
            val f = Fixture(); val owner = Owner("old")
            f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
            f.claims.owner(null)
            if (retired) f.claims.end()
            assertFalse(f.claims.restoreOwner(owner) { false })
            if (retired) assertFalse(f.claims.restoreOwner(owner) { fail("retired must not inspect or reinstall owner"); true })
            f.drain(); assertEquals(2, f.sent.size)
        }
        val f = Fixture(); val old = Owner("old"); val next = Owner("replacement")
        f.claims.owner(old); f.claims.permit(permit('a')); f.drain()
        f.claims.owner(null); f.claims.owner(next)
        assertFalse(f.claims.restoreOwner(old) { fail("replacement owner must be preserved"); true })
        f.claims.permit(permit('b')); f.drain()
        assertNull(f.claims.granted(old)); assertEquals(permit('b'), f.claims.granted(next))
    }

    @Test fun claimWaitsForItsValidatedTrackAndRetryCannotRepeatAnAcceptedNonce() {
        val f = Fixture(); val owner = Owner("codec")
        f.valid = false
        f.claims.owner(owner); f.claims.permit(permit('a')); f.drain()
        assertTrue(f.sent.isEmpty()); assertNull(f.claims.granted(owner))
        f.valid = true
        f.claims.retryOwner(owner); f.drain()
        assertEquals(listOf("codec:${permit('a').nonce}"), f.sent)
        f.claims.retryOwner(owner); f.claims.retryOwner(owner); f.drain()
        assertEquals(1, f.sent.size); assertEquals(permit('a'), f.claims.granted(owner))
    }

    @Test fun lateSongConfigurationCannotSpendFreshNonceOnTheFlushedCodec() {
        val f = Fixture(); val old = Owner("old codec"); val next = Owner("song codec")
        f.claims.owner(old); f.claims.permit(permit('a')); f.drain()
        f.claims.owner(null); assertTrue(f.claims.restoreOwner(old) { true })
        f.valid = false // AudioTrack was removed by flush; configuration has not settled.
        f.claims.permit(permit('b')); f.drain()
        assertEquals(listOf("old codec:${permit('a').nonce}", "old codec:${TidalComponentClaimPermit.CLEAR}"), f.sent)
        f.claims.owner(next); f.drain()
        assertNull(f.claims.granted(next))
        f.valid = true // Successful initializeAudioTrack validates the new concrete owner.
        f.claims.retryOwner(old); f.claims.retryOwner(next); f.drain()
        assertEquals("song codec:${permit('b').nonce}", f.sent.last())
        assertEquals(3, f.sent.size); assertEquals(permit('b'), f.claims.granted(next))
        assertNull(f.claims.granted(old))
    }

    @Test fun retryDoesNotReplaceCurrentOwnerOrReviveAnInvalidRetiredAttempt() {
        val f = Fixture(); val old = Owner("old"); val next = Owner("next")
        f.valid = false
        f.claims.owner(old); f.claims.permit(permit('a')); f.drain()
        f.claims.owner(next); f.drain()
        val queued = f.queue.size
        f.claims.retryOwner(old); assertEquals(queued, f.queue.size)
        f.claims.retryOwner(next); f.drain(); assertTrue(f.sent.isEmpty())
        f.claims.end(); f.valid = true
        f.claims.retryOwner(next); f.drain()
        assertTrue(f.sent.isEmpty()); assertNull(f.claims.granted(next))
    }
}
