package me.kavishdevar.librepods.audio

import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SpatialHeadTrackingProbeTest {
    @Test fun wrongMacModelUnknownRoleAndRetiredOwnerFailBeforeRootConnection() = runBlocking {
        for (case in 0..3) {
            val target = Target()
            if (case == 1) target.metadata.value = AppleMetadata(modelNumber = "A2564")
            if (case == 2) target.states.value = target.states.value.copy(budRoleRaw = emptyList())
            val result = target.probe.run(SpatialHeadTrackingProbeRequest(if (case == 0) OTHER_MAC else MAC),
                target.device, { case != 3 }) { fail("Rejected request must not reach calibration.") }
            assertNotEquals("stopped", result.reason)
            assertEquals(0, result.poses); assertEquals(0, target.clientsCreated.get())
            verifyNoInteractions(target.bridge)
            assertTrue(target.started.isEmpty())
            assertEquals(listOf(target.otherOwner), target.owners.toList())
            verify(target.device, never()).stopHeadTracking()
        }
    }

    @Test fun cancellingCalibrationReleasesOnlyItsMotionOwnerAndClosesItsClient() = runBlocking {
        val target = Target(); val calibrating = CompletableDeferred<Unit>()
        val job = async {
            target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true }) {
                calibrating.complete(Unit)
            }
        }
        try {
            withTimeout(2_000) { calibrating.await() }
            assertEquals(1, target.started.size)
            assertEquals(2, target.owners.size)
        } finally { job.cancelAndJoin() }
        target.assertOwnedCleanup()
    }

    @Test fun aRoleChangeDuringCalibrationStopsAndReleasesOnlyTheProbeOwner() = runBlocking {
        val target = Target(); val calibrating = CompletableDeferred<Unit>()
        val job = async {
            target.probe.run(SpatialHeadTrackingProbeRequest(MAC), target.device, { true }) {
                calibrating.complete(Unit)
            }
        }
        try {
            withTimeout(2_000) { calibrating.await() }
            target.states.value = target.states.value.copy(budRoleRaw = listOf(2))
            val result = withTimeout(2_000) { job.await() }
            assertTrue(result.reason.contains("role")); assertEquals(0, result.poses)
            target.assertOwnedCleanup()
        } finally { job.cancelAndJoin() }
    }

    private class Target {
        val context = mock(Context::class.java)
        val device = mock(AppleDevice::class.java)
        val bridge = mock(ShizukuAudioClient::class.java)
        val states = MutableStateFlow(AppleState(headTrackingState = BuddyState.WAITING, budRoleRaw = listOf(1)))
        val metadata = MutableStateFlow(AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063"))
        val otherOwner = Any()
        val owners = CopyOnWriteArrayList<Any>().apply { add(otherOwner) }
        val started = CopyOnWriteArrayList<Any>()
        val stopped = CopyOnWriteArrayList<Any>()
        val clientsCreated = AtomicInteger()
        val probe = SpatialHeadTrackingProbe(context, flow<MotionReport?> { emit(null); awaitCancellation() }) {
            clientsCreated.incrementAndGet(); bridge
        }
        init {
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
        fun assertOwnedCleanup() {
            assertEquals(1, clientsCreated.get())
            assertEquals(1, started.size); assertEquals(1, stopped.size)
            assertSame(started.single(), stopped.single())
            assertNotSame(otherOwner, started.single()); assertEquals(listOf(otherOwner), owners.toList())
            verify(device, never()).stopHeadTracking()
            verify(bridge).connect(); verify(bridge).close()
            assertTrue(mockingDetails(bridge).invocations.none { it.method.name == "startHeadTrackerProbe" })
        }
    }

    companion object {
        private const val MAC = "00:AA:22:33:44:55"
        private const val OTHER_MAC = "00:AA:22:33:44:66"
        private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
    }
}
