/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TidalComponentTransitionTest {
    @Test fun markerPreservesOriginalEventAndOldClaimIdentityWithoutGrantingANewPermit() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("transition", Context.MODE_PRIVATE)
        val event = TidalComponentTransition("a".repeat(32), 1000, 123, 456, 7, 8_000_000_000)
        assertTrue(event.write(prefs)); assertEquals(event, TidalComponentTransition.read(prefs))
        val sent = event.bundle()
        assertEquals(event.nonce, sent.getString("nonce")); assertEquals(123, sent.getInt("process_pid"))
        assertEquals(456, sent.getInt("audio_session_id")); assertEquals(event.atNs, sent.getLong("event_boottime_ns"))
        assertNull(TidalBufferProbeHooks.claimPermit(prefs))
        prefs.edit().putLong(TidalComponentTransition.PREFIX + "event_boottime_ns", 0).commit()
        assertNull(TidalComponentTransition.read(prefs))
    }

    @Test fun ordinaryFreshClaimRemovesAnyOlderResumeProvenanceAtomically() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("claims", Context.MODE_PRIVATE)
        val resumed = TidalComponentClaimPermit("b".repeat(32), 1100, 61000, 9, 42, 1000,
            "a".repeat(32), 123, 456)
        assertTrue(resumed.write(prefs.edit()).commit())
        assertEquals(resumed, TidalBufferProbeHooks.claimPermit(prefs))
        val ordinary = resumed.copy(nonce = "c".repeat(32), resumeFromNonce = null, resumePid = -1, resumeSession = -1)
        assertTrue(ordinary.write(prefs.edit()).commit())
        assertEquals(ordinary, TidalBufferProbeHooks.claimPermit(prefs))
        assertFalse(prefs.contains(TidalComponentClaimPermit.PREFIX + "resume_from_nonce"))
        assertFalse(prefs.contains(TidalComponentClaimPermit.PREFIX + "resume_process_pid"))
        assertFalse(prefs.contains(TidalComponentClaimPermit.PREFIX + "resume_audio_session_id"))
    }

    @Test fun seekResetBeforeFlushAndStreamChangeKeepClaimsAliveForFreshRootHandoff() {
        for (boundary in listOf("seek", "stream_change")) {
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(boundary, Context.MODE_PRIVATE)
            val queue = ArrayDeque<() -> Unit>()
            val sent = mutableListOf<String>()
            var retired = false
            var timing = true
            val claims = TidalComponentClaims<Any>(post = { _, action -> queue.add(action); true },
                valid = { _, _ -> !retired }, send = { _, nonce -> sent += nonce }, report = {})
            fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
            fun disarm(reason: String) {
                if (TidalBufferProbePolicy.retireComponentOnDisarm(reason)) { retired = true; claims.end() }
                timing = false
            }
            val first = TidalComponentClaimPermit("a".repeat(32), 1100, 61000, 9, 42, 1000)
            val oldOwner = Any()
            claims.owner(oldOwner); claims.permit(first); drain()
            val gate = TidalDecoderInputLeadGate()
            val track = Any()
            gate.begin(oldOwner, track, first, 0, 0, 0, 1000, true) { 0 }
            val oldWindow = gate.begin(oldOwner, track, first, 0, 0, 100_000, 1100, true) { 4800 }
            assertFalse(gate.feed(oldWindow, 600_000, 1100, true, false) { true })

            if (boundary == "seek") gate.reset("position_reset")
            // MediaCodecAudioRenderer calls the superclass codec reset before its sink flush on seek.
            gate.reset("decoder_or_stream_changed")
            disarm("decoder_or_stream_changed")
            assertFalse(timing); assertFalse(retired)
            assertTrue(gate.feed(oldWindow, 600_000, 1100, true, false) { true })

            gate.reset("flush")
            val grant = checkNotNull(claims.granted(oldOwner))
            val marker = TidalComponentTransition(grant.nonce, grant.bufferIssuedMs, 123, 456, 7, 8_000_000_000)
            claims.owner(null)
            disarm("track_teardown")
            assertTrue(marker.write(prefs)); assertEquals(marker, TidalComponentTransition.read(prefs))
            assertFalse(retired)

            val fresh = first.copy(nonce = "b".repeat(32), issuedMs = 1200)
            val nextOwner = Any()
            claims.permit(fresh); claims.owner(nextOwner); drain()
            assertNull(claims.granted(oldOwner)); assertEquals(fresh, claims.granted(nextOwner))
            assertEquals(listOf(first.nonce, TidalComponentClaimPermit.CLEAR, fresh.nonce), sent)
            assertFalse(timing) // A handoff does not silently rearm the first-track timing diagnostic.

            disarm("preferences_changed"); drain()
            assertTrue(retired); assertNull(claims.granted(nextOwner))
            assertEquals(TidalComponentClaimPermit.CLEAR, sent.last())
            claims.permit(first.copy(nonce = "c".repeat(32))); claims.owner(Any()); drain()
            assertEquals(4, sent.size)
        }
    }
}
