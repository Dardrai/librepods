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

    private inner class Target(calibrated: Boolean = true) : AutoCloseable {
        val parent = CoroutineScope(SupervisorJob())
        val device = mock(AppleDevice::class.java)
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val bridge = mock(ShizukuAudioClient::class.java)
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
        val started = CopyOnWriteArrayList<Any>()
        val released = CopyOnWriteArrayList<Any>()
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
            doAnswer { started += it.getArgument<Any>(0); true }.`when`(device).startHeadTracking(any<Any>() ?: Any())
            doAnswer { released += it.getArgument<Any>(0); true }.`when`(device).stopHeadTracking(any<Any>() ?: Any())
            runBlocking {
                `when`(bridge.inspectSpatialAudio(eq(MAC) ?: MAC, anyCurrent())).thenReturn(snapshot)
                `when`(bridge.disableHeadTracking(eq(MAC) ?: MAC, anyCurrent())).thenReturn(Bundle().apply { putBoolean("applied", true) })
            }
            controller = NativeHeadTrackingController(device, parent, blocked::get, current::get,
                bridge, profiles, MutableStateFlow(null), Dispatchers.Default)
        }
        override fun close() { controller.close(); parent.cancel() }
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
            doAnswer {
                owner.set(it.getArgument(1)); entered.countDown()
                release.await(2, TimeUnit.SECONDS); returned.set(true)
                Bundle().apply { putBoolean("applied", true) }
            }.`when`(f.bridge).disableHeadTracking(eq(MAC) ?: MAC, anyCurrent())
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
            val source = flow<MotionReport?> {
                emit(report(1)) // Cached StateFlow value is not a new source sample.
                emit(report(2)); emit(report(3)); emit(report(3)); emit(report(4))
                awaitCancellation()
            }
            val job = async { streamNativeHeadTracking(f.device, source, identity, 7, { true }, poses::add) }
            try {
                waitUntil { poses.size == 2 }
                assertEquals(listOf(7, 7), poses.map { it.discontinuity })
                assertEquals(listOf(60_000_000L, 80_000_000L), poses.map { it.timestampNs })
                assertTrue(poses.all { it.angularVelocity.all { n -> n == 0f } })
            } finally { job.cancelAndJoin() }
            assertEquals(1, f.started.size); assertEquals(f.started.toList(), f.released.toList())
            verify(f.device, never()).stopHeadTracking()
        }
    }

    @Test fun sourceOwnerLossAndBadReferenceStopWithoutEmittingAnotherPose() = runBlocking {
        for (loseOwner in listOf(false, true)) Target().use { f ->
            val current = AtomicBoolean(true); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionReport?> {
                emit(null); emit(report(1)); emit(report(2))
                if (loseOwner) current.set(false)
                emit(report(if (loseOwner) 3 else 1))
            }
            val failure = runCatching { streamNativeHeadTracking(f.device, source, identity, 0, current::get, poses::add) }.exceptionOrNull()
            assertNotNull(failure); assertEquals(1, poses.size)
            assertEquals(if (loseOwner) "owner_changed" else "sensor_time_changed", failure?.message)
            assertEquals(1, f.started.size); assertEquals(f.started.toList(), f.released.toList())
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
        private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
    }
}
