package me.kavishdevar.librepods.shizuku

import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothHeadset
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackReleaseTest {
    @Test fun aStateReadCrossingTheDeadlineCannotIssueALateDisconnect() {
        var elapsed = 0L
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({ elapsed = 100; BluetoothProfile.STATE_CONNECTED }) {
            disconnects++; true
        }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { true }, { elapsed }))
        assertEquals(0, disconnects)
    }

    @Test fun aBlockingPolicyProofMustStillFinishBeforeTheDeadline() {
        var elapsed = 0L
        assertFalse(NativePlaybackRelease.beforeDeadline(100, { elapsed }) { elapsed = 100; true })
        var checks = 0
        var disconnects = 0
        elapsed = 0
        val profile = NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { disconnects++; true }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100,
            { if (++checks == 2) elapsed = 100; true }, { elapsed }))
        assertEquals(0, disconnects)
    }

    @Test fun aLateDisconnectedReplyCannotProduceAReleaseReceipt() {
        var elapsed = 0L
        var reads = 0
        val profile = NativePlaybackRelease.Profile({
            if (++reads == 2) elapsed = 100
            BluetoothProfile.STATE_DISCONNECTED
        }) { fail("Already disconnected"); false }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { true }, { elapsed }))
    }

    @Test fun aDisconnectFinishingAfterDeadlineCannotMutateTheNextProfile() {
        var elapsed = 0L
        var nextWrites = 0
        val first = NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { elapsed = 100; true }
        val second = NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { nextWrites++; true }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(first, second), 100, { true }, { elapsed }))
        assertEquals(0, nextWrites)
    }

    @Test fun unavailableRawServiceCannotTurnThePublicDisconnectedFallbackIntoAReceipt() {
        var reads = 0
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({
            NativePlaybackRelease.verifiedProfileState({ false }) { reads++; BluetoothProfile.STATE_DISCONNECTED }
        }) { disconnects++; true }
        assertThrows(IllegalStateException::class.java) {
            NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { true }, { 0 })
        }
        assertEquals(0, reads); assertEquals(0, disconnects)
    }

    @Test fun aProxyReplacementDuringTheRawReplyRevokesDisconnectedEvidence() {
        var sameProxy = true
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({
            NativePlaybackRelease.verifiedProfileState({ sameProxy }) {
                sameProxy = false
                BluetoothProfile.STATE_DISCONNECTED
            }
        }) { disconnects++; true }
        assertThrows(IllegalStateException::class.java) {
            NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { true }, { 0 })
        }
        assertEquals(0, disconnects)
    }

    @Test fun unknownRawStatesNeverTriggerAProfileDisconnect() {
        for (state in listOf(-1, 4, 100)) {
            var disconnects = 0
            val profile = NativePlaybackRelease.Profile({ NativePlaybackRelease.verifiedProfileState({ true }) { state } }) {
                disconnects++; true
            }
            assertThrows(IllegalStateException::class.java) {
                NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { true }, { 0 })
            }
            assertEquals(0, disconnects)
        }
    }

    @Test fun anAcknowledgedRawDisconnectThatStaysConnectedCannotProduceAReceipt() {
        var elapsed = 0L
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({
            NativePlaybackRelease.verifiedProfileState({ true }) { BluetoothProfile.STATE_CONNECTED }
        }) { disconnects++; true }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 120, { true }, { elapsed }) { elapsed += 40 })
        assertEquals(1, disconnects); assertEquals(120L, elapsed)
    }

    @Test fun theOwnerIsRecheckedAfterTheLastDisconnectedReply() {
        var current = true
        var reads = 0
        val profile = NativePlaybackRelease.Profile({
            if (++reads == 2) current = false
            BluetoothProfile.STATE_DISCONNECTED
        }) { fail("Already disconnected"); false }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { current }, { 0 }))
    }

    @Test fun releaseWaitsForBothProfilesAndNeverReconnects() {
        var elapsed = 0L
        var media = BluetoothProfile.STATE_CONNECTED
        var headset = BluetoothProfile.STATE_CONNECTED
        var disconnects = 0
        val profiles = listOf(
            NativePlaybackRelease.Profile({ media }) { disconnects++; media = BluetoothProfile.STATE_DISCONNECTING; true },
            NativePlaybackRelease.Profile({ headset }) { disconnects++; headset = BluetoothProfile.STATE_DISCONNECTING; true },
        )
        assertTrue(NativePlaybackRelease.releaseProfiles(profiles, 1_000, { true }, { elapsed }) {
            elapsed += 40
            media = BluetoothProfile.STATE_DISCONNECTED
            if (elapsed >= 120) headset = BluetoothProfile.STATE_DISCONNECTED
        })
        assertEquals(2, disconnects)
        assertEquals(120L, elapsed)
    }

    @Test fun ownerCancellationPreventsTheNextProfileMutation() {
        var current = true
        var headsetDisconnects = 0
        val profiles = listOf(
            NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { current = false; true },
            NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { headsetDisconnects++; true },
        )
        assertFalse(NativePlaybackRelease.releaseProfiles(profiles, 100, { current }, { 0 }))
        assertEquals(0, headsetDisconnects)
    }

    @Test fun aStalledHeadsetCannotProduceAReleasedReceipt() {
        var elapsed = 0L
        val profiles = listOf(
            NativePlaybackRelease.Profile({ BluetoothProfile.STATE_DISCONNECTED }) { fail("Already released"); false },
            NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { true },
        )
        assertFalse(NativePlaybackRelease.releaseProfiles(profiles, 120, { true }, { elapsed }) { elapsed += 40 })
        assertEquals(120L, elapsed)
    }

    @Test fun failedProfileQueryPropagatesInsteadOfClaimingRelease() {
        val profile = NativePlaybackRelease.Profile({ error("Profile unavailable") }) { false }
        assertThrows(IllegalStateException::class.java) {
            NativePlaybackRelease.releaseProfiles(listOf(profile), 100, { true }, { 0 })
        }
    }

    @Test fun activeConnectingAndUnknownScoNeverDisconnectProfiles() {
        for (state in listOf(BluetoothHeadset.STATE_AUDIO_CONNECTED, BluetoothHeadset.STATE_AUDIO_CONNECTING, -1, 0, 99)) {
            var disconnects = 0
            val profile = NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { disconnects++; true }
            assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100,
                { NativePlaybackRelease.scoAudioIdle({ true }) { state } }, { 0 }))
            assertEquals(0, disconnects)
        }
    }

    @Test fun unavailableServiceAndFailedScoQueryNeverDisconnectProfiles() {
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { disconnects++; true }
        for (ready in listOf<() -> Boolean>(
            { NativePlaybackRelease.scoAudioIdle({ false }) { error("Unavailable service must not be queried") } },
            { NativePlaybackRelease.scoAudioIdle({ true }) { error("Query failed") } },
            { NativePlaybackRelease.scoAudioIdle({ error("Binder failed") }) { BluetoothHeadset.STATE_AUDIO_DISCONNECTED } },
        )) {
            assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100, ready, { 0 }))
            assertEquals(0, disconnects)
        }
    }

    @Test fun serviceLossAfterAnIdleScoReplyNeverDisconnectsProfiles() {
        var checks = 0
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({ BluetoothProfile.STATE_CONNECTED }) { disconnects++; true }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100,
            { NativePlaybackRelease.scoAudioIdle({ ++checks == 1 }) { BluetoothHeadset.STATE_AUDIO_DISCONNECTED } }, { 0 }))
        assertEquals(2, checks)
        assertEquals(0, disconnects)
    }

    @Test fun scoStartingDuringProfileQueryPreventsTheMutation() {
        var audio = BluetoothHeadset.STATE_AUDIO_DISCONNECTED
        var disconnects = 0
        val profile = NativePlaybackRelease.Profile({ audio = BluetoothHeadset.STATE_AUDIO_CONNECTING; BluetoothProfile.STATE_CONNECTED }) {
            disconnects++; true
        }
        assertFalse(NativePlaybackRelease.releaseProfiles(listOf(profile), 100,
            { NativePlaybackRelease.scoAudioIdle({ true }) { audio } }, { 0 }))
        assertEquals(0, disconnects)
    }

    @Test fun explicitlyIdleScoAllowsBothSelectedProfilesToRelease() {
        var disconnects = 0
        fun profile(): NativePlaybackRelease.Profile {
            var state = BluetoothProfile.STATE_CONNECTED
            return NativePlaybackRelease.Profile({ state }) { disconnects++; state = BluetoothProfile.STATE_DISCONNECTED; true }
        }
        assertTrue(NativePlaybackRelease.releaseProfiles(listOf(profile(), profile()), 100,
            { NativePlaybackRelease.scoAudioIdle({ true }) { BluetoothHeadset.STATE_AUDIO_DISCONNECTED } }, { 0 }))
        assertEquals(2, disconnects)
    }
}
