package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.A2dpRecoveryGuard
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NearbyDeviceConnectionTest {
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val app = LibrePodsApplication()
    private val address = MacAddress("00:11:22:33:44:55")
    private val device = mock(AppleDevice::class.java)
    private val aacp = mock(AACPManager::class.java)
    private val socketMatcherFallback = mock(BluetoothSocket::class.java)
    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val controller = mock(ExperimentalAudioController::class.java)
    private val bridge = mock(ShizukuAudioClient::class.java)
    private val bridgeState = MutableStateFlow(BridgeState(connected = true))
    private val audio = mock(AudioManager::class.java)
    private val bluetooth = mock(BluetoothDevice::class.java)
    private val aacpUuid = ParcelUuid.fromString(A2dpRecoveryGuard.AACP_UUID)
    @Suppress("UNCHECKED_CAST")
    private val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>

    init {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(app, "islandWindow", mock(IslandWindow::class.java))
        `when`(bluetooth.address).thenReturn(address.value)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(bluetooth.uuids).thenReturn(arrayOf(aacpUuid))
        `when`(device.macAddress).thenReturn(address)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState()))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(name = "AirPods")))
        // AACP can be connected while the media profile still belongs to another host.
        `when`(device.connectionState).thenReturn(connectionState)
        `when`(device.aacp).thenReturn(aacp)
        `when`(controller.bridge).thenReturn(bridge)
        `when`(controller.canRefreshSettings()).thenReturn(true)
        `when`(bridge.state).thenReturn(bridgeState)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(emptyArray())
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.AUDIO_SERVICE, audio)
        app.experimentalAudioControllers[address] = controller
        @Suppress("UNCHECKED_CAST")
        (field("audioDevices").get(service) as MutableMap<MacAddress, AppleDevice>)[address] = device
        devices.value = mapOf(address to device)
    }

    @After fun close() { (field("serviceScope").get(service) as CoroutineScope).cancel() }

    @Test fun aLateUuidOrAclNotificationCannotPageControlWithoutALocalAudioProfile() = runBlocking<Unit> {
        connectionState.value = ConnectionState.AVAILABLE
        attachNativeAudio()
        verify(device, never()).connect()
        verify(device, never()).connect(anyCurrent())
        verify(device, never()).connect(anyCurrent(), anyPending())
        verifyNoInteractions(bridge)
    }

    @Test fun nativeAudioAttachmentRetiresAsSoonAsItsExactPairLosesTheOutput() = runBlocking<Unit> {
        connectionState.value = ConnectionState.AVAILABLE
        publishOutput()
        `when`(device.connect(anyCurrent())).thenAnswer { invocation ->
            val current = invocation.getArgument<() -> Boolean>(0)
            assertTrue(current())
            clearOutput()
            assertFalse("An SDP/ACL callback must not retain permission to reclaim a retired route", current())
            false
        }
        attachNativeAudio()
        verify(device).connect(anyCurrent())
        verify(device, never()).connect()
        verifyNoInteractions(bridge)
    }

    @Test fun passiveAuthenticatedReconnectAdvertisementsNeverPageEvenWithLegacyTakeoverEnabled() {
        advance(2_000)
        connectionState.value = ConnectionState.AVAILABLE
        val irk = byteArrayOf(0x79, 4, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
            0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)
        val key = ByteArray(16) { (it + 1).toByte() }
        `when`(device.state).thenReturn(MutableStateFlow(AppleState(
            magicKeys = mapOf(MagicKeyType.IRK to irk, MagicKeyType.ENC_KEY to key))))
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(
            takeoverWhenDisconnected = true, showIslandPopup = false)))
        val manufacturer = ByteArray(27).apply {
            this[0] = 7; this[1] = 25; this[2] = 1; this[3] = 0x27; this[4] = 0x20
            this[5] = 0x60 // Open lid, at least one bud out; no connected peer in byte 10.
            val plain = ByteArray(16).apply { this[1] = 90; this[2] = 85; this[3] = 80 }
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
                .doFinal(plain).copyInto(this, 11)
        }
        val advertised = mock(BluetoothDevice::class.java)
        `when`(advertised.address).thenReturn("5A:16:2B:91:D1:CD")
        val record = mock(ScanRecord::class.java)
        `when`(record.getManufacturerSpecificData(0x004c)).thenReturn(manufacturer)
        val result = mock(ScanResult::class.java)
        `when`(result.device).thenReturn(advertised)
        `when`(result.scanRecord).thenReturn(record)
        `when`(result.timestampNanos).thenReturn(SystemClock.elapsedRealtimeNanos())
        LibrePodsService::class.java.getDeclaredMethod("handleScanResult", ScanResult::class.java)
            .apply { isAccessible = true }.invoke(service, result)
        verify(device).receiveNearbyBattery(any<Set<Battery>>() ?: emptySet())
        verify(device, never()).connect()
        verify(device, never()).connect(anyCurrent())
        verify(device, never()).connect(anyCurrent(), anyPending())
        verifyNoInteractions(bridge)
    }

    @Test fun controlOnlyConnectionStillRequiresVerifiedMediaConnection() = runBlocking<Unit> {
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { publishOutput(); true }
        assertFalse(service.isNearbyDeviceAudioConnected(device))
        assertTrue(service.connectNearbyDevice(device))
        verify(bridge).connect()
        verify(bridge).connectNativePlayback(selectedAddress(), anyCurrent())
        verify(device, never()).connectAudio()
        verify(device, never()).connect(anyCurrent(), anyPending())
    }

    @Test fun explicitTakeoverPagesControlInParallelAndAdoptsItOnlyWhileLocalMediaRemainsOwned() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        val pending = mock(BluetoothSocket::class.java)
        val released = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val controlOwner = AtomicReference<() -> Boolean>()
        `when`(device.connect(anyCurrent(), anyPending())).thenAnswer { invocation ->
            connectionState.value = ConnectionState.CONNECTING
            controlOwner.set(invocation.getArgument(0))
            invocation.getArgument<(BluetoothSocket) -> Unit>(1).invoke(pending)
            entered.countDown()
            try { released.await(5, TimeUnit.SECONDS); false }
            finally { connectionState.value = ConnectionState.DISCONNECTED; completed.set(true) }
        }
        `when`(aacp.cancelPendingConnect(same(pending) ?: pending)).thenAnswer { released.countDown(); true }
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer {
            assertTrue("Media starts after control publishes its socket", entered.await(1, TimeUnit.SECONDS))
            assertFalse("Media must not wait for the control connection to finish", completed.get())
            publishOutput()
            true
        }
        try {
            assertTrue(service.connectNearbyDevice(device))
            assertFalse(completed.get())
            assertTrue("A completed UI request must not retire the control route it just connected", controlOwner.get().invoke())
            verify(aacp, never()).cancelPendingConnect(anySocket())
            clearOutput()
            withTimeout(2_000) { while (!completed.get()) delay(10) }
            assertFalse(controlOwner.get().invoke())
            verify(aacp, atLeastOnce()).cancelPendingConnect(same(pending) ?: pending)
            verify(device, never()).disconnect()
        } finally { released.countDown() }
    }

    @Test fun aFailedEarlyControlConnectionCannotPreventSuccessfulNativePlayback() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        val attempted = AtomicBoolean(false)
        `when`(device.connect(anyCurrent(), anyPending())).thenAnswer { attempted.set(true); false }
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer {
            assertTrue(attempted.get())
            publishOutput()
            true
        }
        assertTrue(service.connectNearbyDevice(device))
        verify(bridge).connectNativePlayback(selectedAddress(), anyCurrent())
        verify(aacp, never()).cancelPendingConnect(anySocket())
    }

    @Test fun aSlowControlSocketFactoryCannotSerializeTheMediaConnection() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        `when`(device.connect(anyCurrent(), anyPending())).thenAnswer {
            connectionState.value = ConnectionState.CONNECTING
            entered.countDown()
            try { released.await(5, TimeUnit.SECONDS); false } finally { completed.set(true) }
        }
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(completed.get())
            publishOutput()
            true
        }
        try {
            withTimeout(2_000) { assertTrue(service.connectNearbyDevice(device)) }
            assertFalse("The first-socket head start must remain bounded even when no socket was published", completed.get())
            verify(bridge).connectNativePlayback(selectedAddress(), anyCurrent())
            Unit
        } finally { clearOutput(); released.countDown() }
    }

    @Test fun cancellingAnExplicitRequestClosesOnlyItsCapturedPendingControlSocket() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        val pending = mock(BluetoothSocket::class.java)
        val replacement = mock(BluetoothSocket::class.java)
        val released = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val mediaStarted = AtomicBoolean(false)
        `when`(device.connect(anyCurrent(), anyPending())).thenAnswer { invocation ->
            connectionState.value = ConnectionState.CONNECTING
            invocation.getArgument<(BluetoothSocket) -> Unit>(1).invoke(pending)
            try { released.await(5, TimeUnit.SECONDS); false }
            finally { connectionState.value = ConnectionState.DISCONNECTED; completed.set(true) }
        }
        `when`(aacp.captureAudioSocket()).thenReturn(replacement)
        `when`(aacp.cancelPendingConnect(same(pending) ?: pending)).thenAnswer { released.countDown(); true }
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { mediaStarted.set(true); true }
        try {
            val request = launch(start = CoroutineStart.UNDISPATCHED) { service.connectNearbyDevice(device) }
            withTimeout(2_000) { while (!mediaStarted.get()) delay(10) }
            request.cancelAndJoin()
            withTimeout(2_000) { while (!completed.get()) delay(10) }
            verify(aacp, atLeastOnce()).cancelPendingConnect(same(pending) ?: pending)
            verify(aacp, never()).cancelPendingConnect(same(replacement) ?: replacement)
            verify(device, never()).disconnect()
        } finally { released.countDown() }
    }

    @Test fun retiringTheWatchdogPreventsALateFactoryFromStartingControlEvenAfterMediaSucceeds() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        val pending = mock(BluetoothSocket::class.java)
        val released = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val mediaStarted = AtomicBoolean(false)
        val controlOwner = AtomicReference<() -> Boolean>()
        `when`(device.connect(anyCurrent(), anyPending())).thenAnswer { invocation ->
            connectionState.value = ConnectionState.CONNECTING
            controlOwner.set(invocation.getArgument(0))
            try {
                released.await(5, TimeUnit.SECONDS)
                assertFalse("Actual media ownership cannot revive a worker retired by its watchdog", controlOwner.get().invoke())
                invocation.getArgument<(BluetoothSocket) -> Unit>(1).invoke(pending)
                false
            } finally { completed.set(true) }
        }
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { mediaStarted.set(true); true }
        try {
            var result: Boolean? = null
            val request = launch(start = CoroutineStart.UNDISPATCHED) { result = service.connectNearbyDevice(device) }
            withTimeout(2_000) { while (!mediaStarted.get()) delay(10) }
            val scopeJob = (field("serviceScope").get(service) as CoroutineScope).coroutineContext[Job]!!
            // Only the watchdog owns a nested timeout; cancel it to exercise the same retirement finally block.
            val watchdog = withTimeout(2_000) {
                var found: Job? = null
                while (found == null) {
                    found = scopeJob.children.firstOrNull { it.children.any() }
                    if (found == null) delay(10)
                }
                requireNotNull(found)
            }
            watchdog.cancelAndJoin()
            assertTrue("Retiring control must not cancel the independent native media request", request.isActive)
            assertFalse(controlOwner.get().invoke())
            publishOutput()
            request.join()
            assertEquals(true, result)
            assertFalse(controlOwner.get().invoke())
            released.countDown()
            withTimeout(2_000) { while (!completed.get()) delay(10) }
            verify(aacp, atLeastOnce()).cancelPendingConnect(same(pending) ?: pending)
            verify(device, never()).disconnect()
        } finally { clearOutput(); released.countDown() }
    }

    @Test fun anUnboundBridgeCannotStartAnEarlyControlSocket() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        bridgeState.value = BridgeState(connected = false)
        val request = launch(start = CoroutineStart.UNDISPATCHED) { service.connectNearbyDevice(device) }
        assertTrue(request.isActive)
        request.cancelAndJoin()
        verify(device, never()).connect(anyCurrent(), anyPending())
        verify(bridge, never()).connectNativePlayback(anyString(), anyCurrent())
    }

    @Test fun anAlreadyConnectingControlSessionIsNeverStartedAgainByTakeover() = runBlocking<Unit> {
        connectionState.value = ConnectionState.CONNECTING
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { publishOutput(); true }
        assertTrue(service.connectNearbyDevice(device))
        verify(device, never()).connect(anyCurrent(), anyPending())
    }

    @Test fun existingLocalMediaConnectionDoesNotCycleTheProfile() = runBlocking<Unit> {
        publishOutput()
        assertTrue(service.isNearbyDeviceAudioConnected(device))
        assertTrue(service.connectNearbyDevice(device))
        verifyNoInteractions(bridge)
    }

    @Test fun removedOrUnpairedDevicesCannotIssueAConnection() = runBlocking<Unit> {
        devices.value = emptyMap()
        assertFalse(service.connectNearbyDevice(device))
        devices.value = mapOf(address to device)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        assertFalse(service.connectNearbyDevice(device))
        verifyNoInteractions(bridge)
    }

    @Test fun anActiveCallOrMicrophoneSetupCannotBeDisrupted() = runBlocking<Unit> {
        `when`(audio.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
        assertFalse(service.connectNearbyDevice(device))
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(controller.canRefreshSettings()).thenReturn(false)
        assertFalse(service.connectNearbyDevice(device))
        verifyNoInteractions(bridge)
    }

    @Test fun failedMediaConnectionDoesNotReportAacpAsSuccessAndCanBeRetried() = runBlocking<Unit> {
        var attempts = 0
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer {
            if (++attempts == 1) false else { publishOutput(); true }
        }
        assertFalse(service.connectNearbyDevice(device))
        assertTrue(service.connectNearbyDevice(device))
        verify(bridge, times(2)).connectNativePlayback(selectedAddress(), anyCurrent())
    }

    @Test fun duplicateTapsAreIgnoredAndCancellationReleasesThePendingRequest() = runBlocking<Unit> {
        bridgeState.value = BridgeState(connected = false)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { service.connectNearbyDevice(device) }
        assertTrue(first.isActive)
        assertFalse(service.connectNearbyDevice(device))
        verify(bridge).connect()
        first.cancelAndJoin()
        bridgeState.value = BridgeState(connected = true)
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { publishOutput(); true }
        assertTrue(service.connectNearbyDevice(device))
    }

    @Test fun losingTheSelectedDeviceWhileBindingStopsBeforeProfileConnection() = runBlocking<Unit> {
        bridgeState.value = BridgeState(connected = false)
        var result: Boolean? = null
        val request = launch(start = CoroutineStart.UNDISPATCHED) { result = service.connectNearbyDevice(device) }
        devices.value = emptyMap()
        bridgeState.value = BridgeState(connected = true)
        request.join()
        assertEquals(false, result)
        verify(bridge, never()).connectNativePlayback(anyString(), anyCurrent())
    }

    @Test fun theShellOwnerIsRetiredIfACallStartsDuringConnection() = runBlocking<Unit> {
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { invocation ->
            `when`(audio.mode).thenReturn(AudioManager.MODE_IN_CALL)
            assertFalse(invocation.getArgument<() -> Boolean>(1).invoke())
            true
        }
        assertFalse(service.connectNearbyDevice(device))
    }

    @Test fun anAuthenticatedPairWithAColdServiceCacheFetchesOnlyItsSelectedDeviceBeforeConnecting() = runBlocking<Unit> {
        var uuids: Array<ParcelUuid>? = null
        `when`(bluetooth.uuids).thenAnswer { uuids }
        `when`(bluetooth.fetchUuidsWithSdp()).thenReturn(true)
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer {
            assertTrue(uuids?.contains(aacpUuid) == true)
            publishOutput()
            true
        }
        launch { delay(60); uuids = arrayOf(aacpUuid) }
        assertTrue(service.connectNearbyDevice(device))
        verify(bluetooth).fetchUuidsWithSdp()
        verify(bridge).connectNativePlayback(selectedAddress(), anyCurrent())
    }

    @Test fun aRejectedServiceDiscoveryCannotBypassTheShellAirPodsCheck() = runBlocking<Unit> {
        connectionState.value = ConnectionState.DISCONNECTED
        `when`(bluetooth.uuids).thenReturn(null)
        `when`(bluetooth.fetchUuidsWithSdp()).thenReturn(false)
        assertFalse(service.connectNearbyDevice(device))
        verify(bluetooth).fetchUuidsWithSdp()
        verifyNoInteractions(bridge)
        verify(device, never()).connect(anyCurrent(), anyPending())
    }

    @Test fun anOwnerRemovedDuringServiceDiscoveryCannotConnectEvenIfTheUuidAppears() = runBlocking<Unit> {
        var uuids: Array<ParcelUuid>? = null
        `when`(bluetooth.uuids).thenAnswer { uuids }
        `when`(bluetooth.fetchUuidsWithSdp()).thenAnswer {
            uuids = arrayOf(aacpUuid)
            devices.value = emptyMap()
            true
        }
        assertFalse(service.connectNearbyDevice(device))
        verify(bridge, never()).connectNativePlayback(anyString(), anyCurrent())
    }

    @Test fun cancellingAServiceDiscoveryDoesNotIssueAMediaConnection() = runBlocking<Unit> {
        `when`(bluetooth.uuids).thenReturn(null)
        `when`(bluetooth.fetchUuidsWithSdp()).thenReturn(true)
        val request = launch(start = CoroutineStart.UNDISPATCHED) { service.connectNearbyDevice(device) }
        assertTrue(request.isActive)
        request.cancelAndJoin()
        verify(bridge, never()).connectNativePlayback(anyString(), anyCurrent())
    }

    @Test fun aVerifiedBluetoothConnectionStaysPendingUntilAndroidPublishesItsMediaOutput() = runBlocking<Unit> {
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenReturn(true)
        var result: Boolean? = null
        val request = launch(start = CoroutineStart.UNDISPATCHED) { result = service.connectNearbyDevice(device) }
        delay(180)
        assertNull("Profile success must not briefly hide Connect before its observable output is ready", result)
        assertTrue(request.isActive)
        assertFalse(service.isNearbyDeviceAudioConnected(device))
        publishOutput()
        request.join()
        assertEquals(true, result)
        assertTrue(service.isNearbyDeviceAudioConnected(device))
        verify(bridge).connectNativePlayback(selectedAddress(), anyCurrent())
    }

    @Test fun aBluetoothProfileWithoutAPublishedMediaOutputCannotReportSuccess() = runBlocking<Unit> {
        val speaker = mock(AudioDeviceInfo::class.java)
        `when`(speaker.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        `when`(speaker.address).thenReturn("")
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(speaker))
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenReturn(true)
        assertFalse(service.connectNearbyDevice(device))
        assertFalse(service.isNearbyDeviceAudioConnected(device))
        val diagnostic = ShadowLog.getLogsForTag("NearbyTakeover").last { it.msg.startsWith("Output unavailable:") }.msg
        assertTrue(diagnostic.contains("ownerFailure=null retired=null"))
        assertTrue(diagnostic.contains("type=2:selected=false:addressPresent=false"))
    }

    @Test fun lateAudioPolicyPublicationCanFinishWithoutAnotherConnectionAttempt() = runBlocking<Unit> {
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenReturn(true)
        launch { delay(1_100); publishOutput() }
        assertTrue(service.connectNearbyDevice(device))
        verify(bridge).connectNativePlayback(selectedAddress(), anyCurrent())
    }

    @Test fun cancellingTheOutputWaitRetiresTheRequestAndAllowsAFreshAttempt() = runBlocking<Unit> {
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenReturn(true)
        val request = launch(start = CoroutineStart.UNDISPATCHED) { service.connectNearbyDevice(device) }
        assertTrue(request.isActive)
        request.cancelAndJoin()
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenAnswer { publishOutput(); true }
        assertTrue(service.connectNearbyDevice(device))
        verify(bridge, times(2)).connectNativePlayback(selectedAddress(), anyCurrent())
    }

    @Test fun losingTheOwnerWhileWaitingForOutputStopsBeforeReportingSuccess() = runBlocking<Unit> {
        `when`(bridge.connectNativePlayback(anyString(), anyCurrent())).thenReturn(true)
        var result: Boolean? = null
        val request = launch(start = CoroutineStart.UNDISPATCHED) { result = service.connectNearbyDevice(device) }
        assertTrue(request.isActive)
        devices.value = emptyMap()
        publishOutput()
        request.join()
        assertEquals(false, result)
        assertFalse(service.isNearbyDeviceAudioConnected(device))
        assertTrue(ShadowLog.getLogsForTag("NearbyTakeover").any { it.msg.startsWith("Retired: reason=device replaced") })
    }

    @Test fun anUnknownNearbyOpeningOffersConnectImmediatelyWithoutStartingAnyConnection() {
        assertFalse(caseLid(false))
        assertFalse(service.shouldDeferNearbyConnect(device))
        assertTrue(caseLid(true))
        assertFalse(service.shouldDeferNearbyConnect(device))
        verifyNoInteractions(bridge)
    }

    @Test fun aLocallyClosedCaseGivesItsNextOpeningOneBoundedPresentationGrace() {
        publishOutput()
        caseLid(false)
        clearOutput()
        assertTrue(caseLid(true))
        assertTrue(service.shouldDeferNearbyConnect(device))
        assertFalse("Deferral must never claim the audio output is already connected", service.isNearbyDeviceAudioConnected(device))
        advance(3500)
        assertFalse("Repeated authenticated open reports cannot restart the grace", caseLid(true))
        advance(500)
        assertFalse(service.shouldDeferNearbyConnect(device))
        verifyNoInteractions(bridge)
    }

    @Test fun aForeignCaseCloseReplacesAnEarlierLocalOpeningHint() {
        publishOutput()
        caseLid(false)
        clearOutput()
        caseLid(true)
        assertTrue(service.shouldDeferNearbyConnect(device))
        advance(4000)
        caseLid(false)
        caseLid(true)
        assertFalse(service.shouldDeferNearbyConnect(device))
    }

    @Test fun rapidCaseCyclesCarryOnlyTheOriginalDeadlineAfterTheLocalOrderingHintExpires() {
        publishOutput()
        mediaState(BluetoothProfile.STATE_CONNECTED)
        clearOutput()
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        caseLid(false)
        caseLid(true)
        assertTrue(service.shouldDeferNearbyConnect(device))
        advance(2500)
        caseLid(false)
        assertTrue("The visible island cannot expose Connect between a close and its next open", service.shouldDeferNearbyConnect(device))
        advance(100)
        caseLid(true)
        advance(1399)
        assertTrue(service.shouldDeferNearbyConnect(device))
        advance(1)
        assertFalse("Rapid cycles cannot extend the initial four-second deadline", service.shouldDeferNearbyConnect(device))
        caseLid(false)
        caseLid(true)
        assertFalse("Old local ownership cannot make later case cycles renew an expired grace", service.shouldDeferNearbyConnect(device))
    }

    @Test fun aNativeReconnectFailureWhileClosedRetiresItsGraceAndFreshOpeningEligibility() {
        publishOutput()
        mediaState(BluetoothProfile.STATE_CONNECTED)
        clearOutput()
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        caseLid(false)
        caseLid(true)
        mediaState(BluetoothProfile.STATE_CONNECTING)
        caseLid(false)
        assertTrue(service.shouldDeferNearbyConnect(device))
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse("A native failure must release even a grace carried by a closed case", service.shouldDeferNearbyConnect(device))
        caseLid(true)
        assertFalse("Reopening cannot revive the known failed attempt", service.shouldDeferNearbyConnect(device))
        caseLid(false)
        caseLid(true)
        assertFalse("The failed attempt must also retire the recent-local ordering hint", service.shouldDeferNearbyConnect(device))
    }

    @Test fun anAuthenticatedCaseCloseReachesTheIslandBeforeItsDisconnectedBatteriesAreMerged() {
        advance(2_000)
        val irk = byteArrayOf(0x79, 4, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
            0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)
        val key = ByteArray(16) { (it + 1).toByte() }
        val oldBattery = setOf(Battery(BatteryComponent.LEFT, 95, BatteryStatus.CHARGING))
        val state = MutableStateFlow(AppleState(battery = oldBattery,
            magicKeys = mapOf(MagicKeyType.IRK to irk, MagicKeyType.ENC_KEY to key)))
        `when`(device.state).thenReturn(state)
        val window = app.islandWindow
        var closed = false
        doAnswer {
            assertEquals("The outgoing island must retain its existing payload until close starts", oldBattery, state.value.battery)
            closed = true
            null
        }.`when`(window).observePairedCaseLid(selectedAddress(), eq(false), anyLong())
        doAnswer { invocation ->
            assertTrue("The authenticated close must precede its battery update", closed)
            state.value = state.value.copy(battery = invocation.getArgument(0))
            null
        }.`when`(device).receiveNearbyBattery(any<Set<Battery>>() ?: emptySet())
        val manufacturer = ByteArray(27).apply {
            this[0] = 7; this[1] = 25; this[2] = 1; this[3] = 0x27; this[4] = 0x20
            this[5] = 0x64; this[8] = 8
            val plain = ByteArray(16).apply { this[1] = 127; this[2] = 127; this[3] = 127 }
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
                .doFinal(plain).copyInto(this, 11)
        }
        val advertised = mock(BluetoothDevice::class.java)
        `when`(advertised.address).thenReturn("5A:16:2B:91:D1:CD")
        val record = mock(ScanRecord::class.java)
        `when`(record.getManufacturerSpecificData(0x004c)).thenReturn(manufacturer)
        val result = mock(ScanResult::class.java)
        val observed = SystemClock.elapsedRealtimeNanos()
        `when`(result.device).thenReturn(advertised)
        `when`(result.scanRecord).thenReturn(record)
        `when`(result.timestampNanos).thenReturn(observed)
        val handle = LibrePodsService::class.java.getDeclaredMethod("handleScanResult", ScanResult::class.java)
            .apply { isAccessible = true }
        handle.invoke(service, result)
        assertTrue(closed)
        assertEquals(3, state.value.battery.size)
        assertTrue("The authoritative battery report must still reach device state", state.value.battery.all {
            it.status == BatteryStatus.DISCONNECTED
        })
        val order = inOrder(window, device)
        order.verify(window).observePairedCaseLid(address.value, false, observed)
        order.verify(device).receiveNearbyBattery(state.value.battery)
        order.verify(window).updatePairedBattery(device, AirPodsModel.AIRPODS_PRO_3)
        clearInvocations(window)
        `when`(result.timestampNanos).thenReturn(observed - 1)
        handle.invoke(service, result)
        verifyNoInteractions(window)
    }

    @Test fun aCloseArrivingJustAfterLocalDisconnectionRetainsOnlyATwoSecondOrderingHint() {
        mediaState(BluetoothProfile.STATE_CONNECTED)
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        advance(1500)
        caseLid(false)
        advance(3000)
        caseLid(true)
        assertTrue(service.shouldDeferNearbyConnect(device))
    }

    @Test fun aLateCaseCloseCannotReuseOldLocalConnectionEvidence() {
        mediaState(BluetoothProfile.STATE_CONNECTED)
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        advance(2000)
        caseLid(false)
        caseLid(true)
        assertFalse(service.shouldDeferNearbyConnect(device))
    }

    @Test fun nativeConnectingAndPendingOutputHintsHaveTheirOwnBoundedDeadlines() {
        mediaState(BluetoothProfile.STATE_CONNECTING)
        assertTrue(service.shouldDeferNearbyConnect(device))
        advance(17_000)
        mediaState(BluetoothProfile.STATE_CONNECTING)
        mediaState(null)
        mediaState(99)
        advance(1000)
        assertFalse("Repeated or malformed profile events cannot renew a stalled attempt", service.shouldDeferNearbyConnect(device))
        mediaState(BluetoothProfile.STATE_CONNECTED)
        assertTrue(service.shouldDeferNearbyConnect(device))
        advance(1000)
        assertFalse(service.shouldDeferNearbyConnect(device))
    }

    @Test fun aFailedNativeReconnectReleasesTheActionWithoutWaitingForTheCaseGrace() {
        publishOutput()
        caseLid(false)
        clearOutput()
        caseLid(true)
        mediaState(BluetoothProfile.STATE_CONNECTING)
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(service.shouldDeferNearbyConnect(device))
    }

    @Test fun observingAUsableOutputConsumesTheHintSoAnImmediateRemoteTakeoverCanOfferConnect() {
        publishOutput()
        caseLid(false)
        clearOutput()
        caseLid(true)
        mediaState(BluetoothProfile.STATE_CONNECTING)
        mediaState(BluetoothProfile.STATE_CONNECTED)
        publishOutput()
        assertFalse(service.shouldDeferNearbyConnect(device))
        clearOutput()
        mediaState(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse("A consumed four-second grace cannot return after the connection is lost", service.shouldDeferNearbyConnect(device))
    }

    @Test fun bluetoothOffAndUnpairingClearPendingHistory() {
        mediaState(BluetoothProfile.STATE_CONNECTING)
        assertTrue(service.shouldDeferNearbyConnect(device))
        receive(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        assertFalse(service.shouldDeferNearbyConnect(device))
        mediaState(BluetoothProfile.STATE_CONNECTING)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        receive(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, bluetooth))
        assertFalse(service.shouldDeferNearbyConnect(device))
        assertTrue((field("nearbyMedia").get(service) as Map<*, *>).isEmpty())
        assertTrue((field("nearbyCases").get(service) as Map<*, *>).isEmpty())
    }

    @Test fun replacingTheDeviceInstanceRetiresItsHintsAndRejectsOldLidObservations() {
        publishOutput()
        caseLid(false)
        clearOutput()
        caseLid(true)
        val replacement = mock(AppleDevice::class.java)
        `when`(replacement.macAddress).thenReturn(address)
        `when`(replacement.bluetoothDevice).thenReturn(bluetooth)
        devices.value = mapOf(address to replacement)
        assertFalse(service.shouldDeferNearbyConnect(device))
        assertFalse(service.shouldDeferNearbyConnect(replacement))
        assertFalse(caseLid(false))
    }

    private fun caseLid(open: Boolean): Boolean = LibrePodsService::class.java
        .getDeclaredMethod("observeNearbyCase", AppleDevice::class.java, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(service, device, open) as Boolean

    private suspend fun attachNativeAudio(): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        LibrePodsService::class.java.getDeclaredMethod("onDeviceConnected", BluetoothDevice::class.java, Continuation::class.java)
            .apply { isAccessible = true }.invoke(service, bluetooth, continuation)
    }

    private fun mediaState(state: Int?) {
        val intent = Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, bluetooth)
        if (state != null) intent.putExtra(BluetoothProfile.EXTRA_STATE, state)
        receive(intent)
    }

    private fun receive(intent: Intent) = (field("bluetoothReceiver").get(service) as BroadcastReceiver).onReceive(service, intent)
    private fun advance(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
    private fun clearOutput() { `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(emptyArray()) }

    private fun publishOutput() {
        val output = mock(AudioDeviceInfo::class.java)
        `when`(output.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(output.address).thenReturn(address.value)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(output))
    }

    private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
    private fun anyPending(): (BluetoothSocket) -> Unit = any<(BluetoothSocket) -> Unit>() ?: {}
    private fun anySocket(): BluetoothSocket = any<BluetoothSocket>() ?: socketMatcherFallback
    private fun selectedAddress(): String = eq(address.value) ?: address.value
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
