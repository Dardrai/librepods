/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class AppSettingsContactTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contactOnlyOffersGitHubAndOpensTheExistingRepository() {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = mock(AppSettingsViewModel::class.java)
        `when`(viewModel.uiState).thenReturn(MutableStateFlow(AppUiState()))
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = false) {
                AppSettingsScreen(viewModel, navigateBack = null, navigateToOpenSourceLicenses = {},
                    navigateToReleaseNotesScreen = {}, navigateToBleSettingsScreen = {}, navigateToAppAppearanceScreen = {},
                    navigateToAppAccessibilityScreen = {}, navigateToIslandSettingsScreen = {})
            }
        }
        compose.onNodeWithText("E-Mail").assertDoesNotExist()
        compose.onNodeWithText("Discord").assertDoesNotExist()
        compose.onNodeWithText("GitHub").performScrollTo().performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertNotNull("GitHub must open a browser instead of an email composer", intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/Ripthulhu/librepods", intent.data.toString())
    }
}
