package me.kavishdevar.librepods.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MicrophonePlaybackRecoveryTest {
    @Test fun quietLiveStopJoinsVerifiedConnectedProfileWithoutCycling() = runBlocking {
        val calls = mutableListOf<String>()
        assertTrue(recoverMicrophonePlayback({ true }, { true },
            { calls += "connect"; true }, { calls += "recover"; true }))
        assertEquals(listOf("connect"), calls)
    }

    @Test fun recorderAndUncertainStopRetainNativeRecovery() = runBlocking {
        val calls = mutableListOf<String>()
        assertTrue(recoverMicrophonePlayback({ true }, { false },
            { calls += "connect"; true }, { calls += "recover"; true }))
        assertEquals(listOf("recover"), calls)
    }

    @Test fun failedConnectOnlyCheckFallsBackWithoutClaimingRecovery() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(recoverMicrophonePlayback({ true }, { true },
            { calls += "connect"; false }, { calls += "recover"; false }))
        assertEquals(listOf("connect", "recover"), calls)
    }

    @Test fun lateMicrophonePacketRevokesNoCycleAfterNativeCheck() = runBlocking {
        var quiet = true
        val calls = mutableListOf<String>()
        assertTrue(recoverMicrophonePlayback({ true }, { quiet },
            { calls += "connect"; quiet = false; true },
            { calls += "recover"; true }))
        assertEquals(listOf("connect", "recover"), calls)
    }

    @Test fun staleOwnerCannotStartAnyNativeOperation() = runBlocking {
        assertFalse(recoverMicrophonePlayback({ false }, { error("must not inspect") },
            { error("must not connect") }, { error("must not recover") }))
    }

    @Test fun ownerChangeDuringNativeCheckCannotCycleReplacementOrClaimSuccess() = runBlocking {
        for (connected in listOf(false, true)) {
            var current = true
            assertFalse(recoverMicrophonePlayback({ current }, { true },
                { current = false; connected }, { error("must not recover replacement") }))
        }
    }

    @Test fun cancellationDoesNotFallThroughToCycling() = runBlocking {
        val cancellation = CancellationException("owner stopped")
        try {
            recoverMicrophonePlayback({ true }, { true },
                { throw cancellation }, { error("must not recover on cancellation") })
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    @Test fun ownerChangeDuringConservativeRecoveryCannotClaimSuccess() = runBlocking {
        var current = true
        assertFalse(recoverMicrophonePlayback({ current }, { false },
            { error("must not connect") }, { current = false; true }))
    }
}
