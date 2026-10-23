package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.core.devices.CallControlAction
import me.kavishdevar.librepods.presentation.components.apple.CallControlSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class CallControlRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun endCallNeedsKnownLayoutAndFailedSendCannotInventASelection() {
        val singlePress = mutableStateOf<Boolean?>(null)
        val supported = mutableStateOf(false)
        val writes = mutableListOf<Boolean>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            CallControlOptions(CallControlAction.HANG_UP.route, singlePress.value, true, null, supported.value) { writes += it }
        } }
        compose.onNodeWithText("Press Once").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText("Press Twice").assertIsNotSelected().assertIsNotEnabled()
        assertTrue(writes.isEmpty())
        compose.runOnIdle { singlePress.value = false; supported.value = true }
        compose.onNodeWithText("Press Twice").assertIsSelected()
        compose.onNodeWithText("Press Once").assertIsEnabled().performClick()
        compose.onNodeWithText("Press Once").assertIsNotSelected()
        compose.onNodeWithText("Press Twice").assertIsSelected()
        assertEquals(listOf(true), writes)
        compose.runOnIdle { singlePress.value = true }
        compose.onNodeWithText("Press Once").assertIsSelected()
        compose.runOnIdle { singlePress.value = false }
        compose.onNodeWithText("Press Twice").assertIsSelected()
        assertEquals("Readback must never issue another write", listOf(true), writes)
    }

    @Test fun knownEndCallChoicesSendOnlySingleOrDoubleAndDisconnectDisablesWrites() {
        val enabled = mutableStateOf(true)
        val writes = mutableListOf<Boolean>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            CallControlOptions(CallControlAction.HANG_UP.route, false, enabled.value, null, supportedLayout = true) { writes += it }
        } }
        compose.onNodeWithText("Press Twice").assertIsSelected()
        compose.onNodeWithText("Press Once").performClick()
        compose.onNodeWithText("Press Twice").performClick()
        assertEquals(listOf(true, false), writes)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Press Once").assertIsNotEnabled()
        compose.onNodeWithText("Press Twice").assertIsNotEnabled()
    }

    @Test fun staleTranslatedRouteCannotBecomeAHangUpSetting() {
        val writes = mutableListOf<Boolean>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            CallControlOptions("Stummschalten", false, true, null) { writes += it }
        } }
        compose.onNodeWithText("Press Once").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText("Press Twice").assertIsNotSelected().assertIsNotEnabled()
        assertTrue(writes.isEmpty())
    }

    @Test fun summaryFollowsEndCallReportsWithoutInferringAnyMuteSelection() {
        val singlePress = mutableStateOf<Boolean?>(false)
        val routes = mutableListOf<String>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            CallControlSettings(singlePress.value) { routes += it }
        } }
        compose.onNode(hasText("Hang Up") and hasText("Press Twice")).assertExists()
        compose.onNodeWithText("Mute/Unmute").assertHasNoClickAction()
        compose.onNodeWithText("Use your phone’s call controls to mute or unmute.").assertIsDisplayed()
        compose.runOnIdle { singlePress.value = true }
        compose.onNode(hasText("Hang Up") and hasText("Press Once")).performClick()
        assertEquals(listOf("hang_up"), routes)
        compose.runOnIdle { singlePress.value = null }
        compose.onNode(hasText("Hang Up") and hasText("Press Once")).assertDoesNotExist()
        compose.onNode(hasText("Mute/Unmute") and hasText("Press Twice")).assertDoesNotExist()
        compose.onNode(hasText("Mute/Unmute") and hasText("Press Once")).assertDoesNotExist()
        assertEquals(1, routes.size)
    }

    @Test fun restoredMuteRouteIsInformationOnlyAndNeverWritesLegacyEndCallValues() {
        val writes = mutableListOf<Boolean>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            CallControlOptions(CallControlAction.MUTE_UNMUTE.route, false, true, null, supportedLayout = true) { writes += it }
        } }
        compose.onNodeWithText("Use your phone’s call controls to mute or unmute.").assertIsDisplayed()
        compose.onAllNodesWithText("Press Once").assertCountEquals(0)
        compose.onAllNodesWithText("Press Twice").assertCountEquals(0)
        assertTrue(writes.isEmpty())
    }

    @Test fun unsupportedVersionCannotOfferAWritableLegacyChoice() {
        val writes = mutableListOf<Boolean>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            CallControlOptions(CallControlAction.HANG_UP.route, null, true, null, supportedLayout = false) { writes += it }
        } }
        compose.onNodeWithText("Press Once").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText("Press Twice").assertIsNotSelected().assertIsNotEnabled()
        assertTrue(writes.isEmpty())
    }
}
