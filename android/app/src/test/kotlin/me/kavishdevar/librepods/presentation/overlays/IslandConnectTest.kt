/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Looper
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandConnectTest {
    // Coroutine deadlines need their own clock; native looper draining can advance a delayed timeout unexpectedly.
    private val scheduler = TestCoroutineScheduler()
    @Before fun controlCoroutineClock() { Dispatchers.setMain(UnconfinedTestDispatcher(scheduler)) }
    @After fun restoreCoroutineClock() { Dispatchers.resetMain() }

    @Test fun repeatedTapsStartOneRequestKeepTheIslandOpenAndRetainNearbyBatteryUpdates() = withWindow { window, first, _ ->
        var calls = 0
        var audioConnected = false
        val answer = CompletableDeferred<Boolean>()
        window.showPairedLid(first, onConnect = { calls++; answer.await() }, isAudioConnected = { audioConnected })
        request(window)
        request(window)
        idle()
        assertEquals(1, calls)
        assertEquals(IslandConnectState.CONNECTING, state(window))
        assertTrue(window.isVisible)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue("The invitation timer cannot dismiss an active Connect request", window.isVisible)
        val reading = Battery(BatteryComponent.LEFT, 71, BatteryStatus.CHARGING)
        first.receiveNearbyBattery(setOf(reading))
        window.updatePairedBattery(first)
        @Suppress("UNCHECKED_CAST")
        val readings = getter(window, "getBatteries") as Set<Battery>
        assertEquals(71, readings.single().level)
        assertEquals(IslandConnectState.CONNECTING, state(window))
        audioConnected = true
        answer.complete(true)
        idle()
        assertEquals(IslandConnectState.IDLE, state(window))
        assertFalse(getter(window, "getNearby") as Boolean)
        assertTrue("Success should update the current island rather than dismiss and reopen it", window.isVisible)
    }

    @Test fun failedAndThrowingRequestsCanRetryAndCannotClaimThatAudioConnected() = withWindow { window, first, _ ->
        var attempts = 0
        var audioConnected = false
        window.showPairedLid(first, onConnect = {
            attempts++
            when (attempts) {
                1 -> throw IOException("Profile unavailable")
                2 -> false
                else -> { audioConnected = true; true }
            }
        }, isAudioConnected = { audioConnected })
        repeat(2) {
            request(window)
            idle()
            assertEquals(IslandConnectState.FAILED, state(window))
            assertTrue(getter(window, "getNearby") as Boolean)
            assertTrue(window.isVisible)
        }
        request(window)
        idle()
        assertEquals(3, attempts)
        assertEquals(IslandConnectState.IDLE, state(window))
        assertFalse(getter(window, "getNearby") as Boolean)
    }

    @Test fun stalledRequestTimesOutAndLeavesAVisibleRetryInsteadOfSpinningForever() = withWindow { window, first, _ ->
        var cancelled = false
        val answer = CompletableDeferred<Boolean>()
        fun diagnostics() = ShadowLog.getLogsForTag("IslandWindow").joinToString("\n") {
            it.msg + (it.throwable?.let { failure -> "\n" + failure.stackTraceToString() } ?: "")
        }
        window.showPairedLid(first, onConnect = {
            try { answer.await() } finally { cancelled = true }
        }, isAudioConnected = { false })
        request(window)
        assertEquals(diagnostics(), IslandConnectState.CONNECTING, state(window))
        scheduler.advanceTimeBy(24_000)
        scheduler.runCurrent()
        assertEquals(diagnostics(), IslandConnectState.CONNECTING, state(window))
        assertFalse("The request must not time out before its deadline", cancelled)
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
        assertTrue("The timeout must cancel the pending work", cancelled)
        assertEquals(diagnostics(), IslandConnectState.FAILED, state(window))
        assertTrue("A timed-out operation must leave its Retry action visible", window.isVisible)
    }

    @Test fun cancelledCompletionCannotAffectAReopenedCaseOrAnotherPair() = withWindow { window, first, second ->
        for (replaceOwner in listOf(false, true)) {
            val oldAnswer = CompletableDeferred<Boolean>()
            val newAnswer = CompletableDeferred<Boolean>()
            val next = if (replaceOwner) second else first
            var oldEntered = false
            var newEntered = false
            try {
                window.showPairedLid(first, onConnect = {
                    oldEntered = true
                    withContext(NonCancellable) { oldAnswer.await() }
                }, isAudioConnected = { false })
                request(window)
                idle()
                assertTrue(oldEntered)
                if (!replaceOwner) window.close()
                window.showPairedLid(next, onConnect = { newEntered = true; newAnswer.await() },
                    isAudioConnected = { false })
                request(window)
                idle()
                assertTrue(newEntered)
                oldAnswer.complete(true)
                idle()
                assertEquals("An old operation cannot complete the new opening", IslandConnectState.CONNECTING, state(window))
                assertEquals(next.macAddress.value, field(window, "ownerAddress"))
                assertTrue(getter(window, "getNearby") as Boolean)
                assertTrue(window.isVisible)
                newAnswer.complete(false)
                idle()
                assertEquals(IslandConnectState.FAILED, state(window))
                window.close()
            } finally {
                oldAnswer.complete(false)
                newAnswer.complete(false)
                idle()
            }
        }
    }

    @Test fun anOwnedCaseCloseFoldsBeforeDisconnectedBatteryCanReplaceItsVisiblePayload() = withWindow { window, first, second ->
        var audioConnected = true
        first.receiveNearbyBattery(setOf(Battery(BatteryComponent.LEFT, 99, BatteryStatus.CHARGING)))
        window.showPairedLid(first, observedAtNanos = 100, onConnect = { false }, isAudioConnected = { audioConnected })
        val visibleBattery = getter(window, "getBatteries")
        assertFalse(getter(window, "getNearby") as Boolean)
        window.observePairedCaseLid(first.macAddress.value, false, 200)
        assertEquals(IslandState.CLOSE, window.islandState)
        assertFalse(window.isVisible)
        audioConnected = false
        first.receiveNearbyBattery(setOf(Battery(BatteryComponent.LEFT, 99, BatteryStatus.DISCONNECTED)))
        window.updatePairedBattery(first)
        assertSame("The folding island must keep its last visible battery payload", visibleBattery, getter(window, "getBatteries"))
        assertFalse("Closing cannot morph Connected into Nearby/Connect", getter(window, "getNearby") as Boolean)
        window.showPairedLid(first, observedAtNanos = 300, onConnect = { false }, isAudioConnected = { audioConnected })
        window.observePairedCaseLid(first.macAddress.value, false, 200)
        window.observePairedCaseLid(second.macAddress.value, false, 400)
        assertTrue("A stale close or another pair cannot dismiss the newer opening", window.isVisible)
        window.observePairedCaseLid(first.macAddress.value, false, 301)
        assertEquals(IslandState.CLOSE, window.islandState)
    }

    @Test fun caseClosePreservesModeNoticesForeignNoticesAndOwnerlessPreviews() = withWindow { window, first, second ->
        val action: suspend () -> Boolean = { false }
        window.show(owner = first, observedAtNanos = 100, statusText = "Transparency", keepOpen = true, onConnect = action)
        window.observePairedCaseLid(first.macAddress.value, false, 101)
        assertTrue("A listening-mode alert is independent of the lid", window.isVisible)
        window.show(owner = first, observedAtNanos = 200, type = IslandType.MOVED_TO_REMOTE, keepOpen = true, onConnect = action)
        window.observePairedCaseLid(first.macAddress.value, false, 201)
        assertTrue(window.isVisible)
        window.show(owner = second, observedAtNanos = 300, keepOpen = true, onConnect = action)
        window.observePairedCaseLid(first.macAddress.value, false, 301)
        assertTrue(window.isVisible)
        window.show(observedAtNanos = 400, keepOpen = true, onConnect = action)
        window.observePairedCaseLid(first.macAddress.value, false, 401)
        assertTrue("An ownerless customization preview must stay open", window.isVisible)
    }

    @Test fun caseCloseCancelsAPendingRequestWithoutChangingItsFoldingSpinner() = withWindow { window, first, _ ->
        val answer = CompletableDeferred<Boolean>()
        var cancelled = false
        window.showPairedLid(first, observedAtNanos = 100, onConnect = {
            try { answer.await() } finally { cancelled = true }
        }, isAudioConnected = { false })
        request(window)
        assertEquals(IslandConnectState.CONNECTING, state(window))
        val visibleBattery = getter(window, "getBatteries")
        window.observePairedCaseLid(first.macAddress.value, false, 101)
        idle()
        assertTrue("The closed case must retire its pending operation promptly", cancelled)
        assertEquals(IslandState.CLOSE, window.islandState)
        assertEquals("The outgoing spinner must not turn back into Connect", IslandConnectState.CONNECTING, state(window))
        assertSame(visibleBattery, getter(window, "getBatteries"))
        assertTrue(getter(window, "getNearby") as Boolean)
        answer.complete(true)
        idle()
        assertEquals(IslandState.CLOSE, window.islandState)
        window.showPairedLid(first, observedAtNanos = 200, onConnect = { false }, isAudioConnected = { false })
        assertEquals("A new opening starts with its normal action state", IslandConnectState.IDLE, state(window))
        assertTrue(window.isVisible)
    }

    @Test fun previewsModeNoticesAndAnAlreadyConnectedPairCannotStartTheAction() = withWindow { window, first, _ ->
        var calls = 0
        val action: suspend () -> Boolean = { calls++; true }
        val hint: () -> Boolean = { error("Non-connection notices must not evaluate reconnection hints") }
        window.show(keepOpen = true, onConnect = action, deferConnectAction = hint,
            isAudioConnected = { error("An ownerless preview must not query a device's audio connection") })
        assertFalse(getter(window, "getNearby") as Boolean)
        request(window)
        window.show(owner = first, statusText = "Transparency", keepOpen = true, onConnect = action, deferConnectAction = hint)
        request(window)
        window.show(owner = first, type = IslandType.MOVED_TO_REMOTE, keepOpen = true, onConnect = action, deferConnectAction = hint)
        request(window)
        window.showPairedLid(first, onConnect = action, isAudioConnected = { true })
        request(window)
        window.showPairedLid(first)
        request(window)
        idle()
        assertEquals(0, calls)
        assertEquals(IslandConnectState.IDLE, state(window))
    }

    @Test fun theAudioConnectionPredicateRefreshesWithoutRequiringANewBatteryReport() = withWindow { window, first, _ ->
        var audioConnected = false
        window.showPairedLid(first, onConnect = { true }, isAudioConnected = { audioConnected })
        assertTrue(getter(window, "getNearby") as Boolean)
        audioConnected = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(getter(window, "getNearby") as Boolean)
        audioConnected = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue("Remote takeover must restore Connect without waiting for a battery change", getter(window, "getNearby") as Boolean)
    }

    @Test fun externallyCompletedAudioConnectionClearsAFailedRetryWithoutANewBatteryReport() = withWindow { window, first, _ ->
        var audioConnected = false
        window.showPairedLid(first, onConnect = { false }, isAudioConnected = { audioConnected })
        request(window)
        idle()
        assertEquals(IslandConnectState.FAILED, state(window))
        audioConnected = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(getter(window, "getNearby") as Boolean)
        assertEquals("The actual profile connection must remove the stale Retry action", IslandConnectState.IDLE, state(window))
        assertTrue(window.isVisible)
    }

    @Test fun lateFailureCannotReplaceAnAlreadyConnectedAudioProfileWithRetry() = withWindow { window, first, _ ->
        var audioConnected = false
        val answer = CompletableDeferred<Boolean>()
        window.showPairedLid(first, onConnect = { answer.await() }, isAudioConnected = { audioConnected })
        request(window)
        assertEquals(IslandConnectState.CONNECTING, state(window))
        audioConnected = true
        answer.complete(false)
        idle()
        assertEquals("A stale operation result cannot override the actual local profile", IslandConnectState.IDLE, state(window))
        assertFalse(getter(window, "getNearby") as Boolean)
        assertTrue(window.isVisible)
    }

    @Test fun outputPublishedDuringTheInitialHintCheckCannotExposeConnect() = withWindow { window, first, _ ->
        var reads = 0
        val audioConnected = { ++reads > 1 }
        window.showPairedLid(first, onConnect = { false }, isAudioConnected = audioConnected,
            deferConnectAction = { !audioConnected() })
        assertEquals(2, reads)
        assertFalse("Actual audio ownership must use the final observation", getter(window, "getNearby") as Boolean)
        assertFalse("An already connected output needs no reconnect grace", getter(window, "getConnectActionDeferred") as Boolean)
        assertEquals(IslandConnectState.IDLE, state(window))
    }

    @Test fun outputPublishedDuringARefreshHintCheckCannotExposeConnectOrDelayLaterRemoteTakeover() = withWindow { window, first, _ ->
        var publishing = false
        var remote = false
        var reads = 0
        var calls = 0
        val audioConnected = { !remote && publishing && ++reads > 1 }
        window.showPairedLid(first, onConnect = { calls++; false }, isAudioConnected = audioConnected,
            deferConnectAction = { !remote && !audioConnected() })
        assertTrue(getter(window, "getNearby") as Boolean)
        assertTrue(getter(window, "getConnectActionDeferred") as Boolean)
        publishing = true
        window.updatePairedBattery(first)
        assertEquals(2, reads)
        assertFalse("A battery refresh cannot retain an older disconnected observation", getter(window, "getNearby") as Boolean)
        assertFalse(getter(window, "getConnectActionDeferred") as Boolean)
        remote = true
        window.updatePairedBattery(first)
        assertTrue("A later remote takeover remains immediately actionable", getter(window, "getNearby") as Boolean)
        assertFalse(getter(window, "getConnectActionDeferred") as Boolean)
        request(window)
        idle()
        assertEquals(1, calls)
        assertEquals(IslandConnectState.FAILED, state(window))
    }

    @Test fun automaticReconnectDefersOnlyTheActionWhileNearbyBatteriesKeepUpdating() = withWindow { window, first, _ ->
        var calls = 0
        window.showPairedLid(first, onConnect = { calls++; true }, isAudioConnected = { false },
            deferConnectAction = { true })
        val generation = getter(window, "getTransitionGeneration")
        assertTrue(getter(window, "getConnectActionDeferred") as Boolean)
        assertTrue("A reconnect hint must not pretend the buds are connected", getter(window, "getNearby") as Boolean)
        for (level in listOf(80, 79, 78)) {
            first.receiveNearbyBattery(setOf(Battery(BatteryComponent.LEFT, level, BatteryStatus.CHARGING)))
            window.updatePairedBattery(first)
            request(window)
            assertEquals(0, calls)
            assertTrue(getter(window, "getConnectActionDeferred") as Boolean)
            @Suppress("UNCHECKED_CAST")
            val batteries = getter(window, "getBatteries") as Set<Battery>
            assertEquals(level, batteries.single().level)
        }
        assertEquals("Battery refreshes must preserve the current island opening", generation,
            getter(window, "getTransitionGeneration"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue("The normal dismissal cannot run before the reconnect hint resolves", window.isVisible)
        assertEquals(IslandConnectState.IDLE, state(window))
    }

    @Test fun failedAutomaticReconnectRevealsConnectWithoutReshowingAndRestartsItsDismissalWindow() = withWindow { window, first, _ ->
        var deferred = true
        window.showPairedLid(first, onConnect = { false }, isAudioConnected = { false },
            deferConnectAction = { deferred })
        val generation = getter(window, "getTransitionGeneration")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        deferred = false
        window.updatePairedBattery(first)
        assertFalse(getter(window, "getConnectActionDeferred") as Boolean)
        assertTrue(getter(window, "getNearby") as Boolean)
        assertEquals(IslandConnectState.IDLE, state(window))
        assertEquals(generation, getter(window, "getTransitionGeneration"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_400))
        assertTrue("The fallback action needs the complete normal dismissal window", window.isVisible)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("Later battery-age polls must not perpetually renew dismissal", IslandState.CLOSE, window.islandState)

        deferred = true
        window.showPairedLid(first, onConnect = { false }, isAudioConnected = { false },
            deferConnectAction = { deferred })
        val reopened = getter(window, "getTransitionGeneration")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        deferred = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse("Age polling alone must reveal the fallback action", getter(window, "getConnectActionDeferred") as Boolean)
        assertTrue(window.isVisible)
        assertEquals(reopened, getter(window, "getTransitionGeneration"))
    }

    @Test fun successfulAutomaticReconnectKeepsConnectHiddenAndResumesNormalDismissal() = withWindow { window, first, _ ->
        var deferred = true
        var audioConnected = false
        var calls = 0
        window.showPairedLid(first, onConnect = { calls++; true }, isAudioConnected = { audioConnected },
            deferConnectAction = { deferred })
        request(window)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        audioConnected = true
        deferred = false
        window.updatePairedBattery(first)
        request(window)
        assertFalse(getter(window, "getConnectActionDeferred") as Boolean)
        assertFalse(getter(window, "getNearby") as Boolean)
        assertEquals(0, calls)
        assertEquals(IslandConnectState.IDLE, state(window))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_600))
        assertEquals("Completed automatic reconnection must resume normal dismissal", IslandState.CLOSE, window.islandState)
    }

    @Test fun aDeferredOpeningCannotLeakItsPolicyIntoAnotherPairOrCustomizationPreview() = withWindow { window, first, second ->
        var oldChecks = 0
        window.showPairedLid(first, onConnect = { true }, isAudioConnected = { false },
            deferConnectAction = { oldChecks++; true })
        assertTrue(getter(window, "getConnectActionDeferred") as Boolean)
        val beforeClose = oldChecks
        window.close()
        request(window)
        window.updatePairedBattery(first)
        assertEquals(beforeClose, oldChecks)
        window.showPairedLid(second, onConnect = { false }, isAudioConnected = { false })
        window.updatePairedBattery(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(beforeClose, oldChecks)
        assertFalse("Another host's AirPods must offer immediate takeover", getter(window, "getConnectActionDeferred") as Boolean)
        window.show(keepOpen = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue("The prior real-device hint cannot dismiss a customization preview", window.isVisible)
        assertFalse(getter(window, "getConnectActionDeferred") as Boolean)
        var deferred = true
        window.show(owner = second, keepOpen = true, onConnect = { false }, isAudioConnected = { false },
            deferConnectAction = { deferred })
        deferred = false
        window.updatePairedBattery(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue("Ending a hint must preserve an explicit keep-open request", window.isVisible)
    }

    @Test fun anAutomaticReconnectHintCannotHideExplicitProgressOrRetry() = withWindow { window, first, _ ->
        var deferred = false
        var attempts = 0
        val answer = CompletableDeferred<Boolean>()
        window.showPairedLid(first, onConnect = { attempts++; answer.await() }, isAudioConnected = { false },
            deferConnectAction = { deferred })
        request(window)
        deferred = true
        window.updatePairedBattery(first)
        assertTrue(getter(window, "getConnectActionDeferred") as Boolean)
        assertEquals(IslandConnectState.CONNECTING, state(window))
        answer.complete(false)
        idle()
        assertEquals(IslandConnectState.FAILED, state(window))
        request(window)
        idle()
        assertEquals("A displayed Retry must stay actionable despite a reconnect hint", 2, attempts)
        assertEquals(IslandConnectState.FAILED, state(window))
    }

    private fun withWindow(block: (IslandWindow, AppleDevice, AppleDevice) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        fun device(address: String): AppleDevice {
            val bluetooth = adapter.getRemoteDevice(address)
            shadowOf(bluetooth).setBondState(BluetoothDevice.BOND_BONDED)
            return AppleDevice(context, adapter, bluetooth, ConnectionState.DISCONNECTED).apply {
                loadInitialState(AppleState(), settings.value, AppleMetadata(name = "AirPods", model = AirPodsModel.AIRPODS_PRO_3))
            }
        }
        val first = device("00:11:22:33:44:55")
        val second = device("00:11:22:33:44:66")
        val window = IslandWindow(context, AppSettingsEntity())
        try { block(window, first, second) } finally {
            window.close()
            idle()
            (field(window, "composeView") as? ComposeView)?.let {
                IslandWindow::class.java.getDeclaredMethod("cleanupAndRemoveView", ComposeView::class.java)
                    .apply { isAccessible = true }.invoke(window, it)
            }
        }
    }

    private fun request(window: IslandWindow) {
        IslandWindow::class.java.getDeclaredMethod("requestConnect").apply { isAccessible = true }.invoke(window)
    }
    private fun state(window: IslandWindow) = getter(window, "getConnectState") as IslandConnectState
    private fun getter(window: IslandWindow, name: String): Any? =
        IslandWindow::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(window)
    private fun field(window: IslandWindow, name: String): Any? =
        IslandWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(window)
    private fun idle() = scheduler.runCurrent()
}
