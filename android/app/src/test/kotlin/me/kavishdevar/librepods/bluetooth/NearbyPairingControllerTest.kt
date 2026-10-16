/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
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
import org.robolectric.shadows.ShadowBroadcastPendingResult
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
    private val nativeReceivers = mutableListOf<BroadcastReceiver>()

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        controller = newController()
    }

    @After fun tearDown() {
        controller.close()
        nativeReceivers.forEach(context::unregisterReceiver)
    }

    @Test fun soleFreshClassicCandidateBondsAtTwoSecondsWithoutWaitingForInquiryEnd() {
        connect()
        assertEquals(PopupWindow.Stage.SETUP, stage())
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
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
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
        assertEquals(PopupWindow.Stage.RETRY, stage())
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
        assertEquals(PopupWindow.Stage.RETRY, stage())
    }

    @Test fun aConfirmedBondShowsContinueWithoutDeferringThePairedCallback() {
        controller.close()
        val paired = mutableListOf<BluetoothDevice>()
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = { paired.add(it) })
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
        assertTrue(paired.isEmpty())

        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)

        assertEquals(listOf(selected), paired)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        assertEquals(context.getString(R.string.pairing_continue), button().text.toString())
        assertTrue(button().performClick())
        idle()
        assertNull(field("popup"))
        advance(3_000)
        assertEquals("Continue must not repeat the paired callback", listOf(selected), paired)
    }

    @Test fun aConfirmedBondWaitsForContinueOrThePhysicalLidClose() {
        controller.close()
        var pairedAt = 0L
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = {
            pairedAt = SystemClock.elapsedRealtime()
        })
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        advanceTo(pairedAt + 90_000)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, false))
        idle()
        assertNull(field("popup"))
    }

    @Test fun successBatteryReadingsBelongToTheExactNewBondAndCannotReopenAClosedCard() {
        val selected = bondingCandidate()
        val oldReading = (field("batteryObservedAfter") as Long) - 1
        val old = setOf(Battery(BatteryComponent.CASE, 25, BatteryStatus.NOT_CHARGING, oldReading))
        controller.updatePairingBattery(selected, old)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        controller.updatePairingBattery(selected, old)
        assertEquals("—", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())

        val foreign = device(2)
        pairing(foreign).setBondState(BluetoothDevice.BOND_BONDED)
        val live = setOf(
            Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 80, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, 70, BatteryStatus.CHARGING))
        controller.updatePairingBattery(foreign, live)
        assertEquals("—", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        controller.updatePairingBattery(selected, live)
        assertEquals("80%", popupView().findViewById<TextView>(R.id.pairing_buds_battery_text).text.toString())
        assertEquals("70%", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())

        controller.cancelPending()
        idle()
        assertEquals(0L, field("batteryObservedAfter"))
        controller.updatePairingBattery(selected, live)
        assertNull(field("popup"))
        connect()
        controller.updatePairingBattery(selected, live)
        assertEquals(PopupWindow.Stage.SETUP, stage())
        assertEquals(View.GONE, popupView().findViewById<View>(R.id.pairing_battery_row).visibility)
    }

    @Test fun verifiedInvitationAndPreConnectReadingsCanSeedTheCompletedCardBeforeAnotherReport() {
        controller.close()
        var verified = emptySet<Battery>()
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = {
            controller.updatePairingBattery(it, verified)
        })
        advance(1_000)
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, true), SystemClock.elapsedRealtimeNanos() - 500_000_000L)
        idle()
        assertEquals(PopupWindow.Stage.NEARBY, stage())
        val observed = field("batteryObservedAfter") as Long
        verified = setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING, observed + 100),
            Battery(BatteryComponent.RIGHT, 82, BatteryStatus.NOT_CHARGING, observed + 100),
            Battery(BatteryComponent.CASE, 76, BatteryStatus.CHARGING, observed))
        assertTrue("The replay fixture must precede tapping Connect", verified.all { it.observedAt!! < System.currentTimeMillis() })
        assertTrue(button().performClick())
        idle()
        assertEquals("Initial Connect must retain the accepted invitation's observation floor", observed, field("batteryObservedAfter"))
        val selected = device(1)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        controller.updatePairingBattery(selected, verified)
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
        assertEquals(View.GONE, popupView().findViewById<View>(R.id.pairing_battery_row).visibility)

        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        assertEquals("82%", popupView().findViewById<TextView>(R.id.pairing_buds_battery_text).text.toString())
        assertEquals("76%", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        idle()
        assertEquals("Success must not clear a synchronous verified seed afterward", "76%",
            popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        controller.close()
        assertEquals(0L, field("batteryObservedAfter"))
        controller.updatePairingBattery(selected, verified)
        assertNull(field("popup"))
    }

    @Test fun retryRetiresThePreviousBatteryFloorAndRejectsEarlierReadings() {
        val selected = bondingCandidate()
        advance(60_000)
        assertEquals(PopupWindow.Stage.RETRY, stage())
        assertEquals(0L, field("batteryObservedAfter"))
        assertTrue(button().performClick())
        idle()
        val observed = field("batteryObservedAfter") as Long
        assertTrue(observed > 0L)
        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        controller.updatePairingBattery(selected, setOf(Battery(BatteryComponent.CASE, 25,
            BatteryStatus.NOT_CHARGING, observed - 1)))
        assertEquals("—", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        controller.updatePairingBattery(selected, setOf(Battery(BatteryComponent.CASE, 73,
            BatteryStatus.NOT_CHARGING, observed)))
        assertEquals("73%", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
    }

    @Test fun removingTheCompletedBondClosesOnlyItsOwnSuccessCard() {
        val selected = bondingCandidate()
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        controller.updatePairingBattery(device(2), emptySet())
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        controller.updatePairingBattery(selected, emptySet())
        assertNull(field("popup"))
        assertEquals(0L, field("batteryObservedAfter"))
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

    @Test fun orderedConsentWaitsForConfirmationAndSkipsTheNativeDialogOnlyOnSuccess() {
        controller.close()
        val answer = CompletableDeferred<Boolean>()
        val entered = CountDownLatch(1)
        controller = NearbyPairingController(context, confirmConsent = { _, _ ->
            entered.countDown()
            answer.await()
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = field("pendingConsent") as BroadcastReceiver.PendingResult
        assertEquals(0, native.get())

        answer.complete(true)
        awaitMainCondition { field("pendingConsent") == null }
        assertFinishedConsent(held, aborted = true)
        advance(1_500)
        assertFinishedConsent(held, aborted = true)
    }

    @Test fun aBondedResultAbortsHeldConsentBeforeRetiringItsConfirmationJob() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        controller = NearbyPairingController(context, confirmConsent = { _, _ ->
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = field("pendingConsent") as BroadcastReceiver.PendingResult
        assertEquals(0, native.get())

        // Exercise an early bond callback before its confirmation IPC has returned.
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        (field("receiver") as BroadcastReceiver).onReceive(context,
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, selected)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED))
        idle()
        assertNull(field("pendingConsent"))
        assertTrue(canceled.await(2, TimeUnit.SECONDS))
        idle()
        assertFinishedConsent(held, aborted = true)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
    }

    @Test fun closingHeldConsentReturnsItToAndroidAndRejectsLateConfirmation() {
        controller.close()
        val entered = CountDownLatch(1)
        val answer = CompletableDeferred<Boolean>()
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, stillCurrent ->
            guard.set(stillCurrent)
            entered.countDown()
            withContext(NonCancellable) { answer.await() }
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = field("pendingConsent") as BroadcastReceiver.PendingResult
        val confirming = field("consentJob") as Job
        controller.cancelPending()
        idle()
        assertNull(field("pendingConsent"))
        assertEquals(1, native.get())
        assertFalse(requireNotNull(guard.get()).invoke())
        assertFinishedConsent(held, aborted = false)
        answer.complete(true)
        awaitMainCondition { confirming.isCompleted }
        assertFinishedConsent(held, aborted = false)
        advance(1_500)
        assertEquals("Cancellation and timeout must finish a held request only once", 1, native.get())
    }

    @Test fun heldConsentHasAnIndependentFifteenHundredMillisecondNativeFallback() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, stillCurrent ->
            guard.set(stillCurrent)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        val startedAt = SystemClock.elapsedRealtime()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        advanceTo(startedAt + 1_499)
        assertNotNull(field("pendingConsent"))
        assertEquals(0, native.get())
        advanceTo(startedAt + 1_500)
        assertNull(field("pendingConsent"))
        assertEquals(1, native.get())
        assertFalse(requireNotNull(guard.get()).invoke())
        assertTrue(canceled.await(2, TimeUnit.SECONDS))
    }

    @Test fun deniedOrFailedConfirmationLeavesTheNativeDialogAvailable() {
        val native = nativeFallback()
        for (fail in listOf(false, true)) {
            controller.close()
            controller = NearbyPairingController(context, confirmConsent = { _, _ ->
                if (fail) throw IllegalStateException("Bridge unavailable")
                false
            })
            val selected = bondingCandidate()
            orderedPairingRequest(selected, 3)
            awaitMainCondition { native.get() == if (fail) 2 else 1 }
            assertNull(field("pendingConsent"))
        }
    }

    @Test fun unrelatedAndNonConsentRequestsNeverWaitForOurConfirmation() {
        controller.close()
        val calls = AtomicInteger()
        controller = NearbyPairingController(context, confirmConsent = { _, _ ->
            calls.incrementAndGet()
            true
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(device(2), 3)
        orderedPairingRequest(selected, 2)
        assertEquals(2, native.get())
        assertEquals(0, calls.get())
        assertNull(field("pendingConsent"))
    }

    private fun connect() {
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, true))
        idle()
        assertEquals(PopupWindow.Stage.NEARBY, stage())
        assertTrue(button().performClick())
        idle()
    }

    private fun newController() = NearbyPairingController(context, confirmConsent = { _, _ -> false })

    private fun bondingCandidate(): BluetoothDevice {
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        return selected
    }

    private fun nativeFallback(): AtomicInteger {
        val received = AtomicInteger()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received.incrementAndGet() }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST), Context.RECEIVER_EXPORTED)
        nativeReceivers += receiver
        return received
    }

    private fun assertFinishedConsent(pending: BroadcastReceiver.PendingResult, aborted: Boolean) {
        // Robolectric 4.17 omits PendingResult's abort flag from its simulated ordered chain.
        assertEquals(aborted, pending.getAbortBroadcast())
        assertTrue(Shadow.extract<ShadowBroadcastPendingResult>(pending).future.isDone)
    }

    private fun field(name: String): Any? = NearbyPairingController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(controller)

    private fun button(): Button {
        return popupView().findViewById(R.id.pairing_primary)
    }

    private fun popupView(): View {
        val popup = field("popup") as PopupWindow
        return PopupWindow::class.java.getDeclaredField("mView").apply { isAccessible = true }.get(popup) as View
    }

    private fun stage(): PopupWindow.Stage = PopupWindow::class.java.getDeclaredField("stage")
        .apply { isAccessible = true }.get(field("popup")) as PopupWindow.Stage

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

    private fun orderedPairingRequest(device: BluetoothDevice, variant: Int) {
        context.sendOrderedBroadcast(request(device, variant), null)
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

    private fun awaitMainCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("Confirmation result did not return to the main thread", condition())
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
