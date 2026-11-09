/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w1200dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ListeningModePanelTest {
    @get:Rule val compose = createComposeRule()
    private data class Profile(val width: Int = 384, val height: Int = 640,
        val fontScale: Float = 1f, val dark: Boolean = false)

    @Test fun allEightSwitchesUseConfirmedStateAndDispatchOneNamedAction() {
        val current = mutableStateOf(snapshot())
        val changes = mutableListOf<Pair<QuickSetting, Boolean>>()
        compose.setContent { fixture(Profile(), current.value) { setting, value -> changes += setting to value } }
        assertEquals(8, QuickSetting.entries.size)
        assertTrue("Opening and rendering the panel must not send settings", changes.isEmpty())
        for (setting in QuickSetting.entries) {
            val control = control(setting).performScrollTo().assertIsDisplayed().assertIsEnabled()
            control.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
            val bounds = control.fetchSemanticsNode().boundsInRoot
            assertTrue("$setting needs a full 48dp touch target", bounds.width >= 48f && bounds.height >= 48f)
            control.performClick()
            assertEquals(setting to true, changes.last())
            assertEquals("One activation sends one action", QuickSetting.entries.indexOf(setting) + 1, changes.size)
            control.assertIsOff()
            compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
                if (it.setting == setting) it.copy(checked = true) else it
            }) }
            control.assertIsOn()
        }
        val loud = control(QuickSetting.LOUD_SOUND_REDUCTION).performScrollTo().fetchSemanticsNode().boundsInRoot
        val protection = control(QuickSetting.EN352_PROTECTION).performScrollTo().fetchSemanticsNode().boundsInRoot
        assertEquals("Protection belongs beside loud sound reduction", loud.top, protection.top, 1f)
        assertTrue(loud.right <= protection.left)
    }

    @Test fun unknownBusyAndUnsupportedControlsCannotInventOrSendSettings() {
        val current = mutableStateOf(snapshot(QuickSetting.entries.map {
            QuickSettingState(it, checked = null, supported = true, enabled = false, reading = true)
        }))
        val changes = mutableListOf<Pair<QuickSetting, Boolean>>()
        compose.setContent { fixture(Profile(), current.value) { setting, value -> changes += setting to value } }
        for (setting in QuickSetting.entries) {
            control(setting).performScrollTo().assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    RuntimeEnvironment.getApplication().getString(R.string.not_available)))
                .performClick()
        }
        assertTrue(changes.isEmpty())
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            it.copy(checked = true, pending = true)
        }) }
        for (setting in QuickSetting.entries) {
            control(setting).performScrollTo().assertIsNotEnabled().assertIsOn()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        }
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            if (it.setting == QuickSetting.LOUD_SOUND_REDUCTION) it.copy(supported = false) else it
        }) }
        control(QuickSetting.LOUD_SOUND_REDUCTION).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            if (it.setting == QuickSetting.LOUD_SOUND_REDUCTION) it.copy(supported = true, checked = null,
                pending = false, reading = false) else it
        }) }
        control(QuickSetting.LOUD_SOUND_REDUCTION).assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                RuntimeEnvironment.getApplication().getString(R.string.not_available)))
        assertTrue("Reports and pending state cannot trigger an action", changes.isEmpty())
    }

    @Test fun narrowLargeTextAndShortLandscapeKeepEverySwitchReachable() {
        val profile = mutableStateOf(Profile(width = 320, height = 300, fontScale = 1.5f))
        var changes = 0
        compose.setContent { fixture(profile.value, snapshot()) { _, _ -> changes++ } }
        for (value in listOf(profile.value, Profile(width = 600, height = 240, fontScale = 1.3f, dark = true))) {
            compose.runOnIdle { profile.value = value }
            for (setting in QuickSetting.entries) {
                val bounds = control(setting).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val frame = compose.onNodeWithTag("quick-panel-fixture").fetchSemanticsNode().boundsInRoot
                assertTrue("$setting must fit the available width at ${value.fontScale} text scale",
                    bounds.left >= frame.left && bounds.right <= frame.right)
                assertTrue("Scrolling must expose the whole $setting target on a short display",
                    bounds.top >= frame.top && bounds.bottom <= frame.bottom)
                assertTrue(bounds.height >= 48f)
            }
        }
        assertEquals("Scrolling and layout cannot apply settings", 0, changes)
    }

    @Test fun waitingGameModeCanBeCancelledAndRequestedOrdinaryChoicesCanBeReplaced() {
        val current = mutableStateOf(snapshot(QuickSetting.entries.map { setting ->
            QuickSettingState(setting, checked = setting in setOf(QuickSetting.GAME_MODE, QuickSetting.CONVERSATION_AWARENESS),
                supported = true, enabled = true, pending = setting == QuickSetting.GAME_MODE,
                requested = setting == QuickSetting.CONVERSATION_AWARENESS)
        }))
        val changes = mutableListOf<Pair<QuickSetting, Boolean>>()
        compose.setContent { fixture(Profile(), current.value) { setting, value -> changes += setting to value } }
        control(QuickSetting.GAME_MODE).performScrollTo().assertIsOn().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On")).performClick()
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            if (it.setting == QuickSetting.GAME_MODE) it.copy(checked = false) else it
        }) }
        control(QuickSetting.GAME_MODE).assertIsOff().assertIsEnabled().performClick()
        control(QuickSetting.CONVERSATION_AWARENESS).performScrollTo().assertIsOn().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On")).performClick()
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            if (it.setting == QuickSetting.CONVERSATION_AWARENESS) it.copy(checked = false) else it
        }) }
        control(QuickSetting.CONVERSATION_AWARENESS).assertIsOff().assertIsEnabled().performClick()
        assertEquals(listOf(QuickSetting.GAME_MODE to false, QuickSetting.GAME_MODE to true, QuickSetting.CONVERSATION_AWARENESS to false,
            QuickSetting.CONVERSATION_AWARENESS to true), changes)
    }

    @Test fun bookkeepingFlagsNeverReplaceSimpleOnOffCaptions() {
        val current = mutableStateOf(snapshot(QuickSetting.entries.map { setting ->
            QuickSettingState(setting, checked = setting == QuickSetting.EAR_DETECTION,
                supported = true, enabled = true, requested = setting == QuickSetting.EAR_DETECTION)
        }))
        val changes = mutableListOf<Pair<QuickSetting, Boolean>>()
        compose.setContent { fixture(Profile(), current.value) { setting, value -> changes += setting to value } }
        val ear = control(QuickSetting.EAR_DETECTION).performScrollTo().assertIsEnabled().assertIsOn()
        ear.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
            .performClick()
        assertEquals(listOf(QuickSetting.EAR_DETECTION to false), changes)
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            if (it.setting == QuickSetting.EAR_DETECTION) it.copy(checked = false) else it
        }) }
        ear.assertIsOff().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        for (flags in listOf(QuickSettingState(QuickSetting.EAR_DETECTION, true, true, false, pending = true),
            QuickSettingState(QuickSetting.EAR_DETECTION, true, true, false, reading = true),
            QuickSettingState(QuickSetting.EAR_DETECTION, true, true, false, retained = true))) {
            compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
                if (it.setting == QuickSetting.EAR_DETECTION) flags else it
            }) }
            ear.assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        }
        for (text in listOf("preferred", "Updating", "Reading", "last known", "Requested"))
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    }

    @Test fun uncertainHearingModesOfferOnlyAnOffButtonWithoutInventingAnOnState() {
        val modes = setOf(QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST)
        val current = mutableStateOf(snapshot(QuickSetting.entries.map { setting ->
            QuickSettingState(setting, checked = if (setting in modes) null else false,
                supported = true, enabled = true, recoveryOff = setting in modes)
        }))
        val changes = mutableListOf<Pair<QuickSetting, Boolean>>()
        compose.setContent { fixture(Profile(), current.value) { setting, value -> changes += setting to value } }
        for ((setting, label) in listOf(QuickSetting.HEARING_AID to R.string.hearing_aid_recovery_off,
            QuickSetting.MEDIA_ASSIST to R.string.media_assist_recovery_off)) {
            control(setting).performScrollTo().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ""))
                .assert(hasText(RuntimeEnvironment.getApplication().getString(label)))
                .performClick()
        }
        assertEquals(listOf(QuickSetting.HEARING_AID to false, QuickSetting.MEDIA_ASSIST to false), changes)
        compose.runOnIdle { current.value = current.value.copy(quickSettings = current.value.quickSettings.map {
            if (it.setting in modes) it.copy(pending = true) else it
        }) }
        for (setting in modes) control(setting).performScrollTo().assertIsNotEnabled()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lightAndDarkPanelsHaveReviewableGlassFixtures() {
        val profile = mutableStateOf(Profile())
        var changes = 0
        val current = snapshot(QuickSetting.entries.mapIndexed { index, setting ->
            QuickSettingState(setting, checked = index % 2 == 0, supported = true, enabled = true,
                requested = setting == QuickSetting.EAR_DETECTION)
        })
        compose.setContent { fixture(profile.value, current) { _, _ -> changes++ } }
        for ((name, value) in listOf("light" to Profile(), "dark-large" to Profile(width = 320, height = 720, fontScale = 1.3f, dark = true))) {
            compose.runOnIdle { profile.value = value }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val output = File("build/outputs/quick-settings-previews/quick-settings-$name.png")
            output.parentFile.mkdirs()
            val bitmap = compose.onNodeWithTag("quick-panel-fixture").captureToImage().asAndroidBitmap()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        assertEquals(0, changes)
    }

    private fun control(setting: QuickSetting) = compose.onNodeWithTag("quick-setting-${setting.name}")

    private fun snapshot(settings: List<QuickSettingState> = QuickSetting.entries.map {
        QuickSettingState(it, checked = false, supported = true, enabled = true)
    }) = ListeningModeSnapshot(AppleMetadata(name = "Dylan’s AirPods", model = AirPodsModel.AIRPODS_PRO_3),
        AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3))),
        AppleSettings(), quickSettings = settings)

    @Composable
    private fun fixture(profile: Profile, snapshot: ListeningModeSnapshot,
        onToggle: (QuickSetting, Boolean) -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, profile.fontScale)) {
            LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = profile.dark) {
                Box(Modifier.requiredSize(profile.width.dp, profile.height.dp)
                    .background(if (profile.dark) Color(0xFF161C29) else Color(0xFFE7E5F4))
                    .testTag("quick-panel-fixture")) {
                    ListeningModePanel(snapshot, onMode = {}, onStrength = {}, onToggle = onToggle,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
