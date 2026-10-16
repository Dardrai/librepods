package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class ATTSettingsWriteTest {
    private val value = byteArrayOf(1, 2, 3)

    private fun declaration(handle: ATTHandles, properties: Int) =
        byteArrayOf(0x09, 21, (handle.value - 1).toByte(), 0, properties.toByte(), handle.value.toByte(), 0) +
            (if (handle == ATTHandles.TRANSPARENCY) "90534d74af9e0394db45abf7c11c62d5"
            else "0a7e45410b0f98906f4dc595050012a4").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun capturedPropertiesUseWriteCommandWithoutWaitingForAnAckAndRequireReadback() {
        for (handle in listOf(ATTHandles.TRANSPARENCY, ATTHandles.HEARING_AID)) {
            val sent = mutableListOf<ByteArray>()
            lateinit var bearer: ATTBearer
            bearer = ATTBearer({ packet ->
                sent += packet
                when (packet[0].toInt()) {
                    0x08 -> bearer.receive(declaration(handle, 0x16))
                    0x0A -> bearer.receive(byteArrayOf(0x0B) + value)
                }
            }, {}, { true })
            assertTrue(bearer.writeVerifiedSetting(handle, value, 100) { true })
            assertEquals(listOf(0x08, 0x52, 0x0A), sent.map { it[0].toInt() })
            assertArrayEquals(byteArrayOf(0x52, handle.value.toByte(), 0) + value, sent[1])
            assertTrue(bearer.isCurrent)
        }
    }

    @Test fun advertisedWriteRequestRequiresExactAckBeforeReadback() {
        val sent = mutableListOf<Int>()
        lateinit var bearer: ATTBearer
        bearer = ATTBearer({ packet ->
            sent += packet[0].toInt()
            when (packet[0].toInt()) {
                0x08 -> bearer.receive(declaration(ATTHandles.TRANSPARENCY, 0x0A))
                0x12 -> bearer.receive(byteArrayOf(0x13))
                0x0A -> bearer.receive(byteArrayOf(0x0B) + value)
            }
        }, {}, { true })
        assertTrue(bearer.writeVerifiedSetting(ATTHandles.TRANSPARENCY, value, 100) { true })
        assertEquals(listOf(0x08, 0x12, 0x0A), sent)
    }

    @Test fun readonlyWrongUuidOrMismatchedDeclarationNeverSendsASetting() {
        val good = declaration(ATTHandles.TRANSPARENCY, 0x16)
        val unsupported = listOf(
            declaration(ATTHandles.TRANSPARENCY, 0x02),
            good.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() },
            good.copyOf().also { it[5] = 0x2A },
            good.copyOf(good.size - 1)
        )
        for (reply in unsupported) {
            var writes = 0
            lateinit var bearer: ATTBearer
            bearer = ATTBearer({ writes++; bearer.receive(reply) }, {}, { true })
            assertFalse(bearer.writeVerifiedSetting(ATTHandles.TRANSPARENCY, value, 100) { true })
            assertEquals(1, writes)
            assertTrue(bearer.isCurrent)
        }
    }

    @Test fun mismatchedReadbackDoesNotClaimSuccess() {
        lateinit var bearer: ATTBearer
        bearer = ATTBearer({ packet ->
            when (packet[0].toInt()) {
                0x08 -> bearer.receive(declaration(ATTHandles.TRANSPARENCY, 0x16))
                0x0A -> bearer.receive(byteArrayOf(0x0B, 9))
            }
        }, {}, { true })
        assertFalse(bearer.writeVerifiedSetting(ATTHandles.TRANSPARENCY, value, 100) { true })
        assertTrue(bearer.isCurrent)
    }

    @Test fun absentReadbackRetiresTheBearerAndCannotConfirmAnotherWrite() {
        var closed = 0
        var writes = 0
        lateinit var bearer: ATTBearer
        bearer = ATTBearer({ packet ->
            writes++
            if (packet[0] == 0x08.toByte()) bearer.receive(declaration(ATTHandles.HEARING_AID, 0x16))
        }, { closed++ }, { true })
        assertFalse(bearer.writeVerifiedSetting(ATTHandles.HEARING_AID, value, 1) { true })
        assertFalse(bearer.isCurrent)
        assertEquals(1, closed)
        val previousWrites = writes
        bearer.receive(byteArrayOf(0x0B) + value)
        assertFalse(bearer.writeVerifiedSetting(ATTHandles.HEARING_AID, value, 1) { true })
        assertEquals(previousWrites, writes)
    }

    @Test fun cancellationAfterPropertyReadCannotSendTheWriteCommand() {
        var allowed = true
        var writes = 0
        lateinit var bearer: ATTBearer
        bearer = ATTBearer({
            writes++
            bearer.receive(declaration(ATTHandles.TRANSPARENCY, 0x16))
            allowed = false
        }, {}, { true })
        assertFalse(bearer.writeVerifiedSetting(ATTHandles.TRANSPARENCY, value, 100) { allowed })
        assertEquals(1, writes)
        assertTrue(bearer.isCurrent)
    }
}
