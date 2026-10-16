/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
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
import java.io.PipedOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CapturedAudioTransportTest {
    @Test fun delayedCommandsAndPacketsCannotMoveToANewConnectionToTheSameAirPods() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val bluetoothDevice = adapter.getRemoteDevice("00:11:22:33:44:55")
        val device = AppleDevice(context, adapter, bluetoothDevice, ConnectionState.DISCONNECTED)
        val manager = device.aacp
        // The shadow device factory caches one socket; a reconnect needs two real identities.
        val first = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        assertNotSame(first, replacement)
        val writes = mutableListOf<ByteArray>()
        fun bind(socket: BluetoothSocket) {
            shadowOf(socket).setOutputStream(object : PipedOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) { writes += bytes.copyOfRange(offset, offset + length) }
                override fun flush() = Unit
            })
            AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
        }
        try {
            bind(first)
            assertSame(first, manager.captureAudioSocket())
            assertTrue(manager.requestMicrophoneStream(first))
            assertEquals(1, writes.size)
            assertTrue(device.state.value.aacpPackets.isEmpty())

            bind(replacement)
            assertFalse(manager.endMicrophoneStream(first))
            assertFalse(manager.disconnect(first))
            assertSame(replacement, manager.captureAudioSocket())
            assertEquals(1, writes.size)
            var packets = 0
            manager.microphonePacketCallback = { source, _ -> assertSame(replacement, source); packets++ }
            val audio = byteArrayOf(4, 0, 4, 0, 0x58, 0, 1, 0) + ByteArray(14) + byteArrayOf(1, 0, 0, 0, 1, 0x55)
            manager.processPacket(audio, first)
            assertEquals(0, packets)
            manager.processPacket(audio, replacement)
            assertEquals(1, packets)
            assertTrue(device.state.value.aacpPackets.isEmpty())

            assertTrue(manager.sendPacket(AACPPacket.createUnknownPacket(MessageOpcode.MICROPHONE_STREAM, byteArrayOf(0, 0, 2, 0, 3, 1))))
            assertTrue(device.state.value.aacpPackets.isEmpty())

            val connectLock = AACPManager::class.java.getDeclaredField("connectLock").apply { isAccessible = true }.get(manager)
            var queuedResult: Boolean? = null
            val queued = Thread { queuedResult = manager.connect() }
            synchronized(connectLock) {
                queued.start()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (queued.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, queued.state)
                assertTrue(manager.disconnect(replacement))
            }
            queued.join(1_000)
            assertFalse("Queued connect survived cancellation", queuedResult ?: true)
            assertNull(manager.captureAudioSocket())
        } finally {
            first.close()
            replacement.close()
        }
    }
}
