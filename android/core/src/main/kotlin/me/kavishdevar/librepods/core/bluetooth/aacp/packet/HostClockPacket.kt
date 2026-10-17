package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calendar time, separate from the microphone stream's RTP clock. */
internal fun hostClockPacket(now: Instant = Instant.now()): AACPPacket? {
    if (now.epochSecond !in 0..0xffff_ffffL) return null
    // Pro 3 diagnostics can apply the text offset, so both representations stay in UTC.
    val text = HOST_CLOCK_FORMAT.format(now).toByteArray(Charsets.UTF_8)
    val payload = ByteBuffer.allocate(10 + text.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(now.epochSecond.toInt()).putInt(now.nano / 1_000)
        .putShort(text.size.toShort()).put(text).array()
    return AACPPacket.createUnknownPacket(MessageOpcode.TIMESTAMP, payload)
}

private val HOST_CLOCK_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)
    .withZone(ZoneOffset.UTC)
