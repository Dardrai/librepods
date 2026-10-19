package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.shizuku.BluetoothVolumeSync
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.utils.ConversationVolume
import me.kavishdevar.librepods.utils.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothVolumeSyncServiceTest {
    private lateinit var service: LibrePodsService
    private lateinit var app: LibrePodsApplication
    private val device = mock(AppleDevice::class.java)
    private val bluetooth = mock(BluetoothDevice::class.java)
    private val controller = mock(ExperimentalAudioController::class.java)
    private val sync = mock(BluetoothVolumeSync::class.java)
    private val audio = mock(AudioManager::class.java)
    private val route = mock(AudioDeviceInfo::class.java)
    private val address = MacAddress("00:11:22:33:44:55")
    private val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private lateinit var devices: MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
    private lateinit var audioDevices: ConcurrentHashMap<MacAddress, AppleDevice>
    private val checks = AtomicInteger()
    private val callbackAssertion = AtomicReference<AssertionError?>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        app = LibrePodsApplication()
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(app, "islandWindow", mock(IslandWindow::class.java))
        field("startedWithPermissions").set(service, true)
        field("bluetoothVolumeSync\$delegate").set(service, lazyOf(sync))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.AUDIO_SERVICE, audio)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(emptyArray())
        `when`(route.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(route.address).thenReturn(address.value)
        setRoutes(listOf(route))
        `when`(device.macAddress).thenReturn(address)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(device.connectionState).thenReturn(connection)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState()))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata()))
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetooth.address).thenReturn(address.value)
        `when`(controller.tryBeginSettingsRefresh()).thenReturn(true)
        @Suppress("UNCHECKED_CAST")
        devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        @Suppress("UNCHECKED_CAST")
        audioDevices = field("audioDevices").get(service) as ConcurrentHashMap<MacAddress, AppleDevice>
        devices.value = mapOf(address to device)
        audioDevices[address] = device
        app.experimentalAudioControllers[address] = controller
        answerCheck { current -> assertTrue(current()) }
    }

    @After fun tearDown() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        MediaController.setMicrophoneCaptureActive(false)
        Dispatchers.resetMain()
    }

    @Test fun selectedBondedAirPodsUseTheSameOwnerAndReleaseTheAudioReservation() = runBlocking {
        checkAndJoin()
        assertEquals(2, checks.get())
        verify(controller, times(2)).tryBeginSettingsRefresh()
        verify(controller, times(2)).endSettingsRefresh()
    }

    @Test fun carOrAmbiguousMediaRoutesNeverReachTheRepair() = runBlocking {
        `when`(route.address).thenReturn("10:20:30:40:50:60")
        checkAndJoin()
        `when`(route.address).thenReturn(address.value)
        setRoutes(listOf(route, mock(AudioDeviceInfo::class.java)))
        checkAndJoin()
        setRoutes(emptyList())
        checkAndJoin()
        assertEquals(0, checks.get())
        verifyNoInteractions(controller)
        val order = inOrder(sync)
        order.verify(sync, times(2)).selectRoute("10:20:30:40:50:60")
        order.verify(sync, times(4)).selectRoute(null)
        order.verifyNoMoreInteractions()
    }

    @Test fun serviceCarHandoffRequiresAFreshStabilityWindowWhenTheSamePairReturns() = runBlocking {
        val volumeAudio = VolumeAudio()
        val elapsed = AtomicLong()
        installRealSync(volumeAudio, elapsed)
        checkAndJoin()
        volumeAudio.behavior = 0
        checkAndJoin()
        elapsed.addAndGet(2_000)

        `when`(route.address).thenReturn("10:20:30:40:50:60")
        checkAndJoin()
        `when`(route.address).thenReturn(address.value)
        checkAndJoin()
        assertTrue(volumeAudio.writes.isEmpty())
        elapsed.addAndGet(499)
        checkAndJoin()
        assertTrue(volumeAudio.writes.isEmpty())

        // Duplicate route broadcasts must keep the fresh fault window and its one active worker.
        volumeAudio.onSnapshot = { scheduleCheck(); scheduleCheck() }
        elapsed.incrementAndGet()
        checkAndJoin()
        assertEquals(listOf("volume:0", "absolute") + (1..11).map { "volume:$it" }, volumeAudio.writes)
        assertEquals(11, volumeAudio.volume)
    }

    @Test fun aCarNotificationDuringRepairRetiresPendingRestorationWithoutStartingAnotherWorker() = runBlocking {
        val volumeAudio = VolumeAudio()
        val elapsed = AtomicLong()
        installRealSync(volumeAudio, elapsed)
        checkAndJoin()
        volumeAudio.behavior = 0
        checkAndJoin()
        elapsed.addAndGet(500)
        var workerBefore: Job? = null
        var workerAfter: Job? = null
        var workerWasActive = false
        volumeAudio.onMute = {
            workerBefore = field("bluetoothVolumeSyncJob").get(service) as Job
            `when`(route.address).thenReturn("10:20:30:40:50:60")
            val callback = field("nativeAudioCallback").get(service) as AudioDeviceCallback
            callback.onAudioDevicesRemoved(emptyArray())
            workerAfter = field("bluetoothVolumeSyncJob").get(service) as Job
            workerWasActive = workerAfter?.isActive == true
        }
        checkAndJoin()
        assertNotNull(workerBefore)
        assertSame(workerBefore, workerAfter)
        assertTrue(workerWasActive)
        assertEquals(listOf("volume:0"), volumeAudio.writes)

        `when`(route.address).thenReturn(address.value)
        volumeAudio.behavior = 3
        elapsed.addAndGet(31_000)
        checkAndJoin()
        assertEquals(listOf("volume:0"), volumeAudio.writes)
        assertEquals(0, volumeAudio.volume)
    }

    @Test fun disconnectedUnbondedOrReplacedAirPodsCannotRepairTheActiveRoute() = runBlocking {
        connection.value = ConnectionState.DISCONNECTED
        checkAndJoin()
        connection.value = ConnectionState.CONNECTED
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        checkAndJoin()
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        audioDevices[address] = mock(AppleDevice::class.java)
        checkAndJoin()
        assertEquals(0, checks.get())
        verifyNoInteractions(controller)
    }

    @Test fun callsAndMicrophoneCapturePreventVolumeRepair() = runBlocking {
        field("telephonyCallState").set(service, TelephonyManager.CALL_STATE_OFFHOOK)
        checkAndJoin()
        field("telephonyCallState").set(service, TelephonyManager.CALL_STATE_IDLE)
        `when`(audio.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
        checkAndJoin()
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        MediaController.setMicrophoneCaptureActive(true)
        checkAndJoin()
        assertEquals(0, checks.get())
        // A capture beginning between the first check and the volume lock still releases the reservation.
        verify(controller, times(2)).endSettingsRefresh()
    }

    @Test fun aBusyAudioControllerIsNeverReleasedByARepairThatDidNotReserveIt() = runBlocking {
        `when`(controller.tryBeginSettingsRefresh()).thenReturn(false)
        checkAndJoin()
        assertEquals(0, checks.get())
        verify(controller, never()).endSettingsRefresh()
    }

    @Test fun activeDeviceBroadcastChecksAnUnchangedOutputEndpoint() = runBlocking {
        val receiver = field("bluetoothReceiver").get(service) as BroadcastReceiver
        receiver.onReceive(service, Intent(A2DP_ACTIVE_DEVICE_CHANGED))
        joinCheck()
        receiver.onReceive(service, Intent(A2DP_ACTIVE_DEVICE_CHANGED))
        joinCheck()
        assertEquals(4, checks.get())
        verify(controller, times(4)).endSettingsRefresh()
    }

    @Test fun removedOutputCallbackAlsoChecksTheCurrentlySelectedRoute() = runBlocking {
        val callback = field("nativeAudioCallback").get(service) as AudioDeviceCallback
        callback.onAudioDevicesRemoved(arrayOf(mock(AudioDeviceInfo::class.java)))
        joinCheck()
        assertEquals(2, checks.get())
    }

    @Test fun onlyAnActualProfileDisconnectRetiresThePairsHealthyVolumeEnrollment() = runBlocking {
        val receiver = field("bluetoothReceiver").get(service) as BroadcastReceiver
        receiver.onReceive(service, Intent(A2DP_ACTIVE_DEVICE_CHANGED))
        joinCheck()
        verify(sync, never()).forget(address.value)
        for (state in listOf(BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED)) {
            receiver.onReceive(service, Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, bluetooth)
                .putExtra(BluetoothProfile.EXTRA_STATE, state))
            joinCheck()
        }
        verify(sync, times(2)).forget(address.value)
    }

    @Test fun bluetoothShutdownRetiresEnrollmentBeforeAnotherConnectionCanNegotiate() {
        val receiver = field("bluetoothReceiver").get(service) as BroadcastReceiver
        receiver.onReceive(service, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF))
        verify(sync).forget(device)
    }

    @Test fun inFlightRepairLosesPermissionWhenItsDeviceControllerOrCallOwnerChanges() = runBlocking {
        answerCheck { current ->
            assertTrue(current())
            field("destroying").set(service, true)
            assertFalse(current())
            field("destroying").set(service, false)
            field("telephonyCallState").set(service, TelephonyManager.CALL_STATE_OFFHOOK)
            assertFalse(current())
            field("telephonyCallState").set(service, TelephonyManager.CALL_STATE_IDLE)
            devices.value = mapOf(address to mock(AppleDevice::class.java))
            assertFalse(current())
            devices.value = mapOf(address to device)
            app.experimentalAudioControllers[address] = mock(ExperimentalAudioController::class.java)
            assertFalse(current())
            app.experimentalAudioControllers[address] = controller
            assertTrue(current())
        }
        checkAndJoin()
        verify(controller, times(2)).endSettingsRefresh()
    }

    @Test fun destructionInvalidatesTheWriteGuardAndReleasesTheReservation() = runBlocking {
        answerCheck { current ->
            assertTrue(current())
            field("destroying").set(service, true)
            (field("serviceScope").get(service) as CoroutineScope).cancel()
            assertFalse(current())
            throw CancellationException("Audio route retired")
        }
        checkAndJoin()
        verify(controller).endSettingsRefresh()
    }

    @Test fun helperFailureReleasesTheReservationAndDoesNotKillLaterChecks() = runBlocking {
        answerCheck { throw IllegalStateException("Audio service unavailable") }
        checkAndJoin()
        verify(controller, times(2)).endSettingsRefresh()
        answerCheck { current -> assertTrue(current()) }
        checkAndJoin()
        assertEquals(4, checks.get())
        verify(controller, times(4)).endSettingsRefresh()
    }

    @Test fun repeatedRouteNotificationsNeverCancelAnInFlightRepair() = runBlocking {
        answerCheck { current ->
            val original = field("bluetoothVolumeSyncJob").get(service) as Job
            scheduleCheck()
            scheduleCheck()
            assertSame(original, field("bluetoothVolumeSyncJob").get(service))
            assertTrue(original.isActive)
            assertTrue(current())
        }
        checkAndJoin()
        assertEquals(2, checks.get())
        verify(controller, times(2)).endSettingsRefresh()
    }

    @Test fun conversationDuckingAndItsRestoreTransitionKeepTheirVolumeOwnership() = runBlocking {
        val volumeField = MediaController::class.java.getDeclaredField("conversationVolume").apply { isAccessible = true }
        val transitionField = MediaController::class.java.getDeclaredField("volumeTransition").apply { isAccessible = true }
        val volume = volumeField.get(null) as ConversationVolume
        val owner = Any()
        val oldTransition = transitionField.get(null)
        try {
            synchronized(MediaController) { volume.begin(10, 25, 40, true, 1, owner) }
            checkAndJoin()
            assertEquals(0, checks.get())
            synchronized(MediaController) {
                volume.cancel(10, owner, restoreAllowed = false)
                transitionField.set(null, Runnable {})
            }
            checkAndJoin()
            assertEquals(0, checks.get())
        } finally {
            synchronized(MediaController) {
                volume.cancel(10, owner, restoreAllowed = false)
                transitionField.set(null, oldTransition)
            }
        }
        checkAndJoin()
        assertEquals(2, checks.get())
        verify(controller, times(6)).endSettingsRefresh()
    }

    private fun answerCheck(answer: (() -> Boolean) -> Unit) {
        doAnswer {
            checks.incrementAndGet()
            try { answer(it.getArgument(2)) } catch (failure: AssertionError) {
                callbackAssertion.compareAndSet(null, failure)
                throw IllegalStateException("Repair callback assertion failed", failure)
            }
            false
        }.`when`(sync).check(eq(address.value) ?: address.value, same(device) ?: device,
            any<() -> Boolean>() ?: { false })
    }

    private fun setRoutes(routes: List<AudioDeviceInfo>) {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(routes)
    }

    private fun installRealSync(audio: VolumeAudio, elapsed: AtomicLong) {
        val realSync = BluetoothVolumeSync(service, audio, { true }, elapsed::get, { elapsed.addAndGet(it) })
        field("bluetoothVolumeSync\$delegate").set(service, lazyOf(realSync))
    }

    private inner class VolumeAudio : BluetoothVolumeSync.Audio {
        @JvmField var volume = 13
        var behavior = 3
        var onSnapshot: () -> Unit = {}
        var onMute: () -> Unit = {}
        val writes = mutableListOf<String>()
        override fun syncEnabled(address: String) = true
        override fun snapshot(): BluetoothVolumeSync.Snapshot {
            onSnapshot()
            return BluetoothVolumeSync.Snapshot(AudioManager.MODE_NORMAL, volume, 25,
                listOf(BluetoothVolumeSync.Route(address.value, 2, 8, behavior, this)))
        }
        override fun setVolume(index: Int) {
            writes += "volume:$index"
            volume = index
            if (index == 0) onMute()
        }
        override fun setAbsolute(route: BluetoothVolumeSync.Route) {
            writes += "absolute"
            behavior = 3
        }
    }

    private suspend fun checkAndJoin() {
        scheduleCheck()
        joinCheck()
    }

    private fun scheduleCheck() {
        LibrePodsService::class.java.getDeclaredMethod("checkBluetoothVolumeSync").apply { isAccessible = true }.invoke(service)
    }

    private suspend fun joinCheck() = withTimeout(2_000) {
        (field("bluetoothVolumeSyncJob").get(service) as Job).join()
        callbackAssertion.getAndSet(null)?.let { throw it }
    }

    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val A2DP_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
    }
}
