package me.kavishdevar.librepods.services

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.utils.ConversationVolume
import me.kavishdevar.librepods.utils.MediaController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConversationAwarenessServiceTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun silentOnIntentAllowsSpeechAndOffIntentCancelsItBeforeAnEcho() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        val app = LibrePodsApplication()
        val device = mock(AppleDevice::class.java)
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val address = MacAddress("00:11:22:33:44:55")
        val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        val states = MutableStateFlow(AppleState(controlStates = mapOf(id to byteArrayOf(2)),
            requestedControls = mapOf(id to byteArrayOf(1))))
        val audio = mock(AudioManager::class.java)
        val output = mock(AudioDeviceInfo::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(device.macAddress).thenReturn(address)
        `when`(device.state).thenReturn(states)
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata()))
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(conversationAwarenessPauseMusicEnabled = false)))
        `when`(device.aacp).thenReturn(aacp)
        `when`(aacp.captureAudioSocket()).thenReturn(socket)
        `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
        `when`(output.address).thenReturn(address.value)
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(output))
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        `when`(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(20)
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(app, "islandWindow", mock(IslandWindow::class.java))
        @Suppress("UNCHECKED_CAST")
        val devices = field(service, "_devices") as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to device)
        val audioField = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
        val previousAudio = audioField.get(null)
        audioField.set(null, audio)
        val volume = MediaController::class.java.getDeclaredField("conversationVolume").apply { isAccessible = true }
            .get(null) as ConversationVolume
        val observer = service.observeAppleState(device)
        try {
            withTimeout(2_000) { states.subscriptionCount.first { it > 0 } }
            states.value = states.value.copy(conversationAwarenessState = 1)
            withTimeout(2_000) { while (!synchronized(MediaController) { volume.speaking }) delay(10) }
            assertTrue(volume.owns(socket))
            states.value = states.value.copy(requestedControls = mapOf(id to byteArrayOf(2)))
            withTimeout(2_000) { while (synchronized(MediaController) { volume.speaking }) delay(10) }
            assertFalse(volume.owns(socket))
        } finally {
            observer.cancelAndJoin()
            (field(service, "serviceScope") as CoroutineScope).cancel()
            audioField.set(null, previousAudio)
            Dispatchers.resetMain()
        }
    }

    private fun field(service: LibrePodsService, name: String): Any? =
        LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
}
