package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
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

/** The actual island payload, including its camera clearance, batteries and shared mode control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandListeningControlsRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Profile(val name: String, val design: DesignSystem, val width: Int,
        val allowOff: Boolean, val fontScale: Float = 1f)
    private val profile = mutableStateOf(Profile("apple-normal", DesignSystem.Apple, 416, false))
    private val mode = mutableStateOf(3)
    private val enabled = mutableStateOf(true)
    private val nearby = mutableStateOf(false)
    private val model = mutableStateOf(AirPodsModel.AIRPODS_PRO_3)
    private val showControls = mutableStateOf(true)
    private val expandedHeight = mutableStateOf(1f)
    private val batteryStyle = mutableStateOf(IslandBatteryStyle.CIRCLE_TEXT)
    private val requestedStrength = mutableStateOf<Int?>(null)
    private val modeWrites = mutableListOf<Int>()
    private val strengthWrites = mutableListOf<Int>()
    private val stateChanges = mutableListOf<IslandState>()
    private var timerResets = 0
    private val title = "Studio AirPods Pro 3"

    @Test fun actualIslandKeepsItsHeaderBatteriesAndControlAlignedAcrossBothThemesAndModes() {
        render()
        for (current in listOf(
            Profile("apple-normal", DesignSystem.Apple, 416, false),
            Profile("apple-narrow-large-text", DesignSystem.Apple, 280, true, 1.3f),
            Profile("material-normal", DesignSystem.Material, 416, true),
            Profile("material-narrow-large-text", DesignSystem.Material, 280, false, 1.3f)
        )) {
            compose.runOnIdle { profile.value = current; mode.value = 3 }
            settle()
            val baseline = stableBounds()
            for ((selected, name) in listOf(3 to "transparency", 4 to "adaptive", 2 to "anc")) {
                compose.runOnIdle { mode.value = selected }
                settle()
                val actual = stableBounds()
                for ((tag, bounds) in baseline) assertSame(bounds, actual.getValue(tag), current.name + "/" + name + ": " + tag)
                val controls = actual.getValue("island-listening-controls")
                val header = actual.getValue("island-header")
                assertEquals("The rail must share the header's leading edge", header.left, controls.left, 1f)
                assertEquals("The rail must share the header's trailing edge", header.right, controls.right, 1f)
                assertTrue("Batteries must remain above the mode rail", controls.top >=
                    listOf("left", "right", "case").maxOf { actual.getValue("island-battery-" + it).bottom })
                val shell = actual.getValue("island-shell")
                assertTrue("The whole control must fit inside the island", controls.left >= shell.left &&
                    controls.right <= shell.right && controls.bottom <= shell.bottom)
                val target = if (current.design == DesignSystem.Apple) bounds("noise-control-thumb") else
                    compose.onNodeWithContentDescription("Transparency").fetchSemanticsNode().boundsInRoot
                assertTrue("Every mode needs at least a 48 dp touch target", target.width >= 144f && target.height >= 144f)
                capture(current.name + "-" + name)
            }
        }
        assertTrue("Reports and rendering must not emit mode commands", modeWrites.isEmpty())
        assertTrue("Reports and rendering must not emit strength commands", strengthWrites.isEmpty())
    }

    @Test fun nearbyControlsStayDisabledAndUnsupportedPairedModelsReserveNoControlSpace() {
        mode.value = 4
        render()
        for (design in listOf(DesignSystem.Apple, DesignSystem.Material)) {
            compose.runOnIdle {
                profile.value = profile.value.copy(name = design.name.lowercase(), design = design)
                enabled.value = false
                nearby.value = true
            }
            settle()
            val disabledBounds = bounds("island-listening-controls")
            compose.onNodeWithContentDescription("Transparency").assertIsNotEnabled().performClick()
            compose.onNodeWithContentDescription("Noise Cancellation").assertIsNotEnabled().performClick()
            if (design == DesignSystem.Apple) {
                compose.onNodeWithTag("noise-control-thumb").assertIsNotEnabled()
                    .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(!it(70f)) }
            }
            compose.runOnIdle { enabled.value = true }
            settle()
            compose.onNodeWithContentDescription("Transparency").assertIsNotEnabled().performClick()
            assertSame(disabledBounds, bounds("island-listening-controls"), "Nearby must not move the rail")
            capture(design.name.lowercase() + "-nearby-disabled")
        }
        assertTrue("Nearby mode actions cannot issue commands", modeWrites.isEmpty())
        assertTrue("Nearby strength actions cannot issue commands", strengthWrites.isEmpty())
        val supportedHeight = bounds("island-shell").height
        compose.runOnIdle { model.value = AirPodsModel.AIRPODS_2 }
        settle()
        compose.onNodeWithTag("island-listening-controls").assertDoesNotExist()
        val unsupported = bounds("island-shell")
        assertTrue("A model without listening modes cannot reserve an empty rail", unsupported.height < supportedHeight)
        compose.runOnIdle { showControls.value = false }
        settle()
        assertSame(unsupported, bounds("island-shell"), "Hiding an unsupported rail must not change the endpoint")
        capture("unsupported-model-no-control-space")
    }

    @Test fun tallExpandedIslandsKeepTheHeaderAtTheCameraSafeTopAtNormalAndLargeTextSizes() {
        showControls.value = false
        batteryStyle.value = IslandBatteryStyle.NONE
        expandedHeight.value = 1200f
        render()
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle {
                profile.value = profile.value.copy(fontScale = fontScale)
                expandedHeight.value = 1200f
            }
            settle()
            val shell = bounds("island-shell")
            val header = bounds("island-header")
            val status = bounds("island-status")
            val name = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
            val artwork = bounds("island-artwork")
            assertEquals("The configured tall shell must retain its height", 1200f, shell.height, 1f)
            assertEquals("The header must start at the camera clearance", shell.top + 126f, header.top, 1f)
            if (fontScale == 1f) {
                assertEquals("Normal text needs a bounded 64dp header", 192f, header.height, 1f)
                capture("tall-island-header-at-camera-safe-top")
            }
            assertEquals("The first text must not gain another top gap", header.top, status.top, 1f)
            assertTrue("Large text must keep both lines inside the header without overlap",
                status.bottom <= name.top + 1f && name.bottom <= header.bottom + 1f)
            assertEquals("Artwork must start beside the text without centering slack", header.top, artwork.top, 1f)
            assertTrue("Artwork must remain within the camera-safe header", artwork.bottom <= header.bottom + 1f)
            compose.onNodeWithTag("island-listening-controls").assertDoesNotExist()
            compose.runOnIdle { expandedHeight.value = 1f }
            settle()
            assertTrue("Natural content must use less height than the tall configuration", bounds("island-shell").height < shell.height)
            assertSame(header, bounds("island-header"), "Changing shell height must not move or stretch the header")
            assertSame(status, bounds("island-status"), "Changing shell height must not move the first text")
            assertSame(artwork, bounds("island-artwork"), "Changing shell height must not move the artwork")
            assertSame(name, compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot,
                "Changing shell height must not move the device name")
        }
    }

    // API33 is used only for Espresso's input bridge; the actual glass captures above use API35 native Skia.
    @Test @Config(sdk = [33]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun draggingTheEmbeddedControlKeepsTheIslandOpenAndDisabledGesturesCannotWrite() {
        render()
        val rail = bounds("island-listening-controls")
        val initialThumb = bounds("noise-control-thumb")
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            swipe(center, Offset(rail.right - initialThumb.left - 1f, center.y), durationMillis = 480)
        }
        settle()
        assertEquals("The embedded rail must reach ANC at its far end", 2, mode.value)
        assertEquals(2, modeWrites.last())
        assertTrue("A horizontal mode gesture must not fold the island", stateChanges.isEmpty())
        assertTrue("Touching the mode rail must keep the island's dismissal timer alive", timerResets > 0)
        val count = modeWrites.size
        val strengths = strengthWrites.size
        compose.runOnIdle { enabled.value = false }
        settle()
        val thumb = bounds("noise-control-thumb")
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            swipe(center, Offset(rail.left - thumb.left + 1f, center.y), durationMillis = 320)
        }
        settle()
        assertEquals("Disabled drags cannot issue mode commands", count, modeWrites.size)
        assertEquals("Disabled drags cannot issue strength commands", strengths, strengthWrites.size)
        assertTrue(stateChanges.isEmpty())
    }

    private fun render() {
        compose.setContent {
            val current = profile.value
            CompositionLocalProvider(LocalDensity provides Density(3f, current.fontScale)) {
                // This mirrors IslandWindow's paired-black-island appearance while retaining the app's chosen design.
                LibrePodsTheme(darkTheme = true, designSystem = current.design) {
                    Box(Modifier.requiredSize(416.dp, 430.dp).background(Color(0xFF666666))
                        .testTag("listening-island-host"), contentAlignment = Alignment.TopCenter) {
                        IslandContent(name = title, type = IslandType.CONNECTED, batteryLevel = 82,
                            reversed = false, otherDeviceName = null,
                            islandSettings = IslandSettings(expandedWidth = current.width * 3f, expandedHeight = expandedHeight.value,
                                compactWidth = 300f, compactHeight = 132f, expandedBatteryStyle = batteryStyle.value,
                                showListeningModeControl = showControls.value),
                            islandState = IslandState.EXPANDED, model = model.value, paired = true, nearby = nearby.value,
                            cameraPaddingPx = 126, cameraCapsuleWidthPx = 132f,
                            batteries = setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING, 1_000L),
                                Battery(BatteryComponent.RIGHT, 80, BatteryStatus.NOT_CHARGING, 1_000L),
                                Battery(BatteryComponent.CASE, 75, BatteryStatus.CHARGING, 1_000L)),
                            batteryNow = 1_000L, onConnect = {},
                            showOffListeningMode = current.allowOff, noiseControlModeValue = mode.value,
                            onNoiseControlModeChanged = { modeWrites += it; mode.value = it },
                            adaptiveStrength = 38, requestedAdaptiveStrength = requestedStrength.value,
                            onAdaptiveStrengthChanged = { strengthWrites += it; requestedStrength.value = it },
                            listeningControlsEnabled = enabled.value,
                            changeState = { state, _ -> stateChanges += state }, resetTimer = { timerResets++ })
                    }
                }
            }
        }
        settle()
    }

    private fun stableBounds() = (listOf("island-shell", "island-header", "island-status", "island-artwork",
        "island-listening-controls") + listOf("left", "right", "case").map { "island-battery-" + it })
        .associateWith { bounds(it) } + ("title" to compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot)

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertSame(expected: Rect, actual: Rect, message: String) {
        assertEquals(message, expected.left, actual.left, 1f)
        assertEquals(message, expected.top, actual.top, 1f)
        assertEquals(message, expected.right, actual.right, 1f)
        assertEquals(message, expected.bottom, actual.bottom, 1f)
    }
    private fun settle() { compose.mainClock.advanceTimeBy(1500); compose.waitForIdle() }
    private fun capture(name: String) {
        val output = File("build/island-listening-previews/" + name + ".png")
        output.parentFile.mkdirs()
        val bitmap = compose.onNodeWithTag("listening-island-host").captureToImage().asAndroidBitmap()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
