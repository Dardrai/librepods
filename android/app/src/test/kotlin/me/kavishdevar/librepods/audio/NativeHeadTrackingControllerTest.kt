/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NativeHeadTrackingControllerTest {
    private val identity = checkNotNull(SpatialPoseProfile.fromBasis(listOf(
        listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))))

    private inner class Target(calibrated: Boolean = true, rejectFirstStart: Boolean = false) : AutoCloseable {
        val parent = CoroutineScope(SupervisorJob())
        val started = CopyOnWriteArrayList<Any>()
        val released = CopyOnWriteArrayList<Any>()
        val device = mock(AppleDevice::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name.startsWith("startHeadTracking") && invocation.arguments.isNotEmpty()) {
                started += invocation.getArgument<Any>(0)
                !rejectFirstStart || started.size != 1
            } else RETURNS_DEFAULTS.answer(invocation)
        })
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        var disable: (() -> Boolean) -> Bundle = { Bundle().apply { putBoolean("applied", true) } }
        val bridge = mock(ShizukuAudioClient::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name.substringBefore('$') == "disableHeadTracking")
                disable(invocation.getArgument(1)) else RETURNS_DEFAULTS.answer(invocation)
        })
        val profiles = mock(SpatialPoseProfileStore::class.java)
        val current = AtomicBoolean(true)
        val blocked = AtomicBoolean(false)
        val states = MutableStateFlow(AppleState(budRoleRaw = listOf(1, 0, 1, 0)))
        val metadata = MutableStateFlow(AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3,
            modelNumber = "A3064", version1 = "8-test"))
        val snapshot = Bundle().apply {
            putInt("version", 1)
            putBoolean("known", true); putBoolean("head_tracker_enabled", false)
            putBoolean("device_enabled", true); putBoolean("enabled", true); putBoolean("route_selected", true)
        }
        val controller: NativeHeadTrackingController
        init {
            `when`(device.macAddress).thenReturn(MacAddress(MAC))
            `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
            `when`(device.aacp).thenReturn(aacp)
            `when`(device.state).thenReturn(states); `when`(device.metadata).thenReturn(metadata)
            `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings()))
            `when`(aacp.captureAudioSocket()).thenReturn(socket)
            `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
            `when`(bridge.hasRootPrivileges).thenReturn(true)
            `when`(profiles.load(any<SpatialPoseProfileKey>() ?: key())).thenReturn(if (calibrated) identity else null)
            doAnswer { released += it.getArgument<Any>(0); true }.`when`(device).stopHeadTracking(any<Any>() ?: Any())
            runBlocking {
                `when`(bridge.inspectSpatialAudio(eq(MAC) ?: MAC, anyCurrent())).thenReturn(snapshot)
            }
            controller = NativeHeadTrackingController(device, parent, blocked::get, current::get,
                bridge, profiles, MutableStateFlow(null), Dispatchers.Default)
        }
        override fun close() { controller.close(); parent.cancel() }
    }

    @Test fun cachedSourceTimingIsIncludedWithoutStartingMotion() {
        Target().use { f ->
            `when`(f.aacp.motionSourceTimingSnapshot()).thenReturn(mapOf("reads" to 12L, "feed_wait_max_nanos" to 30L))
            val source = requireNotNull(f.controller.timingSnapshot().getBundle("source"))
            assertEquals(12L, source.getLong("reads"))
            assertEquals(30L, source.getLong("feed_wait_max_nanos"))
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun calibratedNativeOffIsReadWithoutStartingASensorOrRequestingPermission() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            assertEquals(false, f.controller.state.value.enabled)
            assertTrue(f.controller.state.value.calibrated)
            assertFalse(f.controller.state.value.active)
            assertTrue(f.started.isEmpty())
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("openHeadTracking", "requestPermission", "setFixedSpatialEnabled", "disableHeadTracking") })
        }
    }

    @Test fun explicitOffWorksWithoutACurrentProfileAndCloseDoesNotChangeThePreference() = runBlocking {
        Target(calibrated = false).use { f ->
            f.controller.setEnabled(false)
            waitUntil { !f.controller.state.value.busy && f.controller.state.value.enabled == false }
            f.controller.close()
            waitUntil { mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "close" } }
            assertEquals(1, mockingDetails(f.bridge).invocations.count { it.method.name.substringBefore('$') == "disableHeadTracking" })
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun knownPro3ModelAliasesUseTheSameFullSourceIdentity() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            fun reads() = mockingDetails(f.profiles).invocations.filter { it.method.name == "load" }
            val before = reads().size
            f.metadata.value = f.metadata.value.copy(modelNumber = "A3063")
            waitUntil { reads().size > before }
            val keys = reads().map { it.getArgument<SpatialPoseProfileKey>(0) }
            assertTrue(keys.isNotEmpty())
            assertTrue(keys.all { it == key().copy(modelNumber = "AIRPODS_PRO_3") })
            val cached = f.controller.timingSnapshot()
            assertTrue(cached.getBoolean("calibrated")); assertTrue(cached.getBoolean("available"))
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun unknownRoleNeverStartsAnUncalibratedSourceOrChangesNativeChoice() = runBlocking {
        Target(calibrated = false).use { f ->
            f.states.value = f.states.value.copy(budRoleRaw = emptyList())
            delay(250)
            assertFalse(f.controller.state.value.calibrated)
            assertFalse(f.controller.state.value.available)
            assertTrue(f.started.isEmpty())
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in setOf("openHeadTracking", "disableHeadTracking", "requestPermission") })
        }
    }

    @Test fun nativeOffDoesNotWaitForABlockedBluetoothSourceCleanup() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            val cleanupStarted = CountDownLatch(1); val release = CountDownLatch(1)
            val stuckSource = f.parent.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { cleanupStarted.countDown(); release.await(3, TimeUnit.SECONDS) }
            }
            try {
                // Reproduce a source finally blocked inside the Bluetooth stack's synchronous write.
                ReflectionHelpers.setField(f.controller, "session", stuckSource)
                assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
                f.controller.setEnabled(false)
                waitUntil { !f.controller.state.value.busy && f.controller.state.value.enabled == false }
                assertFalse("Off must finish before Bluetooth cleanup returns", stuckSource.isCompleted)
                assertEquals(1, mockingDetails(f.bridge).invocations.count {
                    it.method.name.substringBefore('$') == "disableHeadTracking"
                })
            } finally { release.countDown(); stuckSource.cancelAndJoin() }
        }
    }

    @Test fun aLateOffReplyCannotClearANewerOnChoice() = runBlocking {
        Target(calibrated = false).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val owner = AtomicReference<() -> Boolean>()
            val returned = AtomicBoolean()
            f.disable = { current ->
                owner.set(current); entered.countDown()
                release.await(2, TimeUnit.SECONDS); returned.set(true)
                Bundle().apply { putBoolean("applied", true) }
            }
            try {
                f.controller.setEnabled(false)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.controller.setCalibrationActive(Any(), true)
                f.controller.setEnabled(true)
                assertFalse("A superseded root request must lose its owner", owner.get().invoke())
                release.countDown(); waitUntil { returned.get() }; delay(150)
                assertEquals(true, f.controller.state.value.enabled)
                assertTrue(f.controller.state.value.busy)
            } finally { release.countDown() }
        }
    }

    @Test fun ownedCalibrationPauseAndCallBlockDoNotWriteTheNativeChoice() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            val first = Any(); val second = Any()
            f.controller.setCalibrationActive(first, true); f.controller.setCalibrationActive(second, true)
            waitUntil { !f.controller.state.value.available }
            f.controller.setCalibrationActive(first, false)
            delay(150); assertFalse(f.controller.state.value.available)
            f.blocked.set(true); f.controller.setCalibrationActive(second, false)
            delay(250); assertFalse(f.controller.state.value.available)
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in setOf("openHeadTracking", "disableHeadTracking") })
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun reportingSourcePrimesFreshRatesAndReleasesOnlyItsOwnMotionOwner() = runBlocking {
        Target().use { f ->
            val poses = mutableListOf<SpatialPose>()
            val arrivals = mutableListOf<Long>()
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> {
                emit(MotionSample(report(1), HOST_START - 1)) // Cached before this reporting epoch.
                for (tick in listOf(2, 3, 3, 4)) {
                    clock += 20_000_000; emit(MotionSample(report(tick), clock))
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 7, { true }, timing,
                now = { clock }) { pose, arrival -> poses += pose; arrivals += arrival } }
            try {
                waitUntil { poses.size == 2 }
                assertEquals(listOf(7, 7), poses.map { it.discontinuity })
                assertEquals(listOf(60_000_000L, 80_000_000L), poses.map { it.timestampNs })
                assertTrue(poses.all { it.angularVelocity.all { n -> n == 0f } })
                assertEquals(listOf(HOST_START + 40_000_000, HOST_START + 80_000_000), arrivals)
                assertEquals(10_000, timing.snapshot().getLong("requested_interval_us"))
            } finally { job.cancelAndJoin() }
            assertEquals(1, f.started.size); assertEquals(f.started.toList(), f.released.toList())
            verify(f.device, never()).stopHeadTracking()
        }
    }

    @Test fun hundredHertzSourceKeepsOriginalTimesAndDoesNotFallBack() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val poses = mutableListOf<Pair<SpatialPose, Long>>()
            val source = flow<MotionSample?> {
                for (tick in 1..101) {
                    clock += 10_000_000
                    emit(MotionSample(report(tick).copy(timestampNs = tick * 10_000_000L,
                        counter = tick * 2L), clock))
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 3, { true }, timing,
                now = { clock }) { pose, arrival -> poses += pose to arrival } }
            try {
                waitUntil { poses.size == 100 }
                assertEquals(10_000L, timing.snapshot().getLong("requested_interval_us"))
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals(101L, timing.snapshot().getLong("source_samples"))
                assertEquals((2..101).map { it * 10_000_000L }, poses.map { it.first.timestampNs })
                assertEquals((2..101).map { HOST_START + it * 10_000_000L }, poses.map { it.second })
                assertTrue(poses.all { it.first.discontinuity == 3 })
                assertEquals(1, f.started.size)
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun sourceOwnerLossAndBadReferenceStopWithoutEmittingAnotherPose() = runBlocking {
        for (loseOwner in listOf(false, true)) Target().use { f ->
            val current = AtomicBoolean(true); val poses = mutableListOf<SpatialPose>()
            var clock = HOST_START
            val source = flow<MotionSample?> {
                emit(null)
                for (tick in listOf(1, 2)) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                if (loseOwner) current.set(false)
                clock += 20_000_000; emit(MotionSample(report(if (loseOwner) 3 else 1), clock))
            }
            val failure = runCatching { streamCalibratedHeadMotion(f.device, source, identity, 0, current::get,
                now = { clock }) { pose, _ -> poses += pose } }.exceptionOrNull()
            assertNotNull(failure); assertEquals(1, poses.size)
            assertEquals(if (loseOwner) "owner_changed" else "sensor_time_changed", failure?.message)
            assertEquals(1, f.started.size); assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun staleSamplesAreNotRestampedAndTheFirstFreshSamplePrimesWithoutAnExtraDrop() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<Pair<SpatialPose, Long>>()
            val source = flow<MotionSample?> {
                clock += 300_000_000
                emit(MotionSample(report(1), HOST_START + 1))
                clock += 20_000_000; emit(MotionSample(report(2), clock - 30_000_000))
                clock += 20_000_000; emit(MotionSample(report(3), clock - 25_000_000))
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }) { pose, arrival -> poses += pose to arrival } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(HOST_START + 315_000_000, poses.single().second)
                val stats = timing.snapshot()
                assertEquals(1, stats.getLong("stale_samples_dropped"))
                assertEquals(2, stats.getLong("source_samples"))
                assertEquals(27_500_000.0, stats.getDouble("app_collection_age_mean_nanos"), 0.0)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun refusedFastRateFallsBackToTheWorkingRateUsingTheSameOwner() = runBlocking {
        Target(rejectFirstStart = true).use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for (tick in 1..2) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(20_000, timing.snapshot().getLong("requested_interval_us"))
                assertTrue(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals("fast_request_refused", timing.snapshot().getString("first_fallback_reason"))
            } finally { job.cancelAndJoin() }
            assertEquals(2, f.started.size); assertSame(f.started[0], f.started[1])
            assertEquals(listOf(f.started[0]), f.released.toList())
        }
    }

    @Test fun anAcceptedFastRequestWithNoValidAttitudeFallsBackWithoutRestartingTheSource() = runBlocking {
        Target().use { f ->
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> { emit(null); awaitCancellation() }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { HOST_START }) { _, _ -> fail("No measurements exist") } }
            try {
                waitUntil { timing.snapshot().getBoolean("fallback_rate") }
                assertEquals("attitude_startup_timeout", timing.snapshot().getString("first_fallback_reason"))
                assertEquals(20_000, timing.snapshot().getLong("requested_interval_us"))
                assertEquals(2, f.started.size); assertSame(f.started[0], f.started[1])
                assertTrue(f.released.isEmpty())
            } finally { job.cancelAndJoin() }
            assertEquals(listOf(f.started[0]), f.released.toList())
        }
    }

    @Test fun oneFreshAttitudeThenAStallFallsBackBeforeTheRootFreshnessWindow() = runBlocking {
        Target().use { f ->
            val clock = AtomicLong(HOST_START)
            val timing = HeadTrackingTiming(); val fallbackAt = AtomicLong()
            val firstArrival = HOST_START + 20_000_000
            val source = flow<MotionSample?> {
                clock.set(firstArrival); emit(MotionSample(report(1), firstArrival))
                clock.set(firstArrival + 180_000_000)
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = clock::get, onFallback = { fallbackAt.set(clock.get()) }) { _, _ ->
                fail("One attitude only primes the converter; fallback cannot fabricate a pose")
            } }
            try {
                waitUntil { timing.snapshot().getBoolean("fallback_rate") }
                assertTrue(fallbackAt.get() - firstArrival in 150_000_000L until 250_000_000L)
                assertEquals("attitude_stall", timing.snapshot().getString("first_fallback_reason"))
                assertEquals(fallbackAt.get(), timing.snapshot().getLong("first_fallback_at_nanos"))
                assertEquals(firstArrival, timing.snapshot().getLong("first_fallback_last_attitude_at_nanos"))
                assertEquals(20_000L, timing.snapshot().getLong("requested_interval_us"))
                assertEquals(2, f.started.size); assertSame(f.started[0], f.started[1])
                assertTrue(f.released.isEmpty())
            } finally { job.cancelAndJoin() }
            assertEquals(listOf(f.started[0]), f.released.toList())
        }
    }

    @Test fun aSocketAlreadyMarkedForFallbackStartsAtTheWorkingRateWithoutTryingFastAgain() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for (tick in 1..2) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }, tryFast = false) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(1, f.started.size)
                assertEquals(20_000L, timing.snapshot().getLong("requested_interval_us"))
                assertTrue(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals("socket_fallback", timing.snapshot().getString("first_fallback_reason"))
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun validIdentityReportsAfterStartupKeepTheFastSourceAlive() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for (tick in 1..20) {
                    clock += 20_000_000
                    emit(MotionSample(if (tick == 1) report(tick) else
                        report(tick).copy(attitudeQ15 = listOf(0, 0, 0)), clock))
                    delay(10)
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 19 }
                assertEquals(1, f.started.size)
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }
    private fun key() = SpatialPoseProfileKey(MAC, listOf(1, 0, 1, 0), "A3064", "8-test")
    private fun report(tick: Int) = MotionReport(tick * 20_000_000L, listOf(1, 0, 0),
        listOf(0, 0, 0), listOf(0, 0, 0), listOf(0, 0, -1000), tick * 4L)
    companion object {
        private const val MAC = "00:AA:22:33:44:55"
        private const val HOST_START = 1_000_000_000L
        private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
    }
}
