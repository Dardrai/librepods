package me.kavishdevar.librepods.audio

import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpatialHeadTrackingProbeTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun wrongMacModelUnknownRoleAndRetiredOwnerFailBeforeRootConnection() = runBlocking {
        for (case in 0..3) {
            val target = Target()
            if (case == 1) target.metadata.value = AppleMetadata(modelNumber = "A2564")
            if (case == 2) target.states.value = target.states.value.copy(budRoleRaw = emptyList())
            val result = target.probe.run(SpatialHeadTrackingProbeRequest(if (case == 0) OTHER_MAC else MAC),
                target.device, { case != 3 }, onStatus = { fail("Rejected request must not reach calibration.") })
            assertNotEquals("stopped", result.reason)
            assertEquals(0, result.poses); assertEquals(0, target.clientsCreated.get())
            verifyNoInteractions(target.bridge)
            assertTrue(target.started.isEmpty())
            assertNull(result.captureFile); assertTrue(target.directory.listFiles().orEmpty().isEmpty())
            assertEquals(listOf(target.otherOwner), target.owners.toList())
            verify(target.device, never()).stopHeadTracking()
        }
    }

    @Test fun cancellingCalibrationReleasesOnlyItsMotionOwnerAndClosesItsClient() = runBlocking {
        val target = Target(); val calibrating = CompletableDeferred<Unit>()
        val job = async {
            target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true }, onStatus = {
                calibrating.complete(Unit)
            })
        }
        try {
            withTimeout(2_000) { calibrating.await() }
            assertEquals(1, target.started.size)
            assertEquals(2, target.owners.size)
        } finally { job.cancelAndJoin() }
        target.assertOwnedCleanup()
        val trace = target.onlyTrace()
        assertEquals("cancelled", trace.last().getString("reason"))
    }

    @Test fun disabledSpatialAudioStopsBeforeMotionAndHeadTrackingLease() = runBlocking {
        for (deviceEnabled in listOf(false, true)) {
            val target = Target()
            `when`(target.bridge.inspectSpatialAudio(eq(MAC) ?: MAC, anyCurrent())).thenReturn(Bundle().apply {
                putBoolean("device_enabled", deviceEnabled); putBoolean("enabled", false)
            })
            val result = target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true })
            assertEquals("spatial_disabled", result.reason); assertFalse(result.calibrated)
            assertNull(result.calibrationFailure); assertNull(result.captureFile)
            assertTrue(target.started.isEmpty()); assertEquals(listOf(target.otherOwner), target.owners.toList())
            verify(target.bridge).connect(); verify(target.bridge).close()
            assertTrue(mockingDetails(target.bridge).invocations.none { it.method.name.substringBefore('$') == "startHeadTrackerProbe" })
            assertTrue(target.directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun sourceIdentityChangesDuringCalibrationStopAndReleaseOnlyTheProbeOwner() = runBlocking {
        val changes = listOf<(Target) -> Unit>(
            { it.states.value = it.states.value.copy(budRoleRaw = listOf(2)) },
            { it.states.value = it.states.value.copy(budRoleRaw = listOf(1, 0)) },
            { it.metadata.value = it.metadata.value.copy(model = AirPodsModel.AIRPODS_3) },
            { it.metadata.value = it.metadata.value.copy(modelNumber = "A3064") },
            { it.metadata.value = it.metadata.value.copy(version1 = "changed-firmware") })
        for (change in changes) {
            val target = Target(); val calibrating = CompletableDeferred<Unit>()
            val job = async {
                target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true }, onStatus = {
                    calibrating.complete(Unit)
                })
            }
            try {
                withTimeout(2_000) { calibrating.await() }
                change(target)
                val result = withTimeout(2_000) { job.await() }
                assertTrue(result.reason.contains("role")); assertEquals(0, result.poses)
                assertNotNull(result.captureFile)
                val trace = target.onlyTrace()
                assertEquals(1, trace.first().getInt("known_role"))
                assertEquals(target.states.value.budRoleRaw.first(), trace.last().getJSONArray("ended_bud_role_raw").getInt(0))
                assertFalse(result.calibrated)
                assertNull(SpatialPoseProfileStore(target.context).load(SpatialPoseProfileKey(MAC, listOf(1), "A3063", "test-firmware")))
                target.assertOwnedCleanup()
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun slowMovementGetsSeparateFiveSecondCuesAndSavesEveryBoundedPhase() = runTest {
        val current = AtomicBoolean(true)
        val progress = mutableListOf<Pair<Long, SpatialCalibrationProgress>>()
        val target = Target(movingReports { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        val result = target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, current::get,
            onProgress = { event ->
                progress += testScheduler.currentTime to event
                if (event.stage == SpatialCalibrationStage.PREPARING && progress.any { it.second.stage == SpatialCalibrationStage.RETURN })
                    current.set(false) // End after accepted calibration; no native sensor is needed for this test.
            })
        assertTrue(result.calibrated); assertNull(result.calibrationFailure)
        assertNotNull(SpatialPoseProfileStore(target.context).load(SpatialPoseProfileKey(MAC, listOf(1), "A3063", "test-firmware")))
        for ((stage, start, seconds) in listOf(
            Triple(SpatialCalibrationStage.NEUTRAL, 0L, 3),
            Triple(SpatialCalibrationStage.LOOK_UP, 3_000L, 5),
            Triple(SpatialCalibrationStage.RETURN, 8_000L, 5))) {
            val ticks = progress.filter { it.second.stage == stage }
            assertEquals((seconds downTo 1).toList(), ticks.map { it.second.secondsRemaining })
            assertEquals((0 until seconds).map { start + it * 1_000 }, ticks.map { it.first })
        }
        val trace = target.onlyTrace()
        val samples = trace.filter { it.getString("type") == "sample" }
        val phases = samples.groupBy { it.getString("calibration_phase") }
        assertEquals(setOf("neutral", "look_up", "return"), phases.keys)
        assertTrue(phases.getValue("look_up").size in 450..1000)
        assertTrue(phases.values.all { it.size <= 1000 })
        assertTrue(samples.zipWithNext().all { (a, b) -> a.getLong("arrival_ns") < b.getLong("arrival_ns") })
        assertTrue(trace.last().getBoolean("calibrated")); assertTrue(trace.last().isNull("calibration_failure"))
        target.assertOwnedCleanup()
    }

    @Test fun aProfileSaveFailureStopsBeforeNativeLeaseAndKeepsItsMathResult() = runTest {
        val target = Target(movingReports { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        val storageContext = mock(Context::class.java)
        val preferences = mock(android.content.SharedPreferences::class.java)
        val editor = mock(android.content.SharedPreferences.Editor::class.java)
        `when`(target.context.applicationContext).thenReturn(storageContext)
        `when`(storageContext.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        val result = target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true })
        assertEquals("profile_save_failed", result.reason)
        assertTrue(result.calibrated); assertNull(result.calibrationFailure); assertEquals(0, result.poses)
        assertTrue(target.onlyTrace().last().getBoolean("calibrated"))
        assertEquals("profile_save_failed", target.onlyTrace().last().getString("reason"))
        verify(editor).commit()
        target.assertOwnedCleanup()
    }

    @Test fun rejectedCalibrationSavesItsInternalGateWithoutShowingItToTheUser() = runTest {
        val target = Target(dispatcher = StandardTestDispatcher(testScheduler))
        val result = target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true })
        assertFalse(result.calibrated)
        assertEquals(SpatialCalibrationFailure.INSUFFICIENT_SAMPLES, result.calibrationFailure)
        assertFalse(result.reason.contains("INSUFFICIENT_SAMPLES")); assertFalse(result.reason.contains("calibration_rejected"))
        val trace = target.onlyTrace()
        assertEquals("INSUFFICIENT_SAMPLES", trace.last().getString("calibration_failure"))
        assertEquals("calibration_rejected", trace.last().getString("reason"))
        target.assertOwnedCleanup()
    }

    @Test fun calibrationCaptureStopsAtItsPhaseBoundWithoutTouchingOtherOwners() = runTest {
        val source = flow<MotionReport?> {
            emit(null)
            for (i in 1..1500) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                emit(report(i.toLong(), 0.0, i * 4L))
            }
            awaitCancellation()
        }
        val target = Target(source, StandardTestDispatcher(testScheduler))
        val result = target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true })
        assertFalse(result.calibrated); assertEquals(0, result.poses)
        val trace = target.onlyTrace()
        assertEquals(1000, trace.first().getInt("max_samples_per_phase"))
        assertEquals(1000, trace.count { it.getString("type") == "sample" })
        target.assertOwnedCleanup()
    }

    private inner class Target(source: Flow<MotionReport?> = flow<MotionReport?> { emit(null); awaitCancellation() },
        dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        val directory = temporary.newFolder()
        val context = mock(Context::class.java)
        val device = mock(AppleDevice::class.java)
        val bridge = mock(ShizukuAudioClient::class.java)
        val states = MutableStateFlow(AppleState(headTrackingState = BuddyState.WAITING, budRoleRaw = listOf(1)))
        val metadata = MutableStateFlow(AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "test-firmware"))
        val otherOwner = Any()
        val owners = CopyOnWriteArrayList<Any>().apply { add(otherOwner) }
        val started = CopyOnWriteArrayList<Any>()
        val stopped = CopyOnWriteArrayList<Any>()
        val clientsCreated = AtomicInteger()
        val probe = SpatialHeadTrackingProbe(context, source, dispatcher) {
            clientsCreated.incrementAndGet(); bridge
        }
        init {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            `when`(context.applicationContext).thenReturn(RuntimeEnvironment.getApplication())
            `when`(context.filesDir).thenReturn(directory)
            `when`(device.macAddress).thenReturn(MacAddress(MAC))
            `when`(device.metadata).thenReturn(metadata)
            `when`(device.state).thenReturn(states)
            `when`(device.settings).thenReturn(MutableStateFlow(me.kavishdevar.librepods.core.devices.AppleSettings()))
            `when`(bridge.state).thenReturn(MutableStateFlow(BridgeState(connected = true)))
            `when`(bridge.hasRootPrivileges).thenReturn(true)
            doAnswer { started += it.getArgument<Any>(0); owners += it.getArgument<Any>(0); true }
                .`when`(device).startHeadTracking(any<Any>() ?: Any())
            doAnswer { stopped += it.getArgument<Any>(0); owners.remove(it.getArgument<Any>(0)); true }
                .`when`(device).stopHeadTracking(any<Any>() ?: Any())
            runBlocking {
                `when`(bridge.inspectSpatialAudio(eq(MAC) ?: MAC, anyCurrent())).thenReturn(Bundle().apply {
                    putBoolean("device_enabled", true); putBoolean("enabled", true)
                })
            }
        }
        fun onlyTrace(): List<JSONObject> {
            val files = File(directory, "spatial-head-tracker-calibrations").listFiles().orEmpty()
            assertEquals(1, files.size)
            return files.single().readLines().map(::JSONObject)
        }
        fun assertOwnedCleanup() {
            assertEquals(1, clientsCreated.get())
            assertEquals(1, started.size); assertEquals(1, stopped.size)
            assertSame(started.single(), stopped.single())
            assertNotSame(otherOwner, started.single()); assertEquals(listOf(otherOwner), owners.toList())
            verify(device, never()).stopHeadTracking()
            verify(bridge).connect(); verify(bridge).close()
            assertTrue(mockingDetails(bridge).invocations.none { it.method.name.substringBefore('$') == "startHeadTrackerProbe" })
        }
    }

    companion object {
        private const val MAC = "00:AA:22:33:44:55"
        private const val OTHER_MAC = "00:AA:22:33:44:66"
        private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
        private fun movingReports(now: () -> Long) = flow<MotionReport?> {
            emit(null)
            var counter = 0L
            while (true) {
                delay(10)
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                val at = now()
                val degrees = when {
                    at < 3_000 -> 0.0
                    at < 6_000 -> (at - 3_000) / 3_000.0 * 25
                    at < 8_000 -> 25.0
                    at < 10_500 -> (10_500 - at) / 2_500.0 * 25
                    else -> 0.0
                }
                emit(report(at, degrees, ++counter * 4))
            }
        }
        private fun report(atMs: Long, degrees: Double, counter: Long): MotionReport {
            val half = Math.toRadians(degrees) / 2
            val rootHalf = kotlin.math.sqrt(0.5)
            return MotionReport(1_000_000_000 + atMs * 1_000_000,
                listOf(sin(half) * rootHalf, -cos(half) * rootHalf, -sin(half) * rootHalf).map { (it * 32768).roundToInt() },
                listOf(0, 0, 0), listOf(0, 0, 0),
                listOf((-1000 * cos(Math.toRadians(degrees))).roundToInt(), (-1000 * sin(Math.toRadians(degrees))).roundToInt(), 0), counter)
        }
    }
}
