/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class UsbAbsoluteVolumeTransportTest {
    @Test fun previousBootLoadedBinderSignaturesCannotBeDiscovered() {
        val nonce = "12345678-1234-1234-1234-123456789abc"
        val request = setOf("version", "nonce")
        val reply = request + "control"
        assertFalse(UsbAbsoluteVolumeTransport.validRequest(1, nonce, request))
        assertFalse(UsbAbsoluteVolumeTransport.validRequest(2, nonce, request))
        assertFalse(UsbAbsoluteVolumeTransport.validRequest(3, nonce, request))
        assertTrue(UsbAbsoluteVolumeTransport.validRequest(4, nonce, request))
        assertFalse(UsbAbsoluteVolumeTransport.validReply(1, nonce, nonce, reply))
        assertFalse(UsbAbsoluteVolumeTransport.validReply(2, nonce, nonce, reply))
        assertFalse(UsbAbsoluteVolumeTransport.validReply(3, nonce, nonce, reply))
        assertTrue(UsbAbsoluteVolumeTransport.validReply(4, nonce, nonce, reply))
    }
}
