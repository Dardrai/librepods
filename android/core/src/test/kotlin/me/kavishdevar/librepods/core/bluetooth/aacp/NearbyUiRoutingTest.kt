package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.Opack
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.parseNearbyUiRequest
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.parseRoutingRequest
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.routingRequestPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class NearbyUiRoutingTest {
    private val peer = MacAddress("01:23:45:67:89:AB")

    private fun str(text: String) = byteArrayOf((0x40 + text.length).toByte()) + text.toByteArray()

    /** Relay envelope as received (0x0011): reversed sender address, u16 length, OPACK body. */
    private fun relay(body: ByteArray, sender: MacAddress = peer): ByteArray {
        val address = sender.value.split(':').map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        return byteArrayOf(4, 0, 4, 0, 0x11, 0) + address +
            byteArrayOf(body.size.toByte(), (body.size ushr 8).toByte()) + body
    }

    /** Body of upstream LibrePods' sendSmartRoutingShowUI, including its trailing zero byte. */
    private val upstreamShowUi: ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xe6.toByte())); write(str("SmartRoutingKeyShowNearbyUI")); write(1)
        write(str("localscore")); write(byteArrayOf(0x31, 0x2d, 1))
        write(str("reason")); write(str("hijackv2"))
        write(str("audioRoutingScore")); write(0xa2)
        write(str("audioRoutingSetOwnershipToFalse")); write(1)
        write(str("remotescore")); write(0xa2)
        write(0)
    }.toByteArray()

    @Test fun upstreamShowNearbyUiRelayIsRecognisedFromAConnectedHost() {
        assertEquals(126, upstreamShowUi.size) // matches upstream's declared length 0x7E
        val packet = relay(upstreamShowUi)
        val request = parseNearbyUiRequest(packet, setOf(peer))
        assertEquals(peer, request?.sender)
        assertNull(request?.deviceName)
        // Unknown senders, envelope damage and every truncation stay passive.
        assertNull(parseNearbyUiRequest(packet, emptySet()))
        assertNull(parseNearbyUiRequest(packet.copyOf().also { it[4] = 0x10 }, setOf(peer)))
        assertNull(parseNearbyUiRequest(packet + 0, setOf(peer)))
        for (length in packet.indices) assertNull(parseNearbyUiRequest(packet.copyOf(length), setOf(peer)))
        // Not an ownership request for the strict exact-match parser.
        assertNull(parseRoutingRequest(packet, setOf(peer)))
    }

    @Test fun keyMustBeTrueAndBtNameIsUsedWhenPresent() {
        val named = byteArrayOf(0xe2.toByte()) + str("SmartRoutingKeyShowNearbyUI") + byteArrayOf(1) +
            str("btName") + str("Example iPhone")
        assertEquals("Example iPhone", parseNearbyUiRequest(relay(named), setOf(peer))?.deviceName)
        val off = byteArrayOf(0xe1.toByte()) + str("SmartRoutingKeyShowNearbyUI") + byteArrayOf(2)
        assertNull(parseNearbyUiRequest(relay(off), setOf(peer)))
    }

    @Test fun ownershipRelaysDoNotShowTheNearbyBanner() {
        for (reverse in listOf(false, true)) {
            val incoming = routingRequestPacket(peer.value, reverse).rawPacket.also { it[4] = 0x11 }
            assertNotNull(parseRoutingRequest(incoming, setOf(peer)))
            assertNull(parseNearbyUiRequest(incoming, setOf(peer)))
        }
    }

    @Test fun opackRejectsUnknownTagsDuplicateKeysAndNonZeroPadding() {
        val ok = byteArrayOf(0xe1.toByte()) + str("a") + byteArrayOf(0x30, 0x64)
        assertEquals(mapOf("a" to 100L), Opack.decodeDictionary(ok))
        assertNull(Opack.decodeDictionary(ok + 0))
        assertEquals(mapOf("a" to 100L), Opack.decodeDictionary(ok + 0, maxPadding = 1))
        assertNull(Opack.decodeDictionary(ok + 1, maxPadding = 1))
        assertNull(Opack.decodeDictionary(byteArrayOf(0xe1.toByte()) + str("a") + byteArrayOf(0x07)))
        assertNull(Opack.decodeDictionary(byteArrayOf(0xe2.toByte()) + str("a") + byteArrayOf(1) + str("a") + byteArrayOf(1)))
        assertNull(Opack.decodeDictionary(byteArrayOf(0xe1.toByte(), 0x09, 0x01)))
        assertNull(Opack.decodeDictionary(str("not a dictionary")))
    }
}
