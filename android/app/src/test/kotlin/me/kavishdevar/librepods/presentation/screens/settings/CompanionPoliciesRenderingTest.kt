package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.devices.AppleSettings
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionPoliciesRenderingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun aSyncedCallPreferenceCannotGrantPhoneConsentAndControlsFitBothDesigns() {
        data class Profile(val design: DesignSystem, val dark: Boolean, val width: Int, val scale: Float)
        val profiles = listOf(Profile(DesignSystem.Apple, false, 448, 1f), Profile(DesignSystem.Apple, true, 448, 1f),
            Profile(DesignSystem.Material, false, 320, 1.3f))
        val profile = mutableStateOf(profiles.first())
        val settings = mutableStateOf(AppleSettings(callTakeoverEnabled = true, callReturnEnabled = true))
        val permission = mutableStateOf(false)
        val host = mutableStateOf(true)
        var calls = 0
        var volumeEdits = 0
        compose.setContent {
            val p = profile.value
            CompositionLocalProvider(LocalDensity provides Density(1f, p.scale)) {
                LibrePodsTheme(darkTheme = p.dark, designSystem = p.design) {
                    Column(Modifier.requiredSize(p.width.dp, 998.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                        .testTag("policies").verticalScroll(rememberScrollState()).padding(16.dp)) {
                        CompanionPoliciesContent(settings.value, true, permission.value, hostAvailable = host.value,
                            onAutoPause = {}, onPopup = {}, onCache = {}, onCalls = { calls++ }, onReturn = {},
                            onFirst = { volumeEdits++ }, onSecond = { volumeEdits++ })
                    }
                }
            }
        }
        for (p in profiles) {
            compose.runOnIdle { profile.value = p; permission.value = false; settings.value = settings.value.copy(callTakeoverConsent = false) }
            compose.onNodeWithText("Shared settings").performScrollTo().assertIsDisplayed()
            capture("shared-${p.design}-${p.dark}-${p.width}")
            compose.onNodeWithText("Take calls on phone").performScrollTo().assertIsDisplayed().performClick()
            compose.onAllNodes(isToggleable(), useUnmergedTree = true)[3].assertIsOff()
            compose.onAllNodes(isToggleable(), useUnmergedTree = true)[4].assertIsNotEnabled()
            compose.runOnIdle { permission.value = true; settings.value = settings.value.copy(callTakeoverConsent = true) }
            compose.onNodeWithText("Return after call").performScrollTo()
            compose.onAllNodes(isToggleable(), useUnmergedTree = true)[3].assertIsOn()
            compose.onAllNodes(isToggleable(), useUnmergedTree = true)[4].assertIsEnabled()
            capture("calls-${p.design}-${p.dark}-${p.width}")
            compose.onNodeWithText("Relative volume").performScrollTo()
            capture("conversation-top-${p.design}-${p.dark}-${p.width}")
            compose.onNodeWithText("Volume while speaking · 43%").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Second-stage volume · 20%").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Pause media while speaking").performScrollTo().assertIsDisplayed()
            capture("conversation-${p.design}-${p.dark}-${p.width}")
        }
        compose.runOnIdle { settings.value = settings.value.copy(conversationAwarenessVolume = 43.5f, conversationAwarenessReducedVolume = 20.25f) }
        compose.onNodeWithText("Volume while speaking · 43.5%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Second-stage volume · 20.25%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Volume while speaking · 43.5%").performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertFalse(action(Float.NaN))
            assertFalse(action(Float.POSITIVE_INFINITY))
            assertFalse(action(Float.NEGATIVE_INFINITY))
        }
        compose.runOnIdle { assertEquals(0, volumeEdits) }
        for ((first, second) in listOf(Float.NaN to 20f, 43f to Float.POSITIVE_INFINITY, 101f to 20f, 43f to -1f, 20f to 21f)) {
            compose.runOnIdle { settings.value = settings.value.copy(conversationAwarenessVolume = first, conversationAwarenessReducedVolume = second) }
            compose.onNodeWithText("A saved volume value is unsupported. Change it in NTPods to restore these controls.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Pause media while speaking").assertIsNotEnabled()
        }
        compose.runOnIdle { host.value = false }
        compose.onNodeWithText("Pause when removed").performScrollTo()
        compose.onNodeWithText("Exact-player pause and resume need Shizuku running with LibrePods access. Your saved preference still syncs with the PC.").assertIsDisplayed()
        assertEquals(3, calls)
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val file = File("build/overlay-previews/companion-v2-$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { assertTrue(compose.onNodeWithTag("policies").captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
