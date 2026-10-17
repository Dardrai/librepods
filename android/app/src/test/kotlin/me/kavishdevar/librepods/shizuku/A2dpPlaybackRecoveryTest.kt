package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class A2dpPlaybackRecoveryTest {
    private val address = "00:11:22:33:44:55"
    private val profile = mock(BluetoothA2dp::class.java)
    private val device = mock(BluetoothDevice::class.java)
    private val owner = mock(IPlaybackRecoveryOwner::class.java)
    private var state = BluetoothProfile.STATE_DISCONNECTED
    private var elapsed = 0L
    private var ownerCurrent = true
    private var bonded = true
    private var tick: () -> Unit = {}
    private var connects = 0
    private var disconnects = 0

    init {
        `when`(profile.connectedDevices).thenReturn(emptyList())
        `when`(profile.getConnectionState(device)).thenAnswer { state }
        `when`(device.address).thenReturn(address)
        `when`(device.bondState).thenAnswer { if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE }
        `when`(owner.isCurrent).thenAnswer { ownerCurrent }
        stub("connect") { connects++; state = BluetoothProfile.STATE_CONNECTING; true }
        stub("disconnect") { disconnects++; state = BluetoothProfile.STATE_DISCONNECTED; true }
    }

    @Test fun explicitConnectNeverCyclesAnAlreadyConnectedMediaProfile() {
        state = BluetoothProfile.STATE_CONNECTED
        assertTrue(run())
        assertEquals(0, connects)
        assertEquals(0, disconnects)
        assertEquals(0L, elapsed)
    }

    @Test fun anExistingConnectionAttemptIsJoinedWithoutSendingAnotherConnect() {
        state = BluetoothProfile.STATE_CONNECTING
        tick = { if (elapsed >= 120) state = BluetoothProfile.STATE_CONNECTED }
        assertTrue(run())
        assertEquals(0, connects)
        assertEquals(0, disconnects)
    }

    @Test fun acceptedConnectionRemainsPendingBeyondTheOldFiveSecondDeadlineUntilItReallyConnects() {
        tick = { if (elapsed >= 6_300) state = BluetoothProfile.STATE_CONNECTED }
        assertTrue(run())
        assertTrue(elapsed > 5500)
        assertEquals(1, connects)
        assertEquals(0, disconnects)
    }

    @Test fun aDuplicateConnectRejectionStillJoinsAndroidsConcurrentConnectionAttempt() {
        stub("connect") { connects++; state = BluetoothProfile.STATE_CONNECTING; false }
        tick = { if (elapsed >= 120) state = BluetoothProfile.STATE_CONNECTED }
        assertTrue(run())
        assertEquals(1, connects)
    }

    @Test fun anAttemptWhichActuallyDisconnectsFailsImmediatelyWithoutIssuingAnotherConnect() {
        tick = { if (elapsed >= 120) state = BluetoothProfile.STATE_DISCONNECTED }
        assertFalse(run())
        assertEquals(120L, elapsed)
        assertEquals(1, connects)
    }

    @Test fun aStalledAttemptHasABoundedDeadline() {
        assertFalse(run())
        assertEquals(18_000L, elapsed)
        assertEquals(1, connects)
    }

    @Test fun anOwnerRetiredDuringConnectionStopsWaitingWithoutDisconnectingOrRetrying() {
        tick = { if (elapsed >= 120) ownerCurrent = false }
        assertFalse(run())
        assertEquals(120L, elapsed)
        assertEquals(1, connects)
        assertEquals(0, disconnects)
    }

    @Test fun anotherLocalMediaDeviceCannotBeDisconnectedByTheSelectedConnection() {
        val other = mock(BluetoothDevice::class.java)
        `when`(other.address).thenReturn("00:11:22:33:44:66")
        `when`(profile.connectedDevices).thenReturn(listOf(other))
        assertFalse(run())
        assertEquals(0, connects)
        assertEquals(0, disconnects)
    }

    @Test fun microphoneRecoveryKeepsItsSelectedProfileCycle() {
        state = BluetoothProfile.STATE_CONNECTED
        tick = { if (elapsed >= 120) state = BluetoothProfile.STATE_CONNECTED }
        assertTrue(run(connectOnly = false))
        assertEquals(1, disconnects)
        assertEquals(1, connects)
    }

    @Test fun aDisconnectAlreadyInProgressFinishesBeforeASingleNewConnection() {
        state = BluetoothProfile.STATE_DISCONNECTING
        tick = {
            if (elapsed == 120L) state = BluetoothProfile.STATE_DISCONNECTED
            if (elapsed >= 240) state = BluetoothProfile.STATE_CONNECTED
        }
        assertTrue(run())
        assertEquals(1, connects)
        assertEquals(0, disconnects)
    }

    @Test fun unpairingDuringConnectionRetiresTheSelectedRequest() {
        tick = { if (elapsed >= 120) bonded = false }
        assertFalse(run())
        assertEquals(120L, elapsed)
        assertEquals(1, connects)
        assertEquals(0, disconnects)
    }

    private fun run(connectOnly: Boolean = true): Boolean =
        A2dpPlaybackRecovery(mock(Context::class.java), address, owner, connectOnly).connectProfile(profile, device,
            if (connectOnly) 18_000 else 5500, now = { elapsed }, pause = { elapsed += 40; tick() })

    private fun stub(method: String, action: () -> Boolean) {
        val recording = doAnswer { action() }.`when`(profile)
        BluetoothA2dp::class.java.getDeclaredMethod(method, BluetoothDevice::class.java)
            .apply { isAccessible = true }.invoke(recording, device)
    }
}
