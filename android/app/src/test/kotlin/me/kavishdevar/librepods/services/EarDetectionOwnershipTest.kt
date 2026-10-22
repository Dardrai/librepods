package me.kavishdevar.librepods.services

import android.bluetooth.BluetoothDevice
import android.app.Application
import android.content.Context
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.BridgeState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
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
    private val bridge = mock(ShizukuAudioClient::class.java)
    private val state = MutableStateFlow(AppleState())
    private val address = MacAddress("00:11:22:33:44:55")
    private val mediaAudio = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
    private val previousAudio = mediaAudio.get(null)
    private val previousPaused = MediaController.iPausedTheMedia
    private val previousPlayed = MediaController.userPlayedTheMedia
    private val full = setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR),
        DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_EAR))

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mediaAudio.set(null, audio)
        MediaController.iPausedTheMedia = false
        MediaController.userPlayedTheMedia = false
        `when`(device.macAddress).thenReturn(address)
        val bluetooth = mock(BluetoothDevice::class.java)
        `when`(bluetooth.address).thenReturn(address.value)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(device.state).thenReturn(state)
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(showIslandPopup = false)))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata()))
        val repository = mock(AppDataRepository::class.java)
        `when`(repository.settings).thenReturn(MutableStateFlow(AppSettingsEntity()))
        field("appDataRepository\$delegate").set(service, lazyOf(repository))
        field("companionBridge\$delegate").set(service, lazyOf(bridge))
        `when`(bridge.state).thenReturn(MutableStateFlow(BridgeState(connected = true)))
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.AUDIO_SERVICE, audio)
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to device)
        runBlocking {
            `when`(bridge.pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                (invocation.arguments[1] as () -> Boolean)()
            }
            `when`(bridge.finishOwnedMedia(true)).thenReturn(true)
        }
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        Dispatchers.resetMain()
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

    @Test fun localEarRemovalAndReinsertionUseOnlyTheAuthorizedExactPlayerLease() = runBlocking<Unit> {
        route(address.value)
        change(full, emptySet())
        verify(bridge).pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })
        change(emptySet(), full)
        verify(bridge).finishOwnedMedia(true)
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        noProfileChanges()
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
        `when`(output.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(output))
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(output))
    }

    private fun change(previous: Set<DeviceComponentState>, current: Set<DeviceComponentState>) {
        state.value = state.value.copy(componentState = current)
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
