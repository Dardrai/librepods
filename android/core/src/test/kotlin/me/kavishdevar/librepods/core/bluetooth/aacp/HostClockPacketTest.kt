package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.hostClockPacket
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.TimeZone

class HostClockPacketTest {
    @Test fun numericAndTextFieldsShareOneInstantWithWholeSecondUtcText() {
        val packet = hostClockPacket(Instant.ofEpochSecond(1_791_839_997, 933_171_999))!!.rawPacket
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x1b, 0), packet.copyOfRange(0, 6))
        val payload = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1_791_839_997, payload.getInt(6))
        assertEquals(933_171, payload.getInt(10))
        assertEquals(25, payload.getShort(14).toInt())
        assertEquals("2026-10-12T21:19:57+00:00", packet.copyOfRange(16, packet.size).toString(Charsets.UTF_8))
        assertEquals(41, packet.size)
    }

    @Test fun unsignedWireRangeAndLeapDayNeverUseTheHostsLocalOffset() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"))
            for ((seconds, text) in listOf(
                0L to "1970-01-01T00:00:00+00:00",
                951_782_400L to "2000-02-29T00:00:00+00:00",
                2_147_483_648L to "2038-01-19T03:14:08+00:00",
                0xffff_ffffL to "2106-02-07T06:28:15+00:00",
            )) {
                val packet = hostClockPacket(Instant.ofEpochSecond(seconds, 999_999_999))!!.rawPacket
                val fields = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(seconds, fields.getInt(6).toLong() and 0xffff_ffffL)
                assertEquals(999_999, fields.getInt(10))
                assertEquals(text, packet.copyOfRange(16, packet.size).toString(Charsets.UTF_8))
            }
        } finally { TimeZone.setDefault(original) }
        assertNull(hostClockPacket(Instant.ofEpochSecond(-1, 999_999_999)))
        assertNull(hostClockPacket(Instant.ofEpochSecond(0x1_0000_0000L)))
    }
}
