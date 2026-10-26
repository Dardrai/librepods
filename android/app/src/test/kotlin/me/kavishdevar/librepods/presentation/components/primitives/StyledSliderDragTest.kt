/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class StyledSliderDragTest {
    @get:Rule val compose = createComposeRule()
    private val value = mutableFloatStateOf(0f)
    private val enabled = mutableStateOf(true)
    private val changes = mutableListOf<Float>()
    private val haptics = mutableListOf<HapticFeedbackType>()
    private fun thumb() = compose.onNodeWithTag("styled-slider-thumb", useUnmergedTree = true)

    private fun show(designSystem: DesignSystem = DesignSystem.Apple, finished: (() -> Unit)? = null, canceled: (() -> Unit)? = null,
        controlledValue: (Float) -> Float = { it }) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
            }) {
                LibrePodsTheme(designSystem = designSystem) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(320.dp, 140.dp)) {
                            StyledSlider(value = value.floatValue, valueRange = -1f..1f,
                                snapPoints = listOf(-1f, 0f, 1f), snapThreshold = .05f, enabled = enabled.value,
                                onValueChange = { changes += it; value.floatValue = controlledValue(it) },
                                onValueChangeFinished = finished, onValueChangeCanceled = canceled)
                        }
                    }
                }
            }
        }
    }

    @Test fun manySmallDeltasEscapeTheCentreSnapAndReturnToIt() {
        show()
        thumb().performTouchInput {
            down(center)
            repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) }
        }
        compose.runOnIdle { assertTrue("Small deltas must accumulate beyond the centre snap", value.floatValue > .2f) }
        thumb().performTouchInput { repeat(60) { moveBy(Offset(-1f, 0f), delayMillis = 8) }; up() }
        compose.runOnIdle {
            assertEquals(0f, value.floatValue, 0f)
            assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics)
        }
    }

    @Test fun clampedEndsDoNotRetainOvershootWhenTheFingerReverses() {
        show()
        thumb().performTouchInput { down(center); moveBy(Offset(200f, 0f)) }
        compose.runOnIdle { assertEquals(1f, value.floatValue, 0f) }
        thumb().performTouchInput { repeat(16) { moveBy(Offset(-1f, 0f), delayMillis = 8) } }
        compose.runOnIdle { assertTrue(value.floatValue < .98f) }
        thumb().performTouchInput { moveBy(Offset(-360f, 0f)) }
        compose.runOnIdle { assertEquals(-1f, value.floatValue, 0f) }
        thumb().performTouchInput { repeat(16) { moveBy(Offset(1f, 0f), delayMillis = 8) }; up() }
        compose.runOnIdle {
            assertTrue(value.floatValue > -.98f)
            assertTrue(changes.all { it in -1f..1f })
        }
    }

    @Test fun anExternalReplacementIsNotReemittedOnStopAndTheNextGestureStartsThere() {
        show()
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        compose.runOnIdle { value.floatValue = -.4f; changes.clear(); haptics.clear() }
        thumb().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(-.4f, value.floatValue, 0f)
            assertTrue(changes.isEmpty())
            assertTrue(haptics.isEmpty())
        }
        thumb().performTouchInput { down(center); repeat(16) { moveBy(Offset(1f, 0f), delayMillis = 8) }; up() }
        compose.runOnIdle { assertTrue(value.floatValue in -.35f..-.2f) }
    }

    @Test fun disablingDuringADragDoesNotCommitAndReenablingStartsFromTheCurrentValue() {
        show()
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        compose.runOnIdle { enabled.value = false; value.floatValue = -.6f; changes.clear(); haptics.clear() }
        thumb().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(-.6f, value.floatValue, 0f)
            assertTrue(changes.isEmpty())
            assertTrue(haptics.isEmpty())
            enabled.value = true
        }
        thumb().performTouchInput { down(center); repeat(16) { moveBy(Offset(1f, 0f), delayMillis = 8) }; up() }
        compose.runOnIdle { assertTrue(value.floatValue in -.55f..-.4f) }
    }

    @Test fun continuingAfterAnExternalReplacementRebasesBeforeTheNextMove() {
        show()
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        compose.runOnIdle { assertTrue(value.floatValue > .2f); value.floatValue = -.4f; changes.clear(); haptics.clear() }
        thumb().performTouchInput { repeat(16) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        compose.runOnIdle {
            assertTrue("The same gesture must continue near the replacement", value.floatValue in -.35f..-.2f)
            assertTrue(changes.all { it in -.4f..-.2f })
            assertTrue("The external jump must not produce a centre-crossing tick", haptics.isEmpty())
        }
        thumb().performTouchInput { up() }
        compose.runOnIdle { assertTrue(value.floatValue in -.35f..-.2f) }
    }

    @Test fun smallDeltasStillEscapeTheSnapWithQuantizedParentFeedback() {
        show { (it * 10).roundToInt() / 10f }
        thumb().performTouchInput { down(center) }
        repeat(6) {
            thumb().performTouchInput { repeat(10) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
            compose.waitForIdle()
        }
        thumb().performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("Unchanged rounded feedback must not discard accumulated movement", value.floatValue >= .3f)
            assertTrue(changes.all { it in -1f..1f })
        }
    }

    @Test fun appleFinishCallbackRunsOnceAfterAllMovesEvenBeforeFeedbackRecomposes() {
        val finished = mutableListOf<Float>()
        show(finished = { finished += value.floatValue })
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) }; up() }
        compose.runOnIdle {
            assertTrue(changes.size > 10)
            assertEquals(1, finished.size)
            assertEquals(value.floatValue, finished.single(), 0f)
            assertTrue(finished.single() > .2f)
        }
    }

    @Test fun materialFinishCallbackRunsOnceAndRemoteChangesDoNotCommit() {
        var finished = 0
        show(designSystem = DesignSystem.Material, finished = { finished++ })
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performTouchInput { down(center); repeat(30) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        compose.runOnIdle { assertEquals(0, finished) }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, finished); value.floatValue = -.6f; enabled.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, finished) }
    }

    @Test fun canceledAppleDragDoesNotFinishOrRoundTheStagedValue() {
        var finished = 0
        var canceled = 0
        show(finished = { finished++ }, canceled = { canceled++ })
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        var count = 0
        compose.runOnIdle { assertTrue(value.floatValue > .2f); count = changes.size }
        thumb().performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(0, finished); assertEquals(1, canceled); assertEquals(count, changes.size) }
    }

    @Test fun materialCancellationDoesNotFinishAndTheNextGestureCanFinish() {
        var finished = 0
        var canceled = 0
        show(designSystem = DesignSystem.Material, finished = { finished++ }, canceled = { canceled++ })
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performTouchInput { down(center); repeat(30) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
        compose.waitForIdle()
        slider.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(0, finished); assertEquals(1, canceled) }
        slider.performTouchInput { down(center); repeat(30) { moveBy(Offset(-1f, 0f), delayMillis = 8) }; up() }
        compose.runOnIdle { assertEquals(1, finished) }
    }
}
