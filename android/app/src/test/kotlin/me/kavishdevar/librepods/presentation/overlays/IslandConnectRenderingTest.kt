/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
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
// Robolectric's SDK33 native renderer uses older Skia without AGSL shader.eval support.
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandConnectRenderingTest {
    @get:Rule val compose = createComposeRule()

    private val title = "Studio AirPods Pro 3 with a longer device name"
    private val settings = IslandSettings(expandedWidth = 1248f, expandedHeight = 630f,
        compactWidth = 300f, compactHeight = 132f, compactBatteryStyle = IslandBatteryStyle.TEXT)
    private val batteries = setOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.CHARGING, 1_000L),
        Battery(BatteryComponent.RIGHT, 75, BatteryStatus.CHARGING, 1_000L),
        Battery(BatteryComponent.CASE, 90, BatteryStatus.NOT_CHARGING, 1_000L))

    @Test fun nearbyActionIsCameraSafeAccessibleAndKeepsItsBoundsThroughoutConnectionAndRetry() {
        val state = mutableStateOf(IslandConnectState.IDLE)
        val nearby = mutableStateOf(true)
        val currentSettings = mutableStateOf(settings)
        val fontScale = mutableStateOf(1f)
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, fontScale.value)) {
                LibrePodsTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(416.dp, 310.dp).background(Color(0xFF666666))
                        .testTag("connect-host"), contentAlignment = Alignment.TopCenter) {
                        IslandContent(name = title, type = IslandType.CONNECTED, batteryLevel = 75,
                            reversed = false, otherDeviceName = null, islandSettings = currentSettings.value,
                            islandState = IslandState.EXPANDED, showOffListeningMode = false,
                            noiseControlModeValue = 3, onNoiseControlModeChanged = {},
                            onAdaptiveStrengthChanged = {}, model = AirPodsModel.AIRPODS_PRO_3,
                            batteries = batteries, batteryNow = 1_000L, paired = true, nearby = nearby.value,
                            cameraPaddingPx = 150, cameraCapsuleWidthPx = 132f,
                            onConnect = { clicks++ }, connectState = state.value)
                    }
                }
            }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithTag("island-connect")
        button.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.onNodeWithText("Connect").assertIsDisplayed()
        val idleBounds = button.fetchSemanticsNode().boundsInRoot
        assertEquals(324f, idleBounds.width, 1f)
        assertTrue("Connect needs a complete 48dp touch target", idleBounds.height >= 144f)
        val nameBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertTrue("The long device name must end before the Connect action", nameBounds.right <= idleBounds.left + 0.5f)
        val host = compose.onNodeWithTag("connect-host").fetchSemanticsNode().boundsInRoot
        assertTrue("Connect must remain below the physical camera", idleBounds.top >= host.top + 150f)
        button.performClick()
        assertEquals(1, clicks)
        save("nearby")

        compose.runOnIdle { state.value = IslandConnectState.CONNECTING }
        compose.waitForIdle()
        button.assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Connecting to $title").assertIsDisplayed()
        assertSameBounds(idleBounds, button.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { nearby.value = false }
        compose.waitForIdle()
        button.assertIsDisplayed().assertIsNotEnabled()
        assertSameBounds(idleBounds, button.fetchSemanticsNode().boundsInRoot)
        save("connecting")

        compose.runOnIdle { nearby.value = true; state.value = IslandConnectState.FAILED }
        compose.waitForIdle()
        button.assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Couldn’t connect").assertIsDisplayed()
        assertSameBounds(idleBounds, button.fetchSemanticsNode().boundsInRoot)
        button.performClick()
        assertEquals(2, clicks)
        save("retry")

        for (style in IslandBatteryStyle.entries) {
            compose.runOnIdle {
                fontScale.value = 1.3f
                currentSettings.value = settings.copy(expandedWidth = 840f, expandedHeight = 900f,
                    expandedBatteryStyle = style, showListeningModeControl = true)
            }
            compose.waitForIdle()
            button.assertIsDisplayed().assertIsEnabled()
            val action = button.fetchSemanticsNode().boundsInRoot
            val name = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
            val status = compose.onNodeWithText("Couldn’t connect").fetchSemanticsNode().boundsInRoot
            assertTrue("At the minimum island width the title cannot cover Connect", name.right <= action.left + 0.5f)
            assertTrue("Larger status text cannot overlap the device name", status.bottom <= name.top + 0.5f)
            assertEquals("Narrow islands use an accessible icon button to leave room for the name", 144f, action.width, 1f)
            assertTrue(action.height >= 144f)
        }
        save("minimum-width")
    }

    @Test fun onlyAnOwnedNearbyBatteryAnnouncementOffersConnect() {
        val paired = mutableStateOf(true)
        val nearby = mutableStateOf(true)
        val hasAction = mutableStateOf(true)
        val type = mutableStateOf(IslandType.CONNECTED)
        val status = mutableStateOf<String?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                LibrePodsTheme(darkTheme = true) {
                    IslandContent(name = title, type = type.value, batteryLevel = 75, reversed = false,
                        otherDeviceName = null, islandSettings = settings, islandState = IslandState.EXPANDED,
                        showOffListeningMode = false, noiseControlModeValue = 3, onNoiseControlModeChanged = {},
                        onAdaptiveStrengthChanged = {}, model = AirPodsModel.AIRPODS_PRO_3,
                        batteries = batteries, batteryNow = 1_000L, paired = paired.value,
                        nearby = nearby.value, statusText = status.value,
                        onConnect = if (hasAction.value) ({}) else null)
                }
            }
        }
        compose.onNodeWithTag("island-connect").assertIsDisplayed()
        compose.runOnIdle { paired.value = false }
        compose.onNodeWithTag("island-connect").assertDoesNotExist()
        compose.runOnIdle { paired.value = true; nearby.value = false }
        compose.onNodeWithTag("island-connect").assertDoesNotExist()
        compose.runOnIdle { nearby.value = true; hasAction.value = false }
        compose.onNodeWithTag("island-connect").assertDoesNotExist()
        compose.runOnIdle { hasAction.value = true; status.value = "Transparency" }
        compose.onNodeWithTag("island-connect").assertDoesNotExist()
        compose.runOnIdle { status.value = null; type.value = IslandType.MOVED_TO_REMOTE }
        compose.onNodeWithTag("island-connect").assertDoesNotExist()
        compose.runOnIdle { type.value = IslandType.CONNECTED }
        compose.onNodeWithTag("island-connect").assertIsDisplayed()
    }

    @Test fun compactNearbyTapRevealsConnectWithoutChangingOrdinaryOpenAppBehavior() {
        val hasAction = mutableStateOf(true)
        var opened = 0
        val states = mutableListOf<IslandState>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                LibrePodsTheme(darkTheme = true) {
                    IslandContent(name = title, type = IslandType.CONNECTED, batteryLevel = 75,
                        reversed = false, otherDeviceName = null,
                        islandSettings = settings.copy(enableExpanded = false, compactOpenAppOnTap = true),
                        islandState = IslandState.COMPACT, openApp = { opened++ },
                        changeState = { state, _ -> states += state }, showOffListeningMode = false,
                        noiseControlModeValue = 3, onNoiseControlModeChanged = {}, onAdaptiveStrengthChanged = {},
                        model = AirPodsModel.AIRPODS_PRO_3, batteries = batteries, batteryNow = 1_000L,
                        paired = true, nearby = true, cameraCapsuleWidthPx = 132f,
                        onConnect = if (hasAction.value) ({}) else null)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("island-compact-artwork", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(IslandState.EXPANDED), states)
            assertEquals(0, opened)
        }
        compose.onNodeWithContentDescription("Show connection controls for $title")
            .assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf(IslandState.EXPANDED, IslandState.EXPANDED), states)
            assertEquals(0, opened)
            hasAction.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("island-compact-artwork", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(listOf(IslandState.EXPANDED, IslandState.EXPANDED), states)
        }
    }

    private fun assertSameBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.5f)
        assertEquals(expected.top, actual.top, 0.5f)
        assertEquals(expected.right, actual.right, 0.5f)
        assertEquals(expected.bottom, actual.bottom, 0.5f)
    }

    private fun save(label: String) {
        val bitmap = compose.onNodeWithTag("connect-host").captureToImage().asAndroidBitmap()
        val file = File("build/overlay-previews/island-connect-$label.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
