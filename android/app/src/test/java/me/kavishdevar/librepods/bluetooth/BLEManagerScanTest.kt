/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BLEManagerScanTest {
    @Test fun lidReportsUseImmediateCallbacksWithTheExistingManufacturerFilter() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val scanner = adapter.bluetoothLeScanner
        val manager = BLEManager(context)
        try {
            manager.startScanning()
            val scan = shadowOf(scanner).activeScans.single()
            val settings = requireNotNull(scan.scanSettings())
            assertEquals(0L, settings.reportDelayMillis)
            assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.scanMode)
            assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, settings.callbackType)
            val filter = scan.scanFilters().single()
            assertEquals(76, filter.manufacturerId)
            assertArrayEquals(byteArrayOf(7, 25) + ByteArray(25), filter.manufacturerData)
            assertArrayEquals(byteArrayOf(-1, -1) + ByteArray(25), filter.manufacturerDataMask)
        } finally {
            manager.stopScanning()
        }
        assertTrue(shadowOf(scanner).activeScans.isEmpty())
    }
}
