/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BLEManagerScanTest {
    private fun report(flags: Int = 0x64, lid: Int = 0x31): ByteArray = ByteArray(27).apply {
        this[0] = 7; this[1] = 25; this[2] = 1; this[3] = 0x27; this[4] = 0x20
        this[5] = flags.toByte(); this[8] = lid.toByte()
    }

    @Suppress("DEPRECATION")
    private fun result(adapter: BluetoothAdapter, manufacturer: ByteArray, company: Int = 76,
                       address: String = "5A:16:2B:91:D1:CD"): ScanResult {
        val bytes = byteArrayOf((manufacturer.size + 3).toByte(), 0xff.toByte(),
            company.toByte(), (company shr 8).toByte()) + manufacturer + byteArrayOf(0)
        val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes",
            ClassParameter.from(ByteArray::class.java, bytes))
        return ScanResult(adapter.getRemoteDevice(address), record, -40, SystemClock.elapsedRealtimeNanos())
    }

    @Test fun immediateAppleScanAcceptsCompoundProximityReportsButStillValidatesTheirRecords() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val scanner = adapter.bluetoothLeScanner
        val manager = BLEManager(context)
        val invitations = mutableListOf<NearbyAirPods.Event>()
        manager.setNearbyDeviceListener { invitations.add(it) }
        try {
            manager.startScanning()
            val scan = shadowOf(scanner).activeScans.single()
            val settings = requireNotNull(scan.scanSettings())
            assertEquals(0L, settings.reportDelayMillis)
            assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.scanMode)
            assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, settings.callbackType)
            val filter = scan.scanFilters().single()
            assertEquals(76, filter.manufacturerId)
            assertArrayEquals(byteArrayOf(), filter.manufacturerData)
            assertNull(filter.manufacturerDataMask)
            val compound = byteArrayOf(0x10, 1, 0x42) + report()
            val valid = result(adapter, compound)
            assertTrue(filter.matches(valid))
            assertFalse(filter.matches(result(adapter, compound, company = 75)))
            val callback = requireNotNull(scan.scanCallback())
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(adapter, report() + report()))
            assertTrue(invitations.isEmpty())
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, valid)
            assertEquals(listOf(NearbyAirPods.Event(0x2720, true)), invitations)
        } finally {
            manager.stopScanning()
        }
        assertTrue(shadowOf(scanner).activeScans.isEmpty())
    }

    @Test fun pairedReportsPreserveUnknownLidsAndClosedCasesNeverAuthorizeReconnect() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val pairedAddress = "AA:BB:CC:DD:EE:00"
        shadowOf(adapter).setBondedDevices(setOf(adapter.getRemoteDevice(pairedAddress)))
        // Synthetic CAPod RPA fixture shared with BluetoothCryptographyTest and NTPods.
        val irk = byteArrayOf(0x79, 0x04, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
            0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)
        val encryption = ByteArray(16) { (it + 1).toByte() }
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("mac_address", pairedAddress)
            .putString("proximity_key_device_address", pairedAddress)
            .putString("IRK", Base64.getEncoder().encodeToString(irk))
            .putString("ENC_KEY", Base64.getEncoder().encodeToString(encryption)).commit()
        val manager = BLEManager(context)
        val lids = mutableListOf<Boolean>()
        manager.setAirPodsStatusListener(object : BLEManager.AirPodsStatusListener {
            override fun onDeviceStatusChanged(device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?) {}
            override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {}
            override fun onLidStateChanged(lidOpen: Boolean) { lids.add(lidOpen) }
            override fun onEarStateChanged(device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean) {}
            override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {}
            override fun onDeviceDisappeared() {}
        })
        try {
            manager.startScanning()
            val scan = shadowOf(adapter.bluetoothLeScanner).activeScans.single()
            val callback = requireNotNull(scan.scanCallback())
            fun send(flags: Int, lid: Int, address: String = "5A:16:2B:91:D1:CD") {
                ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryption, "AES"))
                val plain = ByteArray(16).apply { this[1] = 80; this[2] = 70; this[3] = 60 }
                val manufacturer = report(flags, lid).also { cipher.doFinal(plain).copyInto(it, 11) }
                callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(adapter, manufacturer, address = address))
            }
            send(0x60, 0x31)
            assertEquals(listOf(true), lids)
            assertTrue(requireNotNull(manager.getMostRecentStatus()).mayReconnect)
            send(0x20, 0x38)
            assertEquals(listOf(true), lids)
            assertNull(requireNotNull(manager.getMostRecentStatus()).lidOpen)
            send(0x64, 0x38)
            val closed = requireNotNull(manager.getMostRecentStatus())
            assertEquals(listOf(true, false), lids)
            assertEquals(false, closed.lidOpen)
            assertTrue(closed.bothInCase)
            assertFalse(closed.isLeftCharging || closed.isRightCharging)
            assertFalse(closed.mayReconnect)
            send(0x60, 0x31, "5A:16:2B:91:D1:CC")
            assertEquals(closed, manager.getMostRecentStatus())
            assertEquals(listOf(true, false), lids)
        } finally {
            manager.stopScanning()
            preferences.edit().clear().commit()
        }
    }

    @Test fun reconnectAvailabilityUsesCaseAndModelHintsRatherThanChargingAlone() {
        val outside = BLEManager.AirPodsStatus("unused", model = "AirPods Pro 3")
        assertTrue(outside.mayReconnect)
        assertFalse(outside.copy(lidOpen = false).mayReconnect)
        assertFalse(outside.copy(bothInCase = true, lidOpen = true).mayReconnect)
        assertFalse(outside.copy(model = "AirPods Max").mayReconnect)
        assertFalse(outside.copy(model = "Unknown (65535)").mayReconnect)
    }
}
