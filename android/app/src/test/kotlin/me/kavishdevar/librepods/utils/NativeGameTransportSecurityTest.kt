/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class NativeGameTransportSecurityTest {
    private val rules = NativeGameTransportRules
    private val process = "11111111-1111-4111-8111-111111111111"
    private val client = "22222222-2222-4222-8222-222222222222"
    private val other = "33333333-3333-4333-8333-333333333333"
    private val peer = byteArrayOf(1, 2, 3, 4, 5, 6)

    @Test fun signaturePermissionDoesNotReplaceUidAndPackageChecks() {
        assertTrue(rules.requestSender(10345, rules.APP, 10345))
        for (uid in listOf(-1, 0, 1000, 1002, 2000, 10346))
            assertFalse(rules.requestSender(uid, rules.APP, 10345))
        assertFalse(rules.requestSender(10345, "another.signed.app", 10345))
        assertFalse(rules.requestSender(1002, rules.APP, 1002))
        assertFalse(rules.requestSender(10345, null, 10345))
        for (bluetooth in rules.BLUETOOTH_PACKAGES) {
            assertTrue(rules.replySender(1002, bluetooth, bluetooth))
            assertFalse(rules.replySender(-1, bluetooth, bluetooth))
            assertFalse(rules.replySender(0, bluetooth, bluetooth))
            assertFalse(rules.replySender(1002, rules.APP, bluetooth))
            assertFalse(rules.replySender(1002, bluetooth, "unknown.bluetooth"))
            assertFalse(rules.replySender(1002, rules.BLUETOOTH_PACKAGES.first { it != bluetooth }, bluetooth))
        }
    }

    @Test fun onlyAQueryCanEstablishANewLeaseAndEveryMutationUsesTheProcessNonce() {
        val session = NativeGameSession(process)
        val token = Any()
        assertEquals(rules.STALE, session.accept(token, client, 1, rules.PROBE_FLUSH, process))
        assertEquals(rules.OK, session.accept(token, client, 1, rules.QUERY, null))
        assertEquals(rules.STALE, session.accept(token, client, 2, rules.PROBE_FLUSH, other))
        assertEquals(rules.STALE, session.accept(token, client, 2, rules.PROBE_FLUSH, null))
        assertEquals(rules.OK, session.accept(token, client, 2, rules.PROBE_FLUSH, process))
    }

    @Test fun replayAndAnotherOwnerCannotConsumeOrStealTheLiveLease() {
        val session = NativeGameSession(process)
        val token = Any()
        val stranger = Any()
        assertEquals(rules.OK, session.accept(token, client, 10, rules.QUERY, null))
        assertEquals(rules.STALE, session.accept(token, client, 10, rules.PROBE_FLUSH, process))
        assertEquals(rules.STALE, session.accept(token, client, 9, rules.CANCEL, process))
        assertEquals(rules.BUSY, session.accept(stranger, other, Long.MAX_VALUE, rules.QUERY, null))
        assertEquals(rules.BUSY, session.accept(token, other, 11, rules.CANCEL, process))
        assertEquals(rules.OK, session.accept(token, client, 11, rules.POLL, process))
        assertFalse(session.release(stranger))
        assertEquals(rules.OK, session.accept(token, client, 12, rules.CANCEL, process))
    }

    @Test fun binderDeathReleasesOnlyItsOwnSessionAndOldProcessNoncesNeverAuthorizeWrites() {
        val old = NativeGameSession(process)
        val oldToken = Any()
        assertEquals(rules.OK, old.accept(oldToken, client, 1, rules.QUERY, null))
        assertTrue(old.release(oldToken))
        val newToken = Any()
        assertEquals(rules.OK, old.accept(newToken, other, 1, rules.QUERY, null))
        assertFalse(old.release(oldToken))
        assertEquals(rules.OK, old.accept(newToken, other, 2, rules.POLL, process))
        val restarted = NativeGameSession(other)
        assertEquals(rules.OK, restarted.accept(newToken, client, 50, rules.QUERY, process))
        assertEquals(rules.STALE, restarted.accept(newToken, client, 51, rules.PROBE_FLUSH, process))
        assertEquals(rules.OK, restarted.accept(newToken, client, 51, rules.PROBE_FLUSH, other))
    }

    @Test fun malformedNoncesAndNonpositiveRequestIdsNeverClaimALease() {
        for (nonce in listOf("", "x".repeat(36), "A" + client.drop(1), client.dropLast(1), "$client/")) {
            val session = NativeGameSession(process)
            assertEquals(rules.INVALID, session.accept(Any(), nonce, 1, rules.QUERY, null))
            assertEquals(rules.OK, session.accept(Any(), client, 1, rules.QUERY, null))
        }
        val session = NativeGameSession(process)
        assertEquals(rules.INVALID, session.accept(Any(), client, 0, rules.QUERY, null))
        assertEquals(rules.INVALID, session.accept(Any(), client, Long.MIN_VALUE, rules.QUERY, null))
    }

    @Test fun fixedOperationsRequireTheEntireLiveOwnerAndRejectExtraPayloads() {
        assertTrue(rules.validPayload(rules.QUERY, null, 0, 0))
        assertTrue(rules.validPayload(rules.POLL, null, 0, 1))
        assertTrue(rules.validPayload(rules.PROBE_FLUSH, peer, 7, 0, 8, 64))
        assertTrue(rules.validPayload(rules.READ_FLUSH, peer, 7, 0, 8, 64))
        assertFalse(rules.validPayload(999, peer, 7, 0, 8, 64))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, ByteArray(6), 7, 0, 8, 64))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, ByteArray(6) { -1 }, 7, 0, 8, 64))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, peer.copyOf(7), 7, 0, 8, 64))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, peer, 0, 0, 8, 64))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, peer, 7, 0, 0, 64))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, peer, 7, 0, 8, 0xf00))
        assertFalse(rules.validPayload(rules.PROBE_FLUSH, peer, 7, 1, 8, 64))
        assertFalse(rules.validPayload(rules.POLL, null, 0, 0))
        assertFalse(rules.validPayload(rules.CANCEL, peer, 0, 0))
        assertFalse(rules.validPayload(rules.QUERY, null, 0, 0, 1, 64))
    }

    @Test fun productionClockAndRestorationNeverInferAuthorityFromTheSharedAppLease() {
        assertTrue(rules.validPayload(rules.PREPARE, peer, 7, 0, 8, 64))
        assertTrue(rules.validPayload(rules.RESTORE, null, 0, 0, holdSerial = 10))
        assertTrue(rules.validPayload(rules.BEGIN_CLOCK, peer, 7, 0, 8, 64, 10, 9, 22))
        assertFalse(rules.validPayload(rules.BEGIN_CLOCK, peer, 7, 0, 8, 64, 0, 9, 22))
        assertFalse(rules.validPayload(rules.BEGIN_CLOCK, peer, 7, 0, 8, 64, 10, 10, 0))
        assertFalse(rules.validPayload(rules.BEGIN_CLOCK, peer, 7, 0, 8, 64, 10, 11, 501))
        assertFalse(rules.validPayload(rules.RESTORE, null, 0, 0, holdSerial = 0))
        assertEquals(0, rules.timeoutRestoreSerial(rules.PREPARE, 0, 0).toInt())
        assertEquals(0, rules.timeoutRestoreSerial(rules.QUERY, 99, 99).toInt())
        assertEquals(10, rules.timeoutRestoreSerial(rules.POLL, 10, 0).toInt())
        assertEquals(11, rules.timeoutRestoreSerial(rules.BEGIN_CLOCK, 0, 11).toInt())
        // A different controller's unknown operation cannot authorize a broad owner cancellation.
        assertEquals(0, rules.timeoutRestoreSerial(rules.PROBE_FLUSH, 0, 0).toInt())
    }
}
