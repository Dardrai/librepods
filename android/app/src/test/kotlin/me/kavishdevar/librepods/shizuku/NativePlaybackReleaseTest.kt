package me.kavishdevar.librepods.shizuku

import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothHeadset
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackReleaseTest {
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
