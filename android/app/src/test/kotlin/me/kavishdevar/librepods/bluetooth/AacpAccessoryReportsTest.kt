package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.DeviceComponent
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
    @Test fun capturedPrimaryHandoffMapsEarReportsAndUnknownOrRetiredRolesKeepTheCurrentSide() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        val prefix = byteArrayOf(4, 0, 4, 0, 8, 0)
        fun assertEar(primary: DeviceComponent) {
            device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 2), socket)
            assertEquals(ComponentStatus.IN_EAR, device.state.value.componentState.single { it.component == primary }.status)
            assertEquals(ComponentStatus.IN_CASE, device.state.value.componentState.single { it.component != primary }.status)
        }
        try {
            // Sanitized macOS handoff, frame 2144 in MACOS-CASE-CAPTURE-2026-10-12.md.
            device.aacp.processPacket(prefix + byteArrayOf(1, 0, 1, 0), socket)
            assertEar(DeviceComponent.LEFT)
            device.aacp.processPacket(prefix + byteArrayOf(2, 0, 1, 1), socket)
            assertFalse(device.state.value.leftIsPrimary)
            assertEar(DeviceComponent.RIGHT)
            for (unknown in listOf(0, 3, 0xfe)) {
                device.aacp.processPacket(prefix + byteArrayOf(unknown.toByte(), 0, 1, 1), socket)
                assertFalse(device.state.value.leftIsPrimary)
            }
            val validLeft = prefix + byteArrayOf(1, 0, 1, 0)
            for (size in 0 until validLeft.size) device.aacp.processPacket(validLeft.copyOf(size), socket)
            device.aacp.processPacket(validLeft + 0.toByte(), socket)
            device.aacp.processPacket(validLeft.copyOf().apply { this[0] = 0 }, socket)
            assertFalse(device.state.value.leftIsPrimary)
            device.aacp.processPacket(validLeft, socket)
            assertTrue(device.state.value.leftIsPrimary)
            device.aacp.disconnect()
            device.aacp.processPacket(prefix + byteArrayOf(2, 0, 1, 1), socket)
            assertTrue(device.state.value.leftIsPrimary)
            assertTrue(device.state.value.budRoleRaw.isEmpty())
        } finally { device.aacp.disconnect(); socket.close() }
    }

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
