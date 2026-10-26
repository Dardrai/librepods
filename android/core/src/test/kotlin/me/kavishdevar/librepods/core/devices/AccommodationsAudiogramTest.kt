package me.kavishdevar.librepods.core.devices

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AccommodationsAudiogramTest {
    private fun clinical() = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(2, 2, 0x60, 0)); putFloat(100, 0.5f)
    }.array()
    private fun transparency() = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(0, 1f); putFloat(36, 2f); putFloat(40, -0.25f); putFloat(44, 1f)
        putFloat(48, 0.75f); putFloat(84, -0f); putFloat(88, 0.5f); putFloat(100, 0.5f)
    }.array()

    @Test fun sharedNativeLegacyVectorsMatchBothRecordsAndPreserveEveryOtherByte() {
        val vectors = javaClass.getResourceAsStream("/legacy-audiogram-synthetic-v1.bin")!!.use { it.readBytes() }
        assertEquals(8 * 296, vectors.size)
        repeat(8) { index ->
            val row = vectors.copyOfRange(index * 296, (index + 1) * 296)
            val input = row.copyOfRange(0, 104)
            val saved = input.copyOf()
            val source = AccommodationsAudiogram.parse(input, 7)!!
            assertArrayEquals("PME vector $index", row.copyOfRange(104, 232), source.curves())
            val baseline = transparency()
            val actual = source.transparency(baseline)!!
            assertArrayEquals("CT vector $index", row.copyOfRange(232, 296),
                actual.copyOfRange(4, 36) + actual.copyOfRange(52, 84))
            for (offset in baseline.indices) if (offset !in 4..35 && offset !in 52..83)
                assertEquals("untouched $index/$offset", baseline[offset], actual[offset])
            assertArrayEquals(saved, input)
            input.fill(0)
            assertArrayEquals(row.copyOfRange(104, 232), source.curves())
        }
    }

    @Test fun completeEnrolledProfileValidityIsRequiredIncludingUnrelatedScalars() {
        val valid = clinical()
        for (size in listOf(0, 100, 103, 105)) assertNull(AccommodationsAudiogram.parse(valid.copyOf(size), 7))
        for ((offset, value) in listOf(0 to 1, 1 to 0, 2 to 0, 3 to 1))
            assertNull(AccommodationsAudiogram.parse(valid.copyOf().also { it[offset] = value.toByte() }, 7))
        for ((offset, value) in listOf(4 to -1f, 52 to 91f, 36 to 2f, 40 to 2f, 44 to 0.5f,
            48 to -1f, 84 to Float.NaN, 88 to Float.POSITIVE_INFINITY, 92 to 0.5f, 96 to 2f, 100 to 2f)) {
            val invalid = valid.copyOf()
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
            assertNull("invalid source offset $offset", AccommodationsAudiogram.parse(invalid, 7))
        }
        assertNull(AccommodationsAudiogram.parse(valid, 0))
        assertNotNull(AccommodationsAudiogram.parse(valid.copyOf().also { it[2] = 0x64 }, 7))
    }

    @Test fun personalClassificationRequiresExactCurvesAndTheCurrentValidatedSourceSession() {
        val source = AccommodationsAudiogram.parse(clinical(), 7)!!
        val packet = ByteArray(140).also {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 0, 1, 2).copyInto(it)
            source.curves().copyInto(it, 12)
        }
        assertFalse(ReceivedAccommodations.parse(packet, 7, 1)!!.audiogram)
        assertFalse(ReceivedAccommodations.parse(packet, 8, 1, source)!!.audiogram)
        val received = ReceivedAccommodations.parse(packet, 7, 1, source)!!
        assertTrue(received.audiogram)
        assertNull(received.preset)
        packet[12] = 1
        assertFalse(ReceivedAccommodations.parse(packet, 7, 2, source)!!.audiogram)
    }

    @Test fun transparencyNormalizesNegativeZeroWithoutChangingThePmeSignedZero() {
        val input = clinical()
        val data = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        for (start in listOf(4, 52)) repeat(8) { data.putFloat(start + it * 4, -0f) }
        val source = AccommodationsAudiogram.parse(input, 7)!!
        val curves = ByteBuffer.wrap(source.curves()).order(ByteOrder.LITTLE_ENDIAN)
        repeat(32) { assertEquals(Int.MIN_VALUE, curves.getInt(it * 4)) }
        val ct = ByteBuffer.wrap(source.transparency(transparency())!!).order(ByteOrder.LITTLE_ENDIAN)
        for (start in listOf(4, 52)) repeat(8) { assertEquals(0, ct.getInt(start + it * 4)) }
    }
}
