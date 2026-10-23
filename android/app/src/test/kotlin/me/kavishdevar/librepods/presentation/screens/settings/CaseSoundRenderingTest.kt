package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CaseSoundRenderingTest {
    @get:Rule val compose = createComposeRule()

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun caseSoundExperimentIsAbsentFromAppleSettings() = removedExperiment(DesignSystem.Apple)

    @Test fun caseSoundExperimentIsAbsentFromMaterialSettings() = removedExperiment(DesignSystem.Material)

    private fun removedExperiment(theme: DesignSystem) {
        val viewModel = mock(AppSettingsViewModel::class.java)
        `when`(viewModel.uiState).thenReturn(MutableStateFlow(AppUiState()))
        compose.setContent {
            LibrePodsTheme(designSystem = theme) {
                AppSettingsScreen(viewModel, navigateBack = null, navigateToOpenSourceLicenses = {},
                    navigateToReleaseNotesScreen = {}, navigateToBleSettingsScreen = {}, navigateToAppAppearanceScreen = {},
                    navigateToAppAccessibilityScreen = {}, navigateToIslandSettingsScreen = {})
            }
        }
        for (label in listOf("Case sound experiment", "Find nearby case", "Start two-second experiment", "Pair case with Android"))
            compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithText("Pico dongle").performScrollTo().assertIsDisplayed().assertIsEnabled()
        if (theme == DesignSystem.Material) {
            val output = File("build/overlay-previews/case-sound-removed-material-320.png")
            output.parentFile.mkdirs()
            output.outputStream().use {
                assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
