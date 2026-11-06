package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.IUsbHidConnection
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DongleUsbTest {
    private val descriptor = bytes(0x06, 0x31, 0xff, 0x09, 1, 0xa1, 1, 0x85, 2,
        0x75, 8, 0x95, 63, 0x09, 1, 0xb1, 2, 0x85, 3, 0x09, 2, 0xb1, 2, 0xc0)
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    @Test fun bothPersonalitiesAreCandidatesButTheVendorCollectionMustMatch() {
        assertTrue(DongleUsb.candidate(0xcafe, 0x4005))
        assertTrue(DongleUsb.candidate(0xcafe, 0x4015))
        assertFalse(DongleUsb.candidate(0xcafe, 0x4001))
        assertFalse(DongleUsb.candidate(0x1234, 0x4015))
        assertTrue(DongleUsb.vendorReports(descriptor))
        assertFalse(DongleUsb.vendorReports(descriptor.copyOf().also { it[1] = 0x0c }))
        assertFalse(DongleUsb.vendorReports(descriptor.copyOf().also { it[12] = 62 }))
        assertFalse(DongleUsb.vendorReports(descriptor.copyOf().also { it[18] = 4 }))
        assertFalse(DongleUsb.vendorReports(descriptor.dropLast(1).toByteArray()))
        assertFalse(DongleUsb.vendorReports(bytes(0xfe, 64, 0)))
    }

    @Test fun unboundEndpointZeroUsesActualInterfaceWithoutClaimingTheInterface() {
        val connection = mock(UsbDeviceConnection::class.java)
        val intf = mock(UsbInterface::class.java)
        `when`(intf.id).thenReturn(7)
        `when`(connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(ByteArray::class.java), anyInt(), anyInt()))
            .thenAnswer { call ->
                val buffer = call.arguments[4] as ByteArray
                if (call.arguments[0] == 0xa1) DongleProtocol.header(2).copyInto(buffer)
                64
            }
        val link = DongleUsb.Link(connection, intf)
        assertEquals(64, link.exchange(1, 2, byteArrayOf()).size)
        link.exchange(2, 3, DongleProtocol.header(3))
        verify(connection).controlTransfer(eq(0xa1), eq(1), eq(0x302), eq(7), any(ByteArray::class.java), eq(64), eq(1000))
        verify(connection).controlTransfer(eq(0x21), eq(9), eq(0x303), eq(7), any(ByteArray::class.java), eq(64), eq(1000))
        verify(connection, never()).claimInterface(any(UsbInterface::class.java), anyBoolean())
        link.close(); verify(connection).close()
    }

    @Test fun reEnumerationRetainsSerialAndNeverChoosesAnotherKnownBoard() {
        fun device(serial: String?, path: String) = mock(UsbDevice::class.java).also {
            `when`(it.serialNumber).thenReturn(serial); `when`(it.deviceName).thenReturn(path)
        }
        val first = device("first", "/dev/bus/001/001")
        val returned = device("first", "/dev/bus/001/003")
        val other = device("other", "/dev/bus/001/002")
        assertSame(returned, DongleUsb.resolve(listOf(other, returned), DongleUsb.key(first)))
        assertNull(DongleUsb.resolve(listOf(other), DongleUsb.key(first)))
        assertNull(DongleUsb.resolve(listOf(other), "usb:path:old-path"))
        val unpermitted = device(null, "/dev/bus/001/004")
        assertSame(unpermitted, DongleUsb.resolve(listOf(unpermitted), DongleUsb.key(first)))
        assertNull(DongleUsb.resolve(listOf(unpermitted, device(null, "different")), DongleUsb.key(first)))
    }

    private data class UsbOpen(val manager: UsbManager, val device: UsbDevice,
        val connection: UsbDeviceConnection, val intf: UsbInterface)
    private fun usbOpen(descriptorBusy: Boolean, featureBusy: Boolean = false,
        reportDescriptor: ByteArray = descriptor): UsbOpen {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val intf = mock(UsbInterface::class.java)
        `when`(device.vendorId).thenReturn(0xcafe); `when`(device.productId).thenReturn(0x4015)
        `when`(device.interfaceCount).thenReturn(1); `when`(device.getInterface(0)).thenReturn(intf)
        `when`(intf.id).thenReturn(7); `when`(intf.interfaceClass).thenReturn(UsbConstants.USB_CLASS_HID)
        `when`(manager.hasPermission(device)).thenReturn(true); `when`(manager.openDevice(device)).thenReturn(connection)
        `when`(connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(ByteArray::class.java), anyInt(), anyInt()))
            .thenAnswer { call ->
                val buffer = call.arguments[4] as ByteArray
                if (call.arguments[0] == 0x81) {
                    if (descriptorBusy) -1 else { reportDescriptor.copyInto(buffer); reportDescriptor.size }
                } else if (featureBusy) -1
                else { if (call.arguments[0] == 0xa1) status().copyInto(buffer); 64 }
            }
        return UsbOpen(manager, device, connection, intf)
    }
    private fun sharedControls(reportDescriptor: ByteArray = descriptor) = mock(IUsbHidConnection::class.java).also {
        `when`(it.descriptor()).thenReturn(reportDescriptor)
        `when`(it.exchange(eq(1), eq(2), any(ByteArray::class.java))).thenReturn(status())
    }

    @Test fun aBusyKernelMediaDriverUsesSharedControlsWithoutClaimingOrDetaching() = runBlocking {
        val usb = usbOpen(descriptorBusy = true)
        val shared = sharedControls()
        var closed = false
        doAnswer { closed = true; null }.`when`(usb.connection).close()
        val link = DongleUsb.open(usb.manager, usb.device) { intf ->
            assertTrue(closed); assertSame(usb.intf, intf); shared
        }
        verify(shared).descriptor()
        verify(shared).exchange(eq(1), eq(2), argThat<ByteArray> { it.isEmpty() })
        verify(shared, never()).exchange(eq(2), anyInt(), any(ByteArray::class.java))
        verify(usb.connection, never()).claimInterface(any(UsbInterface::class.java), anyBoolean())
        verify(usb.connection, never()).releaseInterface(any(UsbInterface::class.java))
        link.close(); link.close(); verify(shared).close()
    }

    @Test fun theFirstBusyGetReportCanFallBackButAnUncertainWriteIsNeverReplayed() = runBlocking {
        val usb = usbOpen(descriptorBusy = false, featureBusy = true)
        val shared = sharedControls()
        var opens = 0
        val link = DongleUsb.open(usb.manager, usb.device) { opens++; shared }
        assertEquals(1, opens)
        verify(usb.connection).controlTransfer(eq(0xa1), eq(1), eq(0x302), eq(7), any(ByteArray::class.java), eq(64), eq(1000))
        `when`(shared.exchange(eq(2), eq(3), any(ByteArray::class.java)))
            .thenThrow(android.os.RemoteException("connection ended after write"))
        try { link.exchange(2, 3, DongleProtocol.header(3)); fail("Expected uncertain transfer failure") }
        catch (_: IOException) { }
        assertEquals(1, opens)
        verify(shared).exchange(eq(2), eq(3), any(ByteArray::class.java))
        link.close()
    }

    @Test fun aWorkingUsbfsReadNeverOpensSharedControlsEvenAfterAWriteFailure() = runBlocking {
        val usb = usbOpen(descriptorBusy = false)
        val link = DongleUsb.open(usb.manager, usb.device) { fail("USBFS already works"); null }
        `when`(usb.connection.controlTransfer(eq(0x21), eq(9), eq(0x303), eq(7), any(ByteArray::class.java), eq(64), eq(1000)))
            .thenReturn(-1)
        try { link.exchange(2, 3, DongleProtocol.header(3)); fail("Expected write failure") }
        catch (_: IOException) { }
        verify(usb.connection).controlTransfer(eq(0x21), eq(9), eq(0x303), eq(7), any(ByteArray::class.java), eq(64), eq(1000))
        link.close()
    }

    @Test fun rejectedSharedDescriptorsAndUnchangedReportsCloseTheirHandles() = runBlocking {
        for (invalidDescriptor in listOf(true, false)) {
            val usb = usbOpen(descriptorBusy = true)
            val shared = sharedControls(if (invalidDescriptor) descriptor.copyOf().also { it[1] = 0x0c } else descriptor)
            if (!invalidDescriptor) `when`(shared.exchange(eq(1), eq(2), any(ByteArray::class.java))).thenReturn(ByteArray(64))
            try { DongleUsb.open(usb.manager, usb.device) { shared }; fail("Expected rejected shared controls") }
            catch (_: DongleUsbControlsUnavailable) { }
            verify(shared).close()
            if (invalidDescriptor) verify(shared, never()).exchange(anyInt(), anyInt(), any(ByteArray::class.java))
        }
    }

    @Test fun missingRootExplainsTheMediaKeyDriverAndConsumerOnlyDescriptorsDoNotFallBack() = runBlocking {
        val busy = usbOpen(descriptorBusy = true)
        try { DongleUsb.open(busy.manager, busy.device); fail("Expected root requirement") }
        catch (error: DongleUsbControlsUnavailable) {
            assertTrue(error.message!!.contains("Root Bluetooth access")); assertTrue(error.message!!.contains("media-key driver"))
        }
        val failed = usbOpen(descriptorBusy = true)
        val rootFailure = android.os.RemoteException("shared helper unavailable")
        try { DongleUsb.open(failed.manager, failed.device) { throw rootFailure }; fail("Expected helper failure") }
        catch (error: DongleUsbControlsUnavailable) {
            assertSame(rootFailure, error.cause)
            assertTrue(error.message!!.contains("shared USB helper"))
            assertFalse(error.message!!.contains("Root Bluetooth access"))
        }
        val unsupported = usbOpen(descriptorBusy = false, reportDescriptor = descriptor.copyOf().also { it[1] = 0x0c })
        try {
            DongleUsb.open(unsupported.manager, unsupported.device) { fail("Known consumer descriptor is not a transport failure"); null }
            fail("Expected unsupported descriptor")
        } catch (_: IOException) { }
        verify(unsupported.connection).close()
    }

    @Test fun sharedFeaturesRejectInvalidRequestsAndResponsesBeforeFurtherIo() {
        val shared = sharedControls()
        val link = DongleUsb.Link(shared)
        for (invalid in listOf(DongleProtocol.header(3).copyOf(63), DongleProtocol.header(3).also { it[5] = 2 })) {
            try { link.exchange(2, 3, invalid); fail("Expected malformed request") }
            catch (_: IllegalArgumentException) { }
        }
        verify(shared, never()).exchange(anyInt(), anyInt(), any(ByteArray::class.java))
        `when`(shared.exchange(eq(1), eq(2), any(ByteArray::class.java))).thenReturn(DongleProtocol.header(2).copyOf(63))
        try { link.exchange(1, 2, byteArrayOf()); fail("Expected short response") }
        catch (_: IllegalArgumentException) { }
        link.close()
    }

    @Test fun retiringSharedControlsDoesNotWaitForABinderExchangeAndRejectsItsLateRead() {
        val shared = sharedControls()
        val link = DongleUsb.Link(shared)
        var closedDuringExchange = false
        var closing: Thread? = null
        `when`(shared.exchange(eq(1), eq(2), any(ByteArray::class.java))).thenAnswer {
            val completed = CountDownLatch(1)
            closing = Thread { link.close(); completed.countDown() }.also(Thread::start)
            closedDuringExchange = completed.await(1, TimeUnit.SECONDS)
            status()
        }
        try {
            try { link.exchange(1, 2, byteArrayOf()); fail("Expected retired read") }
            catch (_: IOException) { }
            assertTrue("Retirement must not take a lock held by a root Binder exchange", closedDuringExchange)
            verify(shared).close()
        } finally { closing?.join(2000); link.close() }
    }

    private fun controllerWith(state: DongleState, devices: HashMap<String, UsbDevice> = hashMapOf()): DongleController {
        val controller = DongleController(RuntimeEnvironment.getApplication())
        @Suppress("UNCHECKED_CAST")
        val current = DongleController::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<DongleState>
        current.value = state
        val manager = mock(UsbManager::class.java)
        `when`(manager.deviceList).thenReturn(devices)
        DongleController::class.java.getDeclaredField("usbManager").apply { isAccessible = true }.set(controller, manager)
        DongleController::class.java.getDeclaredField("bridge\$delegate").apply { isAccessible = true }
            .set(controller, lazyOf(mock(ShizukuAudioClient::class.java)))
        return controller
    }

    @Test fun automaticNativeIsBlockedDuringBluetoothControlStartupButNotIdleBackoff() {
        controllerWith(DongleState(selected = true, busy = true)).use { assertTrue(it.inhibitsAutomaticNative()) }
        controllerWith(DongleState(selected = true)).use { assertFalse(it.inhibitsAutomaticNative()) }
        controllerWith(DongleState(selected = true, connected = true)).use { assertTrue(it.inhibitsAutomaticNative()) }
    }

    @Test fun controlOnlyOrMultipleAttachedUsbBoardsBlockAutomaticNativeBeforePermission() {
        fun device(pid: Int) = mock(UsbDevice::class.java).also {
            `when`(it.vendorId).thenReturn(0xcafe); `when`(it.productId).thenReturn(pid)
        }
        val one = device(0x4005)
        controllerWith(DongleState(), hashMapOf("one" to one)).use { assertTrue(it.inhibitsAutomaticNative()) }
        controllerWith(DongleState(), hashMapOf("one" to one, "two" to device(0x4015))).use { assertTrue(it.inhibitsAutomaticNative()) }
        controllerWith(DongleState()).use { assertFalse(it.inhibitsAutomaticNative()) }
    }

    @Test fun usbReEnumerationKeepsNativeBlockedButARealUnplugExpiresWithoutRenewing() {
        fun device(pid: Int) = mock(UsbDevice::class.java).also {
            `when`(it.vendorId).thenReturn(0xcafe); `when`(it.productId).thenReturn(pid)
        }
        val devices = hashMapOf("one" to device(0x4005))
        controllerWith(DongleState(selected = true, usb = true), devices).use {
            assertTrue(it.inhibitsAutomaticNative())
            devices.clear()
            assertTrue(it.inhibitsAutomaticNative())
            ShadowSystemClock.advanceBy(Duration.ofMillis(250))
            assertTrue(it.inhibitsAutomaticNative())
            devices["returned"] = device(0x4015)
            assertTrue(it.inhibitsAutomaticNative())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            assertTrue(it.inhibitsAutomaticNative())
            devices.clear()
            assertTrue(it.inhibitsAutomaticNative())
            ShadowSystemClock.advanceBy(Duration.ofMillis(1999))
            assertTrue(it.inhibitsAutomaticNative())
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            assertFalse(it.inhibitsAutomaticNative())
        }
    }

    @Test fun ownedUsbDetachStartsTheGraceBeforeAnyNativeAdmissionCheck() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE).edit()
            .putString("address", "usb:serial:one").commit()
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0xcafe); `when`(device.productId).thenReturn(0x4015)
        `when`(device.deviceName).thenReturn("/dev/bus/001/001")
        controllerWith(DongleState(selected = true, usb = true)).use {
            DongleController::class.java.getDeclaredField("activeUsbPath").apply { isAccessible = true }
                .set(it, device.deviceName)
            val events = DongleController::class.java.getDeclaredField("usbEvents").apply { isAccessible = true }
                .get(it) as BroadcastReceiver
            val detached = Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
            events.onReceive(context, detached)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1999))
            assertTrue(it.inhibitsAutomaticNative())
            // The retired path cannot make a duplicate detach extend the deadline.
            events.onReceive(context, detached)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            assertFalse(it.inhibitsAutomaticNative())
        }
    }

    @Test fun aSelectedBluetoothAudioEndpointKeepsControlBackoffFromOpeningNativeAirPods() {
        val context = RuntimeEnvironment.getApplication()
        val audio = mock(AudioManager::class.java)
        val route = mock(AudioDeviceInfo::class.java)
        `when`(route.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(route.address).thenReturn("00:11:22:33:44:55")
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(route))
        val shadow = Shadow.extract<ShadowContextImpl>(context.baseContext)
        val previous = context.getSystemService(AudioManager::class.java)
        shadow.setSystemService(Context.AUDIO_SERVICE, audio)
        context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE).edit()
            .putString("address", "00:11:22:33:44:55").commit()
        try {
            controllerWith(DongleState(selected = true)).use {
                assertTrue(it.inhibitsAutomaticNative())
                `when`(route.address).thenReturn("00:11:22:33:44:66")
                assertFalse(it.inhibitsAutomaticNative())
                `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(emptyArray())
                assertFalse(it.inhibitsAutomaticNative())
            }
        } finally { shadow.setSystemService(Context.AUDIO_SERVICE, previous) }
    }

    private fun status(generation: Long = 3, sequence: Long = 0, result: Int = 0, opcode: Int = 0) = DongleProtocol.header(2).also {
        DongleProtocol.put32(it, 8, generation); it[42] = 5
        DongleProtocol.put32(it, 36, sequence); it[40] = result.toByte(); it[41] = opcode.toByte()
    }

    @Test fun acceptedPairingCanChangeGenerationAndResumesTheUsbCommandSequence() = runBlocking {
        var report = status(sequence = 49)
        var writes = 0
        val client = DongleSession({ operation, id, payload ->
            assertEquals(2, id)
            if (operation == 2) {
                writes++; assertEquals(50L, DongleProtocol.u32(payload, 18))
                assertEquals(3L, DongleProtocol.u32(payload, 8))
                report = status(4, 50, 1, 2)
            }
            report
        })
        assertEquals(4L, client.command(2).generation)
        assertEquals(1, writes)
    }

    @Test fun aTimedOutCommandIsNeverReplayed() = runBlocking {
        var clock = 0L; var writes = 0
        val client = DongleSession({ operation, _, _ -> if (operation == 2) writes++; status() },
            now = { clock }, wait = { clock += 1000 })
        try { client.command(1); fail("Expected timeout") } catch (_: java.io.IOException) { }
        assertEquals(1, writes)
    }

    @Test fun reopenedUsbSettingsContinueAfterThePreviousTransaction() = runBlocking {
        var report = DongleProtocol.header(3).also { DongleProtocol.put32(it, 12, 900); it[21] = 3 }
        var writes = 0
        val client = DongleSession({ operation, id, payload ->
            if (id == 2) status() else {
                if (operation == 2) {
                    writes++; assertEquals(901L, DongleProtocol.u32(payload, 12))
                    report = DongleProtocol.header(3).also {
                        DongleProtocol.put32(it, 8, 3); DongleProtocol.put32(it, 12, 901)
                        if (payload[7] == 6.toByte()) it[21] = 3
                        else { it[21] = 1; it[18] = payload[20] }
                    }
                }
                report
            }
        }, resumeSequences = true)
        assertTrue(client.transact(7, byteArrayOf(2)).isEmpty())
        assertEquals(2, writes)
    }
}
