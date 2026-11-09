package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class LoudSoundReductionControlsTest {
    @get:Rule val compose = createComposeRule()
    private enum class Screen { MAIN, ACCESSIBILITY, PROTECTION }
    private val initial = AppleUiState(metadata = AppleMetadata(name = "AirPods Pro 3",
        model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063"), vendorIdHook = false)
    private val label = "Loud Sound Reduction"

    @Test fun mainScreenUsesConfirmedValueAndWriteReadiness() = confirmedControls(Screen.MAIN)
    @Test fun accessibilityUsesConfirmedValueAndWriteReadiness() = confirmedControls(Screen.ACCESSIBILITY)
    @Test fun protectionUsesConfirmedValueAndWriteReadiness() = confirmedControls(Screen.PROTECTION)

    private fun confirmedControls(screen: Screen) {
        val ui = MutableStateFlow(initial)
        val requests = mutableListOf<Boolean>()
        val viewModel = Mockito.mock(AppleViewModel::class.java)
        Mockito.`when`(viewModel.uiState).thenReturn(ui)
        compose.setContent {
            val current by ui.collectAsState()
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                content(screen, current, viewModel, requests::add)
            }
        }
        reveal(screen)
        compose.onNodeWithText(label).assertIsNotEnabled()
        switch().assertIsOff()
        compose.runOnIdle {
            ui.value = initial.copy(state = AppleState(loudSoundReductionEnabled = true,
                loudSoundReductionReadStatus = SettingsReadStatus.AVAILABLE), loudSoundReductionWritable = true)
        }
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        // A submitted change must not replace the last confirmed accessory value.
        switch().assertIsOn()
        compose.runOnIdle {
            if (screen == Screen.PROTECTION) Mockito.verify(viewModel)
                .writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(0))
            else assertEquals(listOf(false), requests)
            ui.value = ui.value.copy(loudSoundReductionWritable = false)
        }
        compose.onNodeWithText(label).assertIsNotEnabled()
        switch().assertIsOn()
        compose.runOnIdle {
            ui.value = ui.value.copy(state = ui.value.state.copy(loudSoundReductionEnabled = false),
                loudSoundReductionWritable = true)
        }
        switch().assertIsOff()
    }

    @Test fun unsupportedModelsDoNotExposeTheSetting() {
        val screen = mutableStateOf(Screen.MAIN)
        val ui = initial.copy(metadata = initial.metadata.copy(model = AirPodsModel.AIRPODS_1))
        val viewModel = Mockito.mock(AppleViewModel::class.java)
        Mockito.`when`(viewModel.uiState).thenReturn(MutableStateFlow(ui))
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            content(screen.value, ui, viewModel, {})
        } }
        for (value in Screen.entries) {
            compose.runOnIdle { screen.value = value }
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test fun dongleScreensDoNotOfferAnUnimplementedTransport() {
        val screen = mutableStateOf(Screen.MAIN)
        val viewModel = Mockito.mock(AppleViewModel::class.java)
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            content(screen.value, initial, viewModel, {}, dongle = true)
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Personalized Volume"))
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { screen.value = Screen.ACCESSIBILITY }
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    private fun reveal(screen: Screen) {
        if (screen == Screen.MAIN) compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        else if (screen == Screen.ACCESSIBILITY) compose.onNodeWithText(label).performScrollTo()
    }

    private fun switch() = compose.onNode(isToggleable() and hasAnyAncestor(hasText(label)))

    @Composable
    private fun content(screen: Screen, ui: AppleUiState, viewModel: AppleViewModel,
        onChange: (Boolean) -> Unit, dongle: Boolean = false) {
        when (screen) {
            Screen.PROTECTION -> HearingProtectionScreen(viewModel, null)
            Screen.ACCESSIBILITY -> AccessibilitySettingsContent(ui, null, {}, { _, _ -> }, { _, _ -> },
                setLoudSoundReduction = onChange, dongle = dongle)
            Screen.MAIN -> AppleSettingsScreen(uiState = ui,
                setControlCommandInt = { _, _ -> }, setControlCommandBoolean = { _, _ -> },
                writeATTCharacteristic = { handle, value ->
                    assertEquals(ATTHandle.LOUD_SOUND_REDUCTION, handle); onChange(value.single() == 1.toByte())
                }, updateSettings = {}, disconnect = {}, navigateBack = null, navigateToRename = {},
                navigateToHearingProtection = {}, navigateToHearingAid = {}, navigateToLeftLongPress = {},
                navigateToRightLongPress = {}, navigateToEqualizer = {}, navigateToHeadTracking = {},
                navigateToAccessibility = {}, navigateToVersion = {}, navigateToCallControlScreen = {},
                navigateToMicrophoneSettings = {}, navigateToRecordingScreen = {}, navigateToHeartRateScreen = {},
                navigateToDebugScreen = {}, dongle = dongle)
        }
    }
}
