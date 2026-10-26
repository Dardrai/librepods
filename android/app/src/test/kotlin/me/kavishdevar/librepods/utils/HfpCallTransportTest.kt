/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class HfpCallTransportTest {
    private val nonce = "11111111-1111-4111-8111-111111111111"
    @Test fun discoveryOnlyAcceptsItsExactVersionAndRequestShape() {
        val keys = setOf("version", "nonce")
        assertTrue(HfpCallTransport.validRequest(1, nonce, keys))
        assertFalse(HfpCallTransport.validRequest(0, nonce, keys))
        assertFalse(HfpCallTransport.validRequest(1, "arbitrary", keys))
        assertFalse(HfpCallTransport.validRequest(1, nonce, keys + "peer"))
        assertFalse(HfpCallTransport.validRequest(1, nonce, keys - "nonce"))
    }
    @Test fun staleAndExtraFieldRepliesCannotDeliverAControlBinder() {
        val keys = setOf("version", "nonce", "control")
        assertTrue(HfpCallTransport.validReply(1, nonce, nonce, keys))
        assertFalse(HfpCallTransport.validReply(2, nonce, nonce, keys))
        assertFalse(HfpCallTransport.validReply(1, "22222222-2222-4222-8222-222222222222", nonce, keys))
        assertFalse(HfpCallTransport.validReply(1, nonce, nonce, keys + "owner"))
        assertFalse(HfpCallTransport.validReply(1, nonce, nonce, keys - "control"))
    }
    @Test fun discoveryUsesTheExistingExactAppAndBluetoothIdentities() {
        val rules = NativeGameTransportRules
        assertTrue(rules.requestSender(10345, rules.APP, 10345))
        assertFalse(rules.requestSender(0, rules.APP, 10345))
        assertFalse(rules.requestSender(10345, "another.signed.app", 10345))
        assertTrue(rules.replySender(1002, "com.google.android.bluetooth", "com.google.android.bluetooth"))
        assertFalse(rules.replySender(1000, "com.google.android.bluetooth", "com.google.android.bluetooth"))
        assertFalse(rules.replySender(1002, "com.android.bluetooth", "com.google.android.bluetooth"))
    }
}
