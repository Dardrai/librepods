package me.kavishdevar.librepods.presentation.components.apple

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
// Espresso's input bridge remains compatible with the supported API33 runtime.
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NoiseControlSettingsTest {
    @get:Rule val compose = createComposeRule()

    // This gesture/state test does not need Robolectric's incomplete native AGSL compiler.
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun adaptiveDragKeepsFinalRequestAcrossReopeningUntilAFreshReportReplacesIt() {
        val reported = mutableStateOf(25)
        val requested = mutableStateOf<Int?>(null)
        val visible = mutableStateOf(true)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                if (visible.value) NoiseControlSettings(
                    modifier = Modifier.width(400.dp).height(64.dp),
                    showOffListeningMode = false,
                    noiseControlModeValue = 4,
                    onNoiseControlModeChanged = {},
                    adaptiveStrength = reported.value,
                    requestedAdaptiveStrength = requested.value,
                    onAdaptiveStrengthChanged = { writes += it; requested.value = it },
                    showLabels = false
                )
            }
        }
        settle()
        val initialLeft = thumbLeft()
        val initialBounds = compose.onNodeWithTag("noise-control-thumb").fetchSemanticsNode().boundsInRoot
        assertEquals("The touch target must follow the translated thumb", 120f, initialLeft, 1.5f)
        assertEquals("Adaptive mode reserves one fifth of the width for the thumb", 80f, initialBounds.width, 1.5f)
        assertTrue("The touch target must be laid out", initialBounds.height > 0f)
        assertTrue("Composition must not write a strength", writes.isEmpty())
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            swipe(center, center + Offset(80f, 0f), durationMillis = 320)
        }
        settle()
        val finalRequested = compose.runOnIdle { requireNotNull(requested.value) }
        // The pressed thumb scales during the drag; assert retained intent, not unscaled finger travel.
        assertTrue("The final gesture position must be committed: requested=$finalRequested, writes=$writes, left=${thumbLeft()}",
            finalRequested > 50)
        assertEquals("The last gesture command must remain the displayed request", writes.last(), finalRequested)
        assertEquals("A request must not replace the device's report", 25, reported.value)
        val finalLeft = thumbLeft()
        assertTrue("Release must not snap back to the old report", finalLeft > initialLeft + 30f)
        assertEquals(80f + finalRequested * 1.6f, finalLeft, 1.5f)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        settle()
        assertEquals("Navigation must retain the separate pending request", finalLeft, thumbLeft(), 1.5f)
        val beforeReport = writes.size
        compose.runOnIdle { reported.value = 30; requested.value = null }
        settle()
        assertEquals("A fresh rounded report must become authoritative", 128f, thumbLeft(), 1.5f)
        assertEquals("Report-driven motion must not send another command", beforeReport, writes.size)
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun singleMovementAdaptiveDragCommitsTheMovementPastTouchSlop() {
        val requested = mutableStateOf<Int?>(null)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                NoiseControlSettings(
                    modifier = Modifier.width(400.dp).height(64.dp),
                    showOffListeningMode = false,
                    noiseControlModeValue = 4,
                    onNoiseControlModeChanged = {},
                    adaptiveStrength = 25,
                    requestedAdaptiveStrength = requested.value,
                    onAdaptiveStrengthChanged = { writes += it; requested.value = it },
                    showLabels = false
                )
            }
        }
        settle()
        val initialLeft = thumbLeft()
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            down(center)
            moveBy(Offset(80f, 0f))
            up()
        }
        settle()
        val finalRequested = compose.runOnIdle { requireNotNull(requested.value) }
        assertTrue("A single movement must change Adaptive strength: requested=$finalRequested, writes=$writes",
            finalRequested > 50)
        assertEquals("Release must commit the changed strength", finalRequested, writes.last())
        assertTrue("The thumb must retain the single movement after release", thumbLeft() > initialLeft + 30f)
        assertEquals("The thumb must display the changed request", 80f + finalRequested * 1.6f, thumbLeft(), 1.5f)
    }

    @Test fun materialStrengthOnlyWritesForUserInputAndFollowsRequestedAndReportedValues() {
        val reported = mutableStateOf(30)
        val requested = mutableStateOf<Int?>(null)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                AdaptiveAudioStrengthSlider(
                    adaptiveStrength = reported.value,
                    requestedAdaptiveStrength = requested.value,
                    onAdaptiveStrengthChanged = { writes += it; requested.value = it },
                    enabled = true
                )
            }
        }
        settle()
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        assertTrue(writes.isEmpty())
        assertEquals(70f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(85f) }
        settle()
        assertEquals(listOf(15), writes)
        assertEquals(30, reported.value)
        assertEquals(85f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        compose.runOnIdle { reported.value = 27; requested.value = null }
        settle()
        assertEquals(73f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        assertEquals("Opening or receiving reports must never send a command", listOf(15), writes)
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun pressedBubbleOverextendsTheRailWithoutResizingItsTouchTargetOrWritingSettings() {
        val enabled = mutableStateOf(true)
        val modes = mutableListOf<Int>()
        val strengths = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Box(Modifier.width(440.dp).height(160.dp), contentAlignment = Alignment.Center) {
                    NoiseControlSettings(
                        modifier = Modifier.width(400.dp).height(64.dp),
                        showOffListeningMode = false,
                        noiseControlModeValue = 4,
                        onNoiseControlModeChanged = { modes += it },
                        adaptiveStrength = 25,
                        onAdaptiveStrengthChanged = { strengths += it },
                        showLabels = false,
                        enabled = enabled.value,
                    )
                }
            }
        }
        settle()
        val thumb = compose.onNodeWithTag("noise-control-thumb", useUnmergedTree = true)
        val bubble = compose.onNodeWithTag("noise-control-bubble", useUnmergedTree = true)
        val touchBounds = thumb.fetchSemanticsNode().boundsInRoot
        assertEquals("Adaptive mode keeps an unscaled 80dp touch target", 80f, touchBounds.width, 0.5f)
        assertEquals("The rail's touch target stays 64dp high", 64f, touchBounds.height, 0.5f)
        assertEquals("The resting glass stays inset within its track", 56f,
            bubble.fetchSemanticsNode().boundsInRoot.height, 0.5f)

        for (ending in listOf("cancel", "release", "disable")) {
            compose.runOnIdle { enabled.value = true }
            settle()
            thumb.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            val expanded = bubble.fetchSemanticsNode().boundsInRoot
            assertEquals("Touch-down expands the actual glass before any drag", 78f, expanded.height, 1.5f)
            assertTrue("The glass rises above the rail", expanded.top < touchBounds.top)
            assertTrue("The glass extends below the rail", expanded.bottom > touchBounds.bottom)
            assertEquals("Visual expansion cannot resize or shift the drag target", touchBounds,
                thumb.fetchSemanticsNode().boundsInRoot)
            assertTrue("Pressing alone cannot change the mode", modes.isEmpty())
            assertTrue("Pressing alone cannot change Adaptive strength", strengths.isEmpty())
            when (ending) {
                "cancel" -> thumb.performTouchInput { cancel() }
                "release" -> thumb.performTouchInput { up() }
                else -> {
                    compose.runOnIdle { enabled.value = false }
                    settle()
                    thumb.performTouchInput { cancel() }
                }
            }
            settle()
            assertEquals("The bubble returns to rest after $ending", 56f,
                bubble.fetchSemanticsNode().boundsInRoot.height, 0.5f)
            assertEquals("The touch target remains stable after $ending", touchBounds,
                thumb.fetchSemanticsNode().boundsInRoot)
            assertTrue("Finishing a press cannot issue mode commands", modes.isEmpty())
            assertTrue("Finishing a press cannot issue strength commands", strengths.isEmpty())
        }
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun modeIconsStayFixedWhileTheGlassPressesAndMovesOverTheTrack() {
        val requested = mutableStateOf<Int?>(null)
        val modes = mutableListOf<Int>()
        val strengths = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Box(Modifier.width(440.dp).height(160.dp), contentAlignment = Alignment.Center) {
                    NoiseControlSettings(
                        modifier = Modifier.width(400.dp).height(64.dp),
                        showOffListeningMode = false,
                        noiseControlModeValue = 4,
                        onNoiseControlModeChanged = { modes += it },
                        adaptiveStrength = 25,
                        requestedAdaptiveStrength = requested.value,
                        onAdaptiveStrengthChanged = { strengths += it; requested.value = it },
                        showLabels = false,
                    )
                }
            }
        }
        settle()
        fun iconBounds() = listOf(3, 4, 2).associateWith { mode ->
            val tag = "noise-control-icon-$mode"
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.also { bounds ->
                    assertEquals("The source icon stays 32dp wide", 32f, bounds.width, 0.5f)
                    assertEquals("The source icon stays 32dp high", 32f, bounds.height, 0.5f)
                }
        }
        val fixedIcons = iconBounds()
        val thumb = compose.onNodeWithTag("noise-control-thumb", useUnmergedTree = true)
        val bubble = compose.onNodeWithTag("noise-control-bubble", useUnmergedTree = true)
        thumb.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("Pressing glass cannot move or stretch its source icons", fixedIcons, iconBounds())
        assertTrue("Pressing alone cannot change a mode: modes=$modes", modes.isEmpty())
        assertTrue("Pressing alone cannot change strength", strengths.isEmpty())
        val pressedBubble = bubble.fetchSemanticsNode().boundsInRoot

        thumb.performTouchInput { moveBy(Offset(20f, 0f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        thumb.performTouchInput {
            advanceEventTime(32)
            moveBy(Offset(60f, 0f))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val heldStrength = compose.runOnIdle { requireNotNull(requested.value) }
        val heldTargetLeft = thumb.fetchSemanticsNode().boundsInRoot.left
        assertTrue("Moving within Adaptive changes its requested strength", heldStrength > 25)
        assertTrue("The glass moves independently across the fixed row",
            bubble.fetchSemanticsNode().boundsInRoot.left > pressedBubble.left + 30f)
        assertEquals("Dragging glass cannot move or stretch its source icons", fixedIcons, iconBounds())
        assertTrue("An Adaptive strength drag cannot select another mode: modes=$modes, strengths=$strengths, left=$heldTargetLeft",
            modes.all { it == 4 })

        thumb.performTouchInput { up() }
        settle()
        assertEquals("Release keeps the gesture's pending strength", heldStrength, requested.value)
        assertEquals(heldStrength, strengths.last())
        assertEquals("Release cannot snap the thumb back to the old report", heldTargetLeft,
            thumb.fetchSemanticsNode().boundsInRoot.left, 1.5f)
        assertEquals("Icons remain fixed after the glass settles", fixedIcons, iconBounds())
        assertTrue("Finishing a strength drag must keep Adaptive selected: modes=$modes", modes.all { it == 4 })

        compose.runOnIdle { modes.clear() }
        val transparency = compose.onNodeWithContentDescription("Transparency")
        transparency.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("Pressing another option leaves the source icons unchanged", fixedIcons, iconBounds())
        assertTrue("A mode command waits for release: modes=$modes", modes.isEmpty())
        transparency.performTouchInput { up() }
        settle()
        assertEquals("The unselected option still activates exactly once", listOf(3), modes)
        assertEquals("A mode tap cannot change Adaptive strength", heldStrength, requested.value)
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun disabledAppleControlCannotSendTapDragOrAccessibilityCommands() {
        val enabled = mutableStateOf(false)
        val modes = mutableListOf<Int>()
        val strengths = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                NoiseControlSettings(
                    modifier = Modifier.width(400.dp).height(64.dp),
                    showOffListeningMode = true,
                    noiseControlModeValue = 4,
                    onNoiseControlModeChanged = { modes += it },
                    adaptiveStrength = 30,
                    onAdaptiveStrengthChanged = { strengths += it },
                    showLabels = false,
                    enabled = enabled.value
                )
            }
        }
        settle()
        compose.onNodeWithContentDescription("Transparency").assertIsNotEnabled()
            .performTouchInput { click() }
        compose.onNodeWithTag("noise-control-thumb").assertIsNotEnabled()
            .performTouchInput { swipe(center, center + Offset(80f, 0f), durationMillis = 200) }
        compose.onNodeWithTag("noise-control-thumb").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue("Disabled accessibility changes must be declined", !it(65f))
        }
        settle()
        assertTrue(modes.isEmpty())
        assertTrue(strengths.isEmpty())
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("noise-control-thumb").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue("Invalid accessibility values must be declined", !it(Float.NaN))
            assertTrue("Valid out-of-range values must be clamped", it(150f))
        }
        assertEquals(listOf(100), strengths)
        compose.onNodeWithContentDescription("Noise Cancellation").performClick()
        assertEquals(listOf(2), modes)
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun capabilitiesHideAdaptiveAndAnnounceTheSelectedMode() {
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                NoiseControlSettings(
                    modifier = Modifier.width(280.dp).height(64.dp),
                    showOffListeningMode = true,
                    showAdaptiveMode = false,
                    noiseControlModeValue = 3,
                    onNoiseControlModeChanged = { writes += it },
                    onAdaptiveStrengthChanged = { error("Unsupported Adaptive must never write") },
                    showLabels = false
                )
            }
        }
        settle()
        compose.onAllNodesWithContentDescription("Adaptive").assertCountEquals(0)
        compose.onNodeWithContentDescription("Transparency").assertIsSelected()
        val target = compose.onNodeWithContentDescription("Off")
        val bounds = target.fetchSemanticsNode().boundsInRoot
        assertTrue("Mode touch targets must remain at least 48 dp", bounds.width >= 48f && bounds.height >= 48f)
        target.performClick()
        assertEquals(listOf(1), writes)
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun modeDragKeepsThePendingSelectionUntilAFreshDeviceReportArrives() {
        val reported = mutableStateOf(3)
        val requested = mutableStateOf<Int?>(null)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                NoiseControlSettings(
                    modifier = Modifier.width(400.dp).height(64.dp),
                    showOffListeningMode = false,
                    noiseControlModeValue = requested.value ?: reported.value,
                    onNoiseControlModeChanged = { writes += it; requested.value = it },
                    adaptiveStrength = 50,
                    onAdaptiveStrengthChanged = {},
                    showLabels = false
                )
            }
        }
        settle()
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            // Adaptive expands during the gesture, moving ANC toward the far end of the rail.
            swipe(center, Offset(399f, center.y), durationMillis = 480)
        }
        settle()
        assertEquals("The far-end drag must select ANC: writes=$writes, left=${thumbLeft()}", 2, requested.value)
        assertEquals("A pending command must not overwrite the report", 3, reported.value)
        assertEquals("Release must retain the pending mode while its acknowledgement is delayed", 400f * 2f / 3f, thumbLeft(), 1.5f)
        val count = writes.size
        compose.runOnIdle { reported.value = 4; requested.value = null }
        settle()
        assertEquals("A fresh Adaptive mode report becomes authoritative", 160f, thumbLeft(), 1.5f)
        assertEquals("Following a report must never issue a mode command", count, writes.size)
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun reportReceivedDuringDragIsFollowedAfterReleaseWithoutAPendingRequest() {
        val reported = mutableStateOf(25)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                NoiseControlSettings(
                    modifier = Modifier.width(400.dp).height(64.dp),
                    showOffListeningMode = false,
                    noiseControlModeValue = 4,
                    onNoiseControlModeChanged = {},
                    adaptiveStrength = reported.value,
                    onAdaptiveStrengthChanged = { writes += it },
                    showLabels = false
                )
            }
        }
        settle()
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            down(center)
            moveBy(Offset(45f, 0f))
        }
        compose.runOnIdle { reported.value = 60 }
        compose.onNodeWithTag("noise-control-thumb").performTouchInput { up() }
        settle()
        assertTrue("The gesture must have issued a user request", writes.isNotEmpty())
        assertEquals("A report received during the drag must not be lost after release", 176f, thumbLeft(), 1.5f)
        val count = writes.size
        settle()
        assertEquals("Following a report must not feed back into another command", count, writes.size)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    private fun thumbLeft() = compose.onNodeWithTag("noise-control-thumb").fetchSemanticsNode().boundsInRoot.left
}
