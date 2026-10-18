package me.kavishdevar.librepods.services

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.AppleRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.utils.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AppleStateObserverTest {
    private lateinit var service: LibrePodsService
    private val device = mock(AppleDevice::class.java)
    private val address = MacAddress("00:11:22:33:44:55")
    private val full = setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR),
        DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_EAR))
    private val states = MutableStateFlow(AppleState(componentState = full))
    private val settings = MutableStateFlow(AppleSettings(showIslandPopup = false))
    private val connections = MutableStateFlow(ConnectionState.CONNECTED)
    private val samples = mock(HeartRateRepository::class.java)
    private val apple = mock(AppleRepository::class.java)
    private val notifications = mock(NotificationManager::class.java)
    private val audio = mock(AudioManager::class.java)
    private val jobs = mutableListOf<Job>()
    private val mediaAudio = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
    private var previousAudio: Any? = null
    private var previousPaused = false
    private var previousPlayed = false
    private val sample = HeartRateSample(180, Instant.parse("2026-10-18T18:00:00Z"))

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        ReflectionHelpers.setField(service, "mApplication", LibrePodsApplication())
        `when`(device.macAddress).thenReturn(address)
        `when`(device.state).thenReturn(states)
        `when`(device.settings).thenReturn(settings)
        `when`(device.connectionState).thenReturn(connections)
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(name = "AirPods")))
        `when`(device.aacp).thenReturn(mock(AACPManager::class.java))
        val bluetooth = mock(BluetoothDevice::class.java)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        field("appleRepository\$delegate").set(service, lazyOf(apple))
        field("heartRateRepository\$delegate").set(service, lazyOf(samples))
        field("islandWindow\$delegate").set(service, lazyOf(mock(IslandWindow::class.java)))
        val appSettings = mock(AppDataRepository::class.java)
        `when`(appSettings.settings).thenReturn(MutableStateFlow(AppSettingsEntity()))
        field("appDataRepository\$delegate").set(service, lazyOf(appSettings))
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to device)
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.NOTIFICATION_SERVICE, notifications)
        previousAudio = mediaAudio.get(null)
        previousPaused = MediaController.iPausedTheMedia
        previousPlayed = MediaController.userPlayedTheMedia
        mediaAudio.set(null, audio)
        MediaController.iPausedTheMedia = false
        MediaController.userPlayedTheMedia = false
        val output = mock(AudioDeviceInfo::class.java)
        `when`(output.address).thenReturn(address.value)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(output))
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(emptyArray())
        `when`(audio.isMusicActive).thenReturn(true)
    }

    @After fun close() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        mediaAudio.set(null, previousAudio)
        MediaController.iPausedTheMedia = previousPaused
        MediaController.userPlayedTheMedia = previousPlayed
        Dispatchers.resetMain()
    }

    @Test fun oneCombinedEmissionProcessesBatteryWearControlsAndHeartRate() = runBlocking {
        observe()
        val combined = states.value.copy(
            battery = setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING)),
            componentState = emptySet(),
            controlStates = mapOf(ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(1)),
            currentHeartRate = sample, hrmState = BuddyState.ACTIVE)
        states.value = combined
        await { calls(samples, "insert") == 1 }
        verify(samples).insert(sample)
        verify(apple).saveCacheFromState(address, combined)
        verify(audio, times(2)).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        assertTrue(MediaController.iPausedTheMedia)
        verify(notifications, never()).notify(eq(999), any(Notification::class.java))
    }

    @Test fun disablingAlertsAndStoppingMeasurementCancelWithoutAnotherSample() = runBlocking {
        states.value = states.value.copy(currentHeartRate = sample, hrmState = BuddyState.ACTIVE)
        settings.value = settings.value.copy(hrmAlertEnabled = true)
        observe()
        jobs += service.observeAppleSettings(device)
        await { alerts() == 1 }
        settings.value = settings.value.copy(hrmAlertEnabled = false)
        await { calls(notifications, "cancel") == 1 }
        settings.value = settings.value.copy(hrmAlertEnabled = true)
        await { alerts() == 2 }
        states.value = states.value.copy(hrmState = BuddyState.INACTIVE)
        await { calls(notifications, "cancel") == 2 }
        assertEquals(2, alerts())
        verify(samples, never()).insert(sample)
    }

    @Test fun disconnectCancelsTheActiveAlertAndAnOldOwnerCannotRecreateIt() = runBlocking {
        states.value = states.value.copy(currentHeartRate = sample, hrmState = BuddyState.ACTIVE)
        settings.value = settings.value.copy(hrmAlertEnabled = true)
        jobs += service.observeAppleSettings(device)
        await { alerts() == 1 }
        connections.value = ConnectionState.DISCONNECTED
        LibrePodsService::class.java.declaredMethods.single {
            it.name.substringBefore('-') == "onDeviceDisconnected" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }.apply { isAccessible = true }.invoke(service, address.value)
        verify(notifications).cancel(999)
        settings.value = settings.value.copy(hrmAlertThreshold = 120)
        await { calls(apple, "saveSettings") >= 2 }
        assertEquals(1, alerts())
        assertNull(field("heartRateAlertOwner").get(service))
    }

    @Test fun aReplacedDeviceCannotPostAnAlertOrCancelItsReplacementsAlert() = runBlocking {
        states.value = states.value.copy(currentHeartRate = sample, hrmState = BuddyState.ACTIVE)
        settings.value = settings.value.copy(hrmAlertEnabled = true)
        val replacement = mock(AppleDevice::class.java)
        `when`(replacement.macAddress).thenReturn(address)
        `when`(replacement.settings).thenReturn(settings)
        `when`(replacement.state).thenReturn(states)
        `when`(replacement.connectionState).thenReturn(connections)
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to replacement)
        val update = LibrePodsService::class.java.getDeclaredMethod("updateHeartRateAlert", AppleDevice::class.java)
            .apply { isAccessible = true }
        update.invoke(service, replacement)
        update.invoke(service, device)
        assertEquals(1, alerts())
        verify(notifications, never()).cancel(999)
        assertSame(replacement, field("heartRateAlertOwner").get(service))
    }

    private suspend fun observe() {
        jobs += service.observeAppleState(device)
        withTimeout(2_000) { states.subscriptionCount.first { it > 0 } }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(2_000) {
        while (!condition()) delay(10)
    }
    private fun calls(mock: Any, method: String) = mockingDetails(mock).invocations.count {
        it.method.name.substringBefore('-') == method
    }
    private fun alerts() = mockingDetails(notifications).invocations.count {
        it.method.name == "notify" && it.arguments.firstOrNull() == 999
    }
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
