package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.Opcodes
import me.kavishdevar.librepods.data.CustomEq
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AACPReceptionTest {
    private fun header(opcode: Byte) = byteArrayOf(4, 0, 4, 0, opcode, 0)
    private fun devices(count: Int = 1) = header(Opcodes.CONNECTED_DEVICES) +
        byteArrayOf(0, 0, count.toByte()) + byteArrayOf(1, 2, 3, 4, 5, 6, 1, 2)

    @Test fun truncatedMetadataCannotInterruptTheConnectionReader() {
        val manager = AACPManager()
        val packets = listOf(
            devices(),
            header(Opcodes.AUDIO_SOURCE) + byteArrayOf(1, 2, 3, 4, 5, 6, 1),
            header(Opcodes.SMART_ROUTING_RESP) + ByteArray(6),
            header(Opcodes.CUSTOM_EQ) + byteArrayOf(5, 0, 1, 2, 50, 50, 50)
        )
        for (packet in packets) for (size in 0 until packet.size) {
            manager.receivePacket(packet.copyOf(size))
        }
        manager.receivePacket(header(Opcodes.INFORMATION))
        manager.receivePacket(header(Opcodes.INFORMATION) + ByteArray(20))
        manager.receivePacket(devices())
        assertEquals("01:02:03:04:05:06", manager.connectedDevices.single().mac)
    }

    @Test fun incompleteDeviceListsPreserveTheLastCompleteReport() {
        val manager = AACPManager()
        manager.receivePacket(devices())
        manager.connectedDevices.single().type = "Laptop"
        val complete = manager.connectedDevices
        manager.receivePacket(devices(count = 2))
        manager.receivePacket(devices(count = 255))
        assertSame(complete, manager.connectedDevices)
        manager.receivePacket(devices())
        assertEquals("Laptop", manager.connectedDevices.single().type)
        manager.receivePacket(header(Opcodes.CONNECTED_DEVICES) + byteArrayOf(0, 0, 0))
        assertTrue(manager.connectedDevices.isEmpty())
    }

    @Test fun malformedEqualizerReportsDoNotResetTheLastValidSetting() {
        val manager = AACPManager()
        val expected = CustomEq(2, 80, 20, 65)
        var notifications = 0
        manager.customEqCallback = { notifications++ }
        manager.receivePacket(byteArrayOf(4, 0, 4, 0) + expected.toPacket())
        manager.receivePacket(header(Opcodes.CUSTOM_EQ))
        manager.receivePacket(header(Opcodes.CUSTOM_EQ) + byteArrayOf(4, 0, 1, 1, 50, 50, 50))
        manager.receivePacket(header(Opcodes.CUSTOM_EQ) + byteArrayOf(5, 0, 1, 1, 101, 50, 50))
        manager.receivePacket(header(Opcodes.CUSTOM_EQ) + byteArrayOf(5, 0, 1, 0, 50, 50, 50))
        manager.receivePacket(header(Opcodes.CUSTOM_EQ) + byteArrayOf(5, 0, 1, -1, 50, 50, 50))
        assertEquals(expected, manager.customEq)
        assertEquals(1, notifications)
        assertFalse(manager.sendCustomEqPacket(CustomEq(1, 50, 50, 50)))
        assertEquals(expected, manager.customEq)
        manager.disconnected()
        assertEquals(CustomEq(1, 50, 50, 50), manager.customEq)
    }

    @Test fun validMetadataStillParsesAfterBoundaryValidation() {
        val manager = AACPManager()
        val information = header(Opcodes.INFORMATION) + byteArrayOf(1, 0) +
            "reserved\u0000My AirPods\u0000A3063\u0000Apple\u0000".toByteArray()
        assertEquals("My AirPods", manager.parseInformationPacket(information).name)
        assertEquals("A3063", manager.parseInformationPacket(information).modelNumber)
        val source = manager.parseAudioSourceResponse(header(Opcodes.AUDIO_SOURCE) +
            byteArrayOf(1, 2, 3, 4, 5, 6, 2))
        assertEquals("06:05:04:03:02:01", source.first)
        assertEquals(AACPManager.Companion.AudioSourceType.MEDIA, source.second)
    }

    @Test fun truncatedEarAndConversationReportsNeverReachFixedOffsetConsumers() {
        val manager = AACPManager()
        val callbacks = mutableListOf<String>()
        manager.setPacketCallback(Proxy.newProxyInstance(
            AACPManager.PacketCallback::class.java.classLoader,
            arrayOf(AACPManager.PacketCallback::class.java)
        ) { _, method, _ -> callbacks.add(method.name); null } as AACPManager.PacketCallback)
        manager.receivePacket(header(Opcodes.EAR_DETECTION) + byteArrayOf(0))
        manager.receivePacket(header(Opcodes.CONVERSATION_AWARENESS) + byteArrayOf(2, 0, 0))
        assertTrue(callbacks.isEmpty())
        manager.receivePacket(header(Opcodes.EAR_DETECTION) + byteArrayOf(0, 1))
        manager.receivePacket(header(Opcodes.CONVERSATION_AWARENESS) + byteArrayOf(2, 0, 0, 1))
        assertEquals(listOf("onEarDetectionReceived", "onConversationAwarenessReceived"), callbacks)
    }
}
