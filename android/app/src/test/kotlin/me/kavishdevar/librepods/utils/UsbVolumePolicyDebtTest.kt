/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class UsbVolumePolicyDebtTest {
    @Test fun nativeFailureRetainsTheObligationAndCannotUnregisterEarly() {
        val debt = UsbVolumePolicyDebt().apply { registration = true; mapping = true; native = true }
        val operations = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            debt.clear(1, { operations += "cache" }, { operations += "disable"; false }, { operations += "reset"; true })
        }
        assertEquals(listOf("cache", "disable"), operations)
        assertFalse(debt.mapping); assertTrue(debt.native); assertTrue(debt.registration)
        debt.clear(1, { fail("Cache already removed") }, { operations += "disable"; true }, { operations += "reset"; true })
        assertEquals(listOf("cache", "disable", "disable", "reset"), operations)
        assertFalse(debt.native || debt.registration)
    }
    @Test fun aForeignCallbackCannotAuthorizeNativeOrCacheRestoration() {
        val debt = UsbVolumePolicyDebt().apply { registration = true; mapping = true; native = true }
        assertThrows(IllegalStateException::class.java) {
            debt.clear(2, { fail("Foreign cache") }, { fail("Foreign native policy"); false }, { fail("Foreign callback"); false })
        }
        assertTrue(debt.mapping && debt.native && debt.registration)
    }
    @Test fun partialRegistrationIsResetEvenWithoutANativeMapping() {
        val debt = UsbVolumePolicyDebt().apply { registration = true }
        var resets = 0
        debt.clear(1, { fail("No cache") }, { fail("No native policy"); false }, { resets++; true })
        assertEquals(1, resets); assertFalse(debt.registration)
        debt.registration = true
        debt.clear(0, { fail("No cache") }, { fail("No native policy"); false }, { fail("Already absent"); false })
        assertFalse(debt.registration)
    }
}
