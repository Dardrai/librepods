package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppleGlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Profile(
        val dark: Boolean = false,
        val design: DesignSystem = DesignSystem.Apple,
        val enabled: Boolean = true,
        val interactive: Boolean = true,
        val sampled: Boolean = true,
    )

    @Test @Config(sdk = [33]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun tapsActivateOnceAndDisabledControlsStayInactiveAcrossBothThemes() {
        val profile = mutableStateOf(Profile())
        var buttonClicks = 0
        var iconClicks = 0
        compose.setContent { Fixture(profile.value, { buttonClicks++ }, { iconClicks++ }) }
        for (design in DesignSystem.entries) {
            for (interactive in listOf(true, false)) {
                compose.runOnIdle { profile.value = Profile(design = design, interactive = interactive) }
                compose.waitForIdle()
                val previousButtons = buttonClicks
                val previousIcons = iconClicks
                compose.onNodeWithTag("glass-button").assertIsEnabled().performTouchInput { click() }
                compose.onNodeWithContentDescription("Settings").assertIsEnabled().performTouchInput { click() }
                compose.waitForIdle()
                assertEquals("A physical button tap must dispatch once", previousButtons + 1, buttonClicks)
                assertEquals("A physical icon tap must dispatch once", previousIcons + 1, iconClicks)
                compose.onNodeWithTag("glass-button").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                assertEquals("Semantic activation must also dispatch once", previousButtons + 2, buttonClicks)
                assertEquals("Semantic activation must also dispatch once", previousIcons + 2, iconClicks)
                compose.runOnIdle { profile.value = profile.value.copy(enabled = false) }
                compose.waitForIdle()
                compose.onNodeWithTag("glass-button").assertIsNotEnabled().performTouchInput { click() }
                compose.onNodeWithContentDescription("Settings").assertIsNotEnabled().performTouchInput { click() }
                compose.waitForIdle()
                assertEquals(previousButtons + 2, buttonClicks)
                assertEquals(previousIcons + 2, iconClicks)
            }
        }
    }

    @Test fun lightAndDarkGlassSampleTheRecordedContentRatherThanTheFlatSurface() {
        val profile = mutableStateOf(Profile())
        val differences = mutableMapOf<String, Float>()
        compose.setContent { Fixture(profile.value) }
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            compose.runOnIdle { profile.value = Profile(dark = dark, sampled = false) }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val flat = cropButton(capture("$theme-flat"))
            compose.runOnIdle { profile.value = profile.value.copy(sampled = true) }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val sampled = cropButton(capture("$theme-resting"))
            differences[theme] = pixelDifference(flat, sampled)
            compose.runOnIdle { profile.value = profile.value.copy(enabled = false) }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            capture("$theme-disabled")
        }
        assertTrue("Recorded content must remain visible through the glass; differences=$differences",
            differences.values.all { it > 8f })
    }

    @Test @Config(sdk = [33]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun pressAndCancellationKeepTheTouchTargetAndDoNotActivateTheControl() {
        val profile = mutableStateOf(Profile())
        var buttonClicks = 0
        var iconClicks = 0
        compose.setContent { Fixture(profile.value, { buttonClicks++ }, { iconClicks++ }) }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { profile.value = Profile(dark = dark) }
            compose.waitForIdle()
            for (tag in listOf("glass-button", "glass-icon")) {
                val node = compose.onNodeWithTag(tag)
                val before = node.fetchSemanticsNode().boundsInRoot
                assertTrue("Glass controls retain a 48dp target", before.width >= 48f && before.height >= 48f)
                node.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(600)
                val pressed = node.fetchSemanticsNode().boundsInRoot
                assertTrue("Press expansion stays restrained", pressed.width <= before.width * 1.09f)
                node.performTouchInput { cancel() }
                compose.mainClock.advanceTimeBy(600)
                compose.waitForIdle()
                assertEquals(0, buttonClicks)
                assertEquals(0, iconClicks)
                val restored = node.fetchSemanticsNode().boundsInRoot
                assertEquals(before.width, restored.width, 0.1f)
                assertEquals(before.height, restored.height, 0.1f)
            }
        }
    }

    @Composable
    private fun Fixture(profile: Profile, onButton: () -> Unit = {}, onIcon: () -> Unit = {}) {
        LibrePodsTheme(darkTheme = profile.dark, designSystem = profile.design) {
            val source = rememberLayerBackdrop()
            val emptySource = rememberLayerBackdrop()
            Box(Modifier.requiredSize(380.dp, 150.dp).testTag("glass-preview")) {
                Box(Modifier.fillMaxSize().layerBackdrop(source).drawBehind {
                    drawRect(Color(0xFFDF845A), size = Size(size.width / 2f, size.height))
                    drawRect(Color(0xFF527CCA), topLeft = Offset(size.width / 2f, 0f),
                        size = Size(size.width / 2f, size.height))
                })
                // Cover the source so pixel differences prove backdrop sampling, rather than plain alpha.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
                Column(Modifier.align(Alignment.Center).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StyledIconButton(modifier = Modifier.testTag("glass-icon"),
                            backdrop = if (profile.sampled) source else emptySource,
                            enabled = profile.enabled, onClick = onIcon) {
                            Icon(LocalIcons.current.Settings, "Settings", tint = MaterialTheme.colorScheme.onBackground)
                        }
                        StyledButton(modifier = Modifier.width(260.dp).testTag("glass-button"),
                            backdrop = if (profile.sampled) source else emptySource,
                            enabled = profile.enabled, isInteractive = profile.interactive, onClick = onButton) {
                            Text("Refresh device settings", color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String): Bitmap {
        // Prime the native snapshot renderer before capturing the full sibling backdrop graph.
        compose.onNodeWithTag("glass-button").captureToImage()
        val bitmap = compose.onNodeWithTag("glass-preview").captureToImage().asAndroidBitmap()
        val output = File("build/apple-glass-previews/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    private fun cropButton(host: Bitmap): Bitmap {
        // Capturing the sampling child alone omits its sibling's recorded graphics layer.
        val hostBounds = compose.onNodeWithTag("glass-preview").fetchSemanticsNode().boundsInRoot
        val buttonBounds = compose.onNodeWithTag("glass-button").fetchSemanticsNode().boundsInRoot
        return Bitmap.createBitmap(host,
            (buttonBounds.left - hostBounds.left).roundToInt(),
            (buttonBounds.top - hostBounds.top).roundToInt(),
            buttonBounds.width.roundToInt(), buttonBounds.height.roundToInt())
    }

    private fun pixelDifference(first: Bitmap, second: Bitmap): Float {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        var difference = 0L
        var count = 0
        for (y in first.height / 4 until first.height * 3 / 4) {
            for (x in first.width / 12 until first.width / 4) {
                val a = first.getPixel(x, y)
                val b = second.getPixel(x, y)
                difference += abs(AndroidColor.red(a) - AndroidColor.red(b)) +
                    abs(AndroidColor.green(a) - AndroidColor.green(b)) +
                    abs(AndroidColor.blue(a) - AndroidColor.blue(b))
                count += 3
            }
        }
        return difference.toFloat() / count
    }
}
