/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StyledSliderAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appleRangeActionIsLabeledBoundedAndCommitsOnceWithoutPointerInput() {
        val value = mutableFloatStateOf(0f)
        val changes = mutableListOf<Float>()
        var finished = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            Box(Modifier.width(320.dp)) { StyledSlider(label = "Balance", value = value.floatValue,
                valueRange = -1f..1f, onValueChange = { changes += it; value.floatValue = it },
                onValueChangeFinished = { finished++ }) }
        } }
        val slider = compose.onNodeWithTag("styled-slider")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Balance")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, -1f..1f)))
        compose.runOnIdle { assertTrue(changes.isEmpty()); assertEquals(0, finished) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.4f)) }
        compose.runOnIdle { assertEquals(listOf(.4f), changes); assertEquals(1, finished) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(Float.NaN)) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(5f)) }
        compose.runOnIdle { assertEquals(1f, value.floatValue); assertEquals(2, finished) }
    }

    @Test fun optimizationKeyboardMovesThroughExactlyThreeLevelsAndStopsAtBoundaries() {
        val value = mutableFloatStateOf(0f)
        val changes = mutableListOf<Float>()
        var finished = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            StyledSlider(label = "Optimization", value = value.floatValue, valueRange = 0f..2f,
                steps = 1, snapPoints = listOf(0f, 1f, 2f),
                onValueChange = { changes += it; value.floatValue = it }, onValueChangeFinished = { finished++ })
        } }
        val slider = compose.onNodeWithTag("styled-slider")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(.1f)) }
        compose.runOnIdle { assertTrue(changes.isEmpty()); assertEquals(0, finished) }
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(3) { slider.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) } }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(2f, 0f..2f, 1)))
        compose.runOnIdle { assertEquals(listOf(1f, 2f), changes); assertEquals(2, finished) }
        slider.performKeyInput { keyDown(Key.MoveHome); keyUp(Key.MoveHome) }
        compose.runOnIdle { assertEquals(0f, value.floatValue); assertEquals(3, finished) }
    }

    @Test fun disabledAppleSliderHasNoAdjustmentActionAndHydrationDoesNotCommit() {
        val enabled = mutableStateOf(false)
        val value = mutableFloatStateOf(0f)
        var changed = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            StyledSlider(label = "Amplification", value = value.floatValue, valueRange = -1f..1f,
                enabled = enabled.value, onValueChange = { changed++ }, onValueChangeFinished = { changed++ })
        } }
        compose.onNodeWithTag("styled-slider").assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        compose.runOnIdle { enabled.value = true; value.floatValue = .7f }
        compose.onNodeWithTag("styled-slider")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.7f, -1f..1f)))
        compose.runOnIdle { assertEquals(0, changed) }
    }

    @Test fun discretePointerMotionAccumulatesWithoutSendingRepeatedValuesInsideOneStep() {
        val value = mutableFloatStateOf(0f)
        val changes = mutableListOf<Float>()
        var finished = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            Box(Modifier.width(320.dp)) { StyledSlider(value = value.floatValue, valueRange = 0f..2f,
                steps = 1, snapPoints = listOf(0f, 1f, 2f),
                onValueChange = { changes += it; value.floatValue = it }, onValueChangeFinished = { finished++ }) }
        } }
        val thumb = compose.onNodeWithTag("styled-slider-thumb", useUnmergedTree = true)
        thumb.performTouchInput { down(center); repeat(10) { moveBy(Offset(1f, 0f), 20) } }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        thumb.performTouchInput { moveBy(Offset(100f, 0f), 100); repeat(10) { moveBy(Offset(1f, 0f), 20) } }
        compose.runOnIdle { assertEquals(listOf(1f), changes) }
        thumb.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(1f), changes); assertEquals(1, finished) }
    }
}
