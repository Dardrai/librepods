package me.kavishdevar.librepods.core.bluetooth.att.types

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class HearingDiagnosticsTest {
    private fun record(left: ULong = 100uL, right: ULong = 101uL, status: Int = 0): ByteArray = ByteArray(71).apply {
        this[0] = 8
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(1, left.toLong()).putLong(9, right.toLong())
            .putInt(17, status).putInt(21, status).putInt(33, -1).putInt(37, -1)
        this[27] = 90; this[28] = 80; this[70] = 0xff.toByte()
    }

    @Test fun exactVersionLengthAndFullUnsignedValuesArePreserved() {
        assertNull(parseSavedHearingDiagnostics(record().copyOf(70), 0x2027))
        assertNull(parseSavedHearingDiagnostics(record().apply { this[0] = 9 }, 0x2027))
        val parsed = parseSavedHearingDiagnostics(record(ULong.MAX_VALUE, status = -65536 or 64), 0x2027)!!
        assertEquals(ULong.MAX_VALUE, parsed.left.timestamp)
        assertEquals(UInt.MAX_VALUE, parsed.left.firstUse)
        assertEquals(0xffff0040u, parsed.left.uninterpretedBits)
        assertEquals(255, parsed.opaqueTail.last())
        assertNull(parsed.left.ancScores)
    }

    @Test fun zeroAbortedAndUnsupportedMeasurementsDoNotExposeComponentPasses() {
        assertNull(parseSavedHearingDiagnostics(record(0uL), 0x2027)!!.left.componentFlags)
        assertNull(parseSavedHearingDiagnostics(record(status = 2 shl 3), 0x2027)!!.left.componentFlags)
        assertNull(parseSavedHearingDiagnostics(record(), 0x9999)!!.left.componentFlags)
        assertNotNull(parseSavedHearingDiagnostics(record(), 0x2027)!!.left.componentFlags)
        assertEquals(90, parseSavedHearingDiagnostics(record(), 0x201b)!!.left.ancScores!!.first())
    }

    @Test fun eachEarNeedsItsOwnFreshCompletedTimestamp() {
        val baseline = HearingSelfCheckBaseline(100uL, 101uL)
        assertFalse(baseline.hasTwoFreshCompletedResults(parseSavedHearingDiagnostics(record(102uL, 101uL), 0x2027)!!))
        assertFalse(baseline.hasTwoFreshCompletedResults(parseSavedHearingDiagnostics(record(102uL, 103uL, 2 shl 3), 0x2027)!!))
        assertTrue(baseline.hasTwoFreshCompletedResults(parseSavedHearingDiagnostics(record(102uL, 103uL), 0x2027)!!))
    }

    @Test fun metadataRequiresExactLengthAndFiniteFloats() {
        val bytes = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(17) { putFloat(it.toFloat()) } }.array()
        assertEquals(17, parseHearingVolumeCurve(bytes)!!.size)
        assertEquals(0f, parseHearingSensitivity(bytes.copyOf(4))!!, 0f)
        assertNull(parseHearingSensitivity(bytes))
        assertNull(parseHearingVolumeCurve(bytes.copyOf(64)))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(64, Float.NaN)
        assertNull(parseHearingVolumeCurve(bytes))
    }
}
