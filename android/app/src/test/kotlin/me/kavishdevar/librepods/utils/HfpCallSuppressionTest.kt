/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.bluetooth.BluetoothDevice
import org.junit.Assert.*
import org.junit.Test

class HfpCallSuppressionTest {
    private val peer = "01:23:45:67:89:AB"
    private fun lease(generation: Long = 1, root: Any = Any(), app: Any = Any(), service: Any = Any()) =
        HfpSuppressionState.Lease(peer, root, app, service, generation)

    @Test fun onlyExactServiceAndPeerAreSuppressed() {
        val state = HfpSuppressionState(); val lease = lease()
        assertTrue(state.publish(lease).active)
        repeat(3) { assertTrue(state.blockConnect(lease.service, peer, true)) }
        assertTrue(state.blockIncoming(lease.service, peer.lowercase()))
        assertFalse(state.blockConnect(lease.service, "01:23:45:67:89:AC", true))
        assertFalse(state.blockIncoming(Any(), peer))
        assertTrue(state.state(lease.root).active)
    }

    @Test fun authorizedExternalConnectRetiresBeforeItsQueuedAcceptance() {
        val retired = mutableListOf<HfpSuppressionState.Lease>()
        val state = HfpSuppressionState { retired += it }; val lease = lease()
        state.publish(lease)
        assertFalse(state.blockConnect(lease.service, peer, false))
        // The subsequently queued state-machine call runs internally, but must remain allowed.
        assertFalse(state.blockIncoming(lease.service, peer))
        assertFalse(state.blockConnect(lease.service, peer, true))
        assertEquals(listOf(lease), retired)
        assertEquals("manual-connect", state.state(lease.root).reason)
        assertTrue(state.release(lease.root).released)
    }

    @Test fun anotherDevicesManualConnectDoesNotConsumeThisLease() {
        val state = HfpSuppressionState(); val lease = lease()
        state.publish(lease)
        assertFalse(state.blockConnect(lease.service, "01:23:45:67:89:AC", false))
        assertTrue(state.state(lease.root).active)
    }

    @Test fun eachOwnerDeathRetiresOnlyItsOriginalGeneration() {
        for (deadOwner in listOf("root", "app")) {
            val state = HfpSuppressionState(); val first = lease()
            state.publish(first)
            first.alive.set(false) // Either linked Binder death marks this exact lease.
            assertTrue(state.retire(first, "$deadOwner-died"))
            assertTrue(state.release(first.root).released)
            val second = lease(2)
            state.publish(second)
            assertFalse(state.retire(first, "late-death"))
            assertTrue(state.blockIncoming(second.service, peer))
            assertEquals(2L, state.state(second.root).generation)
        }
    }

    @Test fun ownerDyingDuringLinkingNeverBecomesAnActiveLease() {
        val state = HfpSuppressionState(); val lease = lease()
        lease.alive.set(false)
        val receipt = state.publish(lease)
        assertFalse(receipt.active); assertTrue(receipt.owned)
        assertEquals(lease.generation, receipt.generation)
        assertFalse(state.blockIncoming(lease.service, peer))
        assertTrue(state.release(lease.root).released)
    }

    @Test fun localDeadOwnerMarkerFailsOpenWithoutCallingAnyRemoteOwner() {
        val state = HfpSuppressionState(); val lease = lease()
        state.publish(lease); lease.alive.set(false)
        assertFalse(state.blockConnect(lease.service, peer, true))
        assertFalse(state.state(lease.root).active)
    }

    @Test fun exactReleaseIsIdempotentButCannotAuthorizeRestoreOverAReplacement() {
        val state = HfpSuppressionState(); val first = lease()
        state.publish(first)
        assertTrue(state.release(first.root).released)
        assertTrue(state.release(first.root).released)
        val second = lease(2, service = first.service)
        state.publish(second)
        val stale = state.release(first.root)
        assertFalse(stale.released); assertTrue(stale.active); assertFalse(stale.owned)
        assertEquals(2L, stale.generation)
        assertEquals(2L, state.state(first.root).generation)
        assertTrue(state.blockConnect(second.service, peer, true))
    }

    @Test fun lostAcquireReplyCanBeReleasedUsingExactRootToken() {
        val state = HfpSuppressionState(); val lease = lease()
        state.publish(lease) // Simulate the caller losing this successful reply.
        val receipt = state.release(lease.root)
        assertTrue(receipt.released); assertFalse(receipt.active)
        assertEquals(lease.generation, receipt.generation)
        assertFalse(state.release(Any()).released)
    }

    @Test fun repeatedAcquireCannotRearmAManuallyRetiredCall() {
        val state = HfpSuppressionState(); val first = lease()
        state.publish(first)
        assertEquals(first.generation, state.publish(lease(2, first.root, first.app, first.service)).generation)
        state.blockConnect(first.service, peer, false)
        val repeated = state.publish(lease(3, first.root, first.app, first.service))
        assertFalse(repeated.active); assertTrue(repeated.owned)
        assertEquals(first.generation, repeated.generation)
    }

    @Test fun serviceCleanupAndRestartDoNotCarrySuppressionAcrossInstances() {
        val state = HfpSuppressionState(); val first = lease()
        state.publish(first)
        state.serviceStopped(Any())
        assertTrue(state.state(first.root).active)
        state.serviceStopped(first.service)
        assertFalse(state.state(first.root).active)
        val second = lease(2)
        state.publish(second)
        state.serviceStopped(first.service)
        assertTrue(state.state(second.root).active)
        assertFalse(state.blockIncoming(first.service, peer))
    }

    @Test fun adapterShutdownStopsAdmissionAndRetiresOnce() {
        val retired = mutableListOf<HfpSuppressionState.Lease>()
        val state = HfpSuppressionState { retired += it }; val lease = lease()
        state.publish(lease); state.close(); state.close()
        assertEquals(listOf(lease), retired)
        assertFalse(state.blockConnect(lease.service, peer, true))
        assertFalse(state.publish(lease(2)).active)
        assertTrue(state.release(lease.root).released)
    }

    @Test fun controlCallsRequireRootAndSelectedAddressesAreValidated() {
        assertTrue(HfpCallSuppression.rootCaller(0))
        for (uid in listOf(-1, 1000, 1002, 2000, 10345)) assertFalse(HfpCallSuppression.rootCaller(uid))
        assertTrue(HfpCallSuppression.validPeer(peer))
        for (bad in listOf("", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "$peer:01", "GG:23:45:67:89:AB"))
            assertFalse(HfpCallSuppression.validPeer(bad))
    }

    class Adapter { fun onCreate() {} ; fun onDestroy() {} }
    @Suppress("UNUSED_PARAMETER") class Headset {
        fun connect(device: BluetoothDevice): Boolean = true
        fun okToAcceptConnection(device: BluetoothDevice, outgoing: Boolean): Boolean = true
        fun cleanup() {}
        companion object { @JvmStatic fun getHeadsetService(): Headset? = null }
    }
    @Suppress("UNUSED_PARAMETER") class ChangedHeadset {
        fun connect(device: BluetoothDevice): Boolean = true
        fun okToAcceptConnection(device: BluetoothDevice): Boolean = true
        fun cleanup() {}
        companion object { @JvmStatic fun getHeadsetService(): ChangedHeadset? = null }
    }
    @Test fun allHookSignaturesAreDiscoveredBeforeInstallation() {
        val methods = HfpCallHookMethods.discover(Adapter::class.java, Headset::class.java)
        assertEquals("connect", methods.connect.name)
        assertTrue(java.lang.reflect.Modifier.isStatic(methods.current.modifiers))
        assertThrows(IllegalArgumentException::class.java) {
            HfpCallHookMethods.discover(Adapter::class.java, ChangedHeadset::class.java)
        }
    }
}
