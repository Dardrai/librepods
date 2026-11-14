package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.audio.NativeHeadTrackingState
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
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
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeHeadTrackingSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun setupRequiresAnExplicitTapAndOffersNoUncalibratedOnSwitch() {
        var setups = 0
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { NativeHeadTrackingSetting(NativeHeadTrackingState(), true, changes::add, { setups++ }) }
            }
        }
        assertEquals(0, setups)
        assertTrue(changes.isEmpty())
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Head Tracking").performClick()
        assertEquals(1, setups)
        assertTrue(changes.isEmpty())
    }

    @Test fun aCalibratedChoiceSwitchesWithoutDuplicatingItsRowsOrShowingInternalProgress() {
        val state = mutableStateOf(NativeHeadTrackingState(enabled = false, calibrated = true, available = true))
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList {
                    NativeHeadTrackingSetting(state.value, true, {
                        changes += it
                        state.value = state.value.copy(enabled = it, busy = true)
                    }, {})
                    styledListItem(contentText = "Other setting", onClick = null)
                }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().assertIsEnabled().performClick()
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        assertEquals(listOf(true), changes)
        compose.onAllNodesWithText("Head Tracking", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Other setting", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Updating", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Preferred", substring = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled().performClick()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun offRemainsAvailableWhenAConfiguredNativeChoiceLosesItsStartEligibility() {
        val changes = mutableListOf<Boolean>()
        val state = mutableStateOf(NativeHeadTrackingState(enabled = true, calibrated = true, available = false))
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { NativeHeadTrackingSetting(state.value, true, changes::add, {}) }
            }
        }
        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled().performClick()
        assertEquals(listOf(false), changes)
        compose.runOnIdle { state.value = state.value.copy(enabled = false) }
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
    }

    @Test fun disconnectedSettingsCannotStartSetupOrChangeTheNativePreference() {
        val state = mutableStateOf(NativeHeadTrackingState())
        var changes = 0
        var setups = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { NativeHeadTrackingSetting(state.value, false, { changes++ }, { setups++ }) }
            }
        }
        compose.onNodeWithText("Head Tracking").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(enabled = true, calibrated = true) }
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        assertEquals(0, changes)
        assertEquals(0, setups)
    }

    @Test fun aRoleWithoutCalibrationCanStillTurnOffAnExistingNativeChoice() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { NativeHeadTrackingSetting(NativeHeadTrackingState(enabled = true), true, changes::add, {}) }
            }
        }
        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled().performClick()
        assertEquals(listOf(false), changes)
        compose.onNodeWithText("Set up head tracking").assertExists()
    }

    @Test fun aStoppedPoseSourceExplainsRecoveryAndStillAllowsOff() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { NativeHeadTrackingSetting(NativeHeadTrackingState(enabled = true,
                    calibrated = true, reason = "motion_changed"), true, changes::add, {}) }
            }
        }
        compose.onNodeWithText("Head tracking paused. Turn it off and on to retry, or recalibrate.").assertExists()
        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled().performClick()
        assertEquals(listOf(false), changes)
    }
}
