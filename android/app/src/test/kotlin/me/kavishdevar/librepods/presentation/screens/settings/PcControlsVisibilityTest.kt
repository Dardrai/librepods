package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.companion.CompanionHandoffController
import me.kavishdevar.librepods.companion.CompanionState
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppUiState
import me.kavishdevar.librepods.services.LibrePodsService
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PcControlsVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultOffToggleShowsAndHidesLinkedHomePanelAndQuickEntryWithoutCommands() {
        val context = RuntimeEnvironment.getApplication()
        val controller = mock(CompanionHandoffController::class.java)
        val service = mock(LibrePodsService::class.java)
        `when`(controller.state).thenReturn(MutableStateFlow(CompanionState(true, true, false, "PC linked. Choose where to listen.")))
        `when`(service.companionHandoff).thenReturn(controller)
        `when`(service.devices).thenReturn(MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap()))
        var settingsOpened = 0
        assertFalse(PcControlsVisibility.get(context))
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Column {
                    PcControlsVisibilitySetting()
                    CompanionHomeSection(onOpenSettings = { settingsOpened++ }, service = service)
                }
            }
        }
        compose.onNodeWithText("Listen on").assertDoesNotExist()
        compose.onNodeWithText("PC settings").assertDoesNotExist()
        compose.onNodeWithText("Show PC controls").performTouchInput { click() }
        compose.onNodeWithText("Listen on").assertIsDisplayed()
        compose.onNodeWithText("PC settings").assertIsDisplayed().performTouchInput { click() }
        assertEquals(1, settingsOpened)
        assertTrue(PcControlsVisibility.get(context))
        compose.onNodeWithText("Show PC controls").performTouchInput { click() }
        compose.onNodeWithText("Listen on").assertDoesNotExist()
        compose.onNodeWithText("PC settings").assertDoesNotExist()
        assertFalse(PcControlsVisibility.get(context))
        verify(controller, never()).unlink()
        verify(controller, never()).reconnect()
        verify(controller, never()).cancelTransfer()
        verify(controller, never()).transfer(anyString())
    }

    @Test fun settingsKeepTheVisibilitySwitchAndPcManagementPathWhenHomeIsHidden() {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = mock(AppSettingsViewModel::class.java)
        `when`(viewModel.uiState).thenReturn(MutableStateFlow(AppUiState()))
        assertFalse(PcControlsVisibility.get(context))
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                AppSettingsScreen(viewModel, navigateBack = null, navigateToOpenSourceLicenses = {},
                    navigateToReleaseNotesScreen = {}, navigateToBleSettingsScreen = {}, navigateToAppAppearanceScreen = {},
                    navigateToAppAccessibilityScreen = {}, navigateToIslandSettingsScreen = {})
            }
        }
        compose.onNodeWithText("Show PC controls").assertIsDisplayed()
        compose.onNodeWithText("Link PC").assertIsDisplayed()
        compose.onNodeWithText("Show PC controls").performTouchInput { click() }
        assertTrue(PcControlsVisibility.get(context))
    }
}
