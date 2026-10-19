package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import me.kavishdevar.librepods.bluetooth.NearbyPairingController
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NearbyInvitationProximityTest {
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val pairing = mock(NearbyPairingController::class.java)
    private val open = NearbyAirPods.Event(0x2720, true)
    private val local = "5A:16:2B:91:D1:CD"
    private val foreign = "5A:16:2B:91:D1:CE"

    init {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        ReflectionHelpers.setField(service, "nearbyPairing", pairing)
        advance(2_000)
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
    }

    @Test fun weakAndUnavailableReportsCannotConsumeAnInvitationBeforeSustainedProximity() {
        for (rssi in listOf(-100, -56, 127)) {
            scan(rssi)
            advance(500)
            scan(rssi)
            advance(1)
        }
        verifyNoInteractions(pairing)
        scan(-55)
        advance(349)
        scan(-54)
        verifyNoInteractions(pairing)
        advance(1)
        val admitted = scan(-53)
        verify(pairing).onNearbyDevice(open, admitted)
        advance(500)
        scan(-50)
        verifyNoMoreInteractions(pairing)
    }

    @Test fun anotherAdvertiserCannotCompleteOrCloseTheInvitation() {
        scan(-50)
        advance(350)
        scan(-50, source = foreign)
        verifyNoInteractions(pairing)
        advance(350)
        val admitted = scan(-50, source = foreign)
        verify(pairing).onNearbyDevice(open, admitted)
        advance(1)
        scan(-90, lidOpen = false)
        verifyNoMoreInteractions(pairing)
        advance(1)
        val closed = scan(-90, lidOpen = false, source = foreign)
        verify(pairing).onNearbyDevice(open.copy(lidOpen = false), closed)
        verifyNoMoreInteractions(pairing)
    }

    @Test fun signalLossCannotCancelAnActivePairingAttempt() {
        scan(-50)
        advance(350)
        val admitted = scan(-50)
        verify(pairing).onNearbyDevice(open, admitted)
        ReflectionHelpers.setField(service, "pairingModel", open.modelId)
        advance(500)
        scan(-100)
        advance(500)
        scan(127)
        verifyNoMoreInteractions(pairing)
    }

    @Test fun restartingTheScannerRequiresFreshProximityConfirmation() {
        scan(-50)
        advance(350)
        scan(-50)
        LibrePodsService::class.java.getDeclaredMethod("stopBleScanner")
            .apply { isAccessible = true }.invoke(service)
        clearInvocations(pairing)
        advance(1)
        scan(-50)
        verifyNoInteractions(pairing)
        advance(350)
        val admitted = scan(-50)
        verify(pairing).onNearbyDevice(open, admitted)
        verifyNoMoreInteractions(pairing)
    }

    private fun scan(rssi: Int, lidOpen: Boolean = true, source: String = local): Long {
        val manufacturer = ByteArray(27).apply {
            this[0] = 7; this[1] = 25; this[2] = 1; this[3] = 0x27; this[4] = 0x20
            this[5] = 0x35; this[8] = if (lidOpen) 0x31 else 0x38
        }
        val advertised = mock(BluetoothDevice::class.java)
        `when`(advertised.address).thenReturn(source)
        val record = mock(ScanRecord::class.java)
        `when`(record.getManufacturerSpecificData(0x004c)).thenReturn(manufacturer)
        val result = mock(ScanResult::class.java)
        val observed = SystemClock.elapsedRealtimeNanos()
        `when`(result.device).thenReturn(advertised)
        `when`(result.scanRecord).thenReturn(record)
        `when`(result.rssi).thenReturn(rssi)
        `when`(result.timestampNanos).thenReturn(observed)
        LibrePodsService::class.java.getDeclaredMethod("handleScanResult", ScanResult::class.java)
            .apply { isAccessible = true }.invoke(service, result)
        return observed
    }

    private fun advance(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
