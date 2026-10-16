/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.utils.Acceleration
import me.kavishdevar.librepods.utils.HeadTracking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AirPodsSensorSessionTest {
    private lateinit var socket: BluetoothSocket
    private lateinit var manager: AACPManager
    private lateinit var sensors: AirPodsSensorSession
    private val writes = CopyOnWriteArrayList<ByteArray>()
    @Volatile private var onWrite: ((ByteArray) -> Unit)? = null

    @Before fun connectTransport() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        socket = adapter.getRemoteDevice("00:11:22:33:44:55")
            .createRfcommSocketToServiceRecord(UUID(0, 1))
        socket.connect()
        shadowOf(socket).setOutputStream(object : PipedOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val packet = bytes.copyOfRange(offset, offset + length)
                writes += packet
                onWrite?.invoke(packet)
            }
            override fun flush() = Unit
        })
        BluetoothConnectionManager.aacpSocket = socket
        manager = AACPManager()
        sensors = AirPodsSensorSession(manager)
        sensors.onConnected(supportsHeartRate = true, supportsMotion = true, firmware = "9A348")
    }

    @After fun closeTransport() {
        sensors.close()
        socket.close()
        BluetoothConnectionManager.aacpSocket = null
        HeadTracking.reset()
        await { Thread.getAllStackTraces().keys.none { it.name == "AirPods sensors" && it.isAlive } }
    }

    @Test fun productionHeadTrackingReceivesValidatedChannelsAcrossSequenceWidths() {
        val received = mutableListOf<RtBuddySensors.Motion>()
        sensors.onHeadTrackingMotion = { received += it; HeadTracking.processMotion(it) }
        assertTrue(sensors.setHeadTrackingEnabled(true))
        for (sequence in listOf(1, 128)) manager.receivePacket(movement(sequence, 16))
        assertEquals(listOf(RtBuddySensors.Motion(0x1234, 0x5678), RtBuddySensors.Motion(0x1234, 0x5678)), received)
        assertEquals(Acceleration(0x5678.toFloat(), 0x1234.toFloat()), HeadTracking.acceleration.value)

        manager.receivePacket(movement(1, 14))
        manager.receivePacket(frame(byteArrayOf(8, 1, 16, 2) + bytes(7, byteArrayOf(8, 16) + bytes(3, ByteArray(58)))))
        manager.receivePacket(announcement(1, 27, "devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191))
        manager.receivePacket(frame(byteArrayOf(8, 3, 16, 3) + bytes(7, byteArrayOf(8, 20) + bytes(3, ByteArray(18)))))
        manager.receivePacket(movement(1, 16))
        assertEquals(2, received.size)
        manager.receivePacket(movement(128, 27))
        assertEquals(3, received.size)
    }

    @Test fun sharedMotionOwnersStopOnlyTheDiscoveredServiceAfterBothRelease() {
        await { writes.any { it.contentEquals(RtBuddySensors.descriptorRequest(1)) } }
        manager.receivePacket(announcement(1, 27, "devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191))
        assertTrue(sensors.setMotionEnabled(true))
        assertTrue(sensors.setHeadTrackingEnabled(true))
        await { motionCommands().size >= 2 }
        assertArrayEquals(RtBuddySensors.streamCommand(2, 27, 1, report = 4), motionCommands()[0])
        assertArrayEquals(RtBuddySensors.streamCommand(3, 27, 20_000), motionCommands()[1])
        assertTrue(sensors.setHeadTrackingEnabled(false))
        Thread.sleep(100)
        assertEquals(2, motionCommands().size)
        assertTrue(sensors.state.value.motionEnabled)
        assertTrue(sensors.setMotionEnabled(false))
        await { motionCommands().size == 4 }
        assertArrayEquals(RtBuddySensors.streamCommand(4, 27, 10, report = 4), motionCommands()[2])
        assertArrayEquals(RtBuddySensors.streamCommand(5, 27, 0), motionCommands()[3])
        assertFalse(sensors.isHeadTrackingEnabled)
    }

    @Test fun ownDescriptorAnnouncementsCannotRestartHeartSetupOrResetItsRetryBudget() {
        onWrite = { packet ->
            if (packet.size == 16 || packet.size == 7) {
                manager.receivePacket(announcement(1, 16, "devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191))
                manager.receivePacket(announcement(3, 20, "HeartRate", RtBuddySensors.HEART_DESCRIPTOR, 601))
            }
        }
        assertTrue(sensors.setHeartRateEnabled(true))
        await(advanceClock = true) { writes.count { it.size == 11 && it[6] == 0x30.toByte() && it[7] == 1.toByte() } == 2 }
        await(advanceClock = true) { !sensors.state.value.heartRateEnabled }
        assertNotNull(sensors.state.value.heartRateError)
        assertEquals(2, writes.count { it.size == 16 && it[2] == 0.toByte() })
        assertEquals(2, writes.count { it.size == 11 && it[6] == 0x30.toByte() && it[7] == 1.toByte() })
        val intervals = writes.filter { it.size >= 29 && it[4] == 0x17.toByte() }
        assertTrue(intervals.any { it[it.size - 5] == 1.toByte() && it.takeLast(4) == listOf<Byte>(0x40, 0x42, 0x0f, 0) })
    }

    private fun motionCommands() = writes.filter { it.size >= 27 && it[4] == 0x17.toByte() }
    private fun await(advanceClock: Boolean = false, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) {
            if (advanceClock) ShadowSystemClock.advanceBy(Duration.ofMillis(250))
            Thread.sleep(10)
        }
        assertTrue("Sensor worker did not reach the expected state", predicate())
    }

    private fun frame(payload: ByteArray) = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0,
        payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
    private fun varint(value: Int): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        do {
            val byte = remaining and 0x7f
            remaining = remaining ushr 7
            out.write(byte or if (remaining != 0) 0x80 else 0)
        } while (remaining != 0)
        return out.toByteArray()
    }
    private fun bytes(id: Int, value: ByteArray) = varint(id * 8 + 2) + varint(value.size) + value
    private fun movement(sequence: Int, service: Int): ByteArray {
        val report = ByteArray(58).apply { this[28] = 0x34; this[29] = 0x12; this[30] = 0x78; this[31] = 0x56 }
        return frame(byteArrayOf(8) + varint(sequence) + byteArrayOf(16, 3) +
            bytes(7, byteArrayOf(8, service.toByte()) + bytes(3, report)))
    }
    private fun announcement(sequence: Int, service: Int, name: String, descriptor: ByteArray, maximum: Long): ByteArray {
        val dictionary = ByteArrayOutputStream()
        fun word(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        fun number(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
        fun item(kind: Int, count: Int, value: ByteArray) {
            dictionary.write(word(kind shl 24 or count))
            dictionary.write(value)
            while (dictionary.size() % 4 != 0) dictionary.write(0)
        }
        dictionary.write(byteArrayOf(0xd3.toByte(), 0, 0, 0, 4, 0, 0, 0x81.toByte()))
        for ((key, kind, value) in listOf(Triple("VendorID", 4, number(1452)),
            Triple("AccessoryService", 9, (name + '\u0000').toByteArray()), Triple("MaxReportSize", 4, number(maximum)),
            Triple("ReportDescriptor", 10, descriptor))) {
            item(9, key.length + 1, (key + '\u0000').toByteArray())
            item(kind, if (kind == 4) 64 else value.size, value)
        }
        return frame(byteArrayOf(8, sequence.toByte(), 16, 3) +
            bytes(5, byteArrayOf(8, service.toByte()) + bytes(2, dictionary.toByteArray())))
    }
}
