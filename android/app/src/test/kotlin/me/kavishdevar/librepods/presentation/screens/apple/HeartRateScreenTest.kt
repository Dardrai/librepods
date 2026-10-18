package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HeartRateScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialMeasurementCanBeStoppedWhileWaitingAndStartedAgain() {
        val uiState = mutableStateOf(AppleUiState())
        var starts = 0
        var stops = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                HeartRateScreen(uiState.value, null, startHr = {
                    starts++
                    uiState.value = uiState.value.copy(state = uiState.value.state.copy(hrmState = BuddyState.WAITING))
                }, stopHr = {
                    stops++
                    uiState.value = uiState.value.copy(state = uiState.value.state.copy(hrmState = BuddyState.INACTIVE))
                }, setHrRange = {}, updateSettings = {})
            }
        }
        val action = compose.onNodeWithContentDescription("Start/Stop")
        action.assertIsOff().performClick()
        compose.waitForIdle()
        action.assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals("Waiting must be cancelled, not initialized a second time", 1, starts)
        assertEquals(1, stops)
        action.assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(2, starts)
        compose.runOnIdle { uiState.value = uiState.value.copy(state = uiState.value.state.copy(hrmState = BuddyState.ACTIVE)) }
        action.assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(2, stops)
    }
}
