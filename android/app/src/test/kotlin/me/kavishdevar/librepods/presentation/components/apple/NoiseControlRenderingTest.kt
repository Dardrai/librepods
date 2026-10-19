package me.kavishdevar.librepods.presentation.components.apple

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect as PixelRect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
            Profile("dark-compact-adaptive", true, 280, 4, labels = false),
            Profile("light-compact-transparency", false, 400, 3, labels = false)
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
                        assertTrue(current.name + ": long caption must fit two visible lines, with ellipsis when needed",
                            layouts.all { it.lineCount in 1..2 && it.getLineBottom(it.lineCount - 1) <= it.size.height + 0.5f })
                    } else {
                        assertTrue(current.name + ": short captions must stay on one unclipped line: " + label,
                            layouts.all { it.lineCount == 1 && it.getLineBottom(0) <= it.size.height + 0.5f })
                    }
                }
            }
            capture(current.name)
        }
    }

    @Test fun restingSelectionPreservesTheStationaryGlyphSize() {
        val mode = mutableStateOf(3)
        compose.setContent {
            LibrePodsTheme(darkTheme = false, designSystem = DesignSystem.Apple) {
                Box(Modifier.requiredSize(440.dp, 180.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .testTag("listening-render-host"), contentAlignment = Alignment.Center) {
                    NoiseControlSettings(modifier = Modifier.width(400.dp).height(64.dp),
                        showOffListeningMode = false, showAdaptiveMode = true,
                        noiseControlModeValue = mode.value, onNoiseControlModeChanged = {},
                        adaptiveStrength = 50, onAdaptiveStrengthChanged = {}, showLabels = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        val glyph = compose.onNodeWithTag("noise-control-icon-2", useUnmergedTree = true)
        val host = compose.onNodeWithTag("listening-render-host")
        val fixedBounds = glyph.fetchSemanticsNode().boundsInRoot
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val pixelsPerDp = hostBounds.width / 440f
        assertEquals("The source glyph retains its 32dp layout width", 32f * pixelsPerDp, fixedBounds.width, 0.5f)
        assertEquals("The source glyph retains its 32dp layout height", 32f * pixelsPerDp, fixedBounds.height, 0.5f)

        fun captureGraph(name: String): Bitmap {
            // Prime child layers before capturing the sibling-backed lens in the complete graph.
            glyph.captureToImage()
            compose.onNodeWithTag("noise-control-bubble", useUnmergedTree = true).captureToImage()
            val bitmap = host.captureToImage().asAndroidBitmap()
            save(name, bitmap)
            return bitmap
        }

        val outside = captureGraph("fixed-anc-outside-lens")
        compose.runOnIdle { mode.value = 2 }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals("The selected source icon must not move with the bubble", fixedBounds,
            glyph.fetchSemanticsNode().boundsInRoot)
        val inside = captureGraph("fixed-anc-inside-lens")
        val magnified = coreInkSimilarity(outside, inside, fixedBounds, hostBounds, pixelsPerDp, 1.2f)
        val unscaled = coreInkSimilarity(outside, inside, fixedBounds, hostBounds, pixelsPerDp, 1f)
        val detail = "restingInkDice1.2=$magnified, restingInkDice1.0=$unscaled"
        File("build/listening-previews/fixed-anc-core-metrics.txt").writeText(detail)
        assertTrue("The resting pill keeps the source glyph at its normal size: $detail", unscaled > 0.95f)
        assertTrue("Selecting a mode alone must not apply the held lens enlargement: $detail",
            unscaled > magnified + 0.1f)
    }

    @Test fun nativePressAndDragRefractTheFixedIconRowWithoutMovingItsGlyphs() {
        var ownerView: View? = null
        val modes = mutableListOf<Int>()
        val strengths = mutableListOf<Int>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { ownerView = view }
            LibrePodsTheme(darkTheme = false, designSystem = DesignSystem.Apple) {
                Box(Modifier.requiredSize(440.dp, 180.dp).drawBehind {
                    val cell = 20.dp.toPx()
                    var row = 0
                    var top = 0f
                    while (top < size.height) {
                        var column = 0
                        var left = 0f
                        while (left < size.width) {
                            drawRect(if ((row + column) % 2 == 0) Color(0xFFF0F0F0) else Color(0xFFD4D4D4),
                                topLeft = Offset(left, top), size = Size(cell, cell))
                            left += cell
                            column++
                        }
                        top += cell
                        row++
                    }
                }.testTag("listening-render-host"), contentAlignment = Alignment.Center) {
                    NoiseControlSettings(modifier = Modifier.width(400.dp).height(64.dp),
                        showOffListeningMode = true, noiseControlModeValue = 3,
                        onNoiseControlModeChanged = { modes += it }, adaptiveStrength = 50,
                        onAdaptiveStrengthChanged = { strengths += it }, showLabels = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        val host = compose.onNodeWithTag("listening-render-host")
        val thumb = compose.onNodeWithTag("noise-control-thumb", useUnmergedTree = true)
        val bubble = compose.onNodeWithTag("noise-control-bubble", useUnmergedTree = true)
        fun glyphBounds() = listOf(3, 4, 2).associateWith { mode ->
            compose.onNodeWithTag("noise-control-icon-$mode", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
        }
        val fixedGlyphs = glyphBounds()
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val touchBounds = thumb.fetchSemanticsNode().boundsInRoot
        val pixelsPerDp = hostBounds.width / 440f
        for ((mode, bounds) in fixedGlyphs) {
            assertEquals("Source icon $mode retains its 32dp width", 32f * pixelsPerDp, bounds.width, 0.5f)
            assertEquals("Source icon $mode retains its 32dp height", 32f * pixelsPerDp, bounds.height, 0.5f)
        }
        fun captureGraph(name: String): Bitmap {
            compose.onNodeWithTag("noise-control-icon-3", useUnmergedTree = true).captureToImage()
            bubble.captureToImage()
            return host.captureToImage().asAndroidBitmap().also { save(name, it) }
        }
        var downTime = 0L
        var eventTime = 0L
        var fingerDown = false
        fun dispatch(action: Int, positionInRoot: Offset, requireHandled: Boolean = true) {
            compose.runOnIdle {
                val owner = requireNotNull(ownerView)
                val target = owner.rootView
                val ownerLocation = IntArray(2).also(owner::getLocationInWindow)
                val targetLocation = IntArray(2).also(target::getLocationInWindow)
                eventTime = maxOf(SystemClock.uptimeMillis(), eventTime + 32L)
                if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
                val event = MotionEvent.obtain(downTime, eventTime, action,
                    positionInRoot.x + ownerLocation[0] - targetLocation[0],
                    positionInRoot.y + ownerLocation[1] - targetLocation[1], 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    val handled = target.dispatchTouchEvent(event)
                    if (requireHandled) assertTrue("The Compose owner must handle native touch action $action", handled)
                    fingerDown = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                } finally {
                    event.recycle()
                }
            }
        }
        val initialPosition = touchBounds.center
        var lastPosition = initialPosition
        val resting = captureGraph("native-interaction-resting")
        val restingBounds = bubble.fetchSemanticsNode().boundsInRoot
        val region = Rect(hostBounds.left + 20f * pixelsPerDp, touchBounds.top - 10f * pixelsPerDp,
            hostBounds.right - 20f * pixelsPerDp, touchBounds.bottom + 10f * pixelsPerDp)
        val minimumFringes = 32f * pixelsPerDp * pixelsPerDp
        assertEquals("The resting selection stays within the 56dp pill", 56f * pixelsPerDp,
            restingBounds.height, 0.5f * pixelsPerDp)
        try {
            // Dispatch on the UI thread directly; this bypasses Espresso's API35 input bridge.
            dispatch(MotionEvent.ACTION_DOWN, initialPosition)
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            val pressed = captureGraph("native-interaction-pressed")
            val pressedBounds = bubble.fetchSemanticsNode().boundsInRoot
            assertNoDarkRimBar(resting, restingBounds, hostBounds, pixelsPerDp)
            assertNoDarkRimBar(pressed, pressedBounds, hostBounds, pixelsPerDp)
            assertEquals("Native touch-down expands the actual glass to 78dp", 78f * pixelsPerDp,
                pressedBounds.height, 2f * pixelsPerDp)
            assertEquals("Native touch-down gives the tab bubble the same horizontal expansion",
                restingBounds.width * 78f / 56f, pressedBounds.width, 2f * pixelsPerDp)
            assertEquals("Pressing the lens leaves the source row fixed", fixedGlyphs, glyphBounds())
            assertTrue("A press alone cannot issue mode commands", modes.isEmpty())
            val magnified = coreInkSimilarity(resting, pressed, fixedGlyphs.getValue(3), hostBounds, pixelsPerDp, 1.2f)
            val unscaled = coreInkSimilarity(resting, pressed, fixedGlyphs.getValue(3), hostBounds, pixelsPerDp, 1f)
            assertTrue("The held optical core enlarges the fixed glyph: 1.2x=$magnified, 1x=$unscaled",
                magnified > 0.9f && magnified > unscaled + 0.1f)

            dispatch(MotionEvent.ACTION_UP, initialPosition)
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            val released = captureGraph("native-interaction-released")
            val releasedBounds = bubble.fetchSemanticsNode().boundsInRoot
            assertEquals("Releasing returns the bubble to its resting width", restingBounds.width,
                releasedBounds.width, 0.5f * pixelsPerDp)
            assertEquals("Releasing returns the bubble to its resting height", restingBounds.height,
                releasedBounds.height, 0.5f * pixelsPerDp)
            assertEquals("Releasing does not shift the selection", restingBounds.center.x,
                releasedBounds.center.x, 0.5f * pixelsPerDp)
            assertEquals("Releasing the lens leaves the source row fixed", fixedGlyphs, glyphBounds())
            assertNoDarkRimBar(released, releasedBounds, hostBounds, pixelsPerDp)
            val releasedSimilarity = coreInkSimilarity(resting, released, fixedGlyphs.getValue(3), hostBounds, pixelsPerDp, 1f)
            assertTrue("Releasing removes the magnification and restores the original glyph: $releasedSimilarity",
                releasedSimilarity > 0.98f)
            val releasedFringes = chromaticPixels(released, region, hostBounds)
            assertTrue("Releasing removes the raised lens refraction: fringes=$releasedFringes",
                releasedFringes < minimumFringes)

            dispatch(MotionEvent.ACTION_DOWN, initialPosition)
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            lastPosition = initialPosition + Offset(20f * pixelsPerDp, 0f)
            dispatch(MotionEvent.ACTION_MOVE, lastPosition)
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            lastPosition = initialPosition + Offset(80f * pixelsPerDp, 0f)
            dispatch(MotionEvent.ACTION_MOVE, lastPosition)
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            val dragged = captureGraph("native-interaction-mid-drag")
            val draggedBounds = bubble.fetchSemanticsNode().boundsInRoot
            assertEquals("Dragging leaves every source glyph fixed", fixedGlyphs, glyphBounds())
            assertTrue("The native drag moves the lens across the captured row",
                draggedBounds.left > pressedBounds.left + 30f * pixelsPerDp)
            assertTrue("The glass remains expanded during a native drag", draggedBounds.height > 64f * pixelsPerDp)

            val restingFringes = chromaticPixels(resting, region, hostBounds)
            val pressedFringes = chromaticPixels(pressed, region, hostBounds)
            val draggedFringes = chromaticPixels(dragged, region, hostBounds)
            val changed = changedPixels(resting, dragged, region, hostBounds)
            val cell = 20f * pixelsPerDp
            val column = floor((draggedBounds.center.x - hostBounds.left) / cell)
            val currentCellX = hostBounds.left + (column + 0.5f) * cell
            val adjacentCellX = currentCellX + if (currentCellX < draggedBounds.center.x) cell else -cell
            val cellY = hostBounds.top + (floor((draggedBounds.center.y - hostBounds.top) / cell) + 0.5f) * cell
            val checkerContrast = abs(patchBrightness(dragged, Offset(currentCellX, cellY), 2f * pixelsPerDp, hostBounds) -
                patchBrightness(dragged, Offset(adjacentCellX, cellY), 2f * pixelsPerDp, hostBounds))
            val detail = "restingFringes=$restingFringes, pressedFringes=$pressedFringes, releasedFringes=$releasedFringes, draggedFringes=$draggedFringes, heldInkDice1.2=$magnified, heldInkDice1.0=$unscaled, releasedInkDice1.0=$releasedSimilarity, changedPixels=$changed, emptyCoreCheckerContrast=$checkerContrast"
            File("build/listening-previews/native-interaction-metrics.txt").writeText(detail)
            assertTrue("The lens changes rendered pixels across the fixed row: $detail", changed > 20f * pixelsPerDp * pixelsPerDp)
            assertTrue("The resting pill does not retain the held lens refraction: $detail",
                restingFringes < minimumFringes)
            assertTrue("The held lens shows chromatic edges as it crosses the fixed icons: $detail",
                draggedFringes >= minimumFringes)
            assertTrue("The empty lens core must preserve the underlying checker contrast: $detail",
                checkerContrast >= 12f)
            dispatch(MotionEvent.ACTION_UP, lastPosition)
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            assertEquals("Release leaves the source glyph layout unchanged", fixedGlyphs, glyphBounds())
            assertTrue("This fixed Transparency fixture cannot send Adaptive strength commands", strengths.isEmpty())
        } finally {
            if (fingerDown) dispatch(MotionEvent.ACTION_CANCEL, lastPosition, requireHandled = false)
        }
    }

    private fun assertNoDarkRimBar(bitmap: Bitmap, lens: Rect, host: Rect, pixelsPerDp: Float) {
        // The center of either rim must not repeat the icon as a black selection underline.
        for (edgeY in listOf(lens.top, lens.bottom - 4f * pixelsPerDp)) {
            val area = captureRegion(bitmap, Rect(lens.center.x - 16f * pixelsPerDp, edgeY,
                lens.center.x + 16f * pixelsPerDp, edgeY + 4f * pixelsPerDp), host)
            var longest = 0
            for (y in area.top until area.bottom) {
                var run = 0
                for (x in area.left until area.right) {
                    val color = bitmap.getPixel(x, y)
                    run = if (maxOf(AndroidColor.red(color), AndroidColor.green(color),
                            AndroidColor.blue(color)) < 100) run + 1 else 0
                    longest = maxOf(longest, run)
                }
            }
            assertTrue("Glass rim must not contain a dark underline: run=$longest",
                longest <= 3f * pixelsPerDp)
        }
    }

    private fun captureRegion(bitmap: Bitmap, region: Rect, host: Rect): PixelRect = PixelRect(
        floor((region.left - host.left) * bitmap.width / host.width).toInt().coerceIn(0, bitmap.width),
        floor((region.top - host.top) * bitmap.height / host.height).toInt().coerceIn(0, bitmap.height),
        ceil((region.right - host.left) * bitmap.width / host.width).toInt().coerceIn(0, bitmap.width),
        ceil((region.bottom - host.top) * bitmap.height / host.height).toInt().coerceIn(0, bitmap.height))

    private fun chromaticPixels(bitmap: Bitmap, region: Rect, host: Rect): Int {
        val area = captureRegion(bitmap, region, host)
        var count = 0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val color = bitmap.getPixel(x, y)
            val red = AndroidColor.red(color)
            val green = AndroidColor.green(color)
            val blue = AndroidColor.blue(color)
            if (AndroidColor.alpha(color) >= 192 && maxOf(red, green, blue) - minOf(red, green, blue) > 18) count++
        }
        return count
    }

    private fun changedPixels(first: Bitmap, second: Bitmap, region: Rect, host: Rect): Int {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val area = captureRegion(first, region, host)
        var count = 0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val a = first.getPixel(x, y)
            val b = second.getPixel(x, y)
            val delta = abs(AndroidColor.red(a) - AndroidColor.red(b)) +
                abs(AndroidColor.green(a) - AndroidColor.green(b)) + abs(AndroidColor.blue(a) - AndroidColor.blue(b))
            if (delta > 36) count++
        }
        return count
    }

    private fun patchBrightness(bitmap: Bitmap, center: Offset, radius: Float, host: Rect): Float {
        val area = captureRegion(bitmap, Rect(center.x - radius, center.y - radius,
            center.x + radius, center.y + radius), host)
        var brightness = 0L
        var count = 0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val color = bitmap.getPixel(x, y)
            brightness += AndroidColor.red(color) + AndroidColor.green(color) + AndroidColor.blue(color)
            count += 3
        }
        assertTrue("The checker patch must contain pixels", count > 0)
        return brightness.toFloat() / count
    }

    private fun coreInkSimilarity(outside: Bitmap, inside: Bitmap, glyph: Rect, host: Rect,
        pixelsPerDp: Float, magnification: Float): Float {
        assertEquals(outside.width, inside.width)
        assertEquals(outside.height, inside.height)
        val scaleX = inside.width / host.width
        val scaleY = inside.height / host.height
        val centerX = (glyph.center.x - host.left) * scaleX
        val centerY = (glyph.center.y - host.top) * scaleY
        // This core stays inside the flat center, excluding the held lens's optical rim.
        val radiusX = 20f * pixelsPerDp * scaleX
        val radiusY = 14f * pixelsPerDp * scaleY
        val roi = PixelRect(floor(centerX - radiusX).toInt().coerceAtLeast(0),
            floor(centerY - radiusY).toInt().coerceAtLeast(0),
            ceil(centerX + radiusX).toInt().coerceAtMost(inside.width),
            ceil(centerY + radiusY).toInt().coerceAtMost(inside.height))
        var intersection = 0
        var totalInk = 0
        for (y in roi.top until roi.bottom) for (x in roi.left until roi.right) {
            val expected = sampledInk(outside, centerX + (x + 0.5f - centerX) / magnification - 0.5f,
                centerY + (y + 0.5f - centerY) / magnification - 0.5f)
            val actual = sampledInk(inside, x.toFloat(), y.toFloat())
            if (expected) totalInk++
            if (actual) totalInk++
            if (expected && actual) intersection++
        }
        assertTrue("The flat core must contain visible glyph ink", totalInk > 0)
        return 2f * intersection / totalInk
    }

    private fun sampledInk(bitmap: Bitmap, x: Float, y: Float): Boolean {
        val left = floor(x).toInt().coerceIn(0, bitmap.width - 2)
        val top = floor(y).toInt().coerceIn(0, bitmap.height - 2)
        val fractionX = (x - left).coerceIn(0f, 1f)
        val fractionY = (y - top).coerceIn(0f, 1f)
        val a = bitmap.getPixel(left, top)
        val b = bitmap.getPixel(left + 1, top)
        val c = bitmap.getPixel(left, top + 1)
        val d = bitmap.getPixel(left + 1, top + 1)
        // Bilinear sampling accounts for antialiasing and fractional pixel centers in the 1.2x scale.
        fun channel(read: (Int) -> Int): Float {
            val upper = read(a) * (1f - fractionX) + read(b) * fractionX
            val lower = read(c) * (1f - fractionX) + read(d) * fractionX
            return upper * (1f - fractionY) + lower * fractionY
        }
        return channel(AndroidColor::alpha) >= 192f &&
            maxOf(channel(AndroidColor::red), channel(AndroidColor::green), channel(AndroidColor::blue)) < 128f
    }

    private fun capture(name: String) {
        save(name, compose.onNodeWithTag("listening-render-host").captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val output = File("build/listening-previews/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
