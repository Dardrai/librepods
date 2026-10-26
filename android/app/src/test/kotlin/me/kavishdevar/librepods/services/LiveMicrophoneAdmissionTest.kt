package me.kavishdevar.librepods.services

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.AirPodsMicrophone
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.LivePcmConsumer
import me.kavishdevar.librepods.audio.MicrophoneState
import me.kavishdevar.librepods.audio.PcmFormat
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.utils.NativeGameModeController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class LiveMicrophoneAdmissionTest {
    private class Sink : LivePcmConsumer {
        val closes = AtomicInteger()
        override fun onFormat(format: PcmFormat) = Unit
        override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) = Unit
        override fun close() { closes.incrementAndGet() }
    }

    private class Fixture : AutoCloseable {
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        val app = LibrePodsApplication()
        val address = MacAddress("00:11:22:33:44:55")
        val device = mock(AppleDevice::class.java)
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val controller = mock(ExperimentalAudioController::class.java)
        val microphone = mock(AirPodsMicrophone::class.java)
        val game = mock(NativeGameModeController::class.java)
        val sink = Sink()
        init {
            ReflectionHelpers.setField(service, "mApplication", app)
            `when`(device.macAddress).thenReturn(address)
            `when`(device.aacp).thenReturn(aacp)
            `when`(aacp.captureAudioSocket()).thenReturn(socket)
            `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
            `when`(controller.microphone).thenReturn(microphone)
            `when`(microphone.state).thenReturn(MutableStateFlow(MicrophoneState()))
            `when`(controller.startLive(sink)).thenReturn(true)
            `when`(game.state).thenReturn(MutableStateFlow(NativeGameModeController.State(desiredOn = true)))
            app.experimentalAudioControllers[address] = controller
            @Suppress("UNCHECKED_CAST")
            (field("audioDevices").get(service) as MutableMap<MacAddress, AppleDevice>)[address] = device
            service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        }
        override fun close() {
            (field("serviceScope").get(service) as CoroutineScope).cancel()
            app.experimentalAudioControllers.clear()
            app.nativeGameModeControllers.clear()
        }
        private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
    }

    @Test fun activeCallOrStaleSocketRejectsWithoutTakingConsumerOwnership() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            f.service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
            assertNull(f.service.startLiveMicrophone(f.device, f.sink))
            f.service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
            `when`(f.aacp.ownsAudioSocket(f.socket)).thenReturn(false)
            assertNull(f.service.startLiveMicrophone(f.device, f.sink))
            verify(f.controller, never()).startLive(f.sink)
            assertEquals(0, f.sink.closes.get())
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun cancellingBeforeGameModeNormalizationStartsClosesConsumerOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            f.app.nativeGameModeControllers[f.address] = f.game
            `when`(f.game.suspendForMicrophone()).thenReturn(true)
            val handle = checkNotNull(f.service.startLiveMicrophone(f.device, f.sink))
            handle.close()
            handle.close()
            runCurrent()
            verify(f.controller, never()).startLive(f.sink)
            verify(f.game, never()).suspendForMicrophone()
            assertEquals(1, f.sink.closes.get())
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun successfulNormalizationStartsOnceAndOwnedHandleStopsOnlyThatConsumer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            f.app.nativeGameModeControllers[f.address] = f.game
            `when`(f.game.suspendForMicrophone()).thenReturn(true)
            val handle = checkNotNull(f.service.startLiveMicrophone(f.device, f.sink))
            runCurrent()
            verify(f.game).suspendForMicrophone()
            verify(f.controller).startLive(f.sink)
            assertEquals(0, f.sink.closes.get())
            handle.close()
            handle.close()
            verify(f.controller, times(1)).stopLive(f.sink)
            assertEquals("An active capture closes its own sink", 0, f.sink.closes.get())
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun ownerLossWhileQueuedCannotStartAndReleasesTheAcceptedConsumer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            f.app.nativeGameModeControllers[f.address] = f.game
            `when`(f.game.suspendForMicrophone()).thenReturn(true)
            val handle = checkNotNull(f.service.startLiveMicrophone(f.device, f.sink))
            `when`(f.aacp.ownsAudioSocket(f.socket)).thenReturn(false)
            runCurrent()
            verify(f.controller, never()).startLive(f.sink)
            assertEquals(1, f.sink.closes.get())
            handle.close()
            assertEquals(1, f.sink.closes.get())
        } } finally { Dispatchers.resetMain() }
    }
}
