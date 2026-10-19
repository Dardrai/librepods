/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.animation.ValueAnimator
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandStatusBarCoverageIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var originalMotionScale = 1f

    @Before fun enableMotion() {
        originalMotionScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
            .apply { isAccessible = true }.invoke(null) as Float
        motionScale(1f)
    }

    @After fun restoreMotion() { motionScale(originalMotionScale) }

    @Test fun appearanceFollowsTheAnimatedShellAndReleasesOnFoldAndDisposal() {
        val state = mutableStateOf(IslandState.EXPANDED)
        val mounted = mutableStateOf(true)
        val coverage = mutableListOf<Boolean>()
        val coverageChanged: (Boolean) -> Unit = { coverage += it }
        val settings = IslandSettings(expandedWidth = 1248f, expandedHeight = 630f,
            compactWidth = 300f, compactHeight = 132f, showListeningModeControl = false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                LibrePodsTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(416.dp, 260.dp), contentAlignment = Alignment.TopCenter) {
                        if (mounted.value) IslandContent(name = "Studio AirPods Pro 3",
                            type = IslandType.CONNECTED, batteryLevel = 80, reversed = false,
                            otherDeviceName = null, islandSettings = settings, islandState = state.value,
                            showOffListeningMode = false, noiseControlModeValue = 3,
                            onNoiseControlModeChanged = {}, onAdaptiveStrengthChanged = {},
                            model = AirPodsModel.AIRPODS_PRO_3, paired = true,
                            cameraPaddingPx = 150, cameraCapsuleWidthPx = 132f,
                            minimumStatusBarWidthPx = 1248f, statusBarIconCenterYpx = 66f,
                            onStatusBarCoverageChanged = coverageChanged)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(false, coverage.lastOrNull())
        advance(32)
        assertTrue("Opening must have a real intermediate shell", shellWidth() < 1247.5f)
        assertFalse("Expanded intent cannot whiten icons before the shell covers them", coverage.last())
        advance(80)
        assertEquals(1248f, shellWidth(), 0.5f)
        assertTrue("The icon background must be covered while the body is still opening", shellHeight() < 630f)
        assertTrue(coverage.last())
        advance(320)

        mutate { state.value = IslandState.COMPACT }
        advance(64)
        assertFalse("The shrinking shell must release before compact content replaces the payload", coverage.last())
        advance(240)
        assertEquals(300f, shellWidth(), 0.5f)
        assertFalse(coverage.last())

        mutate { state.value = IslandState.EXPANDED }
        advance(32)
        assertTrue(shellWidth() < 1247.5f)
        assertFalse(coverage.last())
        advance(80)
        assertEquals(1248f, shellWidth(), 0.5f)
        assertTrue("Reopening must cover icons before the body finishes", shellHeight() < 630f)
        assertTrue(coverage.last())
        advance(320)

        mutate { mounted.value = false }
        assertFalse("Disposing a covered shell must release its appearance", coverage.last())
        assertEquals(listOf(false, true, false, true, false), coverage)
    }

    private fun shellWidth() = compose.onNodeWithTag("island-shell").fetchSemanticsNode().boundsInRoot.width
    private fun shellHeight() = compose.onNodeWithTag("island-shell").fetchSemanticsNode().boundsInRoot.height

    private fun mutate(change: () -> Unit) {
        compose.runOnIdle { change(); Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun motionScale(scale: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(null, scale)
    }
}
