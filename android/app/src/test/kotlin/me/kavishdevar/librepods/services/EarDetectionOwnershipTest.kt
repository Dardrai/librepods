package me.kavishdevar.librepods.services

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.utils.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class EarDetectionOwnershipTest {
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val device = mock(AppleDevice::class.java)
    private val audio = mock(AudioManager::class.java)
    private val address = MacAddress("00:11:22:33:44:55")
    private val mediaAudio = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
    private val previousAudio = mediaAudio.get(null)
    private val previousPaused = MediaController.iPausedTheMedia
    private val previousPlayed = MediaController.userPlayedTheMedia
    private val full = setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR),
        DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_EAR))

    init {
        mediaAudio.set(null, audio)
        MediaController.iPausedTheMedia = false
        MediaController.userPlayedTheMedia = false
        `when`(device.macAddress).thenReturn(address)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState(componentState = full)))
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(showIslandPopup = false)))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata()))
        val repository = mock(AppDataRepository::class.java)
        `when`(repository.settings).thenReturn(MutableStateFlow(AppSettingsEntity()))
        field("appDataRepository\$delegate").set(service, lazyOf(repository))
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        mediaAudio.set(null, previousAudio)
        MediaController.iPausedTheMedia = previousPaused
        MediaController.userPlayedTheMedia = previousPlayed
    }

    @Test fun controlOnlyRemoteWearReportsCannotConnectOrControlPhonePlayback() {
        route("AA:BB:CC:DD:EE:FF")
        `when`(audio.isMusicActive).thenReturn(true)
        change(emptySet(), full)
        change(full, emptySet())
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        noProfileChanges()
        assertFalse(MediaController.iPausedTheMedia)
    }

    @Test fun localEarRemovalAndReinsertionPauseAndResumeWithoutTouchingProfilePolicies() {
        route(address.value)
        `when`(audio.isMusicActive).thenReturn(true)
        change(full, emptySet())
        assertTrue(MediaController.iPausedTheMedia)
        `when`(audio.isMusicActive).thenReturn(false)
        change(emptySet(), full)
        val keys = ArgumentCaptor.forClass(KeyEvent::class.java)
        verify(audio, times(4)).dispatchMediaKeyEvent(keys.capture())
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY), keys.allValues.map { it.keyCode })
        noProfileChanges()
        assertFalse(MediaController.iPausedTheMedia)
    }

    @Test fun aRemoteRouteAfterLocalPauseDoesNotResumeOrReclaimWhenBudsAreWornAgain() {
        route(address.value)
        `when`(audio.isMusicActive).thenReturn(true)
        change(full, emptySet())
        clearInvocations(audio)
        route("AA:BB:CC:DD:EE:FF")
        change(emptySet(), full)
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        noProfileChanges()
    }

    private fun route(value: String) {
        val output = mock(AudioDeviceInfo::class.java)
        `when`(output.address).thenReturn(value)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(output))
    }

    private fun change(previous: Set<DeviceComponentState>, current: Set<DeviceComponentState>) {
        LibrePodsService::class.java.getDeclaredMethod("processComponentStateChange", Device::class.java,
            Set::class.java, Set::class.java).apply { isAccessible = true }.invoke(service, device, previous, current)
    }

    private fun noProfileChanges() {
        verify(device, never()).enableAudio()
        verify(device, never()).disableAudio()
        verify(device, never()).connectA2dp()
        verify(device, never()).disconnectAudio()
    }

    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
