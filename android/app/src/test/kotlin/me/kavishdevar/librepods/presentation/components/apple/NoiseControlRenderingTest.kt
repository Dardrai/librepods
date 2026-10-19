package me.kavishdevar.librepods.presentation.components.apple

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Captures the real Compose control; these are review artifacts rather than pixel goldens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NoiseControlRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Profile(val name: String, val dark: Boolean, val width: Int,
        val mode: Int, val labels: Boolean = true, val fontScale: Float = 1f)

    @Test fun captureListeningModeLightDarkCompactAndNarrow() {
        val profiles = listOf(
            Profile("light-transparency", false, 400, 3),
            Profile("dark-transparency", true, 400, 3),
            Profile("light-adaptive", false, 400, 4),
            Profile("dark-adaptive", true, 400, 4),
            Profile("dark-anc", true, 400, 2),
            Profile("light-narrow-adaptive-large-text", false, 280, 4, fontScale = 1.3f),
            Profile("dark-narrow-transparency", true, 280, 3),
            Profile("dark-compact-adaptive", true, 280, 4, labels = false)
        )
        val profile = mutableStateOf(profiles.first())
        compose.setContent {
            val current = profile.value
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, current.fontScale)) {
                LibrePodsTheme(darkTheme = current.dark, designSystem = DesignSystem.Apple) {
                    Box(Modifier.requiredSize(440.dp, 180.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .testTag("listening-render-host"), contentAlignment = Alignment.Center) {
                        NoiseControlSettings(
                            modifier = Modifier.width(current.width.dp)
                                .then(if (current.labels) Modifier.heightIn(min = 108.dp) else Modifier.height(64.dp)),
                            showOffListeningMode = true,
                            noiseControlModeValue = current.mode,
                            onNoiseControlModeChanged = {},
                            adaptiveStrength = 38,
                            onAdaptiveStrengthChanged = {},
                            showLabels = current.labels
                        )
                    }
                }
            }
        }
        for (current in profiles) {
            compose.runOnIdle { profile.value = current }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            if (current.labels) {
                for (label in listOf("Transparency", "Adaptive", "Noise Cancellation")) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue(current.name + ": label must have a text layout: " + label, layouts.isNotEmpty())
                    if (label == "Noise Cancellation") {
                        assertTrue(current.name + ": multi-line label height must grow with font scale", layouts.none { it.didOverflowHeight })
                    } else {
                        assertTrue(current.name + ": short captions must stay on one unclipped line: " + label,
                            layouts.all { it.lineCount == 1 && it.getLineBottom(0) <= it.size.height + 0.5f })
                    }
                }
            }
            val output = File("build/listening-previews/" + current.name + ".png")
            output.parentFile.mkdirs()
            val bitmap = compose.onNodeWithTag("listening-render-host")
                .captureToImage().asAndroidBitmap()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
