/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class UsbAbsoluteVolumeBrokerTest {
    private val target = UsbAbsoluteVolumeBroker.Target(12, 22, "card=2;device=0;", "BOARD_A", 9)
    private class FakePolicy(val target: UsbAbsoluteVolumeBroker.Target) : UsbAbsoluteVolumeBroker.Policy {
        override var lifecycleSafe = true
        var snapshot = UsbAbsoluteVolumeBroker.Route(target, true, true, true, true)
        var acquires = 0
        var closes = 0
        var owns = true
        var failCleanup = false
        var afterAcquire: () -> Unit = {}
        override fun route(target: UsbAbsoluteVolumeBroker.Target) = snapshot
        override fun acquire(target: UsbAbsoluteVolumeBroker.Target, deadline: Long): UsbAbsoluteVolumeBroker.OwnedPolicy {
            acquires++
            afterAcquire()
            return object : UsbAbsoluteVolumeBroker.OwnedPolicy {
                override fun stillOwned() = owns
                override fun close() { if (failCleanup) error("cleanup"); if (owns) closes++ }
            }
        }
    }
    private var time = 1_000L
    private fun authority() = UsbAbsoluteVolumeBroker.Authority(1, true, target.connection,
        time + 4_000, true, true)
    private fun broker(policy: FakePolicy, contract: Int? = 1) = UsbAbsoluteVolumeBroker(policy, { time }, contract)
    private fun acquire(broker: UsbAbsoluteVolumeBroker, owner: Any = this,
        proof: UsbAbsoluteVolumeBroker.Authority = authority(), enabled: Boolean = true, sdk: Int = 37, uid: Int = 0) =
        broker.acquire(owner, target, proof, enabled, sdk, uid)

    @Test fun defaultOffAndMissingContractNeverTouchRegistrationOrNativePolicy() {
        val policy = FakePolicy(target)
        assertEquals("disabled", acquire(broker(policy), enabled = false).reason)
        assertEquals("firmware_contract_unavailable", acquire(broker(policy, null)).reason)
        assertEquals("root_required", acquire(broker(policy), uid = 2000).reason)
        assertEquals("platform_unavailable", acquire(broker(policy), sdk = 38).reason)
        policy.lifecycleSafe = false
        assertEquals("native_policy_unavailable", acquire(broker(policy)).reason)
        assertEquals(0, policy.acquires)
    }

    @Test fun capabilityFreshnessAndPhysicalRelayProofAreRequired() {
        for (proof in listOf(authority().copy(contractVersion = 2), authority().copy(advertised = false),
            authority().copy(connection = 10), authority().copy(expiresAt = time),
            authority().copy(expiresAt = time + 5_001), authority().copy(physicalLevelKnown = false),
            authority().copy(relaySuppressed = false))) {
            val policy = FakePolicy(target)
            assertFalse(acquire(broker(policy), proof = proof).active)
            assertEquals(0, policy.acquires)
        }
    }

    @Test fun anotherUsbOutputRouteModeOrForeignBehaviorRejectsActivation() {
        val original = FakePolicy(target).snapshot
        for (route in listOf(original.copy(soleUsbOutput = false), original.copy(selected = false),
            original.copy(normalMode = false), original.copy(variableVolume = false),
            original.copy(target = target.copy(boardSerial = "BOARD_B")))) {
            val policy = FakePolicy(target).apply { snapshot = route }
            assertEquals("route_unavailable", acquire(broker(policy)).reason)
            assertEquals(0, policy.acquires)
        }
    }

    @Test fun detachWhileAcquiringCleansTheOwnedRegistrationAndNativePolicyTogether() {
        val policy = FakePolicy(target)
        policy.afterAcquire = { policy.snapshot = policy.snapshot.copy(selected = false) }
        assertEquals("stale", acquire(broker(policy)).reason)
        assertEquals(1, policy.acquires)
        assertEquals(1, policy.closes)
    }

    @Test fun anOwnedAbsoluteRegistrationIsNotMistakenForAForeignInitialBehavior() {
        val policy = FakePolicy(target)
        policy.afterAcquire = { policy.snapshot = policy.snapshot.copy(variableVolume = false) }
        val broker = broker(policy)
        assertTrue(acquire(broker).active)
        assertTrue(broker.renew(this, target, authority()).active)
        assertTrue(broker.poll().active)
        broker.close()
        assertEquals("closed", acquire(broker).reason)
    }

    @Test fun onlyTheSameOwnerCanRenewOrReleaseAndOldDeathCannotCloseReplacement() {
        val policy = FakePolicy(target)
        val broker = broker(policy)
        val first = Any(); val second = Any()
        assertTrue(acquire(broker, first).active)
        assertEquals("wrong_owner", broker.renew(second, target, authority()).reason)
        assertFalse(broker.release(second))
        assertTrue(broker.release(first))
        assertTrue(acquire(broker, second).active)
        assertFalse(broker.release(first))
        assertTrue(broker.poll().active)
        assertEquals(1, policy.closes)
        broker.close()
        assertEquals(2, policy.closes)
    }

    @Test fun ExpiredAuthorityCannotBeRevivedWithALateRenewal() {
        val policy = FakePolicy(target)
        val broker = broker(policy)
        assertTrue(acquire(broker).active)
        time += 4_000
        assertEquals("stale", broker.renew(this, target, authority()).reason)
        assertEquals(1, policy.closes)
        assertFalse(broker.poll().active)
    }

    @Test fun anOwnedLeaseCanRenewDuringPhysicalSetReadbackWithoutChangingTheConfirmedLevel() {
        val policy = FakePolicy(target)
        val broker = broker(policy)
        assertTrue(acquire(broker).active)
        assertTrue(broker.renew(this, target, authority().copy(physicalLevelKnown = false)).active)
        assertTrue(broker.poll().active)
        broker.close()
    }

    @Test fun routeLossAndForeignPolicyReplacementEndTheLeaseWithoutUndoingForeignOwnership() {
        val policy = FakePolicy(target)
        val broker = broker(policy)
        assertTrue(acquire(broker).active)
        policy.owns = false
        assertEquals("stale", broker.poll().reason)
        assertEquals(0, policy.closes)
        policy.owns = true
        policy.snapshot = policy.snapshot.copy(variableVolume = true)
        assertTrue(acquire(broker).active)
        policy.snapshot = policy.snapshot.copy(soleUsbOutput = false)
        assertEquals("stale", broker.poll().reason)
        assertEquals(1, policy.closes)
    }

    @Test fun cleanupFailureQuarantinesSubsequentRequests() {
        val policy = FakePolicy(target)
        val broker = broker(policy)
        assertTrue(acquire(broker).active)
        policy.failCleanup = true
        assertFalse(broker.release(this))
        assertEquals("cleanup_failed", acquire(broker).reason)
        assertEquals("cleanup_failed", broker.poll().reason)
        assertEquals(1, policy.acquires)
        policy.failCleanup = false
        assertTrue(broker.release(this))
        assertEquals(1, policy.closes)
        assertTrue(acquire(broker).active)
    }
}
