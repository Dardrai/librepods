package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import me.kavishdevar.librepods.companion.CompanionState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionSettingsRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeActionsStayNeutralAndReadableAcrossLinkTransferAndRecoveryStates() {
        data class Profile(val theme: DesignSystem, val width: Int, val scale: Float, val dark: Boolean)
        val profiles = listOf(Profile(DesignSystem.Apple, 448, 1f, false), Profile(DesignSystem.Apple, 448, 1f, true),
            Profile(DesignSystem.Material, 320, 1.3f, false))
        val profile = mutableStateOf(profiles.first())
        val state = mutableStateOf(CompanionState())
        val home = mutableStateOf(true)
        var phone = 0; var localPhone = 0; var pc = 0; var cancel = 0; var reconnect = 0; var manage = 0; var link = 0
        compose.setContent {
            val current = profile.value
            CompositionLocalProvider(LocalDensity provides Density(1f, current.scale)) {
                LibrePodsTheme(darkTheme = current.dark, designSystem = current.theme) {
                    Column(Modifier.requiredSize(current.width.dp, 998.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp).testTag("companion-settings")) {
                        CompanionSettingsContent(state.value, true, { link++ }, { reconnect++ }, {},
                            { phone++ }, { pc++ }, { cancel++ }, if (home.value) ({ manage++ }) else null,
                            onLocalPhone = { localPhone++ })
                    }
                }
            }
        }
        for (current in profiles) {
            compose.runOnIdle { profile.value = current; state.value = CompanionState() }
            compose.onNodeWithText("Link PC").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Phone").assertDoesNotExist()
            capture("unlinked-${current.theme}-${current.dark}-${current.width}")
            compose.runOnIdle { state.value = CompanionState(true, true, false, "PC linked. Choose where to listen.") }
            capture("linked-${current.theme}-${current.dark}-${current.width}")
            compose.onNodeWithText("Phone").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithText("PC").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithText("PC settings").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Unlink PC").assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(0)
            compose.runOnIdle { state.value = CompanionState(true, false, false,
                "PC connection stopped. Open NTPods and tap Reconnect. Check where your audio is playing before trying again.") }
            compose.onNodeWithText("PC offline").assertIsDisplayed()
            compose.onNodeWithText("Choose Phone to listen here, or reconnect to your PC. Check where audio is playing.").assertIsDisplayed()
            compose.onNodeWithText("Phone").assertIsEnabled().performClick()
            compose.onNodeWithText("PC").assertIsNotEnabled()
            compose.onNodeWithText("Reconnect to PC").assertIsDisplayed().assertIsEnabled().performClick()
            capture("offline-${current.theme}-${current.dark}-${current.width}")
            compose.runOnIdle { state.value = state.value.copy(busy = true, detail = "Connecting phone audio…") }
            compose.onNodeWithText("Phone").assertIsNotEnabled()
            compose.onNodeWithText("PC").assertIsNotEnabled()
            compose.runOnIdle { state.value = CompanionState(true, true, true, "Preparing audio transfer…") }
            compose.onNodeWithText("Preparing the switch…").assertIsDisplayed()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
            compose.runOnIdle { state.value = state.value.copy(busy = true, detail = "Releasing phone audio…") }
            capture("busy-${current.theme}-${current.dark}-${current.width}")
            compose.onNodeWithText("Phone").assertIsNotEnabled()
            compose.onNodeWithText("PC").assertIsNotEnabled()
            compose.onNodeWithText("PC settings").assertDoesNotExist()
            compose.onNodeWithText("Cancel transfer").assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle { state.value = CompanionState(true, true, false,
                "Audio transfer stopped. Choose where to listen to try again.") }
            compose.onNodeWithText("Couldn’t confirm the switch. Choose a destination to try again.").assertIsDisplayed()
            compose.onNodeWithText("Phone").assertIsEnabled()
            compose.onNodeWithText("PC").assertIsEnabled()
            compose.onNodeWithText("Cancel transfer").assertDoesNotExist()
            capture("error-${current.theme}-${current.dark}-${current.width}")
            compose.runOnIdle { state.value = state.value.copy(detail = "Audio transfer complete.") }
            compose.onNodeWithText("Audio switched. Choose a destination to switch again.").assertIsDisplayed()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        }
        compose.onNodeWithText("PC settings").performClick()
        compose.runOnIdle { home.value = false }
        compose.onNodeWithText("Unlink PC").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("PC settings").assertDoesNotExist()
        capture("settings-Apple-or-Material")
        compose.runOnIdle { state.value = CompanionState(detail = "Saved PC link is unavailable. Link your PC again.") }
        compose.onNodeWithText("Saved PC link is unavailable. Link your PC again.").assertIsDisplayed()
        compose.onNodeWithText("Link PC").performClick()
        assertEquals(3, phone); assertEquals(3, localPhone); assertEquals(3, pc); assertEquals(3, cancel); assertEquals(3, reconnect)
        assertEquals(1, manage); assertEquals(1, link)
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        val file = File("build/overlay-previews/companion-$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use {
            assertTrue(compose.onNodeWithTag("companion-settings").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
