package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.bluetooth.BluetoothSocket
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.audio.NativeHeadTrackingController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SpatialCalibrationRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingDoesNotStartAndLeavingTheAppCancelsWithoutReplayingOnResume() {
        val target = Target()
        val owner = Owner()
        val starts = AtomicInteger()
        val ends = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    SpatialCalibrationRoute(target.device, {}, runProbe = { _, _, _, _, _ ->
                        starts.incrementAndGet()
                        try { awaitCancellation() } finally { ends.incrementAndGet() }
                    })
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, starts.get())
        verify(target.aacp, never()).captureAudioSocket()
        compose.onNodeWithTag("spatial_calibration_primary").performClick()
        compose.waitUntil(5_000) { starts.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { ends.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, starts.get())
        compose.onNodeWithText("Setup paused").assertExists()
    }

    @Test fun explicitCancelStopsOneAttemptAndRetryRequiresAnotherTap() {
        val target = Target()
        val starts = AtomicInteger()
        val ends = AtomicInteger()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SpatialCalibrationRoute(target.device, {}, runProbe = { _, _, _, _, _ ->
                    starts.incrementAndGet()
                    try { awaitCancellation() } finally { ends.incrementAndGet() }
                })
            }
        }
        compose.onNodeWithTag("spatial_calibration_primary").performClick()
        compose.waitUntil(5_000) { starts.get() == 1 }
        compose.onNodeWithTag("spatial_calibration_primary").performClick()
        compose.waitUntil(5_000) { ends.get() == 1 }
        compose.waitForIdle()
        assertEquals(1, starts.get())
        compose.onNodeWithTag("spatial_calibration_primary").performClick()
        compose.waitUntil(5_000) { starts.get() == 2 }
        compose.onNodeWithTag("spatial_calibration_primary").performClick()
        compose.waitUntil(5_000) { ends.get() == 2 }
    }

    @Test fun setupPausesOnlyItsOwnNormalTrackingOwnerWithoutChangingTheNativeChoice() {
        val target = Target()
        val owner = Owner()
        val visible = mutableStateOf(true)
        val controller = mock(NativeHeadTrackingController::class.java)
        val paused = mutableListOf<Pair<Any, Boolean>>()
        doAnswer {
            paused += it.getArgument<Any>(0) to it.getArgument<Boolean>(1)
            null
        }.`when`(controller).setCalibrationActive(any() ?: Any(), anyBoolean())
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    if (visible.value) SpatialCalibrationRoute(target.device, {}, calibrationController = controller)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(paused.isNotEmpty())
        assertTrue(paused.last().second)
        val token = paused.first().first
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertFalse(paused.last().second)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertTrue(paused.last().second)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertFalse(paused.last().second)
        assertTrue(paused.all { it.first === token })
        verify(controller, never()).setEnabled(anyBoolean())
        verify(target.aacp, never()).captureAudioSocket()
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Target {
        val device = mock(AppleDevice::class.java)
        val aacp = mock(AACPManager::class.java)
        init {
            val socket = mock(BluetoothSocket::class.java)
            `when`(device.aacp).thenReturn(aacp)
            `when`(device.macAddress).thenReturn(MacAddress("00:AA:22:33:44:55"))
            `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
            `when`(device.state).thenReturn(MutableStateFlow(AppleState()))
            `when`(aacp.captureAudioSocket()).thenReturn(socket)
            `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
        }
    }
}
