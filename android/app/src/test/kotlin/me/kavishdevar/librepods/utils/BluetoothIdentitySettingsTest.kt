// SPDX-License-Identifier: GPL-3.0-or-later
package me.kavishdevar.librepods.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BluetoothIdentitySettingsTest {
    private var approved = false
    private var remembered: Boolean? = null
    private var legacy = false
    private var calls = mutableListOf<IdentityAction>()
    private var reply: IdentityHelperStatus? = null
    private fun controller(modern: Boolean = true, initial: Boolean = false,
        run: suspend (IdentityAction) -> IdentityHelperStatus? = { reply }) =
        IdentityPreferenceController(modern, initial, { legacy }, { legacy = it; true },
            { approved }, { approved = true; remembered = it }, { calls += it; run(it) })

    @Test fun parsesOnlyTheBoundedHelperProtocol() {
        assertEquals(IdentityHelperStatus(true, true), parseIdentityStatus("version=1\nconfigured=1\nactive=1\n"))
        assertEquals(IdentityHelperStatus(false, false), parseIdentityStatus("version=1\nconfigured=0\nactive=0\n"))
        for (invalid in listOf("", "version=2\nconfigured=1\nactive=1", "version=1\nconfigured=1\nactive=true",
            "version=1\nactive=1\nconfigured=1", "version=1\nconfigured=1\nactive=1\nactive=0", "x".repeat(129)))
            assertNull(parseIdentityStatus(invalid))
    }

    @Test fun openingSettingsBeforeRootConsentDoesNotRequestRoot() = runBlocking {
        val controller = controller()
        controller.refresh()
        assertTrue(calls.isEmpty())
        assertFalse(controller.state.value.known)
        assertFalse(controller.state.value.enabled)
    }

    @Test fun checkedStateChangesOnlyAfterVerifiedHelperResponse() = runBlocking {
        val controller = controller()
        reply = IdentityHelperStatus(true, true)
        assertEquals(IdentityUpdateResult.UPDATED, controller.setEnabled(true))
        assertEquals(listOf(IdentityAction.ENABLE), calls)
        assertTrue(controller.state.value.enabled)
        assertTrue(controller.state.value.known)
        assertFalse(controller.state.value.busy)
        assertEquals(true, remembered)
        reply = IdentityHelperStatus(false, false)
        assertEquals(IdentityUpdateResult.UPDATED, controller.setEnabled(false))
        assertFalse(controller.state.value.enabled)
    }

    @Test fun deniedOrMissingRootRetainsPriorChoiceWithoutClaimingConfirmation() = runBlocking {
        val controller = controller(initial = true)
        assertEquals(IdentityUpdateResult.FAILED, controller.setEnabled(false))
        assertTrue(controller.state.value.enabled)
        assertFalse(controller.state.value.known)
        assertFalse(approved)
        assertNull(remembered)
    }

    @Test fun propertyReadbackAndBootIntentMustBothMatchTheRequestedChange() = runBlocking {
        val controller = controller()
        for (status in listOf(IdentityHelperStatus(true, false), IdentityHelperStatus(false, true))) {
            reply = status
            assertEquals(IdentityUpdateResult.FAILED, controller.setEnabled(true))
            assertFalse(controller.state.value.enabled)
            assertFalse(controller.state.value.known)
        }
    }

    @Test fun aSecondTapAndRefreshCannotStartConcurrentRootProcesses() = runBlocking {
        val response = CompletableDeferred<IdentityHelperStatus>()
        val controller = controller(run = { response.await() })
        val first = async { controller.setEnabled(true) }
        yield()
        assertTrue(controller.state.value.busy)
        assertEquals(IdentityUpdateResult.BUSY, controller.setEnabled(false))
        controller.refresh()
        assertEquals(1, calls.size)
        response.complete(IdentityHelperStatus(true, true))
        assertEquals(IdentityUpdateResult.UPDATED, first.await())
    }

    @Test fun cancellationClearsBusyStateAndLeavesThePreviousChoiceUnconfirmed() = runBlocking {
        var wait = true
        val controller = controller(run = { if (wait) awaitCancellation() else IdentityHelperStatus(true, true) })
        val first = launch { controller.setEnabled(true) }
        yield()
        first.cancelAndJoin()
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.enabled)
        assertFalse(controller.state.value.known)
        wait = false
        assertEquals(IdentityUpdateResult.UPDATED, controller.setEnabled(true))
    }

    @Test fun anApprovedStatusReadReplacesTheCachedHintWithActualProperties() = runBlocking {
        approved = true
        val controller = controller(initial = true)
        reply = IdentityHelperStatus(true, false)
        controller.refresh()
        assertEquals(listOf(IdentityAction.STATUS), calls)
        assertFalse(controller.state.value.enabled)
        assertTrue(controller.state.value.known)
    }

    @Test fun olderAndroidKeepsTheXposedBackendAndNeverRequestsRoot() = runBlocking {
        legacy = true
        val controller = controller(modern = false)
        controller.refresh()
        assertTrue(controller.state.value.enabled)
        assertEquals(IdentityUpdateResult.UPDATED, controller.setEnabled(false))
        assertFalse(legacy)
        assertTrue(calls.isEmpty())
    }
}
