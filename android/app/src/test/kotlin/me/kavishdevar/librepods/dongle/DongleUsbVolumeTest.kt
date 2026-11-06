package me.kavishdevar.librepods.dongle

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.media.AudioManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.shizuku.IPlaybackRecoveryOwner
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumeEvents
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumePolicy
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleUsbVolumeTest {
    private class Harness(var paused: Boolean = true, val expected: Int = 64, val cleanup: Boolean = true) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = mock(ShizukuAudioClient::class.java)
        val policy = mock(IUsbAbsoluteVolumePolicy::class.java)
        var target = DongleUsbVolume.Target(Any(), "synthetic_board", 9, 3, 128)
        val savedRequests = mutableSetOf<String>()
        var failActivation = false
        val steps = mutableListOf<String>()
        val actions = mutableListOf<Int>()
        val setLevels = mutableListOf<Int>()
        var physical = 64
        var revision = 1L
        var held = false
        var current = true
        var deferSet = false
        var setLevel: (Int) -> Int = { it }
        var pending = false
        var beforeRead: () -> Unit = {}
        var beforeSet: suspend () -> Unit = {}
        var afterRenew: suspend (DongleUsbVolume.Observation) -> DongleUsbVolume.Observation = { it }
        var afterSet: suspend (DongleUsbVolume.Observation) -> DongleUsbVolume.Observation = { it }
        var releaseGate: CompletableDeferred<Unit>? = null
        val events = mutableListOf<IUsbAbsoluteVolumeEvents>()
        val owners = mutableListOf<IPlaybackRecoveryOwner>()
        val renewProofs = mutableListOf<Bundle>()
        val activatedLevels = mutableListOf<Int>()
        val notifiedLevels = mutableListOf<Int>()
        val notifyAttempts = mutableListOf<Pair<Int, Long>>()
        var beforeNotify: (Int, Long) -> Unit = { _, _ -> }
        private var hostSequence = 0L
        private var latestHostLevel: Int? = null
        var notifications = 0
        private val dummyOwner = object : IPlaybackRecoveryOwner.Stub() { override fun isCurrent() = true }
        private val dummyEvents = mock(IUsbAbsoluteVolumeEvents::class.java)
        var latest = DongleUsbVolume.State()
        fun observation() = DongleUsbVolume.Observation(DongleProtocol.PhysicalVolume(if (pending) null else physical, if (pending) expected else null, revision,
            pending, pending, held, if (held) 40 else null, if (held) 5_000 else 0, 3, true), SystemClock.elapsedRealtime())
        val controller = DongleUsbVolume(RuntimeEnvironment.getApplication(), scope, bridge, { target.takeIf { current } }, { paused },
            read = { beforeRead(); observation() }, action = { _, action, _, _, level, guard ->
                if (action == 3) beforeSet()
                guard(observation().value); if (action == 2) releaseGate?.await(); actions += action
                when (action) { 0 -> { steps += "firmware_acquire"; held = true }
                    2 -> { steps += "firmware_release"; held = false }
                    3 -> { steps += "firmware_set"; setLevels += level
                        if (deferSet) pending = true else { physical = setLevel(level); revision++ } } }
                val fresh = observation()
                when (action) { 1 -> afterRenew(fresh); 3 -> afterSet(fresh); else -> fresh }
            }, changed = { latest = it }, requested = { it in savedRequests }, discover = { policy })
        init { runBlocking {
            `when`(bridge.hasRootPrivileges).thenReturn(true)
            `when`(bridge.inspectUsbAbsoluteVolumePolicy(any(IUsbAbsoluteVolumePolicy::class.java) ?: policy, anyInt(), anyString()))
                .thenReturn(Bundle().apply { putBoolean("available", true); putInt("expected_physical_level", expected); putInt("device_id", 10)
                    putString("device_address", "card=1;device=0") })
            `when`(bridge.startUsbAbsoluteVolume(any(IPlaybackRecoveryOwner::class.java) ?: dummyOwner, any(IUsbAbsoluteVolumePolicy::class.java) ?: policy,
                any(IUsbAbsoluteVolumeEvents::class.java) ?: dummyEvents, any(Bundle::class.java) ?: Bundle()))
                .thenAnswer { call ->
                    steps += "root_start"; owners += call.getArgument<IPlaybackRecoveryOwner>(0)
                    events += call.getArgument<IUsbAbsoluteVolumeEvents>(2)
                    activatedLevels += call.getArgument<Bundle>(3).getInt("level", -1)
                    Bundle().apply { putBoolean("active", !failActivation) }
                }
            `when`(bridge.renewUsbAbsoluteVolume(any(IPlaybackRecoveryOwner::class.java) ?: dummyOwner, any(Bundle::class.java) ?: Bundle()))
                .thenAnswer { call -> renewProofs += Bundle(call.getArgument<Bundle>(1)); Bundle().apply { putBoolean("active", true) } }
            `when`(bridge.notifyUsbAbsoluteVolume(any(IPlaybackRecoveryOwner::class.java) ?: dummyOwner, any(Bundle::class.java) ?: Bundle(), anyLong()))
                .thenAnswer { call ->
                    val level = call.getArgument<Bundle>(1).getInt("level")
                    val sequence = call.getArgument<Long>(2)
                    notifyAttempts += level to sequence
                    beforeNotify(level, sequence)
                    // Model the system-server admission gate after IPC, including a newly arrived key callback.
                    val skipped = sequence > 0 && hostSequence > sequence && latestHostLevel != level
                    if (!skipped) { notifications++; notifiedLevels += level }
                    Bundle().apply { putBoolean("active", true) }
                }
            `when`(bridge.releaseUsbAbsoluteVolume(any(IPlaybackRecoveryOwner::class.java) ?: dummyOwner))
                .thenAnswer { steps += "root_release"; Bundle().apply { putBoolean("cleanup_complete", cleanup) } }
            DongleUsbVolume::class.java.getDeclaredField("prepared").apply { isAccessible = true }
                .set(controller, DongleUsbVolume.Setup(target, policy, 10, "card=1;device=0"))
        } }
        fun enable(enabled: Boolean) {
            if (enabled) savedRequests += target.serial else savedRequests -= target.serial
            controller.enable(enabled)
        }
        fun hostVolume(level: Int) {
            hostSequence++; latestHostLevel = level
            val caller = android.os.Binder.getCallingUid()
            try { ShadowBinder.setCallingUid(1000); events.last().onVolumeChanged(target.controlSession, level, hostSequence) }
            finally { ShadowBinder.setCallingUid(caller) }
        }
        override fun close() { controller.retire(); scope.cancel() }
    }

    @Test fun discoveryAndConstructionNeverAcquireOrSetGain() {
        Harness().use { assertTrue(it.actions.isEmpty()); assertFalse(it.latest.enabled) }
    }
    @Test fun audioTopologyChangeDropsPreparedReadinessWithoutAcquiringOrChangingGain() {
        Harness().use {
            runBlocking {
                `when`(it.bridge.inspectUsbAbsoluteVolumePolicy(any(IUsbAbsoluteVolumePolicy::class.java) ?: it.policy, anyInt(), anyString()))
                    .thenReturn(Bundle().apply { putBoolean("available", false) })
            }
            it.controller.topologyChanged()
            val prepared = DongleUsbVolume::class.java.getDeclaredField("prepared").apply { isAccessible = true }.get(it.controller)
            assertNull(prepared); assertFalse(it.latest.available); assertFalse(it.latest.enabled)
            assertTrue(it.actions.isEmpty())
        }
    }
    private fun staleAudioCache(action: () -> Unit) {
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val old = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        // The demonstrated failure was an empty/stale public USB cache. Readiness must use root.
        shadowOf(audio).setOutputDevices(emptyList())
        try { action() } finally { shadowOf(audio).setOutputDevices(old) }
    }
    @Test fun rootResolvesTheDeviceIdWhenTheAppsUsbCacheIsEmpty() = staleAudioCache {
        Harness().use { h ->
            h.controller.probe()
            assertTrue(h.latest.available); assertFalse(h.latest.enabled); assertTrue(h.actions.isEmpty())
            runBlocking {
                verify(h.bridge).inspectUsbAbsoluteVolumePolicy(h.policy, 0, "synthetic_board")
            }
        }
    }
    @Test fun topologyDuringInspectRejectsTheOldResultAndSerializesTheFreshProbe() = staleAudioCache {
        Harness().use { h ->
            val entered = listOf(CountDownLatch(1), CountDownLatch(1))
            val finish = listOf(CountDownLatch(1), CountDownLatch(1)); val calls = AtomicInteger()
            runBlocking {
                `when`(h.bridge.inspectUsbAbsoluteVolumePolicy(any(IUsbAbsoluteVolumePolicy::class.java) ?: h.policy, anyInt(), anyString()))
                    .thenAnswer {
                        val at = calls.getAndIncrement(); entered[at].countDown()
                        check(finish[at].await(5, TimeUnit.SECONDS))
                        Bundle().apply { putBoolean("available", true); putInt("device_id", 10); putString("device_address", "card=1;device=0") }
                    }
            }
            val first = thread(isDaemon = true) { h.controller.probe() }
            try {
                assertTrue(entered[0].await(5, TimeUnit.SECONDS))
                h.controller.probe(); assertEquals(1, calls.get())
                h.controller.topologyChanged(); finish[0].countDown()
                assertTrue(entered[1].await(5, TimeUnit.SECONDS))
                assertFalse(h.latest.available); assertTrue(h.actions.isEmpty())
                finish[1].countDown(); first.join(5_000)
                assertFalse(first.isAlive); assertTrue(h.latest.available); assertEquals(2, calls.get())
            } finally { finish.forEach { it.countDown() }; first.join(5_000) }
        }
    }
    @Test fun anOldUnavailableProbeCannotHideAnExplicitlyEnabledOwner() = staleAudioCache {
        Harness().use { h ->
            val entered = CountDownLatch(1); val finish = CountDownLatch(1); val calls = AtomicInteger()
            runBlocking {
                `when`(h.bridge.inspectUsbAbsoluteVolumePolicy(any(IUsbAbsoluteVolumePolicy::class.java) ?: h.policy, anyInt(), anyString()))
                    .thenAnswer {
                        val first = calls.getAndIncrement() == 0
                        if (first) { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
                        Bundle().apply { putBoolean("available", !first); putInt("expected_physical_level", 64); putInt("device_id", 10)
                            putString("device_address", "card=1;device=0") }
                    }
            }
            val first = thread(isDaemon = true) { h.controller.probe() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.enable(true); assertTrue(h.latest.enabled)
                finish.countDown(); first.join(5_000)
                assertFalse(first.isAlive); assertTrue(h.latest.enabled)
                assertEquals(1, h.actions.count { it == 0 }); assertFalse(h.actions.contains(3))
            } finally { finish.countDown(); first.join(5_000) }
        }
    }
    @Test fun explicitOptInRequiresPausedPlaybackBeforeAnyFirmwareMutation() {
        Harness(paused = false, expected = 90).use {
            it.enable(true)
            assertTrue(it.actions.isEmpty()); assertFalse(it.latest.enabled); assertFalse(it.latest.failed)
            assertTrue(it.savedRequests.contains(it.target.serial))
            it.paused = true; it.controller.reconcile()
            assertTrue(it.latest.enabled)
        }
    }

    @Test fun aSavedBoardRequestRestoresOnlyANewIdleVerifiedTargetAndOffStopsRestoration() {
        Harness(expected = 90).use {
            it.enable(true)
            it.current = false; it.controller.retire()
            assertFalse(it.latest.enabled)
            assertTrue(it.savedRequests.contains(it.target.serial))
            it.target = it.target.copy(owner = Any(), controlSession = 10)
            it.held = false; it.current = true; it.paused = false
            it.controller.reconcile()
            assertEquals(1, it.actions.count { action -> action == 0 })
            it.paused = true; it.controller.reconcile()
            assertTrue(it.latest.enabled)
            assertEquals(2, it.actions.count { action -> action == 0 })
            assertEquals(listOf(64, 64), it.activatedLevels); assertFalse(it.actions.contains(3))
            it.enable(false)
            repeat(3) { _ -> it.controller.reconcile() }
            assertFalse(it.latest.enabled); assertTrue(it.savedRequests.isEmpty())
            assertEquals(2, it.actions.count { action -> action == 0 })
        }
    }

    @Test fun aRealActivationFailureIsNotRetriedByPollingUntilAnExplicitRetry() {
        Harness().use {
            it.failActivation = true; it.enable(true)
            assertFalse(it.latest.enabled); assertTrue(it.latest.failed)
            repeat(4) { _ -> it.controller.reconcile(); it.controller.probe() }
            assertEquals(1, it.actions.count { action -> action == 0 })
            it.failActivation = false; it.enable(true)
            assertTrue(it.latest.enabled); assertEquals(2, it.actions.count { action -> action == 0 })
        }
    }

    @Test fun anUnexpectedEndedLeaseIsQuarantinedUntilANewConnection() {
        Harness().use {
            it.enable(true)
            val uid = android.os.Binder.getCallingUid()
            try { ShadowBinder.setCallingUid(1000); it.events.last().onPolicyEnded("stale", true) }
            finally { ShadowBinder.setCallingUid(uid) }
            repeat(4) { _ -> it.controller.reconcile(); it.controller.probe() }
            assertFalse(it.latest.enabled); assertEquals(1, it.actions.count { action -> action == 0 })
            it.target = it.target.copy(owner = Any(), controlSession = 10)
            it.controller.reconcile()
            assertTrue(it.latest.enabled); assertEquals(2, it.actions.count { action -> action == 0 })
        }
    }

    @Test fun anOldFirmwareLeaseDefersRestorationWithoutReusingItsTokenOrSuppressingTheRequest() {
        Harness().use {
            it.savedRequests += it.target.serial; it.held = true
            repeat(3) { _ -> it.controller.reconcile() }
            assertTrue(it.actions.isEmpty()); assertFalse(it.latest.enabled); assertFalse(it.latest.failed)
            it.held = false; it.controller.reconcile()
            assertTrue(it.latest.enabled); assertEquals(listOf(0), it.actions)
        }
    }

    @Test fun temporaryTargetLossClearsActiveStateWithoutForgettingOrQuarantiningTheRequest() = runBlocking {
        Harness().use {
            it.enable(true)
            it.current = false
            delay(850)
            assertFalse(it.latest.enabled); assertTrue(it.steps.contains("root_release"))
            assertTrue(it.savedRequests.contains(it.target.serial))
            it.held = false; it.current = true; it.controller.reconcile()
            assertTrue(it.latest.enabled); assertEquals(2, it.actions.count { action -> action == 0 })
        }
    }
    @Test fun temporaryScopeLossDuringReadbackDefersTheSavedRequestInsteadOfQuarantiningIt() = runBlocking {
        Harness(expected = 90).use {
            it.deferSet = true
            it.beforeRead = {
                if (it.pending) it.current = false
                check(it.current) { "AirPods connection changed" }
            }
            it.enable(true)
            val caller = android.os.Binder.getCallingUid()
            try { ShadowBinder.setCallingUid(1000); it.hostVolume(90) }
            finally { ShadowBinder.setCallingUid(caller) }
            delay(120)
            assertFalse(it.latest.enabled); assertFalse(it.latest.pending); assertFalse(it.latest.failed)
            assertEquals(1, it.steps.count { step -> step == "root_start" })
            assertTrue(it.savedRequests.contains(it.target.serial))
            it.beforeRead = {}; it.pending = false; it.held = false; it.deferSet = false; it.current = true
            it.controller.reconcile()
            assertTrue(it.latest.enabled); assertEquals(2, it.actions.count { action -> action == 0 })
        }
    }
    @Test fun activationAdoptsThePhysicalMasterAndNativeCleanupPrecedesFirmwareRelease() = runBlocking {
        Harness(expected = 90).use {
            it.enable(true)
            assertEquals(listOf("firmware_acquire", "root_start"), it.steps)
            assertEquals(listOf(64), it.activatedLevels); assertEquals(64, it.physical)
            assertTrue(it.latest.enabled); assertFalse(it.latest.pending)
            it.enable(false)
            assertTrue(it.steps.indexOf("root_release") < it.steps.indexOf("firmware_release"))
            assertEquals(listOf(0, 2), it.actions)
            assertFalse(it.latest.enabled)
        }
    }
    @Test fun activationPreservesEveryBoundaryLevelWithoutQuantizingItToTheHostIndex() {
        for (level in listOf(0, 1, 2, 64, 126, 127)) Harness(expected = 90).use {
            it.physical = level; it.enable(true)
            assertTrue(it.latest.enabled); assertEquals(level, it.physical)
            assertEquals(listOf(level), it.activatedLevels); assertEquals(listOf(0), it.actions)
        }
    }
    @Test fun anExplicitHostCallbackCanChangeGainAfterReadOnlyActivation() = runBlocking {
        Harness(expected = 90).use {
            it.enable(true)
            assertEquals(listOf(0), it.actions); assertEquals(64, it.physical)
            val caller = android.os.Binder.getCallingUid()
            try { ShadowBinder.setCallingUid(1000); it.hostVolume(90) }
            finally { ShadowBinder.setCallingUid(caller) }
            assertEquals(90, it.physical); assertTrue(it.actions.contains(3))
            it.enable(false)
        }
    }
    @Test fun settingsCheckpointsKeepTheSameLeaseWhileTheNormalHeartbeatWaitsForControllerIo() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            val heartbeat = CompletableDeferred<Unit>()
            h.afterRenew = { old -> heartbeat.await(); old }
            h.hostVolume(64) // The normal heartbeat has captured an older reply and is blocked by settings I/O.
            assertEquals(listOf(0, 1), h.actions)
            h.physical = 81; h.revision++
            var checkpoints = 0
            suspend fun checkpoint(): Long? = h.controller.keepAliveForSettings { selected, usbGeneration, token ->
                assertEquals(h.target, selected); assertEquals(3L, usbGeneration); assertEquals(40L, token)
                checkpoints++; h.observation()
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val first = checkNotNull(checkpoint())
            assertEquals(SystemClock.elapsedRealtime() + 4500, first)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val second = checkNotNull(checkpoint())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val caller = android.os.Binder.getCallingUid()
            try { ShadowBinder.setCallingUid(0); assertTrue(h.owners.single().isCurrent) }
            finally { ShadowBinder.setCallingUid(caller) }
            assertEquals(2, checkpoints); assertTrue(h.latest.enabled)
            assertEquals(listOf(81), h.notifiedLevels)
            assertEquals(second - 4500, h.renewProofs.last().getLong("observed_at"))
            heartbeat.complete(Unit)
            assertTrue(h.latest.enabled); assertEquals(listOf(81), h.notifiedLevels)
            assertEquals(second - 4500, h.renewProofs.last().getLong("observed_at"))
            assertFalse(h.steps.contains("root_release")); assertFalse(h.actions.contains(3))
            assertTrue(h.savedRequests.contains(h.target.serial))
            h.enable(false)
            assertTrue(h.steps.indexOf("root_release") < h.steps.indexOf("firmware_release"))
        }
    }
    @Test fun settingsCheckpointsReturnNullOnlyWhenNoNativeBridgeIsActive() = runBlocking {
        Harness().use { h ->
            assertNull(h.controller.keepAliveForSettings { _, _, _ -> error("No lease should be renewed") })
            h.enable(true)
            h.enable(false)
            assertNull(h.controller.keepAliveForSettings { _, _, _ -> error("The retired lease was reused") })
            assertEquals(listOf(0, 2), h.actions)
        }
    }
    @Test fun aSettingsCheckpointRejectsChangedLeaseScopeAndCleansUpWithoutSettingGain() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            try {
                h.controller.keepAliveForSettings { _, _, _ -> h.observation().let { it.copy(value = it.value.copy(ownToken = 41)) } }
                fail("A different firmware lease was accepted")
            } catch (_: IllegalStateException) { }
            assertFalse(h.latest.enabled); assertTrue(h.latest.failed)
            assertEquals(listOf(0, 2), h.actions); assertTrue(h.renewProofs.isEmpty())
            assertNull(h.controller.keepAliveForSettings { _, _, _ -> error("Retired lease was renewed") })
        }
    }
    @Test fun aCapturedOldCheckpointCannotRenewOrCancelANewerOwner() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            @Suppress("UNCHECKED_CAST")
            val old = DongleUsbVolume::class.java.getDeclaredField("settingsCheckpoint").apply { isAccessible = true }.get(h.controller) as
                suspend (suspend (DongleUsbVolume.Target, Long, Long) -> DongleUsbVolume.Observation) -> Long
            h.enable(false)
            h.target = h.target.copy(owner = Any(), controlSession = 10)
            h.enable(true)
            try { old { _, _, _ -> error("An old checkpoint reached firmware") }; fail("An old owner was accepted") }
            catch (_: IllegalStateException) { }
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(2, h.actions.count { it == 0 }); assertFalse(h.actions.contains(3))
        }
    }
    @Test fun anExpiredLeaseCannotBeResurrectedByASettingsCheckpoint() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            try { h.controller.keepAliveForSettings { _, _, _ -> error("Expired firmware lease was renewed") }; fail("Expired lease was accepted") }
            catch (_: IllegalStateException) { }
            assertFalse(h.latest.enabled); assertTrue(h.latest.failed)
            assertEquals(1, h.actions.count { it == 0 }); assertFalse(h.actions.contains(3))
        }
    }
    @Test fun aFreshSettledClampedValueStaysActiveAndUpdatesTheHostToTheActualGain() = runBlocking {
        Harness().use { h ->
            h.setLevel = { it.coerceAtMost(70) }
            h.enable(true); h.hostVolume(90)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(70, h.physical); assertEquals(listOf(70), h.notifiedLevels)
            assertEquals(1, h.actions.count { it == 3 }); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aNativeGesturePublishedWhileHeartbeatIoIsBlockedRetiresTheOlderHostRequest() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            val heartbeat = CompletableDeferred<Unit>()
            h.afterRenew = { old -> heartbeat.await(); old }
            h.hostVolume(90)
            h.physical = 51; h.revision++
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            heartbeat.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(51, h.physical); assertEquals(listOf(51), h.notifiedLevels)
            assertTrue(h.setLevels.isEmpty()); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aNativeGestureBeforeSetCommitRetiresTheStagedHostRequestWithoutStoppingUnity() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            val commit = CompletableDeferred<Unit>()
            h.beforeSet = { commit.await() }
            h.hostVolume(90)
            h.physical = 51; h.revision++
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            commit.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(51), h.notifiedLevels); assertTrue(h.setLevels.isEmpty())
            assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aNewPhysicalReadBeforeCommitRetiresTheOldHostRequestEvenBeforeItsRevisionIsPublished() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            h.beforeSet = { h.physical = 51; h.revision++ }
            h.hostVolume(90)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertTrue(h.setLevels.isEmpty()); assertTrue(h.notifiedLevels.isEmpty())
            delay(850)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(51), h.notifiedLevels); assertEquals(51, h.physical)
            assertTrue(h.setLevels.isEmpty()); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun anExactOwnAcknowledgementPreservesTheNextQueuedHostSliderTarget() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(80)
            h.hostVolume(90)
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(80, 90), h.setLevels); assertEquals(90, h.physical)
            assertEquals(listOf(90), h.notifiedLevels); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aCheckpointPublishingOurAcknowledgementFirstPreservesTheNextQueuedHostTarget() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(80); h.hostVolume(90)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            assertTrue(h.notifiedLevels.isEmpty())
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(80, 90), h.setLevels); assertEquals(90, h.physical)
            assertEquals(listOf(90), h.notifiedLevels); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun fastVolumeDownKeysDoNotMirrorAnOlderAcknowledgementOverTheLatestHostTarget() = runBlocking {
        Harness().use { h ->
            h.physical = 127; h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(122)
            for (level in listOf(117, 112, 107, 102, 97, 91, 86, 81, 76)) h.hostVolume(level)
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(122, 76), h.setLevels); assertEquals(76, h.physical)
            assertEquals(listOf(76), h.notifiedLevels)
            assertTrue(h.renewProofs.any { it.getInt("level") == 122 })
            assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aCheckpointAcknowledgingTheOlderSetKeepsTheLatestHostTargetOnScreen() = runBlocking {
        Harness().use { h ->
            h.physical = 127; h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(122); h.hostVolume(76)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            assertTrue(h.notifiedLevels.isEmpty())
            assertEquals(122, h.renewProofs.last().getInt("level"))
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(122, 76), h.setLevels); assertEquals(listOf(76), h.notifiedLevels)
        }
    }
    @Test fun aKeyCallbackArrivingInsideNotificationIpcCannotPublishTheOlderAcknowledgement() = runBlocking {
        Harness().use { h ->
            h.physical = 127; h.enable(true)
            h.beforeNotify = { level, sequence -> if (level == 122 && sequence == 1L) h.hostVolume(76) }
            h.hostVolume(122)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(122, 76), h.setLevels)
            assertEquals(listOf(122 to 1L, 76 to 2L), h.notifyAttempts)
            assertEquals(listOf(76), h.notifiedLevels)
        }
    }
    @Test fun anExactUnmuteAcknowledgementStillSeedsTheHostFromZeroToTheRestoredLevel() = runBlocking {
        Harness().use { h ->
            h.physical = 0; h.enable(true)
            h.hostVolume(127)
            assertTrue(h.latest.enabled); assertEquals(127, h.physical)
            assertEquals(listOf(127 to 1L), h.notifyAttempts); assertEquals(listOf(127), h.notifiedLevels)
        }
    }
    @Test fun aDuplicatePhysicalReportAfterOurAcknowledgementCannotRetireTheNewerKeyIntent() = runBlocking {
        Harness().use { h ->
            h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(80); h.hostVolume(90)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            assertEquals(listOf(80 to 1L), h.notifyAttempts); assertTrue(h.notifiedLevels.isEmpty())
            h.revision++
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            assertEquals(listOf(80 to 1L), h.notifyAttempts)
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(80, 90), h.setLevels)
            assertEquals(listOf(80 to 1L, 90 to 2L), h.notifyAttempts); assertEquals(listOf(90), h.notifiedLevels)
        }
    }
    @Test fun zeroOrRepeatedPolicyRequestSequencesCannotChangeGain() = runBlocking {
        Harness().use { h ->
            h.enable(true); h.hostVolume(80)
            val caller = android.os.Binder.getCallingUid()
            try {
                ShadowBinder.setCallingUid(1000)
                h.events.single().onVolumeChanged(h.target.controlSession, 90, 0)
                h.events.single().onVolumeChanged(h.target.controlSession, 90, 1)
            } finally { ShadowBinder.setCallingUid(caller) }
            assertTrue(h.latest.enabled); assertEquals(listOf(80), h.setLevels)
            assertEquals(listOf(80 to 1L), h.notifyAttempts)
        }
    }
    @Test fun aNewNativeLevelStillMirrorsAndRetiresOlderQueuedHostTargetsDuringASet() = runBlocking {
        Harness().use { h ->
            h.physical = 127; h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(122); h.hostVolume(76)
            h.physical = 73; h.revision++
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            assertEquals(listOf(73), h.notifiedLevels)
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(122), h.setLevels); assertEquals(73, h.physical)
            assertEquals(listOf(73), h.notifiedLevels)
        }
    }
    @Test fun duplicateCallbacksAndMaxNoOpsDoNotLeaveNotificationSuppressionBehind() = runBlocking {
        Harness().use { h ->
            h.physical = 127; h.enable(true)
            h.hostVolume(127)
            assertTrue(h.setLevels.isEmpty())
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(122); h.hostVolume(122)
            acknowledgement.complete(Unit)
            assertEquals(listOf(122), h.setLevels); assertEquals(listOf(122), h.notifiedLevels)
            h.hostVolume(76); h.hostVolume(127); h.hostVolume(127)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(122, 76, 127), h.setLevels)
            assertEquals(listOf(122, 76, 127), h.notifiedLevels)
        }
    }
    @Test fun aClampedAcknowledgementRetiresTheNextOlderHostTargetAndMirrorsTheActualGain() = runBlocking {
        Harness().use { h ->
            h.setLevel = { it.coerceAtMost(70) }
            h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(90)
            h.hostVolume(100)
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(90), h.setLevels); assertEquals(70, h.physical)
            assertEquals(listOf(70), h.notifiedLevels); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aClampResolvesThePendingSetSoALaterNativeReturnToItsOldTargetSupersedesNewerQueuedIntent() = runBlocking {
        Harness().use { h ->
            h.setLevel = { it.coerceAtMost(70) }
            h.enable(true)
            val acknowledgement = CompletableDeferred<Unit>()
            h.afterSet = { fresh -> acknowledgement.await(); fresh }
            h.hostVolume(90); h.hostVolume(100)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            h.hostVolume(110)
            h.physical = 90; h.revision++
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            h.controller.keepAliveForSettings { _, _, _ -> h.observation() }
            acknowledgement.complete(Unit)
            assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(90), h.setLevels); assertEquals(90, h.physical)
            assertEquals(listOf(70, 90), h.notifiedLevels); assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun aNativeGestureCanSupersedeASetButAnUnchangedBaselineCannotConfirmIt() = runBlocking {
        Harness().use { h ->
            h.deferSet = true
            var reads = 0
            h.beforeRead = {
                if (h.held) {
                    reads++; h.pending = false
                    if (reads == 1) h.physical = 64 else { h.physical = 51; h.revision++ }
                }
            }
            h.enable(true); h.hostVolume(90)
            assertTrue(h.pending); assertTrue(h.notifiedLevels.isEmpty())
            delay(250)
            assertEquals(2, reads); assertTrue(h.latest.enabled); assertFalse(h.latest.failed)
            assertEquals(listOf(51), h.notifiedLevels); assertEquals(1, h.actions.count { it == 3 })
            assertFalse(h.steps.contains("root_release"))
        }
    }
    @Test fun unknownNativeCleanupLeavesFirmwareQuietAndRefusesSettingsCheckpoints() = runBlocking {
        Harness(cleanup = false).use {
            it.enable(true)
            it.enable(false)
            try { it.controller.keepAliveForSettings { _, _, _ -> error("Unconfirmed cleanup admitted ATT work") }; fail("Cleanup is unknown") }
            catch (_: IllegalStateException) { }
            assertEquals(listOf(0), it.actions)
            assertFalse(it.latest.enabled); assertTrue(it.latest.failed)
            assertTrue(it.latest.cleanupFailed); assertFalse(it.latest.available)
        }
    }
    @Test fun rapidOffOnWaitsForCleanupAndDelayedOldCallbackCannotCancelTheNewOwner() = runBlocking {
        Harness().use {
            it.enable(true)
            val oldEvents = it.events.single()
            val oldOwner = it.owners.single()
            it.releaseGate = CompletableDeferred()
            it.enable(false)
            assertTrue(it.latest.pending)
            it.enable(true)
            assertEquals(1, it.actions.count { action -> action == 0 })
            checkNotNull(it.releaseGate).complete(Unit)
            it.enable(true)
            assertTrue(it.latest.enabled)
            assertEquals(2, it.actions.count { action -> action == 0 })
            val caller = android.os.Binder.getCallingUid()
            try {
                ShadowBinder.setCallingUid(1000); oldEvents.onPolicyEnded("late", true)
                oldEvents.onPolicyEnded("late failure", false)
                assertTrue(it.latest.enabled)
                ShadowBinder.setCallingUid(0)
                assertFalse(oldOwner.isCurrent)
                assertTrue(it.owners.last().isCurrent)
            } finally { ShadowBinder.setCallingUid(caller) }
            it.enable(false)
        }
    }
    @Test fun disconnectDuringRequestedPhysicalReadbackNeverReacquiresNativePolicy() {
        Harness(expected = 90).use {
            it.deferSet = true
            it.enable(true)
            val caller = android.os.Binder.getCallingUid()
            try { ShadowBinder.setCallingUid(1000); it.hostVolume(90) }
            finally { ShadowBinder.setCallingUid(caller) }
            assertTrue(it.pending)
            it.current = false; it.controller.retire()
            assertFalse(it.latest.enabled)
            assertEquals(1, it.steps.count { step -> step == "root_start" })
            assertEquals(listOf(0, 1, 3), it.actions)
        }
    }
    @Test fun expiredLeaseDuringPendingSetRevokesTheRootOwnerWithoutReacquisition() = runBlocking {
        Harness().use {
            it.enable(true)
            it.deferSet = true
            val caller = android.os.Binder.getCallingUid()
            try {
                ShadowBinder.setCallingUid(1000)
                it.hostVolume(90)
                assertTrue(it.pending)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
                ShadowBinder.setCallingUid(0)
                assertFalse(it.owners.single().isCurrent)
                assertEquals(1, it.actions.count { action -> action == 0 })
                it.enable(false)
                assertTrue(it.steps.indexOf("root_release") < it.steps.indexOf("firmware_release"))
            } finally { ShadowBinder.setCallingUid(caller) }
        }
    }

    @Test fun pendingUnknownPhysicalVolumeRenewsWithoutUpdatingTheHostIndex() = runBlocking {
        Harness().use {
            it.enable(true)
            it.pending = true
            delay(850)
            assertTrue(it.latest.enabled)
            assertTrue(it.renewProofs.isNotEmpty())
            assertEquals(255, it.renewProofs.last().getInt("level"))
            assertEquals(30, it.renewProofs.last().getInt("flags"))
            assertEquals(0, it.notifications)
            it.enable(false)
        }
    }

    @Test fun proofDeadlineStartsBeforeTheControlReplyAndPreservesItsExactScope() {
        Harness().use {
            val observation = it.observation().copy(observedAt = 1000, value = it.observation().value.copy(
                authorityActive = true, ownToken = 40, remainingMs = 2500))
            assertEquals(3000L, DongleUsbVolume.deadline(observation))
            val proof = DongleUsbVolume.proof(it.target, 10, "card=1;device=0", observation)
            assertEquals(1000L, proof.getLong("observed_at")); assertEquals(2500L, proof.getLong("ttl_ms"))
            assertEquals(40L, proof.getLong("firmware_token")); assertEquals(3L, proof.getLong("usb_generation"))
            assertEquals(9L, proof.getLong("control_session")); assertEquals(25, proof.getInt("flags"))
            assertEquals("card=1;device=0", proof.getString("device_address"))
        }
    }
}
