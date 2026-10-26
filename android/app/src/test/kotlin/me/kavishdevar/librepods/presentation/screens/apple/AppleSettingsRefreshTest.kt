package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Keep Espresso's input bridge and Compose density on the same supported API 33 runtime.
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppleSettingsRefreshTest {
    @get:Rule val compose = createComposeRule()

    // Apple backdrop AGSL is incomplete in Robolectric; test its actual input and semantics separately.
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun settingsHaveNoManualRefreshAndOpeningDoesNotDisconnectOrWrite() {
        var actions = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SettingsScreen(disconnect = { actions++ }, setBoolean = { _, _ -> actions++ })
            }
        }
        scrollToBottom()
        compose.onNodeWithText(text(R.string.refresh_device_settings)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.refresh_device_settings_busy)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.refresh_device_settings_description)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("Opening settings must not change the connection or device settings", 0, actions) }
        compose.onNodeWithText(text(R.string.disconnect)).assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals("Disconnect remains an explicit user action", 1, actions) }
    }

    @Config(qualifiers = "w320dp-h900dp-mdpi")
    @Test fun materialSettingsFitNarrowLightScreenAtLargeText() {
        renderMaterial(dark = false, fontScale = 1.3f, name = "light-narrow-large")
    }

    @Test fun materialSettingsRemainReadableInDarkAppearance() {
        renderMaterial(dark = true, fontScale = 1f, name = "dark-material")
    }

    @Test fun legacyUnpaidStateCannotDisableSupportedAudioControls() {
        var requested: ControlCommandIdentifier? = null
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                SettingsScreen(
                    setBoolean = { id, _ -> requested = id })
            }
        }
        val label = text(R.string.conversation_awareness)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG, requested) }
    }

    @Config(qualifiers = "w320dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun savedChoicesDoNotAddABannerOnNarrowAppleScreen() = savedChoicesWithoutBanner(DesignSystem.Apple, "narrow")

    @Config(qualifiers = "w320dp-h900dp-mdpi")
    @Test fun savedChoicesDoNotAddABannerOnNarrowMaterialScreen() = savedChoicesWithoutBanner(DesignSystem.Material, "narrow")

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun savedChoicesDoNotAddABannerOnWideAppleScreen() = savedChoicesWithoutBanner(DesignSystem.Apple, "wide")

    @Test fun savedChoicesDoNotAddABannerOnWideMaterialScreen() = savedChoicesWithoutBanner(DesignSystem.Material, "wide")

    private fun savedChoicesWithoutBanner(theme: DesignSystem, width: String) {
        val writes = mutableListOf<Pair<ControlCommandIdentifier, Boolean>>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                LibrePodsTheme(designSystem = theme) {
                    SettingsScreen(
                        setBoolean = { id, value -> writes += id to value },
                        settings = AppleSettings(savedUnconfirmedChoices = mapOf("desired_noise_modes_cycle" to "0b")))
                }
            }
        }
        compose.onNodeWithText("Saved choices").assertDoesNotExist()
        compose.onAllNodesWithText("not confirmed", substring = true).assertCountEquals(0)
        val name = text(R.string.name)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Saved choices").assertDoesNotExist()
        assertTrue("Displaying saved state must not issue an accessory command", writes.isEmpty())
        if (theme == DesignSystem.Apple) return // Robolectric legacy rendering cannot capture the Apple RuntimeShader.
        val output = File("build/overlay-previews/device-settings-${theme.name.lowercase()}-$width-clean.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun earDetectionSwitchUsesSavedReportAndPendingChoicesInTheSameOrderAsAutomation() {
        val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG
        val disabled = AppleSettings(earDetectionEnabled = false)
        val enabled = AppleSettings(earDetectionEnabled = true)
        val cases = listOf(
            Triple(AppleState(), disabled, true),
            Triple(AppleState(controlStates = mapOf(id to byteArrayOf(1))), enabled, true),
            Triple(AppleState(controlStates = mapOf(id to byteArrayOf(2)),
                requestedControls = mapOf(id to byteArrayOf(1))), enabled, true),
            Triple(AppleState(controlStates = mapOf(id to byteArrayOf(1)),
                requestedControls = mapOf(id to byteArrayOf(2))), disabled, false),
            Triple(AppleState(controlStates = mapOf(id to byteArrayOf(1)),
                requestedControls = mapOf(id to byteArrayOf(0))), enabled, true),
        )
        val choice = mutableStateOf(cases.first())
        val writes = mutableListOf<Pair<ControlCommandIdentifier, Boolean>>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                SettingsScreen(
                    setBoolean = { control, value -> writes += control to value },
                    state = choice.value.first, settings = choice.value.second)
            }
        }
        val label = text(R.string.ear_detection)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        assertTrue("Displaying a choice must not send it", writes.isEmpty())
        for ((index, case) in cases.withIndex()) {
            compose.runOnIdle { choice.value = case }
            compose.waitForIdle()
            compose.onNodeWithText(label).assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(id to case.third, writes[index]) }
        }
        assertEquals(cases.size, writes.size)
    }

    private fun renderMaterial(dark: Boolean, fontScale: Float, name: String) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                LibrePodsTheme(darkTheme = dark, designSystem = DesignSystem.Material) {
                    SettingsScreen()
                }
            }
        }
        scrollToBottom()
        compose.onNodeWithText(text(R.string.refresh_device_settings)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.refresh_device_settings_description)).assertDoesNotExist()
        val disconnect = compose.onNodeWithText(text(R.string.disconnect))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Disconnect must retain a full touch target", disconnect.height >= 48f)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/overlay-previews/automatic-settings-$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun scrollToBottom() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.disconnect)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Composable
    private fun SettingsScreen(
        disconnect: () -> Unit = {},
        setBoolean: (ControlCommandIdentifier, Boolean) -> Unit = { _, _ -> },
        state: AppleState = AppleState(),
        settings: AppleSettings = AppleSettings(),
    ) {
        AppleSettingsScreen(
            uiState = AppleUiState(state = state, settings = settings, metadata = AppleMetadata(name = "AirPods Pro 3",
                model = AirPodsModel.AIRPODS_PRO_3, modelName = "AirPods Pro 3", modelNumber = "A3063",
                version1 = "9A348"),
                appState = AppStateEntity(isPremium = false, timeUntilFOSSPremiumExpiry = 1)),
            setControlCommandInt = { _, _ -> },
            setControlCommandBoolean = setBoolean,
            writeATTCharacteristic = { _, _ -> },
            updateSettings = {},
            disconnect = disconnect,
            navigateBack = null,
            navigateToRename = {},
            navigateToHearingProtection = {},
            navigateToHearingAid = {},
            navigateToLeftLongPress = {},
            navigateToRightLongPress = {},
            navigateToEqualizer = {},
            navigateToHeadTracking = {},
            navigateToAccessibility = {},
            navigateToVersion = {},
            navigateToCallControlScreen = {},
            navigateToMicrophoneSettings = {},
            navigateToRecordingScreen = {},
            navigateToHeartRateScreen = {},
            navigateToDebugScreen = {}
        )
    }
}
