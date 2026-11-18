/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.nio.ByteBuffer
import java.nio.ByteOrder
import me.kavishdevar.librepods.audio.SpatialPose
import org.junit.Assert.*
import org.junit.Test

class AtmosCanonicalFrameTest {
    private val pose = SpatialPose(0x0102030405060708L, 255,
        listOf(0.25f, -0.5f, -0.0f), listOf(1.25f, -2.5f, 3.75f), 10_000_000L)

    @Test fun canonicalFramePreservesBothOriginalClocksAndSixFloatBits() {
        val arrival = 0x1122334455667788L
        val bytes = AtmosCanonicalFrame.encode(pose, arrival)
        assertEquals(48, bytes.size)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), bytes.copyOf(4))
        val wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(255, wire.getInt(4)); assertEquals(pose.timestampNs, wire.getLong(8)); assertEquals(arrival, wire.getLong(16))
        val decoded = requireNotNull(AtmosCanonicalFrame.decode(bytes))
        assertEquals(pose.timestampNs, decoded.sensorNs); assertEquals(arrival, decoded.arrivalNs)
        assertEquals(pose.discontinuity, decoded.discontinuity)
        assertEquals((pose.rotationVector + pose.angularVelocity).map(Float::toRawBits),
            (decoded.rotation + decoded.angularVelocity).map(Float::toRawBits))
        // Frame construction is not a freshness check and must never replace old arrivals with now.
        assertEquals(1L, requireNotNull(AtmosCanonicalFrame.decode(AtmosCanonicalFrame.encode(pose, 1))).arrivalNs)
    }

    @Test fun malformedFrameOrCanonicalMetadataIsRejected() {
        val valid = AtmosCanonicalFrame.encode(pose, 100)
        assertNull(AtmosCanonicalFrame.decode(valid.copyOf(47)))
        assertNull(AtmosCanonicalFrame.decode(valid.copyOf(49)))
        assertNull(AtmosCanonicalFrame.decode(valid.copyOf().apply { this[0] = 0 }))
        for ((offset, value) in listOf(4 to 256, 4 to -1, 24 to Float.NaN.toRawBits(), 36 to Float.POSITIVE_INFINITY.toRawBits())) {
            assertNull(AtmosCanonicalFrame.decode(valid.copyOf().apply {
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            }))
        }
        for (offset in listOf(8, 16)) assertNull(AtmosCanonicalFrame.decode(valid.copyOf().apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, 0)
        }))
        assertThrows(IllegalArgumentException::class.java) { AtmosCanonicalFrame.encode(pose, 0) }
        assertThrows(IllegalArgumentException::class.java) { AtmosCanonicalFrame.encode(pose.copy(rotationVector = listOf(1f)), 100) }
    }
}
