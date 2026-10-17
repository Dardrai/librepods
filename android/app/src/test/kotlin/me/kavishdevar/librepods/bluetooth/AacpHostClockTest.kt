package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AirPodsModel
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [ProtocolReportSocketShadow::class])
class AacpHostClockTest {
    @Test fun hearingIdentitySendsOneClockPerControlSessionAndRetiredReportsCannotSend() {
        val device = device()
        val manager = device.aacp
        val first = socket()
        val replacement = socket()
        bind(manager, first)
        try {
            manager.processPacket(information("A3063"), first)
            assertEquals(AirPodsModel.AIRPODS_PRO_3, device.metadata.value.model)
            val clock = output(first)
            assertClock(clock)
            manager.processPacket(information("A3063"), first)
            assertArrayEquals(clock, output(first))
            assertTrue(manager.disconnect(first))
            bind(manager, replacement)
            assertTrue(output(replacement).isEmpty())
            manager.processPacket(information("A3063"), first)
            assertTrue(output(replacement).isEmpty())
            manager.processPacket(information("A3063"), replacement)
            assertClock(output(replacement))
            manager.processPacket(information("A3063"), replacement)
            assertEquals(41, output(replacement).size)
        } finally { manager.disconnect(); first.close(); replacement.close() }
    }

    @Test fun cachedHearingIdentityAndFreshUnknownOrNonHearingModelsDoNotSendClock() {
        val device = device()
        device.loadInitialState(device.state.value, device.settings.value,
            device.metadata.value.copy(model = AirPodsModel.AIRPODS_PRO_3))
        val socket = socket()
        bind(device.aacp, socket)
        try {
            assertTrue(output(socket).isEmpty())
            for (model in listOf("Unknown model", "A1523")) {
                device.aacp.processPacket(information(model), socket)
                assertTrue(output(socket).isEmpty())
            }
            device.aacp.processPacket(information("A3063"), socket)
            assertClock(output(socket))
        } finally { device.aacp.disconnect(); socket.close() }
    }

    private fun assertClock(packet: ByteArray) {
        assertEquals(41, packet.size)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x1b, 0), packet.copyOfRange(0, 6))
        val fields = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(fields.getInt(10) in 0..999_999)
        assertEquals(25, fields.getShort(14).toInt())
        val text = packet.copyOfRange(16, packet.size).toString(Charsets.UTF_8)
        assertTrue(text.endsWith("+00:00"))
        assertEquals(fields.getInt(6).toLong() and 0xffff_ffffL, OffsetDateTime.parse(text).toEpochSecond())
    }

    private fun information(model: String) = byteArrayOf(4, 0, 4, 0, 0x1d, 0, 0, 0, 0, 0, 0) +
        listOf("AirPods", model, "Apple", "serial", "9A348", "9A348", "1.0", "update", "left", "right", "9A348")
            .joinToString("\u0000", postfix = "\u0000").toByteArray()

    private fun device(): AppleDevice {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        return AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
    }

    private fun socket() = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
    private fun output(socket: BluetoothSocket) = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes.toByteArray()
    private fun bind(manager: AACPManager, socket: BluetoothSocket) {
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
    }
}
