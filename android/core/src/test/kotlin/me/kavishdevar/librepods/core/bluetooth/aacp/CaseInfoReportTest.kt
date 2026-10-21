package me.kavishdevar.librepods.core.bluetooth.aacp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CaseInfoReportTest {
    private fun packet(): ByteArray = byteArrayOf(4, 0, 4, 0, 0x23, 0) + ByteArray(25).apply {
        this[0] = 3; this[12] = 1; this[13] = 'X'.code.toByte()
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(1, 0x4c).putInt(3, -1).putShort(7, 1)
        this[10] = 1; this[11] = 2; this[15] = 3; this[20] = 0x80.toByte()
    }

    @Test fun boundedV3PreservesUnsignedIdentityAndSplitVersionButDoesNotGrantCaseOwnership() {
        val report = parseCaseInfoReport(packet())!!
        assertEquals(0x4c, report.vendor)
        assertEquals(UInt.MAX_VALUE, report.product)
        assertEquals(0x8000000000030201uL, report.version)
        assertEquals("X", report.name)
    }

    @Test fun malformedLengthNameAndVersionCannotReplaceKnownMetadata() {
        val valid = packet()
        for (size in 0 until valid.size) assertNull(parseCaseInfoReport(valid.copyOf(size)))
        assertNull(parseCaseInfoReport(valid + 0))
        for ((offset, value) in listOf(6 to 4, 18 to 2, 19 to 0, 19 to 255, 20 to 1)) {
            assertNull(parseCaseInfoReport(valid.copyOf().apply { this[offset] = value.toByte() }))
        }
    }
}
