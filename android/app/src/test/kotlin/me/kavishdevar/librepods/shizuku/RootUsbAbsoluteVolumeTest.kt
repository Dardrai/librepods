/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RootUsbAbsoluteVolumeTest {
    private val owner = object : IPlaybackRecoveryOwner.Stub() { override fun isCurrent() = true }
    private val events = object : IUsbAbsoluteVolumeEvents.Stub() {
        override fun onVolumeChanged(connection: Long, level: Int, requestSequence: Long) {}
        override fun onPolicyEnded(reason: String, cleanupComplete: Boolean) {}
    }
    private class Policy : IUsbAbsoluteVolumePolicy.Stub() {
        data class Notification(val connection: Long, val level: Int, val address: String, val acknowledgedSequence: Long)
        var starts = 0; var releases = 0; var clean = true
        var inspections = 0; var renewals = 0; var notifications = 0
        val addresses = mutableListOf<String>()
        val notificationRequests = mutableListOf<Notification>()
        private fun active() = Bundle().apply { putBoolean("active", true); putBoolean("cleanup_complete", true) }
        override fun inspect(audioDeviceId: Int, boardSerial: String, verifiedAddress: String) = active().also {
            inspections++; addresses += verifiedAddress
        }
        override fun acquire(owner: IBinder, audioDeviceId: Int, boardSerial: String, verifiedAddress: String, connection: Long,
            expiresAt: Long, physicalLevel: Int, events: IUsbAbsoluteVolumeEvents): Bundle {
            starts++; addresses += verifiedAddress; return active()
        }
        override fun renew(owner: IBinder, connection: Long, expiresAt: Long, verifiedAddress: String) = active().also {
            renewals++; addresses += verifiedAddress
        }
        override fun notifyPhysicalVolume(owner: IBinder, connection: Long, level: Int, verifiedAddress: String,
            acknowledgedRequestSequence: Long) = active().also {
            notifications++; addresses += verifiedAddress
            notificationRequests += Notification(connection, level, verifiedAddress, acknowledgedRequestSequence)
        }
        override fun release(owner: IBinder) = Bundle().apply { releases++; putBoolean("cleanup_complete", clean) }
    }
    private fun proof() = Bundle().apply {
        putBoolean("enabled", true); putInt("device_id", 3); putString("board_serial", "BOARD_A")
        putString("device_address", "card=1;device=0")
        putLong("control_session", 7); putLong("connection_generation", 8); putInt("capabilities", 128)
        putInt("version", 1); putInt("flags", 25); putInt("level", 64); putLong("observation_revision", 2)
        putLong("firmware_token", 9); putLong("ttl_ms", 4_000); putLong("usb_generation", 10)
        putLong("observed_at", SystemClock.elapsedRealtime())
    }
    private fun bridge() = RootUsbAbsoluteVolume { _, _ -> "card=1;device=0" }
    @Test fun absentOptInCapabilityOrConfirmedPhysicalStateNeverActivatesPolicy() {
        ShadowProcess.setUid(0)
        for (change in listOf<(Bundle) -> Unit>({ it.remove("enabled") }, { it.putInt("capabilities", 127) },
            { it.putInt("flags", 27) }, { it.putInt("flags", 29) }, { it.putLong("firmware_token", 0) },
            { it.putLong("usb_generation", 0) }, { it.putInt("level", 255) }, { it.putInt("version", 2) },
            { it.putLong("ttl_ms", 250) }, { it.putString("extra", "not accepted") })) {
            val policy = Policy()
            val request = proof().also(change)
            assertFalse(bridge().start(owner, policy, events, request).getBoolean("active"))
            assertEquals(0, policy.starts)
        }
        ShadowProcess.setUid(2000)
        val policy = Policy()
        assertFalse(bridge().start(owner, policy, events, proof()).getBoolean("active"))
        assertEquals(0, policy.starts)
    }
    @Test fun anotherOwnerAndChangedFirmwareIdentityCannotReuseTheLease() {
        ShadowProcess.setUid(0)
        val bridge = bridge(); val policy = Policy()
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        val other = object : IPlaybackRecoveryOwner.Stub() { override fun isCurrent() = true }
        bridge.release(other)
        assertEquals(0, policy.releases)
        assertFalse(bridge.renew(owner, proof().apply { putLong("usb_generation", 11) }).getBoolean("active"))
        assertEquals(1, policy.releases)
    }
    @Test fun cleanupDebtRemainsRetryableAfterTheFirstReleaseFails() {
        ShadowProcess.setUid(0)
        val bridge = bridge(); val policy = Policy()
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        policy.clean = false
        assertFalse(bridge.release(owner).getBoolean("cleanup_complete"))
        assertFalse(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        policy.clean = true
        assertTrue(bridge.release(owner).getBoolean("cleanup_complete"))
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        bridge.close()
        assertEquals(3, policy.releases)
    }

    @Test fun aValidPendingSetCanRenewButCannotNotifyAnUnknownPhysicalLevel() {
        ShadowProcess.setUid(0)
        val bridge = bridge(); val policy = Policy()
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        val pending = proof().apply { putInt("flags", 28); putInt("level", 255) }
        assertTrue(bridge.renew(owner, pending).getBoolean("active"))
        assertFalse(bridge.notify(owner, pending).getBoolean("active"))
        assertEquals(1, policy.releases)
    }

    @Test fun rootResolvesAddressBeforeInspectionAndRejectsForgedAppAddresses() {
        ShadowProcess.setUid(0)
        var reads = 0
        val bridge = RootUsbAbsoluteVolume { device, serial ->
            assertEquals(3, device); assertEquals("BOARD_A", serial); reads++; "card=1;device=0"
        }
        val policy = Policy()
        val inspection = bridge.inspect(policy, 3, "BOARD_A")
        assertEquals("card=1;device=0", inspection.getString("device_address"))
        assertEquals(3, inspection.getInt("device_id"))
        assertEquals(1, reads); assertEquals(1, policy.inspections)
        val forged = proof().apply { putString("device_address", "card=2;device=0") }
        assertEquals("root_identity_unavailable", bridge.start(owner, policy, events, forged).getString("reason"))
        assertEquals(2, reads); assertEquals(0, policy.starts)
        assertEquals(listOf("card=1;device=0"), policy.addresses)
    }

    @Test fun everyRenewAndNotificationRechecksRootIdentityAndFailureReleasesUnity() {
        ShadowProcess.setUid(0)
        var address: String? = "card=1;device=0"
        var reads = 0
        val bridge = RootUsbAbsoluteVolume { _, _ -> reads++; address }
        val policy = Policy()
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        assertTrue(bridge.renew(owner, proof()).getBoolean("active"))
        assertTrue(bridge.notify(owner, proof()).getBoolean("active"))
        assertEquals(3, reads); assertEquals(1, policy.renewals); assertEquals(1, policy.notifications)
        address = "card=2;device=0"
        assertEquals("root_identity_unavailable", bridge.renew(owner, proof()).getString("reason"))
        assertEquals(1, policy.releases); assertEquals(1, policy.renewals)
        address = "card=1;device=0"
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        address = null
        assertEquals("root_identity_unavailable", bridge.notify(owner, proof()).getString("reason"))
        assertEquals(2, policy.releases); assertEquals(1, policy.notifications)
    }

    @Test fun inaccessibleRootIdentityNeverReachesSystemPolicy() {
        ShadowProcess.setUid(0)
        val bridge = RootUsbAbsoluteVolume { _, _ -> throw SecurityException("private device data") }
        val policy = Policy()
        assertEquals("root_identity_unavailable", bridge.inspect(policy, 3, "BOARD_A").getString("reason"))
        assertEquals("root_identity_unavailable", bridge.start(owner, policy, events, proof()).getString("reason"))
        assertEquals(0, policy.inspections); assertEquals(0, policy.starts)
    }

    @Test fun nativeAndAcknowledgedNotificationsForwardTheirSequenceWithFreshIdentityProof() {
        ShadowProcess.setUid(0)
        var identityReads = 0
        val bridge = RootUsbAbsoluteVolume { device, serial ->
            assertEquals(3, device); assertEquals("BOARD_A", serial)
            identityReads++; "card=1;device=0"
        }
        val policy = Policy()
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        assertTrue(bridge.notify(owner, proof().apply {
            putInt("level", 71); putLong("observation_revision", 3)
        }, 0).getBoolean("active"))
        assertTrue(bridge.notify(owner, proof().apply {
            putInt("level", 90); putLong("observation_revision", 4)
        }, 47).getBoolean("active"))
        assertEquals(3, identityReads)
        assertEquals(listOf(
            Policy.Notification(7, 71, "card=1;device=0", 0),
            Policy.Notification(7, 90, "card=1;device=0", 47),
        ), policy.notificationRequests)
        assertEquals(0, policy.releases)
    }

    @Test fun aPositiveAcknowledgementCannotBypassTheBoundUsbIdentity() {
        ShadowProcess.setUid(0)
        var address = "card=1;device=0"
        val bridge = RootUsbAbsoluteVolume { _, _ -> address }
        val policy = Policy()
        assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
        address = "card=2;device=0"
        assertEquals("root_identity_unavailable", bridge.notify(owner, proof(), 47).getString("reason"))
        assertTrue(policy.notificationRequests.isEmpty())
        assertEquals(0, policy.notifications)
        assertEquals(1, policy.releases)
    }

    @Test fun negativeAcknowledgementSequencesNeverReachSystemPolicy() {
        ShadowProcess.setUid(0)
        for (sequence in listOf(-1L, Long.MIN_VALUE)) {
            val bridge = bridge()
            val policy = Policy()
            assertTrue(bridge.start(owner, policy, events, proof()).getBoolean("active"))
            assertFalse(bridge.notify(owner, proof(), sequence).getBoolean("active"))
            assertEquals(0, policy.notifications)
            assertTrue(policy.notificationRequests.isEmpty())
            bridge.close()
        }
    }
}
