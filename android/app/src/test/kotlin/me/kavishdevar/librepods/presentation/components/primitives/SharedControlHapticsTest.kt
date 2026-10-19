/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class SharedControlHapticsTest {
    @get:Rule val compose = createComposeRule()

    private class Feedback : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }

    @Test fun remoteSwitchUpdatesAreSilentButSemanticActivationEmitsOnce() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val feedback = Feedback()
        var writes = 0
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    StyledSwitch(checked.value, { checked.value = it; writes++ }, enabled.value)
                }
            }
        }
        compose.runOnIdle { checked.value = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { checked.value = false }
        compose.mainClock.advanceTimeBy(1000)
        assertTrue(feedback.events.isEmpty())
        assertEquals(0, writes)

        compose.onNodeWithTag("styled-switch").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.ToggleOn), feedback.events)
        assertEquals(1, writes)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("styled-switch").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, feedback.events.size)
        assertEquals(1, writes)
    }

    @Test fun toggleRowsAndTheirSwitchesEachEmitOneHapticInEitherTheme() {
        val theme = mutableStateOf(DesignSystem.Apple)
        val checked = mutableStateOf(false)
        val feedback = Feedback()
        var writes = 0
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = theme.value) {
                    StyledToggle(label = "Toggle", checked = checked.value,
                        onCheckedChange = { checked.value = it; writes++ })
                }
            }
        }
        for (design in listOf(DesignSystem.Apple, DesignSystem.Material)) {
            compose.runOnIdle { theme.value = design; checked.value = false }
            compose.waitForIdle()
            feedback.events.clear()
            writes = 0
            compose.onNodeWithText("Toggle").performClick()
            compose.waitForIdle()
            assertEquals("$design row", listOf(HapticFeedbackType.ToggleOn), feedback.events)
            assertEquals(1, writes)
            if (design == DesignSystem.Apple) {
                compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput { click() }
                compose.waitForIdle()
                assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), feedback.events)
                assertEquals(2, writes)
            }
            compose.runOnIdle { checked.value = !checked.value }
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(if (design == DesignSystem.Apple) 2 else 1, feedback.events.size)
        }
    }

    @Test fun switchDragEmitsOneToggleInsteadOfATickAndStateEcho() {
        val checked = mutableStateOf(false)
        val feedback = Feedback()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    Box(Modifier.requiredSize(180.dp, 96.dp), contentAlignment = Alignment.Center) {
                        StyledSwitch(checked.value, { checked.value = it })
                    }
                }
            }
        }
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(150f, 0f), 300)
        }
        compose.mainClock.advanceTimeBy(1000)
        assertTrue(checked.value)
        assertEquals(listOf(HapticFeedbackType.ToggleOn), feedback.events)
    }

    @Test fun switchDragCanReturnBeforeTheDeviceAcknowledgesItsFirstChange() {
        val feedback = Feedback()
        val writes = mutableListOf<Boolean>()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    Box(Modifier.requiredSize(180.dp, 96.dp), contentAlignment = Alignment.Center) {
                        StyledSwitch(false, { writes += it })
                    }
                }
            }
        }
        val thumb = compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true)
        thumb.performTouchInput { down(center); moveBy(Offset(150f, 0f)) }
        compose.waitForIdle()
        thumb.performTouchInput { moveBy(Offset(-150f, 0f)) }
        compose.waitForIdle()
        thumb.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), writes)
        assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), feedback.events)
    }

    @Test fun canceledSwitchAndButtonPressesCannotActivateOrBuzz() {
        val feedback = Feedback()
        var writes = 0
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    Column {
                        StyledSwitch(false, { writes++ })
                        StyledButton(onClick = { writes++ }) { Text("Button") }
                    }
                }
            }
        }
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput { down(center); cancel() }
        compose.onNodeWithText("Button").performTouchInput { down(center); cancel() }
        compose.waitForIdle()
        assertEquals(0, writes)
        assertTrue(feedback.events.isEmpty())
    }

    @Test fun buttonsAndRowsUseOneEnabledActionHapticInEitherTheme() {
        val theme = mutableStateOf(DesignSystem.Apple)
        val enabled = mutableStateOf(true)
        val feedback = Feedback()
        var writes = 0
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = theme.value) {
                    Column {
                        StyledButton(onClick = { writes++ }, enabled = enabled.value) { Text("Button") }
                        StyledIconButton(onClick = { writes++ }, enabled = enabled.value) { Text("Icon") }
                        StyledListItem(contentText = "Row", onClick = { writes++ }, enabled = enabled.value)
                    }
                }
            }
        }
        for (design in listOf(DesignSystem.Apple, DesignSystem.Material)) {
            compose.runOnIdle { theme.value = design; enabled.value = true }
            compose.waitForIdle()
            feedback.events.clear()
            writes = 0
            for (label in listOf("Button", "Icon", "Row")) compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
            assertEquals("$design actions", List(3) { HapticFeedbackType.ContextClick }, feedback.events)
            assertEquals(3, writes)
            compose.runOnIdle { enabled.value = false }
            for (label in listOf("Button", "Icon", "Row")) compose.onNodeWithText(label).performTouchInput { click() }
            compose.waitForIdle()
            assertEquals(3, feedback.events.size)
            assertEquals(3, writes)
        }
    }

    @Test fun sliderEmitsOneTickForAContinuousDragAndNoneForRemoteOrDisabledUpdates() {
        val value = mutableStateOf(0.25f)
        val enabled = mutableStateOf(true)
        val feedback = Feedback()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    Box(Modifier.requiredSize(300.dp, 100.dp).testTag("slider")) {
                        StyledSlider(value = value.value, onValueChange = { value.value = it },
                            valueRange = 0f..1f, enabled = enabled.value)
                    }
                }
            }
        }
        compose.runOnIdle { value.value = 0.5f }
        compose.waitForIdle()
        assertTrue(feedback.events.isEmpty())
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            swipe(center, Offset(width * 0.9f, center.y), 400)
        }
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.SegmentTick), feedback.events)
        compose.runOnIdle { enabled.value = false; value.value = 0.25f }
        compose.onNodeWithTag("slider").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, feedback.events.size)
    }
}
