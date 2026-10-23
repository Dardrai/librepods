package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
@Config(sdk = [33], application = Application::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StyledButtonRenderingTest {
    @get:Rule val compose = createComposeRule()

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun appleRequiredActionsFitNarrowScreenAtDoubleTextSize() = requiredActions(DesignSystem.Apple)

    @Test fun materialRequiredActionsFitNarrowScreenAtDoubleTextSize() = requiredActions(DesignSystem.Material)

    private fun requiredActions(theme: DesignSystem) {
        val overflow = mutableMapOf<String, Boolean>()
        var clicked = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LibrePodsTheme(designSystem = theme) {
                    Column(Modifier.requiredSize(320.dp, 400.dp).padding(16.dp).testTag("actions"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (label in listOf("Phone", "PC")) StyledButton(Modifier.weight(1f).testTag(label), onClick = { clicked++ }) {
                                Text(label, onTextLayout = { overflow[label] = it.hasVisualOverflow })
                            }
                        }
                        for (label in listOf("Apply", "Applying…")) StyledButton(Modifier.fillMaxWidth().testTag(label), onClick = { clicked++ }) {
                            Text(label, onTextLayout = { overflow[label] = it.hasVisualOverflow })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        for (label in listOf("Phone", "PC", "Apply", "Applying…")) {
            assertEquals("$theme $label must render its complete label at 2x text", false, overflow[label])
            val button = compose.onNodeWithTag(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$theme $label must retain a full touch target", button.height >= 48f)
            compose.onNodeWithTag(label).performClick()
        }
        assertEquals(4, clicked)
        if (theme == DesignSystem.Apple) return // Robolectric legacy rendering cannot capture the Apple RuntimeShader.
        val output = File("build/overlay-previews/required-actions-${theme.name.lowercase()}-narrow-2x.png")
        output.parentFile.mkdirs()
        output.outputStream().use {
            assertTrue(compose.onNodeWithTag("actions").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
