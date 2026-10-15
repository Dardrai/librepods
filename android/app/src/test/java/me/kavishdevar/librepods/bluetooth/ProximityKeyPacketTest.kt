package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class ProximityKeyPacketTest {
    private fun record(type: Int, length: Int = 16): ByteArray = byteArrayOf(type.toByte(), (type shr 8).toByte(),
        length.toByte(), (length shr 8).toByte()) + ByteArray(length) { (it + 1).toByte() }
    private fun packet(vararg records: ByteArray): ByteArray = byteArrayOf(4, 0, 4, 0, 0x31, 0, records.size.toByte()) +
        records.fold(byteArrayOf()) { bytes, record -> bytes + record }

    @Test fun acceptsKnownKeysAndSkipsBoundedUnknownTypes() {
        val keys = ProximityKeyPacket.parse(packet(record(1), record(0x401, 256), record(4)))
        assertEquals(setOf(1, 4), keys.keys)
        assertArrayEquals(ByteArray(16) { (it + 1).toByte() }, keys[1])
    }

    @Test fun validatesWholePacketBeforeAnyKeysEscape() {
        val good = packet(record(1), record(4))
        for (length in 0 until good.size) assertThrows(IllegalArgumentException::class.java) {
            ProximityKeyPacket.parse(good.copyOf(length))
        }
        for (bad in listOf(good + byteArrayOf(0), packet(record(1), record(1)), packet(record(1, 15)),
            packet(record(1, 272)), packet(record(1).also { it.fill(0, 4) }),
            good.copyOf().also { it[0] = 0 }, good.copyOf().also { it[6] = 0xff.toByte() })) {
            assertThrows(IllegalArgumentException::class.java) { ProximityKeyPacket.parse(bad) }
        }
    }

    @Test fun privatePayloadClassificationDoesNotDependOnValidFraming() {
        for (opcode in listOf(0x31, 0x58)) {
            val bytes = byteArrayOf(4, 0, 4, 0, opcode.toByte())
            assertTrue(PrivateAacpPacket.isPrivate(bytes))
            assertTrue(PrivateAacpPacket.isPrivate(bytes.copyOf().also { it[0] = 0 }))
            assertTrue(PrivateAacpPacket.isPrivateLog("AirPods: " + bytes.joinToString(" ") { "%02X".format(it) }))
        }
        assertFalse(PrivateAacpPacket.isPrivate(byteArrayOf(4, 0, 4, 0, 4, 0)))
        assertFalse(PrivateAacpPacket.isPrivate(byteArrayOf(4, 0)))
        assertFalse(PrivateAacpPacket.isPrivateLog("AirPods: 04 00 04 00 04 00"))
    }
}
