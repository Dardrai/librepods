package me.kavishdevar.librepods.services

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.data.StemPressBud
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
import org.robolectric.shadows.ShadowSettings
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
    private val island = mock(IslandWindow::class.java)
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
        field("islandWindow\$delegate").set(service, lazyOf(island))
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
        ShadowSettings.setCanDrawOverlays(false)
        Dispatchers.resetMain()
    }

    @Test fun hostHoldEventsReachTheHandlerWithALocalAudioOutput() = runBlocking<Unit> {
        val events = stemEvents()
        val socket = mock(BluetoothSocket::class.java)
        val event = AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.RIGHT, socket)
        `when`(device.aacp.ownsAudioSocket(socket)).thenReturn(true)
        `when`(device.hostHoldAction(event)).thenReturn(StemAction.CYCLE_NOISE_CONTROL_MODES)
        jobs += service.observeStemActions(device)
        events.emit(event)
        await { calls(device, "cycleHoldListeningMode") == 1 }
        verify(device).cycleHoldListeningMode(event)
    }

    @Test fun retiredHoldEventsCannotLaunchAnAssistantOrChangeModes() = runBlocking<Unit> {
        val events = stemEvents()
        val socket = mock(BluetoothSocket::class.java)
        val event = AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.LEFT, socket)
        `when`(device.aacp.ownsAudioSocket(socket)).thenReturn(false)
        jobs += service.observeStemActions(device)
        withTimeout(2_000) {
            events.subscriptionCount.first { it > 0 }
            events.emit(event)
            // This unbuffered second emission waits until the first complete handler finishes.
            events.emit(event)
        }
        verify(device.aacp, atLeastOnce()).ownsAudioSocket(socket)
        verify(device, never()).hostHoldAction(any(AppleEvent.StemPress::class.java) ?: event)
        verify(device, never()).cycleHoldListeningMode(any(AppleEvent.StemPress::class.java) ?: event)
    }

    private fun stemEvents(): MutableSharedFlow<AppleEvent> {
        val events = MutableSharedFlow<AppleEvent>()
        `when`(device.events).thenReturn(events)
        val output = mock(AudioDeviceInfo::class.java)
        `when`(output.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(output.address).thenReturn(address.value)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(output))
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.AUDIO_SERVICE, audio)
        return events
    }

    @Test fun oneCombinedEmissionProcessesBatteryWearControlsAndHeartRate() = runBlocking {
        observe()
        val combined = states.value.copy(
            battery = setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING)),
            componentState = emptySet(),
            controlStates = mapOf(ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(2)),
            currentHeartRate = sample, hrmState = BuddyState.ACTIVE)
        states.value = combined
        await { calls(samples, "insert") == 1 }
        verify(samples).insert(sample)
        verify(apple).saveCacheFromState(address, combined)
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        assertFalse(MediaController.iPausedTheMedia)
        verify(notifications, never()).notify(eq(999), any(Notification::class.java))
    }

    @Test fun routineDeviceNotificationUpdatesOnlyAlertOnce() {
        service.updateDeviceNotification(device)
        val posted = org.mockito.ArgumentCaptor.forClass(Notification::class.java)
        verify(notifications).notify(eq(address.toNotificationId()), posted.capture())
        assertTrue(posted.value.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun savedDisabledEarDetectionWithoutAReportDoesNotPauseWhenOneBudIsRemoved() = runBlocking {
        assertEarDetectionPause(AppleState(componentState = full), saved = false, pauses = false)
    }

    @Test fun aDisabledEarDetectionReportOverridesAnEnabledSavedChoice() = runBlocking {
        assertEarDetectionPause(AppleState(componentState = full, controlStates = mapOf(
            ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(1))), saved = true, pauses = false)
    }

    @Test fun aPendingDisabledChoiceStopsPausingBeforeItsEcho() = runBlocking {
        val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
        assertEarDetectionPause(AppleState(componentState = full, controlStates = mapOf(id to byteArrayOf(2)),
            requestedControls = mapOf(id to byteArrayOf(1))), saved = true, pauses = false)
    }

    @Test fun aPendingAccessoryChoiceCannotSendGenericMediaKeysWithoutAnOwnedPlayer() = runBlocking {
        val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
        assertEarDetectionPause(AppleState(componentState = full, controlStates = mapOf(id to byteArrayOf(1)),
            requestedControls = mapOf(id to byteArrayOf(2))), saved = false, pauses = false)
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

    @Test fun allowOffChangesRefreshTheLiveIslandsControlsButAnOldPairCannotUpdateItsReplacement() = runBlocking {
        settings.value = settings.value.copy(allowOffChoice = true)
        jobs += service.observeAppleSettings(device)
        await { calls(apple, "saveSettings") == 1 }
        verify(island).updateAdaptiveStrength(device)
        settings.value = settings.value.copy(allowOffChoice = false)
        await { calls(apple, "saveSettings") == 2 }
        verify(island, times(2)).updateAdaptiveStrength(device)

        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        val replacement = mock(AppleDevice::class.java)
        devices.value = mapOf(address to replacement)
        settings.value = settings.value.copy(allowOffChoice = true)
        await { calls(apple, "saveSettings") == 3 }
        verify(island, times(2)).updateAdaptiveStrength(device)
        verify(island, never()).updateAdaptiveStrength(replacement)
    }

    @Test fun conversationTransparencyBeforeSpeechAndItsRestoreStayQuietButManualChoicesAnnounce() = runBlocking {
        val mode = ControlCommandIdentifier.LISTENING_MODE
        val conversation = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        val socket = mock(BluetoothSocket::class.java)
        `when`(device.aacp.captureAudioSocket()).thenReturn(socket)
        `when`(device.aacp.ownsAudioSocket(socket)).thenReturn(true)
        settings.value = settings.value.copy(showIslandPopup = true)
        ShadowSettings.setCanDrawOverlays(true)
        states.value = states.value.copy(controlStates = mapOf(conversation to byteArrayOf(1), mode to byteArrayOf(2)))
        observe()

        suspend fun report(value: Int, requested: Int? = null) {
            val notificationsBefore = calls(notifications, "notify")
            states.value = states.value.copy(controlStates = states.value.controlStates + (mode to byteArrayOf(value.toByte())),
                requestedControls = requested?.let { mapOf(mode to byteArrayOf(it.toByte())) } ?: emptyMap(),
                lastListeningModeRequest = requested?.let { byteArrayOf(it.toByte()) } ?: states.value.lastListeningModeRequest)
            await { calls(notifications, "notify") == notificationsBefore + 1 }
        }

        // The first fresh report establishes a live baseline after the cached initial array.
        report(2)
        assertEquals(1, calls(apple, "saveCacheFromState"))
        assertEquals(0, calls(island, "show"))

        states.value = states.value.copy(controlStates = states.value.controlStates + (mode to byteArrayOf(3)))
        await { calls(apple, "saveCacheFromState") == 2 }
        // Persistence happens before the presentation grace period; speech arrives inside it.
        states.value = states.value.copy(conversationAwarenessState = 1)
        await { calls(notifications, "notify") == 2 }
        assertEquals("Automatic Transparency must not announce before its speech event", 0, calls(island, "show"))
        states.value = states.value.copy(conversationAwarenessState = 4)
        report(2)
        assertEquals("Returning to the mode from before the conversation must stay quiet", 0, calls(island, "show"))

        report(4)
        assertEquals("A manual mode outside the automatic cycle still announces", 1, calls(island, "show"))
        states.value = states.value.copy(conversationAwarenessState = 1)
        // A duplicate live report lets the pending request reach previousState before its echo clears it.
        report(4, requested = 3)
        assertEquals("A pending choice is not itself a mode report", 1, calls(island, "show"))
        report(3)
        assertEquals("An explicit Transparency echo must announce even while speech is active", 2, calls(island, "show"))
    }

    @Test fun coalescedSpeechAndLocalRequestsKeepAutomaticModesQuietAndExplicitChoicesVisible() = runBlocking {
        val mode = ControlCommandIdentifier.LISTENING_MODE
        val socket = mock(BluetoothSocket::class.java)
        `when`(device.aacp.captureAudioSocket()).thenReturn(socket)
        `when`(device.aacp.ownsAudioSocket(socket)).thenReturn(true)
        settings.value = settings.value.copy(showIslandPopup = true)
        ShadowSettings.setCanDrawOverlays(true)
        states.value = states.value.copy(controlStates = mapOf(
            ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG to byteArrayOf(1), mode to byteArrayOf(2)))
        observe()
        states.value = states.value.copy(controlStates = states.value.controlStates + (mode to byteArrayOf(2)))
        await { calls(notifications, "notify") == 1 }

        states.value = states.value.copy(controlStates = states.value.controlStates + (mode to byteArrayOf(3)))
        await { calls(apple, "saveCacheFromState") == 2 }
        // The entire speech cycle coalesces while presentation waits; only its terminal stage survives.
        states.value = states.value.copy(conversationAwarenessState = 4, conversationAwarenessSequence = 1L)
        await { calls(notifications, "notify") == 2 }
        assertEquals("A completed speech cycle still identifies its automatic Transparency report", 0, calls(island, "show"))
        states.value = states.value.copy(controlStates = states.value.controlStates + (mode to byteArrayOf(2)))
        await { calls(notifications, "notify") == 3 }
        assertEquals("The coalesced cycle's matching restore must stay quiet", 0, calls(island, "show"))

        assertTrue(states.value.requestedControls.isEmpty())
        // The successful write and echo coalesce, leaving no pending-map emission for the observer.
        states.value = states.value.copy(controlStates = states.value.controlStates + (mode to byteArrayOf(3)),
            conversationAwarenessState = 1, conversationAwarenessSequence = 2L,
            lastListeningModeRequest = byteArrayOf(3))
        await { calls(notifications, "notify") == 4 }
        assertTrue(states.value.requestedControls.isEmpty())
        assertEquals("A retained successful local request must announce despite active speech", 1, calls(island, "show"))
    }

    @Test fun coalescedUiEchoesStayQuietAfterReleaseAndPhysicalChangesStillAnnounce() = runBlocking {
        val mode = ControlCommandIdentifier.LISTENING_MODE
        val socket = mock(BluetoothSocket::class.java)
        `when`(device.aacp.captureAudioSocket()).thenReturn(socket)
        `when`(device.aacp.ownsAudioSocket(socket)).thenReturn(true)
        settings.value = settings.value.copy(showIslandPopup = true)
        ShadowSettings.setCanDrawOverlays(true)
        states.value = states.value.copy(controlStates = mapOf(mode to byteArrayOf(2)))
        observe()
        states.value = states.value.copy(controlStates = mapOf(mode to byteArrayOf(2)))
        await { calls(notifications, "notify") == 1 }

        val echo = byteArrayOf(4)
        val quiet = QuietListeningModeReport(echo, CompletableDeferred(true))
        // The successful UI request, release and ACK coalesce; no picker/gesture suppression remains.
        states.value = states.value.copy(controlStates = mapOf(mode to echo),
            requestedControls = emptyMap(), lastListeningModeRequest = byteArrayOf(4), quietListeningModeReport = quiet,
            battery = setOf(Battery(BatteryComponent.LEFT, 81, BatteryStatus.NOT_CHARGING)))
        // Battery and controls each refresh the notification in this combined emission.
        await { calls(notifications, "notify") >= 3 }
        assertEquals(0, calls(island, "show"))
        assertTrue("A visible island must still receive the updated controls", calls(island, "updateAdaptiveStrength") >= 2)
        for ((index, physical) in listOf(2, 4).withIndex()) {
            // Even a retained old marker cannot classify a fresh same-value physical report as quiet.
            states.value = states.value.copy(controlStates = mapOf(mode to byteArrayOf(physical.toByte())))
            await { calls(notifications, "notify") >= index + 4 }
            assertEquals(index + 1, calls(island, "show"))
        }
    }

    @Test fun aSlowUiWriteStaysQuietWithoutBlockingObservationAndAFailedWriteCannotSilenceTheNextReport() = runBlocking {
        val mode = ControlCommandIdentifier.LISTENING_MODE
        val socket = mock(BluetoothSocket::class.java)
        `when`(device.aacp.captureAudioSocket()).thenReturn(socket)
        `when`(device.aacp.ownsAudioSocket(socket)).thenReturn(true)
        settings.value = settings.value.copy(showIslandPopup = true)
        ShadowSettings.setCanDrawOverlays(true)
        states.value = states.value.copy(controlStates = mapOf(mode to byteArrayOf(2)))
        observe()
        states.value = states.value.copy(controlStates = mapOf(mode to byteArrayOf(2)))
        await { calls(notifications, "notify") == 1 }
        for ((index, completion) in listOf(CompletableDeferred<Boolean>(), CompletableDeferred(false)).withIndex()) {
            val report = byteArrayOf(if (index == 0) 4 else 3)
            states.value = states.value.copy(controlStates = mapOf(mode to report),
                quietListeningModeReport = QuietListeningModeReport(report, completion))
            await { calls(notifications, "notify") == index + 2 }
            assertEquals("A slow tagged UI echo stays quiet, while a known failed write does not", index, calls(island, "show"))
            completion.complete(false)
        }
    }

    private suspend fun assertEarDetectionPause(initial: AppleState, saved: Boolean, pauses: Boolean) {
        states.value = initial
        settings.value = settings.value.copy(earDetectionEnabled = saved)
        observe()
        states.value = initial.copy(componentState = full.map {
            if (it.component == DeviceComponent.RIGHT) it.copy(status = ComponentStatus.OUT_OF_EAR) else it
        }.toSet())
        await { calls(notifications, "notify") > 0 }
        assertEquals(if (pauses) 2 else 0, calls(audio, "dispatchMediaKeyEvent"))
        assertEquals(pauses, MediaController.iPausedTheMedia)
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
