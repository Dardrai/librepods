package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.bluetooth.cases.CaseSoundState
import me.kavishdevar.librepods.bluetooth.cases.CaseSoundViewModel
import me.kavishdevar.librepods.presentation.navigation.LocalIsCurrentEntry
import me.kavishdevar.librepods.presentation.navigation.LocalSharedTransitionScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CaseSoundRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun experimentUsesExistingThemesAndKeepsStopReachableWithoutStartingAnyRadioWork() {
        data class Profile(val system: DesignSystem, val width: Int, val scale: Float, val dark: Boolean)
        val profiles = listOf(Profile(DesignSystem.Apple, 448, 1f, false), Profile(DesignSystem.Apple, 448, 1f, true),
            Profile(DesignSystem.Material, 320, 1.3f, false))
        val current = mutableStateOf(profiles.first())
        val uiState = mutableStateOf(CaseSoundState())
        val controller = CaseSoundViewModel(RuntimeEnvironment.getApplication())
        compose.setContent {
            val profile = current.value
            CompositionLocalProvider(LocalDensity provides Density(1f, profile.scale)) {
                LibrePodsTheme(darkTheme = profile.dark, designSystem = profile.system) {
                    Box(Modifier.requiredSize(profile.width.dp, 998.dp).testTag("case-sound")) {
                        SharedTransitionLayout {
                            CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalIsCurrentEntry provides true) {
                                CaseSoundContent(uiState.value, controller, navigateBack = {})
                            }
                        }
                    }
                }
            }
        }
        for (profile in profiles) {
            compose.runOnIdle { current.value = profile }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            compose.onNodeWithText("DULT").assertIsSelected()
            compose.onNodeWithText("Hawkeye configuration").assertIsNotSelected()
            compose.onNodeWithText("Find nearby case").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Stop / cancel").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            val file = File("build/overlay-previews/case-sound-${profile.system}-${profile.dark}-${profile.width}.png")
            file.parentFile.mkdirs()
            file.outputStream().use { stream ->
                assertTrue(compose.onNodeWithTag("case-sound").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }

        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
            val file = File("build/overlay-previews/case-sound-$name-320.png")
            file.outputStream().use { stream ->
                assertTrue(compose.onNodeWithTag("case-sound").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
        compose.runOnIdle { uiState.value = CaseSoundState(ready = true, canPair = true) }
        compose.onNodeWithText("Start two-second experiment").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("This is my case, with both earbuds inside and out of my ears.").performScrollTo().performClick()
        compose.onNodeWithText("Start two-second experiment").performScrollTo().assertIsEnabled()
        capture("ready")

        compose.runOnIdle { uiState.value = CaseSoundState(busy = true, ready = true, entries = listOf("Start: Request issued; sound unconfirmed.")) }
        compose.onNodeWithText("Stop / cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Start two-second experiment").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Stop / cancel").assertIsDisplayed()
        capture("active")

        compose.runOnIdle { uiState.value = CaseSoundState(ready = true, outcomeEnabled = true, entries = listOf("Result: Request unconfirmed.")) }
        compose.onNodeWithText("Not sure").performScrollTo().assertIsDisplayed()
        capture("outcome")
    }
}
