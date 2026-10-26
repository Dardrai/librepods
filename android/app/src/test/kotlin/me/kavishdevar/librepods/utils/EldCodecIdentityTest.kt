/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class EldCodecIdentityTest {
    private fun fields(type: Int, priority: Int = 1_000_000) = EldCodecIdentity.Fields(type,
        priority, 0x22, 0x07, 0x03, Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0x123456789abcdefL)

    @Test fun outwardIdentityChangesOnlyTheReservedTypeAndRoundTripsEveryOtherField() {
        val native = fields(5)
        val public = EldCodecIdentity.outbound(native, true)
        assertEquals(native.copy(type = 0x4c8001), public)
        assertEquals(native, EldCodecIdentity.inbound(public, true))
    }

    @Test fun inactiveBridgePreservesAllStockCodecsIncludingLc3ByIdentity() {
        for (type in listOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 255, Int.MAX_VALUE)) {
            val value = fields(type)
            assertSame(value, EldCodecIdentity.outbound(value, false))
            assertSame(value, EldCodecIdentity.inbound(value, false))
        }
    }

    @Test fun activeBridgeDoesNotTranslatePublicLc3IntoEld() {
        assertNull(EldCodecIdentity.inbound(fields(5), true))
        assertEquals(5, EldCodecIdentity.inbound(fields(0x4c8001), true)!!.type)
    }

    @Test fun stalePrivatePreferenceCannotReachAnInactiveNativeAdapter() {
        assertNull(EldCodecIdentity.inbound(fields(0x4c8001), false))
    }

    @Test fun explicitPrivateDisabledPriorityAndAllSpecificFieldsSurvive() {
        val value = fields(0x4c8001, -1)
        assertEquals(value.copy(type = 5), EldCodecIdentity.inbound(value, true))
    }

    @Test fun stockAndUnknownCodecsPassThroughAnActiveBridgeWithoutReconstruction() {
        for (type in listOf(-1, 0, 1, 2, 3, 4, 6, 7, 31, 1001, Int.MAX_VALUE)) {
            val value = fields(type)
            assertSame(value, EldCodecIdentity.outbound(value, true))
            assertSame(value, EldCodecIdentity.inbound(value, true))
        }
    }

    @Test fun generatedPriorityAppendsToTheExactNote8CatalogWithoutChangingOpusOrStockEntries() {
        val catalog = listOf(0, 1, 2, 3, 4, 6).map { fields(it) }
        assertSame(catalog, EldCodecIdentity.priorities(catalog, false))
        val appended = EldCodecIdentity.priorities(catalog, true)
        assertEquals(catalog, appended.take(6))
        assertEquals(EldCodecIdentity.Fields(0x4c8001, 10_001, 0, 0, 0, 0, 0, 0, 0), appended.last())
        val explicit = catalog + fields(0x4c8001, -1)
        assertSame(explicit, EldCodecIdentity.priorities(explicit, true))
    }

    @Test fun explicitHighestAacPriorityRemainsAboveThePrototypeEldPolicy() {
        val aac = fields(1, 1_000_000)
        assertSame(aac, EldCodecIdentity.priorities(listOf(aac), true).first())
        assertTrue(aac.priority > EldCodecIdentity.INITIAL_PRIORITY)
    }

    @Test fun noPrivateEldEntryIsEverAdvertisedAsHardwareOffloaded() {
        assertNull(EldCodecIdentity.offload(fields(0x4c8001), true))
        assertNull(EldCodecIdentity.offload(fields(0x4c8001), false))
        assertNull(EldCodecIdentity.offload(fields(5), true))
        val lc3 = fields(5)
        assertSame(lc3, EldCodecIdentity.offload(lc3, false))
        for (type in listOf(0, 1, 2, 3, 4, 6)) {
            val stock = fields(type)
            assertSame(stock, EldCodecIdentity.offload(stock, true))
        }
    }
}
