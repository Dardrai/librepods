package me.kavishdevar.librepods.shizuku

import android.bluetooth.BluetoothProfile
import org.junit.Assert.*
import org.junit.Test

class NativeCallConnectionTest {
    @Test fun aStateReadPastTheCallDeadlineCannotConnectOrClaimReadiness() {
        for (state in listOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTED)) {
            var elapsed = 0L
            var connects = 0
            assertFalse(NativeCallConnection.connectSelected({ elapsed = 18_000; state }, { true },
                { connects++; true }, { elapsed }, {}))
            assertEquals(0, connects)
        }
    }

    @Test fun aSlowCallPermissionProofCannotAuthorizeALateConnect() {
        var elapsed = 0L
        var connects = 0
        assertFalse(NativeCallConnection.connectSelected({ BluetoothProfile.STATE_DISCONNECTED },
            { elapsed = 18_000; true }, { connects++; true }, { elapsed }, {}))
        assertEquals(0, connects)
    }

    @Test fun aConnectAcknowledgedAfterTheDeadlineCannotBeFollowedByAnotherReadOrReceipt() {
        var elapsed = 0L
        var reads = 0
        var connects = 0
        assertFalse(NativeCallConnection.connectSelected({ reads++; BluetoothProfile.STATE_DISCONNECTED }, { true },
            { connects++; elapsed = 18_000; true }, { elapsed }, {}))
        assertEquals(1, reads); assertEquals(1, connects)
    }

    @Test fun ringingReadinessDoesNotPretendThatScoAudioHasStarted() {
        val ready = NativeCallConnection.receipt(BluetoothProfile.STATE_CONNECTED, true, 10)
        assertEquals(NativeCallConnection.READY, ready and NativeCallConnection.READY)
        assertEquals(0, ready and NativeCallConnection.SCO_CONNECTED)
        assertTrue(ready and NativeCallConnection.SCO_IDLE != 0)
        assertTrue(NativeCallConnection.receipt(2, true, 12) and NativeCallConnection.SCO_CONNECTED != 0)
        assertEquals(0, NativeCallConnection.receipt(2, true, -1))
        assertEquals(0, NativeCallConnection.receipt(2, false, 12) and NativeCallConnection.SELECTED_ACTIVE)
    }

    @Test fun aRetiredCallCannotConnectAndAConnectingCallIsJoinedWithoutAnotherMutation() {
        var calls = 0
        assertFalse(NativeCallConnection.connectSelected({ 0 }, { false }, { calls++; true }, { 0 }, {}))
        assertEquals(0, calls)
        var time = 0L
        var state = 1
        assertTrue(NativeCallConnection.connectSelected({ state }, { true }, { calls++; true },
            { time }, { time += 40; state = 2 }))
        assertEquals(0, calls)
    }

    @Test fun anEndedCallStopsBeforeItsLateProfileConnectionAndFailedConnectionsAreNotRetried() {
        var time = 0L
        var active = true
        var state = 0
        var calls = 0
        assertFalse(NativeCallConnection.connectSelected({ state }, { active }, { calls++; state = 1; true },
            { time }, { time += 40; active = false; state = 2 }))
        assertEquals(1, calls)
        assertFalse(NativeCallConnection.connectSelected({ 0 }, { true }, { calls++; false }, { time }, {}))
        assertEquals(2, calls)
    }
}
