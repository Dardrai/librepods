/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.QuickSetting
import me.kavishdevar.librepods.presentation.overlays.QuickSettingsChoices
import me.kavishdevar.librepods.presentation.overlays.quickSettingsOrder
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppUiState
import me.kavishdevar.librepods.utils.QuickSettingsPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class QuickSettingsConfigurationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appSettingsPersistVisibilityAcrossSheetReopeningWithoutChangingDeviceSettings() {
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
        compose.onNodeWithText("Quick Settings").performScrollTo().performClick()
        val ear = compose.onNodeWithTag("quick-setting-choice-EAR_DETECTION")
        ear.performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("quick-setting-choice-GAME_MODE").performScrollTo().performClick().assertIsOff()
        assertEquals(setOf("EAR_DETECTION", "GAME_MODE"), QuickSettingsPreferences.hidden(context))
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("quick-setting-choice-EAR_DETECTION").assertDoesNotExist()
        compose.onNodeWithText("Quick Settings").performScrollTo().performClick()
        ear.performScrollTo().assertIsOff()
        compose.runOnIdle { QuickSettingsPreferences.setHidden(context, "EAR_DETECTION", false) }
        ear.assertIsOn()
        assertTrue("Visibility configuration must not call a device or identity writer",
            mockingDetails(viewModel).invocations.all { it.method.name == "getUiState" })
    }

    @Test @Config(sdk = [35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun visibilityChooserHasLightAndDarkGlassFixturesWithoutRenderingUnrelatedSettings() {
        val dark = mutableStateOf(false)
        var changes = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = dark.value) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.requiredSize(448.dp, 960.dp)
                    .background(if (dark.value) Color(0xFF161C29) else Color(0xFFE7E5F4))
                    .testTag("quick-settings-configuration")) {
                    Box(Modifier.matchParentSize().layerBackdrop(backdrop)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.18f)))
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(stringResource(R.string.quick_settings_title), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.quick_settings_visibility_description), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        QuickSettingsChoices(quickSettingsOrder, setOf(QuickSetting.EAR_DETECTION, QuickSetting.GAME_MODE),
                            { _, _ -> changes++ }, backdrop)
                    }
                }
            }
        }
        for ((name, isDark) in listOf("light" to false, "dark" to true)) {
            compose.runOnIdle { dark.value = isDark }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val frame = compose.onNodeWithTag("quick-settings-configuration").fetchSemanticsNode().boundsInRoot
            for (setting in QuickSetting.entries) {
                val control = compose.onNodeWithTag("quick-setting-choice-${setting.name}").assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("$setting must fit and keep a full touch target", control.height >= 48f &&
                    control.left >= frame.left && control.right <= frame.right && control.bottom <= frame.bottom)
            }
            val output = File("build/outputs/quick-settings-previews/quick-settings-configuration-$name.png")
            output.parentFile?.mkdirs()
            val bitmap = compose.onNodeWithTag("quick-settings-configuration").captureToImage().asAndroidBitmap()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        assertEquals("Rendering visibility choices must not change a preference", 0, changes)
    }
}
