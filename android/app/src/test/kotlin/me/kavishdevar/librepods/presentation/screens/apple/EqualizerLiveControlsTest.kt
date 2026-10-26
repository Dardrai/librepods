package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CustomEq
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EqualizerLiveControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun initialAndIncomingReportsNeverWrite() {
        val current = mutableStateOf(AppleUiState(state = AppleState(customEq = CustomEq(2, 50, 50, 50))))
        val changes = mutableListOf<Triple<Int, Int, Int>>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Material) {
            EqualizerScreen(current.value, null, {}, { low, mid, high -> changes += Triple(low, mid, high) })
        } }
        compose.waitForIdle()
        compose.runOnIdle { current.value = current.value.copy(state = current.value.state.copy(customEq = CustomEq(2, 20, 70, 90))) }
        compose.waitForIdle()
        assertTrue(changes.isEmpty())
    }
    @Test fun multipleSmallMovesAccumulateAndPublishUserMotionWithoutWaitingForRelease() {
        val offsets = List(3) { mutableFloatStateOf(0f) }
        val changes = mutableListOf<Float>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Material) {
            Column(Modifier.width(360.dp)) { EqualizerCard(offsets[0], offsets[1], offsets[2],
                onBandsChanged = { changes += offsets[0].floatValue }) }
        } }
        compose.onNodeWithTag("equalizer-band-0").performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, -3f), 30) }
            up()
        }
        assertTrue(changes.size > 1)
        assertTrue(offsets[0].floatValue < -10f)
        assertTrue(changes.last() <= changes.first())
    }

    @Test fun bandHasFullTouchTargetAndAccessibleRangeWithoutChangingTheVisibleGraph() {
        val offsets = List(3) { mutableFloatStateOf(0f) }
        val changes = mutableListOf<Float>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            Column(Modifier.width(320.dp)) { EqualizerCard(offsets[0], offsets[1], offsets[2],
                onBandsChanged = { changes += offsets[0].floatValue }) }
        } }
        val low = compose.onNodeWithTag("equalizer-band-0")
        low.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Low")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(50f, 0f..100f)))
        low.performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        compose.runOnIdle { assertEquals(1, changes.size); assertEquals(-50f, offsets[0].floatValue, 0.01f) }
        low.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        low.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        low.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(74f, 0f..100f)))
    }

    @Test fun disabledBandCannotBeChangedByTouchOrAccessibility() {
        val offsets = List(3) { mutableFloatStateOf(0f) }
        var changes = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Material) {
            EqualizerCard(offsets[0], offsets[1], offsets[2], enabled = false, onBandsChanged = { changes++ })
        } }
        val low = compose.onNodeWithTag("equalizer-band-0")
        low.assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        low.performTouchInput { down(center); moveBy(Offset(0f, -60f), 450); up() }
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0f, offsets[0].floatValue) }
    }
}
