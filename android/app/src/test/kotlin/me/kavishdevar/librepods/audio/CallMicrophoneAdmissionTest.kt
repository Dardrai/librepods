package me.kavishdevar.librepods.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CallMicrophoneAdmissionTest {
    private class Fixture(val onActive: (Boolean) -> Unit = {}) : AutoCloseable {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val device = mock(AppleDevice::class.java)
        val sink = mock(LivePcmConsumer::class.java)
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        lateinit var recover: (Long) -> Unit
        val bridgeState = MutableStateFlow(BridgeState(connected = true))
        val microphoneState = MutableStateFlow(MicrophoneState())
        private val bridges = mockConstruction(ShizukuAudioClient::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name in listOf("connectNativePlayback", "restoreNativePlayback")) false
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        }) { bridge, _ ->
            `when`(bridge.state).thenReturn(bridgeState)
            `when`(bridge.isMicrophonePrivacyBlocked()).thenReturn(false)
        }
        private val microphones = mockConstruction(AirPodsMicrophone::class.java) { microphone, construction ->
            @Suppress("UNCHECKED_CAST")
            recover = construction.arguments()[2] as (Long) -> Unit
            `when`(microphone.state).thenReturn(microphoneState)
            `when`(microphone.startLive(sink)).thenReturn(true)
        }
        val controller: ExperimentalAudioController
        val microphone get() = microphones.constructed().single()
        val bridge get() = bridges.constructed().single()
        init {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            `when`(context.filesDir).thenReturn(File("build"))
            `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
            `when`(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)).thenReturn(PackageManager.PERMISSION_GRANTED)
            `when`(audio.isMicrophoneMute).thenReturn(true)
            `when`(device.aacp).thenReturn(aacp)
            `when`(device.macAddress).thenReturn(MacAddress("00:11:22:33:44:55"))
            `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
            `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(modelNumber = "A3063")))
            controller = ExperimentalAudioController(context, device, onActive)
        }
        fun ownedCapture(generation: Long = 7) {
            microphoneState.value = MicrophoneState(MicrophonePhase.CAPTURING, generation)
            `when`(microphone.ownsLiveConsumer(sink)).thenReturn(true)
            `when`(microphone.transportFor(generation)).thenReturn(socket)
        }
        fun recovering(generation: Long = 7) {
            microphoneState.value = MicrophoneState(MicrophonePhase.RECOVERING, generation)
            recover(generation)
        }
        override fun close() {
            controller.close()
            microphones.close(); bridges.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun ordinaryLiveAndRecorderRemainBlockedByNativeMute() {
        Fixture().use { fixture ->
            assertFalse(fixture.controller.startLive(fixture.sink))
            assertFalse(fixture.controller.startRecording())
            verify(fixture.microphone, never()).startLive(fixture.sink)
            verify(fixture.microphone, never()).start(null)
        }
    }

    @Test fun preparedCallMayStartMutedAndIsRecheckedAfterForegroundAdmission() {
        Fixture().use { fixture ->
            var checks = 0
            assertTrue(fixture.controller.startLiveForCall(fixture.sink) { checks++; true })
            assertEquals(2, checks)
            verify(fixture.microphone).startLive(fixture.sink)
        }
    }

    @Test fun rootRouteRetiredDuringForegroundAdmissionCannotStartTheMicrophone() {
        var current = true
        Fixture { if (it) current = false }.use { fixture ->
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { current })
            verify(fixture.microphone, never()).startLive(fixture.sink)
        }
    }

    @Test fun callMuteExceptionNeverBypassesSensorPrivacyOrAppPermission() {
        Fixture().use { fixture ->
            `when`(fixture.bridge.isMicrophonePrivacyBlocked()).thenReturn(true)
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { true })
            `when`(fixture.bridge.isMicrophonePrivacyBlocked()).thenReturn(false)
            `when`(fixture.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)).thenReturn(PackageManager.PERMISSION_DENIED)
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { true })
            verify(fixture.microphone, never()).startLive(fixture.sink)
        }
    }

    @Test fun preparedCallStillNeedsTheBridgeAndReadableNativeMuteState() {
        Fixture().use { fixture ->
            fixture.bridgeState.value = BridgeState(connected = false)
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { true })
            fixture.bridgeState.value = BridgeState(connected = true)
            `when`(fixture.context.getSystemService(AudioManager::class.java)).thenReturn(null)
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { true })
            verify(fixture.microphone, never()).startLive(fixture.sink)
        }
    }

    @Test fun missingOrFailingCallOwnershipRejectsEvenWhenAndroidIsUnmuted() {
        Fixture().use { fixture ->
            `when`(fixture.audio.isMicrophoneMute).thenReturn(false)
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { false })
            assertFalse(fixture.controller.startLiveForCall(fixture.sink) { error("route disappeared") })
            verify(fixture.microphone, never()).startLive(fixture.sink)
        }
    }

    @Test fun quiescedCallPermitOnlyArmsForItsCurrentLiveConsumer() {
        Fixture().use { f ->
            assertFalse(f.controller.armCallStopRecovery(f.sink))
            f.ownedCapture()
            assertFalse(f.controller.armCallStopRecovery(mock(LivePcmConsumer::class.java)))
            assertTrue(f.controller.armCallStopRecovery(f.sink))
            f.controller.close()
            assertFalse(f.controller.armCallStopRecovery(f.sink))
        }
    }

    @Test fun exactQuiescedCallCompletesQuietStopWithoutNativePlaybackRecovery() {
        Fixture().use { f ->
            f.ownedCapture()
            assertTrue(f.controller.armCallStopRecovery(f.sink))
            f.controller.clearCallStopRecovery(mock(LivePcmConsumer::class.java))
            `when`(f.microphone.completeCallStopRecovery(7, f.sink)).thenReturn(true)
            f.recovering()
            verify(f.microphone).completeCallStopRecovery(7, f.sink)
            verify(f.microphone, never()).completeRecovery(anyLong(), anyBoolean())
        }
    }

    @Test fun revokedOrChangedGenerationPermitCannotSkipRecovery() {
        for (invalidate in listOf<(Fixture) -> Unit>(
            { it.controller.clearCallStopRecovery(it.sink) },
            { it.ownedCapture(8) })) {
            Fixture().use { f ->
                f.ownedCapture()
                assertTrue(f.controller.armCallStopRecovery(f.sink))
                invalidate(f)
                val generation = f.microphoneState.value.generation
                f.recovering(generation)
                verify(f.microphone, never()).completeCallStopRecovery(generation, f.sink)
                verify(f.microphone).completeRecovery(generation, false)
            }
        }
    }

    @Test fun unverifiedStopCannotUseTheQuiescedCallShortcut() {
        Fixture().use { f ->
            f.ownedCapture()
            assertTrue(f.controller.armCallStopRecovery(f.sink))
            `when`(f.microphone.completeCallStopRecovery(7, f.sink)).thenReturn(false)
            f.recovering()
            verify(f.microphone).completeCallStopRecovery(7, f.sink)
            verify(f.microphone).completeRecovery(7, false)
        }
    }
}
