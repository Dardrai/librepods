package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.icons.LocalIcons
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
class ScaffoldGlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Profile(val dark: Boolean, val width: Int, val fontScale: Float)

    @Test fun longTitlesKeepClearOfGlassActionsAcrossThemesAndTextSizes() {
        val profiles = listOf(Profile(false, 400, 1f), Profile(true, 400, 1f),
            Profile(false, 320, 1.3f), Profile(true, 320, 1.3f))
        val profile = mutableStateOf(profiles.first())
        val title = "AirPods accessibility and listening settings"
        compose.setContent {
            val current = profile.value
            CompositionLocalProvider(LocalDensity provides Density(1f, current.fontScale)) {
                LibrePodsTheme(darkTheme = current.dark, designSystem = DesignSystem.Apple) {
                    Box(Modifier.requiredSize(current.width.dp, 400.dp).testTag("glass-scaffold")) {
                        StyledScaffold(title = title, navigateBack = {}, actionButtons = listOf(
                            { backdrop ->
                                StyledIconButton(Modifier.testTag("glass-action-one"), onClick = {}, backdrop = backdrop) {
                                    Icon(LocalIcons.current.Settings, contentDescription = "Glass settings")
                                }
                            },
                            { backdrop ->
                                StyledIconButton(Modifier.testTag("glass-action-two"), onClick = {}, backdrop = backdrop) {
                                    Icon(LocalIcons.current.Settings, contentDescription = "Other settings")
                                }
                            }
                        )) { _, _ ->
                            // Contrasting content behind the floating controls makes their refraction visible.
                            Column(Modifier.fillMaxSize().background(Color(0xFF727C89))) {
                                repeat(8) { row ->
                                    Box(Modifier.fillMaxWidth().height(50.dp).background(
                                        if (row % 2 == 0) Color(0xFF526CA8) else Color(0xFFB68C72)))
                                }
                            }
                        }
                    }
                }
            }
        }
        for (current in profiles) {
            compose.runOnIdle { profile.value = current }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val titleBounds = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithTag("glass-action-one").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("glass-action-two").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Title clears the back control", titleBounds.left >= back.right)
            assertTrue("Title clears both actions", titleBounds.right <= first.left)
            assertEquals("Title and controls share the header center", first.center.y, titleBounds.center.y, 0.5f)
            assertTrue("Separate touch targets", second.left - first.right >= 7.5f)
            assertTrue("Actions keep 48dp touch targets", first.width >= 48f && first.height >= 48f)
            val file = File("build/apple-glass-previews/scaffold-${current.dark}-${current.width}.png")
            file.parentFile.mkdirs()
            file.outputStream().use { stream ->
                assertTrue(compose.onNodeWithTag("glass-scaffold").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
    }
}
