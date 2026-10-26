/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.kavishdevar.librepods.presentation.components.apple.AudioSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeGameModeControlsTest {
    @get:Rule val compose = createComposeRule()
    private fun controls(dark: Boolean) {
        val visible = mutableStateOf(false)
        val available = mutableStateOf(false)
        val checked = mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = dark) {
                AudioSettings(false, false, false, false, false, {}, false, {}, false, {}, {}, false,
                    gameModeVisible = visible.value, gameModeEnabled = available.value,
                    gameModeChecked = checked.value, gameModeDescription = "Waiting for audio",
                    onGameModeChanged = { changes.add(it); checked.value = it })
            }
        }
        compose.onNodeWithText("Game Mode").assertDoesNotExist()
        compose.runOnIdle { visible.value = true; available.value = true }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Game Mode").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { available.value = false }
        // Off remains available if the route disappears during a requested transition.
        compose.onNodeWithText("Game Mode").assertIsEnabled().performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    @Test fun lightThemePreservesGuardedOffAction() = controls(false)
    @Test fun darkThemePreservesGuardedOffAction() = controls(true)
}
