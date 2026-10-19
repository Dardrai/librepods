/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.components.primitives

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import java.io.File
import me.kavishdevar.librepods.data.app.AccessibilitySettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StyledSwitchRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Profile(val dark: Boolean, val checked: Boolean,
        val enabled: Boolean = true, val differentiate: Boolean = false) {
        val label get() = (if (dark) "dark" else "light") + (if (checked) "-on" else "-off") +
            (if (!enabled) "-disabled" else "") + (if (differentiate) "-differentiate" else "")
    }

    @Test fun glassThumbKeepsItsTrackAndEndpointGeometryAcrossThemesAndAccessibilityStates() {
        val profiles = listOf(false, true).flatMap { dark -> listOf(false, true).flatMap { checked ->
            listOf(Profile(dark, checked), Profile(dark, checked, enabled = false),
                Profile(dark, checked, differentiate = true))
        } }
        val profile = mutableStateOf(profiles.first())
        var writes = 0
        compose.setContent {
            val current = profile.value
            LibrePodsTheme(darkTheme = current.dark, designSystem = DesignSystem.Apple,
                accessibilitySettings = AccessibilitySettings(differentiateWithoutColor = current.differentiate)) {
                Box(Modifier.requiredSize(180.dp, 96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .testTag("switch-preview"), contentAlignment = Alignment.Center) {
                    StyledSwitch(current.checked, { writes++ }, enabled = current.enabled,
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
        }
        val endpoints = mutableMapOf<Boolean, Float>()
        for (current in profiles) {
            compose.runOnIdle { profile.value = current }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val track = compose.onNodeWithTag("styled-switch").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val thumb = compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("The switch track must retain its width", 192f, track.width, 1f)
            assertEquals("The switch track must retain its height", 84f, track.height, 1f)
            assertEquals("The glass thumb must not inflate its resting width", 117f, thumb.width, 1f)
            assertEquals("The glass thumb must not inflate its resting height", 72f, thumb.height, 1f)
            assertTrue("The thumb must stay inside the track", thumb.left >= track.left && thumb.right <= track.right)
            assertEquals("The thumb stays vertically centered", track.center.y, thumb.center.y, 1f)
            val endpoint = endpoints.getOrPut(current.checked) { thumb.left }
            assertEquals("Visual/accessibility states cannot shift the thumb", endpoint, thumb.left, 1f)
            val bitmap = compose.onNodeWithTag("switch-preview").captureToImage().asAndroidBitmap()
            val output = File("build/shared-controls-previews/switch-" + current.label + ".png")
            output.parentFile.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        assertTrue("On and off need distinct thumb positions", endpoints.getValue(true) > endpoints.getValue(false))
        assertEquals("Rendering and accessibility preferences cannot change the setting", 0, writes)
    }

    // API33 is used only for Espresso's input bridge; native API35 captures exercise the actual glass.
    @Test @Config(sdk = [33]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun thumbTapAndDragStillToggleWhileDisabledGesturesCannotWrite() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val writes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Box(Modifier.requiredSize(180.dp, 96.dp), contentAlignment = Alignment.Center) {
                    StyledSwitch(checked.value, { writes += it; checked.value = it }, enabled = enabled.value)
                }
            }
        }
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(checked.value)
        assertEquals(listOf(true), writes)

        compose.runOnIdle { checked.value = false; enabled.value = false; writes.clear() }
        compose.waitForIdle()
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(150f, 0f), 300) }
        compose.waitForIdle()
        assertFalse(checked.value)
        assertTrue("Disabled gestures cannot send a setting command", writes.isEmpty())

        compose.runOnIdle { enabled.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(150f, 0f), 300) }
        compose.waitForIdle()
        assertTrue("The existing drag gesture must still turn the switch on", checked.value)
        assertTrue(writes.isNotEmpty() && writes.last())
    }
}
