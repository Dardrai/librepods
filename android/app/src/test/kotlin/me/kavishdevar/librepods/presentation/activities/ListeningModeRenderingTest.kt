/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.activities

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.ListeningModeContent
import me.kavishdevar.librepods.presentation.overlays.ListeningModeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1200dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ListeningModeRenderingTest {
    @get:Rule val compose = createComposeRule()
    private data class Profile(val label: String, val railWidth: Int, val dark: Boolean = false, val fontScale: Float = 1f,
        val model: AirPodsModel = AirPodsModel.AIRPODS_PRO_3)

    @Test fun nativeDialogContainsOnlyTheSharedRailAndKeepsItsGeometryAcrossStates() {
        val profile = mutableStateOf(Profile("light-416", 416))
        val pending = mutableStateOf<Int?>(null)
        val failed = mutableStateOf(false)
        val reading = mutableStateOf(false)
        var writes = 0
        compose.setContent {
            val value = profile.value
            CompositionLocalProvider(LocalDensity provides Density(1f, value.fontScale)) {
                LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = value.dark) {
                    val state = if (reading.value) AppleState() else AppleState(
                        controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3)),
                        requestedControls = pending.value?.let { mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(it.toByte())) } ?: emptyMap())
                    Box(Modifier.width(value.railWidth.dp).height(64.dp)
                        .background(if (value.dark) Color(0xFF38383A) else Color(0xFFE2E2E7))
                        .testTag("listening-popup-frame")) {
                        ListeningModeContent(ListeningModeSnapshot(AppleMetadata(name = "Dylan’s AirPods", model = value.model), state, AppleSettings()), commandFailed = failed.value,
                            onMode = { writes++ }, onStrength = { writes++ })
                    }
                }
            }
        }
        for (value in listOf(Profile("light-416", 416), Profile("dark-320-large", 320, true, 1.3f),
            Profile("light-320-large", 320, fontScale = 1.3f), Profile("dark-416", 416, true),
            Profile("legacy-pro", 320, model = AirPodsModel.AIRPODS_PRO_1),
            Profile("dark-tablet-600-large", 600, true, 1.5f))) {
            compose.runOnIdle { profile.value = value; pending.value = null; failed.value = false; reading.value = false }
            settle()
            val before = assertRail(value)
            assertModes(value)
            save(value.label)
            for (mode in if (value.model == AirPodsModel.AIRPODS_PRO_1) listOf(2, 3) else listOf(4, 2, 3)) {
                compose.runOnIdle { pending.value = mode }
                settle()
                assertStatus("Switching listening mode…")
                assertEquals("Changing to mode $mode cannot resize its rail", before, assertRail(value))
                assertModes(value)
                if (mode == 4 && value.label in listOf("light-416", "dark-320-large", "dark-tablet-600-large")) save(value.label + "-adaptive")
            }
            compose.runOnIdle { pending.value = null; failed.value = true }
            settle()
            assertStatus("Couldn’t change listening mode. Try again.")
            assertEquals("A failed request cannot resize its rail", before, assertRail(value))
            assertModes(value)
            compose.runOnIdle { failed.value = false; reading.value = true }
            settle()
            assertStatus("Reading listening mode…")
            assertEquals("Loading cannot resize its rail", before, assertRail(value))
            if (value.label in listOf("light-416", "dark-320-large", "dark-tablet-600-large")) save(value.label + "-reading")
        }
        assertEquals("Rendering and reports cannot send commands", 0, writes)
    }

    private fun assertRail(profile: Profile): Rect {
        val control = compose.onNodeWithTag("listening-mode-control", useUnmergedTree = true).assertIsDisplayed()
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("listening-popup-frame").fetchSemanticsNode().boundsInRoot
        assertEquals("The rail fills the pill window", profile.railWidth.toFloat(), bounds.width, 0.5f)
        assertEquals("The rail stays 64dp high", 64f, bounds.height, 0.5f)
        assertEquals("The popup contains no band or outer spacing", bounds, frame)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Close listening modes").assertDoesNotExist()
        return bounds
    }

    private fun assertModes(profile: Profile) {
        compose.onNodeWithContentDescription("Transparency").assertIsDisplayed()
        compose.onNodeWithContentDescription("Noise Cancellation").assertIsDisplayed()
        if (profile.model == AirPodsModel.AIRPODS_PRO_1) compose.onNodeWithContentDescription("Adaptive").assertDoesNotExist()
        else compose.onNodeWithContentDescription("Adaptive").assertIsDisplayed()
    }

    private fun assertStatus(status: String) {
        compose.onNodeWithTag("listening-mode-control", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun settle() { compose.mainClock.advanceTimeBy(1500); compose.waitForIdle() }
    private fun save(label: String) {
        val image = compose.onNodeWithTag("listening-popup-frame").captureToImage().asAndroidBitmap()
        val output = File("build/outputs/listening-mode-previews/listening-popup-$label.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
