/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.audio.SpatialCalibrationFailure
import me.kavishdevar.librepods.audio.SpatialCalibrationProgress
import me.kavishdevar.librepods.audio.SpatialCalibrationStage
import me.kavishdevar.librepods.audio.SpatialHeadTrackingProbeResult
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class SpatialCalibrationScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun result(reason: String = "deadline", restored: Boolean = true, closed: Boolean = true) =
        SpatialHeadTrackingProbeResult(reason, 123, Bundle().apply {
            putBoolean("restored", restored); putBoolean("sink_closed", closed)
        }, calibrated = true)

    @Test fun introNeverStartsAutomaticallyAndActionsAreExplicit() {
        var starts = 0; var cancels = 0; var backs = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SpatialCalibrationScreen(null, false, null, { starts++ }, { cancels++ }, { backs++ })
            }
        }
        compose.waitForIdle()
        assertEquals(0, starts); assertEquals(0, cancels); assertEquals(0, backs)
        compose.onNodeWithText("Set up your head position").assertIsDisplayed()
        compose.onNodeWithText("Start").performScrollTo().performClick()
        assertEquals(1, starts); assertEquals(0, cancels)
        compose.onNodeWithText("Not Now").performScrollTo().performClick()
        assertEquals(1, backs)
    }

    @Test fun movementCuesAreDistinctAndCountdownUpdatesDoNotVibrate() {
        val progress = mutableStateOf(SpatialCalibrationProgress(SpatialCalibrationStage.NEUTRAL, 3))
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    SpatialCalibrationScreen(progress.value, true, null, {}, {}, {})
                }
            }
        }
        compose.waitForIdle(); assertTrue(feedback.isEmpty())
        compose.runOnIdle { progress.value = SpatialCalibrationProgress(SpatialCalibrationStage.LOOK_UP, 5) }
        compose.waitForIdle(); assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), feedback)
        compose.runOnIdle { progress.value = SpatialCalibrationProgress(SpatialCalibrationStage.LOOK_UP, 4) }
        compose.waitForIdle(); assertEquals(1, feedback.size)
        compose.runOnIdle { progress.value = SpatialCalibrationProgress(SpatialCalibrationStage.RETURN, 5) }
        compose.waitForIdle(); assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate, HapticFeedbackType.LongPress), feedback)
        compose.runOnIdle { progress.value = SpatialCalibrationProgress(SpatialCalibrationStage.RETURN, 4) }
        compose.waitForIdle(); assertEquals(2, feedback.size)
        compose.onNodeWithText("Return to level").assertIsDisplayed()
        compose.onNodeWithText("4 seconds remaining").assertIsDisplayed()
        compose.runOnIdle { progress.value = SpatialCalibrationProgress(SpatialCalibrationStage.READY, 30) }
        compose.onNodeWithTag("spatial_calibration_countdown").assertDoesNotExist()
        assertEquals(2, feedback.size)
    }

    @Test fun successfulFinishNeedsCalibrationNormalCompletionAndVerifiedCleanup() {
        assertTrue(spatialCalibrationCompleted(result()))
        for (rejected in listOf(result("sink_stopped"), result(restored = false), result(closed = false),
            result().copy(calibrated = false), result().copy(cleanup = null),
            result().copy(calibrationFailure = SpatialCalibrationFailure.RETURN_NOT_NEUTRAL))) assertFalse(spatialCalibrationCompleted(rejected))
        val current = mutableStateOf<SpatialHeadTrackingProbeResult?>(result())
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SpatialCalibrationScreen(null, false, current.value, {}, {}, {})
            }
        }
        compose.onNodeWithText("Calibration complete").assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { current.value = result("sink_stopped") }
        compose.onNodeWithText("Calibration worked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try Again").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sink_stopped").assertDoesNotExist()
        compose.onNodeWithText("123").assertDoesNotExist()
        compose.runOnIdle { current.value = result(restored = false) }
        compose.onNodeWithText("Setup couldn’t finish").assertIsDisplayed()
        compose.onNodeWithText("Try Again").assertDoesNotExist()
        compose.onNodeWithText("Go Back").performScrollTo().assertIsDisplayed()
    }

    @Test fun typedRejectionAndCancellationOfferPlainRecoveryWithoutPrivateDetails() {
        val current = mutableStateOf(SpatialHeadTrackingProbeResult("private raw gate", 0, null,
            calibrationFailure = SpatialCalibrationFailure.RETURN_NOT_NEUTRAL, captureFile = File("/private/capture.jsonl")))
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SpatialCalibrationScreen(null, false, current.value, {}, {}, {})
            }
        }
        compose.onNodeWithText("Return to the same level position you started in, then hold still.").assertIsDisplayed()
        compose.onNodeWithText("private raw gate").assertDoesNotExist()
        compose.onNodeWithText("/private/capture.jsonl").assertDoesNotExist()
        compose.runOnIdle { current.value = SpatialHeadTrackingProbeResult("cancelled", 0, null) }
        compose.onNodeWithText("Setup paused").assertIsDisplayed()
        compose.onNodeWithText("Start Again").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { current.value = SpatialHeadTrackingProbeResult("spatial_disabled", 0, null) }
        compose.onNodeWithText("Turn on Fixed Spatial Audio in AirPods settings, then start again.").assertIsDisplayed()
        compose.onNodeWithText("Go Back").performScrollTo().assertIsDisplayed()
    }

    @Test fun aStorageFailureDoesNotClaimTheCalibrationWasSavedOrAutomaticallyRetry() {
        var retries = 0
        val failed = SpatialHeadTrackingProbeResult("profile_save_failed", 0, null, calibrated = true)
        assertFalse(spatialCalibrationCompleted(failed))
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SpatialCalibrationScreen(null, false, failed, { retries++ }, {}, {})
            }
        }
        compose.onNodeWithText("Setup wasn’t saved").assertIsDisplayed()
        compose.onNodeWithText("Your head position was measured, but the app couldn’t save it. Try again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Calibration complete").assertDoesNotExist()
        compose.onNodeWithText("profile_save_failed").assertDoesNotExist()
        assertEquals(0, retries)
        compose.onNodeWithText("Try Again").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun appleLightAndDarkKeepInstructionsAndActionsReachableAtDoubleTextSize() {
        val dark = mutableStateOf(false)
        val progress = mutableStateOf<SpatialCalibrationProgress?>(null)
        val running = mutableStateOf(false)
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LibrePodsTheme(darkTheme = dark.value, designSystem = DesignSystem.Apple) {
                    Box(Modifier.requiredSize(320.dp, 640.dp).testTag("calibration_narrow_window")) {
                        SpatialCalibrationScreen(progress.value, running.value, null, { starts++ }, {}, {})
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night; progress.value = null; running.value = false }
            compose.onNodeWithTag("spatial_calibration_primary").performScrollTo().assertIsDisplayed()
            val button = compose.onNodeWithTag("spatial_calibration_primary").fetchSemanticsNode().boundsInRoot
            val window = compose.onNodeWithTag("calibration_narrow_window").fetchSemanticsNode().boundsInRoot
            assertTrue(button.height >= 48f); assertTrue(button.left >= window.left + 16 && button.right <= window.right - 16)
            compose.runOnIdle { progress.value = SpatialCalibrationProgress(SpatialCalibrationStage.LOOK_UP, 5); running.value = true }
            compose.onNodeWithTag("spatial_calibration_instruction").assertIsDisplayed()
            val instruction = compose.onNodeWithTag("spatial_calibration_instruction").fetchSemanticsNode().boundsInRoot
            assertTrue("Cue must stay below the scaffold chrome", instruction.top >= 64f)
            compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        }
        assertEquals(0, starts)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun materialLightAndDarkProvidePhonePreviewArtifacts() {
        val dark = mutableStateOf(false)
        compose.setContent {
            LibrePodsTheme(darkTheme = dark.value, designSystem = DesignSystem.Material) {
                Box(Modifier.requiredSize(360.dp, 800.dp).testTag("calibration_preview")) {
                    SpatialCalibrationScreen(SpatialCalibrationProgress(SpatialCalibrationStage.LOOK_UP, 5),
                        true, null, {}, {}, {})
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            val output = File("build/calibration-previews/phone-${if (night) "dark" else "light"}.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use {
                assertTrue(compose.onNodeWithTag("calibration_preview").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
