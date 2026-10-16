/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import kotlinx.coroutines.awaitCancellation
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowSettings
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [PairingDeviceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NearbyPairingControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var adapter: BluetoothAdapter
    private lateinit var controller: NearbyPairingController

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        controller = newController()
    }

    @After fun tearDown() { controller.close() }

    @Test fun soleFreshClassicCandidateBondsAtTwoSecondsWithoutWaitingForInquiryEnd() {
        connect()
        val device = device(1)
        found(device)
        val observedAt = field("firstCandidateAt") as Long
        advanceTo(observedAt + 1_000)
        found(device)
        // Native view invalidation can advance the test clock by a frame during broadcast delivery.
        advanceTo(observedAt + 1_900)
        assertTrue(SystemClock.elapsedRealtime() < observedAt + 2_000)
        assertEquals(0, pairing(device).requests)
        advanceTo(observedAt + 2_000)
        assertEquals(1, pairing(device).requests)
        assertEquals("Bonding must use the original candidate's exact two-second deadline", 2_000L,
            pairing(device).bondAt - observedAt)
        assertEquals(BluetoothDevice.TRANSPORT_BREDR, pairing(device).transport)
        assertFalse("Our inquiry must stop before Android pairing starts", pairing(device).discoveringAtBond)
        assertFalse(adapter.isDiscovering)
        advance(15_000)
        assertEquals("The old full-scan callback must not request another bond", 1, pairing(device).requests)
    }

    @Test fun anotherCandidateBeforeTheSelectionDeadlinePreventsAutomaticPairing() {
        connect()
        val first = device(1)
        val second = device(2)
        found(first)
        val observedAt = field("firstCandidateAt") as Long
        advanceTo(observedAt + 1_900)
        found(second)
        assertTrue("The second candidate must arrive before selection", SystemClock.elapsedRealtime() < observedAt + 2_000)
        advance(2_001)
        assertEquals(0, pairing(first).requests)
        assertEquals(0, pairing(second).requests)
        assertTrue(adapter.isDiscovering)
    }

    @Test fun namesResolveOnlyForClassicAddressesFoundInThisScan() {
        connect()
        val unrelated = device(2)
        nameChanged(unrelated, "AirPods Pro 3")
        advance(2_000)
        assertEquals(0, pairing(unrelated).requests)

        val candidate = device(1, name = null)
        found(candidate)
        advance(500)
        nameChanged(candidate, "Studio AirPods Pro 3")
        advance(1_999)
        assertEquals(0, pairing(candidate).requests)
        advance(1)
        assertEquals("A delayed name must not wait for another discovery cycle", 1, pairing(candidate).requests)
    }

    @Test fun aConflictingResolvedNameRetiresThePendingSelection() {
        connect()
        val candidate = device(1)
        found(candidate)
        advance(1_000)
        nameChanged(candidate, "AirPods Max")
        advance(2_000)
        assertEquals(0, pairing(candidate).requests)
    }

    @Test fun leDiscoveryCannotBecomeAClassicCandidateThroughCachedDualTypeOrNameEvents() {
        connect()
        val candidate = device(1)
        found(candidate, null)
        found(candidate, BluetoothDevice.DEVICE_TYPE_LE)
        nameChanged(candidate, "AirPods Pro 3")
        advance(2_000)
        assertEquals(0, pairing(candidate).requests)
        found(candidate, BluetoothDevice.DEVICE_TYPE_CLASSIC)
        advance(2_000)
        assertEquals(1, pairing(candidate).requests)
    }

    @Test fun canceledInvitationRetiresItsReceiverAndPendingSelectionBeforeReopening() {
        connect()
        val previous = device(1)
        found(previous)
        val oldReceiver = field("receiver") as BroadcastReceiver
        advance(1_000)
        controller.cancelPending()
        idle()
        connect()
        oldReceiver.onReceive(context, discovery(previous, BluetoothDevice.DEVICE_TYPE_CLASSIC))
        val current = device(2)
        found(current)
        advance(2_000)
        assertEquals(0, pairing(previous).requests)
        assertEquals(1, pairing(current).requests)
    }

    @Test fun globalDeadlineAndForeignDiscoveryOwnershipSurviveTheFastPath() {
        adapter.startDiscovery()
        connect()
        assertTrue("Connect must not cancel another app's discovery", adapter.isDiscovering)
        controller.close()
        assertTrue("Closing the invitation must not cancel another app's discovery", adapter.isDiscovering)
        adapter.cancelDiscovery()

        controller = newController()
        connect()
        advance(59_000)
        val late = device(1)
        found(late)
        advance(2_000)
        assertEquals("Selection must not outlive the global 60s request deadline", 0, pairing(late).requests)
        assertFalse(adapter.isDiscovering)
        assertTrue(button().isEnabled)
    }

    @Test
    @Config(sdk = [35])
    fun olderAndroidRequiresAClassicCapableDeviceAndUsesTheLegacyBondApi() {
        connect()
        val le = device(1, type = BluetoothDevice.DEVICE_TYPE_LE)
        val unknown = device(2, type = BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        val cachedDual = device(4)
        found(le, null)
        found(unknown, null)
        found(cachedDual, BluetoothDevice.DEVICE_TYPE_LE)
        advance(2_000)
        assertEquals(0, pairing(le).requests)
        assertEquals(0, pairing(unknown).requests)
        assertEquals(0, pairing(cachedDual).requests)
        val classic = device(3, type = BluetoothDevice.DEVICE_TYPE_CLASSIC)
        found(classic, null)
        advance(2_000)
        assertEquals(1, pairing(classic).requests)
        assertNull(pairing(classic).transport)
    }

    @Test fun consentConfirmationRequiresTheSelectedBondingDeviceAndRetiresWithItsExactReceiver() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val calls = AtomicInteger()
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, stillCurrent ->
            calls.incrementAndGet()
            guard.set(stillCurrent)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        val firstReceiver = field("receiver") as BroadcastReceiver
        pairingRequest(selected, 3) // No real BOND_BONDING state yet.
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairingRequest(device(2), 3)
        pairingRequest(selected, 1) // PIN/passkey variants always remain Android's responsibility.
        assertEquals(0, calls.get())
        pairingRequest(selected, 3)
        assertTrue("Consent job did not run", entered.await(2, TimeUnit.SECONDS))
        assertTrue(requireNotNull(guard.get()).invoke())
        pairingRequest(selected, 3)
        assertEquals(1, calls.get())

        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        bondChanged(selected, BluetoothDevice.BOND_NONE)
        assertFalse(requireNotNull(guard.get()).invoke())
        assertTrue("Retiring a bond receiver must cancel confirmation work", canceled.await(2, TimeUnit.SECONDS))
        advance(2_000)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        firstReceiver.onReceive(context, request(selected, 3))
        assertFalse("An old consent guard must reject a later bond to the same address", requireNotNull(guard.get()).invoke())
        assertEquals(1, calls.get())
    }

    private fun connect() {
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, true))
        idle()
        assertTrue(button().performClick())
        idle()
    }

    private fun newController() = NearbyPairingController(context, confirmConsent = { _, _ -> false })

    private fun field(name: String): Any? = NearbyPairingController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(controller)

    private fun button(): Button {
        val popup = field("popup") as PopupWindow
        val view = PopupWindow::class.java.getDeclaredField("mView").apply { isAccessible = true }.get(popup) as View
        return view.findViewById(R.id.pairing_primary)
    }

    private fun device(id: Int, name: String? = "AirPods Pro 3", type: Int = BluetoothDevice.DEVICE_TYPE_DUAL): BluetoothDevice =
        adapter.getRemoteDevice("00:11:22:33:44:${id.toString(16).padStart(2, '0')}").also {
            pairing(it).setName(name)
            pairing(it).setType(type)
            pairing(it).setBondState(BluetoothDevice.BOND_NONE)
        }

    private fun discovery(device: BluetoothDevice, type: Int?): Intent = Intent(BluetoothDevice.ACTION_FOUND)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        .apply { if (type != null) putExtra(BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE, type) }

    private fun found(device: BluetoothDevice, type: Int? = BluetoothDevice.DEVICE_TYPE_CLASSIC) {
        context.sendBroadcast(discovery(device, type))
        idle()
    }

    private fun nameChanged(device: BluetoothDevice, name: String) {
        pairing(device).setName(name)
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_NAME_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_NAME, name))
        idle()
    }

    private fun request(device: BluetoothDevice, variant: Int) = Intent(BluetoothDevice.ACTION_PAIRING_REQUEST)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant)

    private fun pairingRequest(device: BluetoothDevice, variant: Int) {
        context.sendBroadcast(request(device, variant))
        idle()
    }

    private fun bondChanged(device: BluetoothDevice, state: Int) {
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_BOND_STATE, state))
        idle()
    }

    private fun pairing(device: BluetoothDevice): PairingDeviceShadow = Shadow.extract(device)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun advanceTo(target: Long) {
        val remaining = target - SystemClock.elapsedRealtime()
        assertTrue("Fixture work crossed the requested timer boundary", remaining >= 0)
        advance(remaining)
    }
}

@Implements(BluetoothDevice::class)
class PairingDeviceShadow : ShadowBluetoothDevice() {
    var requests = 0
    var transport: Int? = null
    var discoveringAtBond = false
    var bondAt = 0L

    @Implementation public override fun createBond(): Boolean = record(null)
    @Implementation public override fun createBond(transport: Int): Boolean = record(transport)

    private fun record(selectedTransport: Int?): Boolean {
        requests++
        transport = selectedTransport
        bondAt = SystemClock.elapsedRealtime()
        discoveringAtBond = BluetoothAdapter.getDefaultAdapter()!!.isDiscovering
        return true
    }
}
