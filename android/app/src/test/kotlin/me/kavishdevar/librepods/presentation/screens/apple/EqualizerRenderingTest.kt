/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CustomEq
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
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
@Config(sdk = [35], application = Application::class, qualifiers = "w700dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EqualizerRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun graphAndLabelsFitNarrowLargeTextAndWideLayoutsInBothAppearances() {
        data class Profile(val design: DesignSystem, val dark: Boolean, val width: Int, val height: Int, val scale: Float)
        val profiles = listOf(Profile(DesignSystem.Apple, false, 360, 800, 1f),
            Profile(DesignSystem.Apple, true, 320, 800, 1.4f),
            Profile(DesignSystem.Material, false, 600, 650, 1.3f))
        val current = mutableStateOf(profiles.first())
        var changes = 0
        compose.setContent {
            val profile = current.value
            CompositionLocalProvider(LocalDensity provides Density(1f, profile.scale)) {
                LibrePodsTheme(darkTheme = profile.dark, designSystem = profile.design) {
                    Box(Modifier.requiredSize(profile.width.dp, profile.height.dp).testTag("eq-preview")) {
                        EqualizerScreen(AppleUiState(state = AppleState(customEq = CustomEq(2, 25, 50, 75))), {},
                            { changes++ }, { _, _, _ -> changes++ })
                    }
                }
            }
        }
        for (profile in profiles) {
            compose.runOnIdle { current.value = profile }
            for (i in 0..2) compose.onNodeWithTag("equalizer-band-$i").assertIsDisplayed()
            val file = File("build/overlay-previews/eq-polish-${profile.design}-${profile.dark}-${profile.width}.png")
            file.parentFile.mkdirs()
            file.outputStream().use { compose.onNodeWithTag("eq-preview").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals(0, changes)
    }
}
