package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.content.Intent
import me.kavishdevar.librepods.receivers.BootReceiver
import me.kavishdevar.librepods.services.LibrePodsService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BluetoothPermissionsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun bothCurrentNearbyGrantsAreRequiredAndRevocationIsObserved() {
        shadowOf(context).denyPermissions(*nearbyDevicesPermissions.toTypedArray())
        assertFalse(hasNearbyDevicesPermissions(context))
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertFalse(hasNearbyDevicesPermissions(context))
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(hasNearbyDevicesPermissions(context))
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(hasNearbyDevicesPermissions(context))
    }

    @Test fun bootAndUpgradeNeverStartBluetoothWithoutPermission() {
        shadowOf(context).denyPermissions(*nearbyDevicesPermissions.toTypedArray())
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            BootReceiver().onReceive(context, Intent(action))
            assertNull(shadowOf(context).nextStartedService)
        }
    }

    @Test fun permittedBootAndUpgradeStillStartTheService() {
        shadowOf(context).grantPermissions(*nearbyDevicesPermissions.toTypedArray())
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            BootReceiver().onReceive(context, Intent(action))
            assertEquals(LibrePodsService::class.java.name, shadowOf(context).nextStartedService.component?.className)
        }
    }

    @Test fun deniedServiceStartupStopsWithoutBluetoothWorkAndCanBeDestroyed() {
        shadowOf(context).denyPermissions(*nearbyDevicesPermissions.toTypedArray())
        val controller = Robolectric.buildService(LibrePodsService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(service.devices.value.isEmpty())
        } finally {
            controller.destroy()
        }
    }
}
