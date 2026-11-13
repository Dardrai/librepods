/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.bluetooth.BluetoothDevice
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HfpBatteryNegotiationTest {
    private val peer = "01:23:45:67:89:AB"
    private val other = "01:23:45:67:89:AC"
    private val response = "+XAPL=iPhone,2"
    private val pro3 = arrayOf<Any>("  4c-2027-fa40", 32778)

    @Test fun onlyTheSamePro3ExchangeWithdrawsTheExactBatteryFeature() {
        val policy = HfpBatteryNegotiation()
        assertEquals(response, policy.response(peer, response))
        policy.withXapl(pro3, peer) {
            assertEquals("+XAPL=iPhone,0", policy.response(peer.lowercase(), response))
            assertEquals(response, policy.response(other, response))
            assertEquals(response, policy.response(null, response))
            for (value in listOf(null, "+XAPL=iPhone,0", "+XAPL=iPhone,3", "+XAPL=Android,2", "$response\r"))
                assertEquals(value, policy.response(peer, value))
        }
        assertEquals(response, policy.response(peer, response))
    }

    @Test fun otherModelsMalformedArgumentsAndUnownedPeersKeepTheNativeResponse() {
        val policy = HfpBatteryNegotiation()
        val invalid = listOf(null, emptyArray<Any>(), arrayOf<Any>("4c-2027-fa40"),
            arrayOf<Any>("4c-2027-fa40", "32778"), arrayOf<Any>("4c-2027-fa40", -1),
            arrayOf<Any>("4c-2027-fa40", 0), arrayOf<Any>("4c-2027-fa40", 1),
            arrayOf<Any>("4c-2027-fa40-extra", 2), arrayOf<Any>("4c-2027-fa40", 2, 1),
            arrayOf<Any>("4d-2027-fa40", 2), arrayOf<Any>("4c-2026-fa40", 2),
            arrayOf<Any>("+4c-2027-fa40", 2), arrayOf<Any>("4c-2027-xyz", 2),
            arrayOf<Any>("4c-2027-123456789", 2), arrayOf<Any>(" ".repeat(33) + "4c-2027-fa40", 2))
        for (arguments in invalid) policy.withXapl(arguments, peer) {
            assertEquals(response, policy.response(peer, response))
        }
        for (address in listOf(null, "", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "bogus"))
            policy.withXapl(pro3, address) { assertEquals(response, policy.response(address, response)) }
        policy.withXapl(arrayOf<Any>("004C-2027-FA40", 2), peer) {
            assertEquals("+XAPL=iPhone,0", policy.response(peer, response))
        }
    }

    @Test fun nestedExchangesCannotBorrowAnOuterPeersScopeAndExceptionsClearIt() {
        val policy = HfpBatteryNegotiation()
        policy.withXapl(pro3, peer) {
            policy.withXapl(arrayOf<Any>("4c-2026-fa40", 2), peer) {
                assertEquals(response, policy.response(peer, response))
            }
            policy.withXapl(pro3, other) {
                assertEquals(response, policy.response(peer, response))
                assertEquals("+XAPL=iPhone,0", policy.response(other, response))
            }
            assertEquals("+XAPL=iPhone,0", policy.response(peer, response))
        }
        assertThrows(IllegalStateException::class.java) {
            policy.withXapl(pro3, peer) { error("original exchange failed") }
        }
        assertEquals(response, policy.response(peer, response))
    }

    @Test fun anotherThreadCannotUseTheXaplScope() {
        val policy = HfpBatteryNegotiation()
        val worker = Executors.newSingleThreadExecutor()
        try {
            policy.withXapl(pro3, peer) {
                assertEquals(response, worker.submit<String?> { policy.response(peer, response) }.get(2, TimeUnit.SECONDS))
                assertEquals("+XAPL=iPhone,0", policy.response(peer, response))
            }
        } finally { worker.shutdownNow() }
    }

    @Suppress("UNUSED_PARAMETER") class State { fun processAtXapl(arguments: Array<Any>, device: BluetoothDevice) {} }
    @Suppress("UNUSED_PARAMETER") class Native { fun atResponseString(device: BluetoothDevice, value: String): Boolean = true }
    @Suppress("UNUSED_PARAMETER") class ChangedState { fun processAtXapl(arguments: List<Any>, device: BluetoothDevice) {} }
    @Suppress("UNUSED_PARAMETER") class ChangedNative { fun atResponseString(device: BluetoothDevice, value: String) {} }

    @Test fun bothNamedSignaturesMustMatchBeforeEitherHookIsInstalled() {
        val methods = HfpBatteryHookMethods.discover(State::class.java, Native::class.java)
        assertEquals("processAtXapl", methods.exchange.name)
        assertEquals("atResponseString", methods.response.name)
        assertThrows(IllegalArgumentException::class.java) { HfpBatteryHookMethods.discover(ChangedState::class.java, Native::class.java) }
        assertThrows(IllegalArgumentException::class.java) { HfpBatteryHookMethods.discover(State::class.java, ChangedNative::class.java) }
    }
}
