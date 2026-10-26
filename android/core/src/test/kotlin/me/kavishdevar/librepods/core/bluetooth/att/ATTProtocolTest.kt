package me.kavishdevar.librepods.core.bluetooth.att

import org.junit.Assert.*
import org.junit.Test

class ATTProtocolTest {
    private fun page(declaration: Int, handle: Int, properties: Int = 0x0a) =
        byteArrayOf(9, 21, declaration.toByte(), (declaration ushr 8).toByte(), properties.toByte(), handle.toByte(), (handle ushr 8).toByte()) +
            ATTHandle.TRANSPARENCY.uuid!!.attBytes()

    @Test fun discoveryRejectsInvalidRangesDuplicateUnreadableAndTruncatedDeclarations() {
        for (response in listOf(byteArrayOf(7, 0, 0, 0x40, 0), byteArrayOf(7, 0x40, 0, 0x20, 0), byteArrayOf(7, 1))) {
            assertThrows(ATTFailure::class.java) { discoverATTSettings { _, _ -> response } }
        }
        val invalid = listOf(page(0x20, 0x20), page(0x20, 0x41), page(0x20, 0x21, 8), page(0x20, 0x21).dropLast(1).toByteArray(),
            page(0x20, 0x21) + page(0x22, 0x23).copyOfRange(2, 23))
        for (page in invalid) {
            var index = 0
            assertThrows(ATTFailure::class.java) { discoverATTSettings { _, _ -> if (index++ == 0) byteArrayOf(7, 0x20, 0, 0x40, 0) else page } }
        }
    }

    @Test fun discoveryNoAttributeMustMatchRequestAndStayWithinRange() {
        val request = byteArrayOf(8, 0x20, 0, 0x40, 0, 3, 0x28)
        assertTrue(noATTAttribute(byteArrayOf(1, 8, 0x40, 0, 0x0a), request))
        assertFalse(noATTAttribute(byteArrayOf(1, 6, 0x20, 0, 0x0a), request))
        assertFalse(noATTAttribute(byteArrayOf(1, 8, 0x41, 0, 0x0a), request))
        assertFalse(noATTAttribute(byteArrayOf(1, 8, 0x20, 0, 0x0e), request))
    }

    @Test fun timeoutRetiresBearerAndLateReplyCannotCompleteAnotherRead() {
        var closed = false
        var sends = 0
        val bearer = ATTBearer(256, { sends++ }, { closed = true }, { true })
        assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x0a, 0x35, 0), 0x0b, 1) }
        assertTrue(closed)
        bearer.receive(byteArrayOf(0x0b, 1))
        assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x0a, 0x36, 0), 0x0b, 1) }
        assertEquals(1, sends)
    }

    @Test fun mismatchedErrorAndMalformedAcknowledgementRetireBearer() {
        for (reply in listOf(byteArrayOf(1, 0x12, 0x36, 0, 0x0e), byteArrayOf(0x13, 0))) {
            var closed = false
            lateinit var bearer: ATTBearer
            bearer = ATTBearer(256, { bearer.receive(reply) }, { closed = true }, { true })
            assertThrows(ATTFailure::class.java) { bearer.request(byteArrayOf(0x12, 0x35, 0, 1), 0x13) }
            assertTrue(closed)
        }
    }
}
