package me.kavishdevar.librepods.utils

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.IAudioBridge
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class MicrophoneRouteProbePreflightTest {
    private class Fixture(connected: Boolean = false, privileged: Boolean = false) {
        val bridge = mock(ShizukuAudioClient::class.java)
        val state = MutableStateFlow(BridgeState(connected = connected, backend = "shizuku"))
        var root = privileged
        init {
            `when`(bridge.state).thenReturn(state)
            `when`(bridge.hasRootPrivileges).thenAnswer { root }
        }
    }

    @Test fun acceptsConnectedRootUidThroughEitherTransportWithoutChangingRootBackendSemantics() {
        val bridge = mock(ShizukuAudioClient::class.java, CALLS_REAL_METHODS)
        ReflectionHelpers.setField(bridge, "bridge", mock(IAudioBridge::class.java))
        ReflectionHelpers.setField(bridge, "backendUid", 0)
        ReflectionHelpers.setField(bridge, "rootBackend", false)
        assertTrue(bridge.hasRootPrivileges)
        assertFalse(bridge.isRoot)
        ReflectionHelpers.setField(bridge, "rootBackend", true)
        assertTrue(bridge.hasRootPrivileges)
        assertTrue(bridge.isRoot)
        ReflectionHelpers.setField(bridge, "backendUid", 2000)
        assertFalse(bridge.hasRootPrivileges)
        ReflectionHelpers.setField(bridge, "backendUid", 0)
        ReflectionHelpers.setField(bridge, "closed", true)
        assertFalse(bridge.hasRootPrivileges)
    }

    @Test fun readyRootModeShizukuNeedsNeitherReconnectNorPermissionPrompt() = runTest {
        val f = Fixture(connected = true, privileged = true)
        awaitMicrophoneProbeRoot(f.bridge) { true }
        verify(f.bridge, never()).connect()
        verify(f.bridge, never()).requestPermission()
    }

    @Test fun ordinaryConnectWaitsForAuthorizedRootWithoutRequestingPermission() = runTest {
        val f = Fixture()
        val result = async { awaitMicrophoneProbeRoot(f.bridge) { true } }
        runCurrent()
        verify(f.bridge).connect()
        assertFalse(result.isCompleted)
        advanceTimeBy(300)
        f.root = true
        f.state.value = f.state.value.copy(connected = true, backend = "root")
        result.await()
        verify(f.bridge, never()).requestPermission()
    }

    @Test fun shellBackendFailsWithoutMigratingOrPrompting() = runTest {
        val f = Fixture(connected = true)
        val error = runCatching { awaitMicrophoneProbeRoot(f.bridge) { true } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("UID-0"))
        verify(f.bridge, never()).connect()
        verify(f.bridge, never()).requestPermission()
    }

    @Test fun connectionWaitIsBoundedAndAChangedPeerCannotStartAfterIt() = runTest {
        val absent = Fixture()
        val timedOut = runCatching { awaitMicrophoneProbeRoot(absent.bridge) { true } }.exceptionOrNull()
        assertTrue(timedOut?.message.orEmpty().contains("five seconds"))
        assertEquals(5000, testScheduler.currentTime)
        verify(absent.bridge, never()).requestPermission()

        val f = Fixture()
        var current = true
        val result = async { runCatching { awaitMicrophoneProbeRoot(f.bridge) { current } } }
        runCurrent()
        current = false
        f.root = true
        f.state.value = f.state.value.copy(connected = true)
        assertTrue(result.await().exceptionOrNull()?.message.orEmpty().contains("connection changed"))
        verify(f.bridge, never()).requestPermission()
    }

    @Test fun failedAdmissionIdentifiesGameMicrophoneAndConnectionSeparately() {
        requireMicrophoneProbeAdmission(true, true, MicrophonePhase.IDLE)
        val peer = assertThrows(IllegalStateException::class.java) {
            requireMicrophoneProbeAdmission(false, true, MicrophonePhase.IDLE)
        }
        val game = assertThrows(IllegalStateException::class.java) {
            requireMicrophoneProbeAdmission(true, false, MicrophonePhase.IDLE)
        }
        val microphone = assertThrows(IllegalStateException::class.java) {
            requireMicrophoneProbeAdmission(true, true, MicrophonePhase.CAPTURING)
        }
        assertTrue(peer.message.orEmpty().contains("connection changed"))
        assertTrue(game.message.orEmpty().contains("Game Mode"))
        assertTrue(microphone.message.orEmpty().contains("Microphone is not idle"))
    }

    @Test fun errorRetriesRequireRetiredTransportAndCannotBypassStoppingOrRecovery() {
        requireMicrophoneProbeAdmission(true, true, MicrophonePhase.ERROR, transportInUse = false)
        assertThrows(IllegalStateException::class.java) {
            requireMicrophoneProbeAdmission(true, true, MicrophonePhase.ERROR, transportInUse = true)
        }
        for (phase in listOf(MicrophonePhase.STOPPING, MicrophonePhase.RECOVERING)) {
            assertThrows(IllegalStateException::class.java) {
                requireMicrophoneProbeAdmission(true, true, phase)
            }
        }
    }
}
