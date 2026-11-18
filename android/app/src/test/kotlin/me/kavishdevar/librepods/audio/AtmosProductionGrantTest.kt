/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosProductionGrantTest {
    private val address = "00:AA:22:33:44:55"
    private fun preparation() = Bundle().apply {
        putLong("choice_epoch", 7); putLong("control", 11); putInt("target_uid", 10414)
        putByteArray("nonce", ByteArray(16) { 1 }); putLong("buffer_issued_ms", 1000)
        putLong("issued_ms", 1200); putLong("deadline_ms", 61000); putInt("boot", 3)
        putInt("route_id", 780); putString("peer", address)
    }

    @Test fun usesOnlyTheRootIssuedBoundedGrantAndExactCurrentChoice() {
        val grant = atmosProductionGrant(preparation(), address, 7, 2000, 3)!!
        assertEquals(1000L, grant.issuedMs); assertEquals(61000L, grant.deadlineMs)
        assertEquals(780, grant.routeId)
        assertNull(atmosProductionGrant(preparation(), address, 8, 2000, 3))
        assertNull(atmosProductionGrant(preparation(), address, 0, 2000, 3))
        assertNull(atmosProductionGrant(preparation(), address, 7, 61000, 3))
        assertNull(atmosProductionGrant(preparation(), address, 7, 999, 3))
        assertNull(atmosProductionGrant(preparation(), address, 7, 2000, 4))
        assertNull(atmosProductionGrant(preparation(), "00:AA:22:33:44:66", 7, 2000, 3))
    }

    @Test fun missingRootBindingOrUnboundedTimesCannotBecomeProductionAttestation() {
        for (key in listOf("nonce", "control", "target_uid", "choice_epoch", "buffer_issued_ms", "deadline_ms", "boot", "route_id", "peer"))
            assertNull(key, atmosProductionGrant(preparation().apply { remove(key) }, address, 7, 2000, 3))
        for (nonce in listOf(ByteArray(16), ByteArray(15) { 1 }))
            assertNull(atmosProductionGrant(preparation().apply { putByteArray("nonce", nonce) }, address, 7, 2000, 3))
        assertNull(atmosProductionGrant(preparation().apply { putLong("deadline_ms", Long.MAX_VALUE) }, address, 7, 2000, 3))
    }
}
