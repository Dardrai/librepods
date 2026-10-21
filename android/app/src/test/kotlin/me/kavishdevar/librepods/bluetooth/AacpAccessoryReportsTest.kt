package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [ClosingBluetoothSocketShadow::class])
class AacpAccessoryReportsTest {
    @Test fun unknownRolesKeepEarMappingAndMalformedOrRetiredReportsCannotChangeMetadata() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        try {
            val role = byteArrayOf(4, 0, 4, 0, 8, 0, 0xfe.toByte(), 0x80.toByte(), 3, 4)
            device.aacp.processPacket(role, socket)
            assertTrue(device.state.value.leftIsPrimary)
            assertEquals(listOf(254, 128, 3, 4), device.state.value.budRoleRaw)
            device.aacp.processPacket(role.copyOf(7).apply { this[6] = 0 }, socket)
            assertTrue(device.state.value.leftIsPrimary)
            val info = byteArrayOf(4, 0, 4, 0, 0x23, 0) + ByteArray(25).apply {
                this[0] = 3; this[12] = 1; this[13] = 'X'.code.toByte()
            }
            device.aacp.processPacket(info, socket)
            assertEquals("X", device.state.value.caseInfo?.name)
            device.aacp.processPacket(info.copyOf(30), socket)
            assertEquals("X", device.state.value.caseInfo?.name)
            device.aacp.disconnect()
            assertNull(device.state.value.caseInfo)
            assertTrue(device.state.value.budRoleRaw.isEmpty())
            device.aacp.processPacket(info, socket)
            assertNull(device.state.value.caseInfo)
        } finally { device.aacp.disconnect(); socket.close() }
    }
}
