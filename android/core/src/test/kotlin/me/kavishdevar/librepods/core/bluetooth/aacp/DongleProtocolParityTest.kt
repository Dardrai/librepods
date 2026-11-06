package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AudioSourcePacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ConnectedDevicesPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.parseRoutingRequest
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.routingRequestPacket
import org.junit.Assert.*
import org.junit.Test

class DongleProtocolParityTest {
    private val peer = MacAddress("01:23:45:67:89:AB")
    // Independent firmware wire fixtures, with a synthetic host address.
    private val hijack = "040004001000ab8967452301620001e54a6c6f63616c73636f7265306446726561736f6e4848696a61636b763251617564696f526f7574696e6753636f7265312d015f617564696f526f7574696e675365744f776e657273686970546f46616c7365014b72656d6f746573636f7265a5".hexToByteArray()
    private val reverse = "040004001000ab8967452301590001e35f617564696f526f7574696e675365744f776e657273686970546f46616c73650159617564696f526f7574696e6753686f775265766572736555490146726561736f6e535265766572736542616e6e6572546170706564".hexToByteArray()

    @Test fun experimentsAreIndependentAndPreserveTheExistingCapabilityMask() {
        assertArrayEquals(byteArrayOf(0xd7.toByte(), 0, 0, 0, 0, 0, 0, 0), sourceFeatureCapabilitiesPayload(false))
        assertArrayEquals(byteArrayOf(0xd7.toByte(), 2, 0, 0, 0, 0, 0, 0), sourceFeatureCapabilitiesPayload(true))
        assertArrayEquals(byteArrayOf(2, 0, 1, 0, 0x10), macSourceAnnouncementPayload())
        sourceFeatureCapabilitiesPayload(true).fill(0)
        assertEquals(0xd7.toByte(), sourceFeatureCapabilitiesPayload(true)[0])
    }

    @Test fun capturedRoutingMessagesRequireTheKnownSenderAndExactEnvelopeAndDictionary() {
        for ((outgoing, tapped) in listOf(hijack to false, reverse to true)) {
            assertArrayEquals(outgoing, routingRequestPacket(peer.value, tapped).rawPacket)
            val incoming = outgoing.copyOf().also { it[4] = 0x11 }
            val parsed = parseRoutingRequest(incoming, setOf(peer))!!
            assertEquals(peer, parsed.sender); assertEquals(tapped, parsed.reverseTapped)
            assertNull(parseRoutingRequest(incoming, emptySet()))
            for (length in incoming.indices) assertNull(parseRoutingRequest(incoming.copyOf(length), setOf(peer)))
            assertNull(parseRoutingRequest(incoming + 0, setOf(peer)))
            for (offset in incoming.indices) assertNull("byte $offset", parseRoutingRequest(incoming.copyOf().also { it[offset] = (it[offset].toInt() xor 0x80).toByte() }, setOf(peer)))
        }
    }

    @Test fun sourceAddressIsReversedButConnectedListAddressesAreNot() {
        val source = byteArrayOf(4, 0, 4, 0, 0x0e, 0) + byteArrayOf(0xab.toByte(), 0x89.toByte(), 0x67, 0x45, 0x23, 1, 2)
        assertEquals(peer, AudioSourcePacket.parse(source).audioSource.mac)
        assertThrows(IllegalArgumentException::class.java) { AudioSourcePacket.parse(source + 0) }
        assertThrows(IllegalArgumentException::class.java) { AudioSourcePacket.parse(source.copyOf().also { it.fill(0, 6, 12) }) }
        val list = byteArrayOf(4, 0, 4, 0, 0x2e, 0, 0, 0, 1, 1, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0, 0)
        assertEquals(peer, ConnectedDevicesPacket.parse(list).connectedDevices.single().macAddress)
        assertThrows(IllegalArgumentException::class.java) { ConnectedDevicesPacket.parse(list.copyOf().also { it.fill(0xff.toByte(), 9, 15) }) }
    }
}
