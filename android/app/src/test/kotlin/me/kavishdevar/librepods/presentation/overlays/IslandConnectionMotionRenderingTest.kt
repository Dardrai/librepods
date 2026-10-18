package me.kavishdevar.librepods.presentation.overlays

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandConnectionMotionRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val title = "Studio AirPods Pro 3 with a longer device name"
    private val nearby = mutableStateOf(true)
    private val state = mutableStateOf(IslandConnectState.IDLE)
    private val settings = mutableStateOf(IslandSettings(expandedWidth = 1248f, expandedHeight = 630f,
        compactWidth = 300f, compactHeight = 132f))
    private val fontScale = mutableStateOf(1f)
    private val batteryNow = mutableStateOf(1_000L)
    private val batteries = mutableStateOf(setOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.CHARGING, 1_000L),
        Battery(BatteryComponent.RIGHT, 75, BatteryStatus.CHARGING, 1_000L),
        Battery(BatteryComponent.CASE, 90, BatteryStatus.NOT_CHARGING, 1_000L)))
    private var clicks = 0
    private var originalMotionScale = 1f

    @Before fun enableMotion() {
        originalMotionScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
            .apply { isAccessible = true }.invoke(null) as Float
        motionScale(1f)
    }

    @After fun restoreMotion() { motionScale(originalMotionScale) }

    @Test fun ownershipChangesKeepHeaderGeometryAndImmediatelyRetireTheFadingAction() {
        render()
        val name = bounds(title)
        val caption = taggedBounds("island-status")
        val artwork = taggedBounds("island-artwork")
        val slot = taggedBounds("island-action-slot")
        val action = taggedBounds("island-connect")
        val start = capture("normal-nearby")
        compose.mainClock.autoAdvance = false
        mutate { nearby.value = false }
        advance(48)
        val middle = capture("normal-connected-middle")
        assertSameBounds(name, bounds(title))
        assertSameBounds(caption, taggedBounds("island-status"))
        assertSameBounds(artwork, taggedBounds("island-artwork"))
        assertSameBounds(slot, taggedBounds("island-action-slot"))
        assertTrue("The scrolling caption must remain above the name", caption.bottom <= name.top + 0.5f)
        compose.onAllNodesWithText("Nearby").assertCountEquals(0)
        compose.onAllNodesWithText("Connected").assertCountEquals(1)
        compose.onNodeWithTag("island-connect").assertDoesNotExist()
        compose.onNodeWithTag("island-connect", useUnmergedTree = true).assertIsNotEnabled()
        val host = taggedBounds("connection-motion-host")
        compose.onNodeWithTag("connection-motion-host").performTouchInput {
            click(action.center - host.topLeft)
        }
        assertEquals("A fading, hidden action cannot initiate takeover", 0, clicks)
        advance(240)
        assertSameBounds(name, bounds(title))
        compose.onNodeWithTag("island-connect", useUnmergedTree = true).assertDoesNotExist()
        val end = capture("normal-connected")
        assertFalse("The ownership change must have an intermediate visual frame", start.sameAs(middle))
        assertFalse("The caption must finish moving after the sampled middle frame", middle.sameAs(end))
        mutate { nearby.value = true }
        advance(240)
        mutate { state.value = IslandConnectState.CONNECTING }
        advance(48)
        capture("normal-connecting-middle")
        compose.onNodeWithTag("island-connect").assertIsNotEnabled()
        compose.onAllNodesWithText("Connect").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Connecting to $title").assertCountEquals(1)
        assertSameBounds(name, bounds(title))
        assertSameBounds(artwork, taggedBounds("island-artwork"))
        mutate { state.value = IslandConnectState.FAILED }
        advance(80)
        capture("normal-retry-middle")
        compose.onAllNodesWithText("Connect").assertCountEquals(0)
        compose.onAllNodesWithText("Retry").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Connecting to $title").assertCountEquals(0)
        assertSameBounds(name, bounds(title))
        advance(240)
        capture("normal-retry")
    }

    @Test fun narrowHeaderKeepsItsGeometryAndOnlyAnnouncesTheLatestInterruptedState() {
        settings.value = settings.value.copy(expandedWidth = 840f, expandedHeight = 900f)
        fontScale.value = 1.3f
        render()
        val name = bounds(title)
        val caption = taggedBounds("island-status")
        val artwork = taggedBounds("island-artwork")
        val slot = taggedBounds("island-action-slot")
        assertEquals(144f, slot.width, 0.5f)
        assertTrue(name.right <= slot.left + 0.5f)
        capture("narrow-nearby")
        compose.mainClock.autoAdvance = false
        mutate { state.value = IslandConnectState.CONNECTING }
        advance(48)
        capture("narrow-connecting-middle")
        compose.onNodeWithTag("island-connect").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Connecting to $title").assertExists()
        compose.onAllNodesWithText("Nearby").assertCountEquals(0)
        compose.onAllNodesWithText("Connecting…").assertCountEquals(1)
        assertSameBounds(name, bounds(title))
        assertSameBounds(caption, taggedBounds("island-status"))
        assertSameBounds(artwork, taggedBounds("island-artwork"))
        assertSameBounds(slot, taggedBounds("island-action-slot"))
        mutate { state.value = IslandConnectState.FAILED }
        advance(80)
        capture("narrow-retry-middle")
        compose.onAllNodesWithText("Connecting…").assertCountEquals(0)
        compose.onAllNodesWithText("Couldn’t connect").assertCountEquals(1)
        assertSameBounds(name, bounds(title))
        assertSameBounds(caption, taggedBounds("island-status"))
        assertSameBounds(artwork, taggedBounds("island-artwork"))
        advance(240)
        compose.onNodeWithText("Couldn’t connect").assertIsDisplayed()
        capture("narrow-retry")
    }

    @Test fun removeAnimationsSnapsTheContentToItsLatestStateWithoutChangingNameBounds() {
        motionScale(0f)
        try {
            render()
            val name = bounds(title)
            val action = taggedBounds("island-connect")
            compose.mainClock.autoAdvance = false
            mutate { state.value = IslandConnectState.CONNECTING }
            capture("reduced-motion-connecting")
            compose.onNodeWithText("Connecting…").assertIsDisplayed()
            compose.onNodeWithTag("island-connect").assertIsNotEnabled()
            compose.onAllNodesWithText("Connect", useUnmergedTree = true).assertCountEquals(0)
            mutate { state.value = IslandConnectState.FAILED }
            capture("reduced-motion-retry")
            compose.onNodeWithText("Retry").assertIsDisplayed()
            compose.onAllNodesWithText("Connecting…", useUnmergedTree = true).assertCountEquals(0)
            mutate { nearby.value = false; state.value = IslandConnectState.IDLE }
            capture("reduced-motion-connected")
            val caption = bounds("Connected")
            assertTrue("The latest caption must be placed in its viewport: $caption",
                caption.overlaps(taggedBounds("island-status")))
            compose.onNodeWithText("Connected").assertIsDisplayed()
            compose.onAllNodesWithText("Nearby").assertCountEquals(0)
            compose.onNodeWithTag("island-connect").assertDoesNotExist()
            compose.onNodeWithTag("island-connect", useUnmergedTree = true).assertDoesNotExist()
            assertSameBounds(name, bounds(title))
            val host = taggedBounds("connection-motion-host")
            compose.onNodeWithTag("connection-motion-host").performTouchInput {
                click(action.center - host.topLeft)
            }
            assertEquals("The removed action cannot initiate takeover", 0, clicks)
        } finally { motionScale(1f) }
    }

    @Test fun smallFontKeepsTheIconAndSpinnerViewportAtFullSize() {
        settings.value = settings.value.copy(expandedWidth = 840f)
        fontScale.value = 0.85f
        render()
        val name = bounds(title)
        val content = taggedBounds("island-connect-content", unmerged = true)
        assertEquals("The 20dp icon must fit even when text is smaller", 60f, content.height, 0.5f)
        capture("small-font-nearby")
        compose.runOnIdle { state.value = IslandConnectState.CONNECTING }
        compose.waitForIdle()
        assertSameBounds(content, taggedBounds("island-connect-content", unmerged = true))
        assertSameBounds(name, bounds(title))
        compose.onNodeWithTag("island-connect").assertIsNotEnabled()
        capture("small-font-connecting")
    }

    @Test fun batteryFreshnessAndConnectionUpdatesKeepOneStableLayoutAtPixelSize() {
        batteryFreshnessKeepsGeometry(1f)
    }

    @Test fun largerTextKeepsTheSameLayoutAcrossLiveMixedCachedAndMissingReadings() {
        batteryFreshnessKeepsGeometry(1.3f)
    }

    @Test fun twoHundredPercentTextKeepsVisibleContentAndFullSizeActionsAcrossFreshnessChanges() {
        batteryFreshnessKeepsGeometry(2f)
    }

    @Test @Config(qualifiers = "w998dp-h360dp-xxhdpi")
    fun shortLandscapeKeepsTheShellOnScreenAndScrollsLargeTextControlsIntoView() {
        settings.value = settings.value.copy(showListeningModeControl = true)
        fontScale.value = 2f
        val placement = islandPlacement(android.graphics.Rect(0, 0, 2992, 960), Insets.of(0, 0, 0, 72),
            RectF(1472f, 18f, 1520f, 114f), 3f, settings.value)
        settings.value = settings.value.copy(expandedWidth = placement.expandedWidth.toFloat(),
            expandedHeight = placement.expandedHeight.toFloat())
        render(maximumHeight = placement.maximumHeight, hostHeight = (placement.maximumHeight / 3f).dp,
            cameraTop = placement.contentTop)
        val shell = taggedBounds("island-shell")
        assertTrue("The final content minimum must respect the available display height",
            shell.height <= placement.maximumHeight + 0.5f)
        compose.onNodeWithText(title).assertIsDisplayed()
        val button = taggedBounds("island-connect")
        assertTrue("Large text cannot shrink the Connect target", button.width >= 144f && button.height >= 144f)
        assertTrue("The header must remain camera-safe", button.top >= shell.top + placement.contentTop - 0.5f)
        capture("landscape-large-text-top")
        compose.onNodeWithTag("island-listening-controls").performScrollTo().assertIsDisplayed()
        val controls = taggedBounds("island-listening-controls")
        assertTrue("Listening controls must scroll above the bottom inset", controls.bottom <= shell.bottom + 0.5f)
        assertTrue("Listening controls must not scroll behind the camera", controls.top >= shell.top + placement.contentTop - 0.5f)
        assertSameBounds(shell, taggedBounds("island-shell"))
        capture("landscape-large-text-controls")
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("island-connect").assertIsDisplayed()
    }

    private fun batteryFreshnessKeepsGeometry(scale: Float) {
        fontScale.value = scale
        batteryNow.value = 600_000L
        val current = batteries.value.map { it.copy(observedAt = batteryNow.value) }.toSet()
        val stages = linkedMapOf(
            "live" to current,
            "case-grace" to current.map { if (it.component == BatteryComponent.CASE)
                it.copy(status = BatteryStatus.DISCONNECTED, present = false) else it }.toSet(),
            "mixed" to current.map { if (it.component == BatteryComponent.CASE) it.copy(observedAt = 1_000L) else it }.toSet(),
            "one-bud-in-case" to current.map { if (it.component == BatteryComponent.RIGHT)
                it.copy(status = BatteryStatus.DISCONNECTED, present = false, observedAt = 1_000L) else it }.toSet(),
            "shared-age" to current.map { it.copy(observedAt = 1_000L) }.toSet(),
            "different-ages" to current.map { it.copy(observedAt = when (it.component) {
                BatteryComponent.LEFT -> 300_000L
                BatteryComponent.RIGHT -> 420_000L
                else -> 1_000L
            }) }.toSet(),
            "missing-case" to current.filterNot { it.component == BatteryComponent.CASE }.toSet(),
            "missing" to emptySet(),
        )
        batteries.value = current
        render()
        for (style in listOf(IslandBatteryStyle.CIRCLE_TEXT, IslandBatteryStyle.CIRCLE, IslandBatteryStyle.TEXT)) {
            mutate { settings.value = settings.value.copy(expandedBatteryStyle = style); batteries.value = current; nearby.value = true }
            advance(240)
            compose.onNodeWithText(title).assertIsDisplayed()
            val button = taggedBounds("island-connect")
            assertTrue("Every text scale needs a complete 48dp action", button.height >= 144f && button.width >= 144f)
            val baseline = stableBatteryBounds()
            for ((label, readings) in stages) {
                mutate { batteries.value = readings; nearby.value = label == "live" }
                advance(240)
                val actual = stableBatteryBounds()
                for ((tag, expected) in baseline) assertSameBounds(expected, actual.getValue(tag), "$scale/$style/$label: $tag")
                val caption = taggedBounds("island-status")
                val name = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val cells = listOf("left", "right", "case").map { taggedBounds("island-battery-$it") }
                assertTrue("Freshness must not move the status into the name", caption.bottom <= name.top + 0.5f)
                assertTrue("The header must remain separate from the batteries", name.bottom <= cells.minOf { it.top } + 0.5f)
                if (label == "live") {
                    compose.onNodeWithTag("island-connect").assertIsDisplayed()
                    val action = taggedBounds("island-connect")
                    assertTrue("Freshness must preserve a complete 48dp action", action.height >= 144f && action.width >= 144f)
                }
                if (style == IslandBatteryStyle.CIRCLE_TEXT) capture("freshness-${scale}x-$label")
                if (label == "case-grace") compose.onNodeWithContentDescription("Case, 90%, Current").assertExists()
                if (label == "mixed") compose.onNodeWithContentDescription("Case, 90%, Cached, Last seen 9m ago").assertExists()
                if (label == "missing-case") compose.onNodeWithContentDescription("Case, —, Unavailable").assertExists()
            }
        }
    }

    private fun stableBatteryBounds(): Map<String, Rect> =
        (listOf("island-shell", "island-header", "island-status", "island-artwork", "island-action-slot") +
            listOf("left", "right", "case").map { "island-battery-$it" }).associateWith { taggedBounds(it) } +
            ("title" to bounds(title))

    private fun render(maximumHeight: Int = Int.MAX_VALUE, hostHeight: Dp = 310.dp, cameraTop: Int = 150) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, fontScale.value)) {
                LibrePodsTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(416.dp, hostHeight).background(Color(0xFF666666))
                        .testTag("connection-motion-host"), contentAlignment = Alignment.TopCenter) {
                        IslandContent(name = title, type = IslandType.CONNECTED, batteryLevel = 80,
                            reversed = false, otherDeviceName = null, islandSettings = settings.value,
                            islandState = IslandState.EXPANDED, showOffListeningMode = false,
                            noiseControlModeValue = 3, onNoiseControlModeChanged = {}, onAdaptiveStrengthChanged = {},
                            model = AirPodsModel.AIRPODS_PRO_3,
                            batteries = batteries.value, batteryNow = batteryNow.value, paired = true, nearby = nearby.value,
                            cameraPaddingPx = cameraTop, cameraCapsuleWidthPx = 132f, maximumHeightPx = maximumHeight,
                            onConnect = { clicks++ }, connectState = state.value)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
    private fun taggedBounds(tag: String, unmerged: Boolean = false) =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot
    private fun mutate(change: () -> Unit) {
        compose.runOnIdle {
            change()
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }
    private fun motionScale(scale: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(null, scale)
    }
    private fun assertSameBounds(expected: Rect, actual: Rect, message: String? = null) {
        assertEquals(message, expected.left, actual.left, 0.5f)
        assertEquals(message, expected.top, actual.top, 0.5f)
        assertEquals(message, expected.right, actual.right, 0.5f)
        assertEquals(message, expected.bottom, actual.bottom, 0.5f)
    }

    private fun capture(label: String): Bitmap {
        val host = compose.onNodeWithTag("connection-motion-host")
        val bitmap = host.captureToImage().asAndroidBitmap()
        val file = File("build/overlay-previews/island-connection-motion-$label.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(file.parentFile, "island-connection-motion-$label-semantics.txt").writeText(
            host.printToString() + "\n\nUnmerged:\n" +
                compose.onNodeWithTag("connection-motion-host", useUnmergedTree = true).printToString())
        return bitmap
    }
}
