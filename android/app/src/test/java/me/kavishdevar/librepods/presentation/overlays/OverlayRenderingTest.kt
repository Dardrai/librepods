/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.DisplayCutout
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayRenderingTest {
    @Before fun disableAnimations() {
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun pairingBindsActionsAndKeepsItsWhiteCardInBothThemes() {
        for ((night, width, fontScale) in listOf(Triple(false, 412, 1f), Triple(true, 320, 1.28f))) {
            val context = renderContext(night, width, fontScale)
            val lightTheme = context.resources.getIdentifier("isLightTheme", "attr", "android")
            assertNotEquals("Framework light-theme attribute is missing", 0, lightTheme)
            val themeFlags = intArrayOf(android.R.attr.forceDarkAllowed, lightTheme)
            fun flags(target: Context): List<Boolean> {
                val values = target.obtainStyledAttributes(themeFlags)
                return try { themeFlags.indices.map { values.getBoolean(it, true) } } finally { values.recycle() }
            }
            val originalFlags = flags(context)
            var connected = 0
            var dismissed = 0
            val popup = PopupWindow(context) { dismissed++ }
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                popup.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf(
                    context.getString(R.string.pairing_connect) to { connected++ }
                ))
                val view = root(popup, "mView")
                val name = view.findViewById<TextView>(R.id.name)
                val button = view.findViewById<Button>(R.id.pairing_primary)
                assertFalse("Pairing card permits hardware color inversion", view.isForceDarkAllowed)
                assertEquals(listOf(false, false), flags(view.context))
                assertEquals("Pairing theme changed the app's theme", originalFlags, flags(context))
                assertEquals("AirPods Pro 3", name.text.toString())
                assertTrue(Color.luminance(name.currentTextColor) < 0.2)
                assertNotNull(view.findViewById<ImageView>(R.id.popup_artwork).drawable)
                assertTrue(button.isEnabled)
                render(view, "pairing-${if (night) "dark-narrow-large" else "light"}", width)
                assertPairingControlsFit(view)
                button.performClick()
                assertEquals(1, connected)

                val gesture = context.getString(R.string.pairing_double_tap)
                popup.showPairingActions(gesture, emptyList())
                assertEquals(gesture, view.findViewById<TextView>(R.id.pairing_message).text.toString())
                assertFalse(button.isEnabled)
                render(view, "pairing-searching-${if (night) "dark-narrow-large" else "light"}", width)
                assertPairingControlsFit(view)
                popup.close()
                popup.close()
                assertEquals(1, dismissed)
            } finally {
                popup.forceClose()
            }
        }
    }

    @Test fun invitationExpiresOnceWhileConnectKeepsPairingProgressVisible() {
        val context = renderContext(night = false, widthDp = 412)
        var expired = 0
        val invitation = PopupWindow(context) { expired++ }
        try {
            invitation.openPairing("AirPods Pro 3", 0x2720)
            invitation.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf(
                context.getString(R.string.pairing_connect) to {}
            ))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11_999))
            assertEquals(0, expired)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(1, expired)
            assertFalse(field(invitation, "attached") as Boolean)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertEquals(1, expired)
        } finally {
            invitation.forceClose()
        }
        assertEquals(1, expired)

        var dismissed = 0
        val pairing = PopupWindow(context) { dismissed++ }
        try {
            pairing.openPairing("AirPods Pro 3", 0x2720)
            pairing.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf(
                context.getString(R.string.pairing_connect) to {
                    pairing.showPairingActions(context.getString(R.string.pairing_double_tap), emptyList())
                }
            ))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            val view = root(pairing, "mView")
            view.findViewById<Button>(R.id.pairing_primary).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
            assertEquals(0, dismissed)
            assertTrue(field(pairing, "attached") as Boolean)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.pairing_progress).visibility)
            assertFalse(view.findViewById<Button>(R.id.pairing_primary).isEnabled)
            pairing.close()
            assertEquals(1, dismissed)
        } finally {
            pairing.forceClose()
        }
        assertEquals(1, dismissed)
    }

    @Test fun islandShowsIndependentFreshStaleAndUnknownReadings() {
        val context = renderContext(night = true, widthDp = 320, fontScale = 1.28f)
        val now = System.currentTimeMillis()
        val batteries = listOf(
            Battery(BatteryComponent.LEFT, 86, BatteryStatus.NOT_CHARGING, now, true),
            Battery(BatteryComponent.RIGHT, 82, BatteryStatus.NOT_CHARGING, now, true),
            Battery(BatteryComponent.CASE, 74, BatteryStatus.CHARGING, now, true)
        )
        val island = IslandWindow(context)
        try {
            val snapshot = IslandSnapshot("test-pair", "AirPods Pro 3", "A3063", batteries)
            island.show(snapshot)
            val view = root(island, "view")
            render(view, "island-fresh-narrow-large", 320)
            assertEquals(1f, view.findViewById<View>(R.id.island_header).alpha, 0.001f)
            assertEquals(1f, view.findViewById<View>(R.id.island_battery_row).alpha, 0.001f)
            assertEquals("86%", view.findViewById<TextView>(R.id.island_left_value).text.toString())
            assertEquals("82%", view.findViewById<TextView>(R.id.island_right_value).text.toString())
            assertEquals("74%", view.findViewById<TextView>(R.id.island_case_value).text.toString())
            assertNotNull(view.findViewById<ImageView>(R.id.island_artwork).drawable)

            island.show(snapshot.copy(address = "stale-pair", batteries = batteries.map {
                it.copy(updatedAt = now - 120_000, live = false)
            }))
            render(view, "island-stale-narrow-large", 320)
            assertEquals("74%", view.findViewById<TextView>(R.id.island_case_value).text.toString())
            assertTrue(view.findViewById<View>(R.id.island_case_cell).alpha < 1f)

            island.show(snapshot.copy(address = "unknown-pair", batteries = emptyList()))
            render(view, "island-unknown-narrow-large", 320)
            assertEquals("—", view.findViewById<TextView>(R.id.island_left_value).text.toString())
            assertEquals("—", view.findViewById<TextView>(R.id.island_right_value).text.toString())
            assertEquals("—", view.findViewById<TextView>(R.id.island_case_value).text.toString())
        } finally {
            island.forceClose()
        }
        val headphones = IslandWindow(renderContext(night = false, widthDp = 412))
        try {
            headphones.show(IslandSnapshot("headphones", "AirPods Max", "A2096", listOf(
                Battery(BatteryComponent.LEFT, 65, BatteryStatus.NOT_CHARGING)
            )))
            val view = root(headphones, "view")
            render(view, "island-headphones-light", 412)
            assertEquals(View.GONE, view.findViewById<View>(R.id.island_right_cell).visibility)
            assertEquals(View.GONE, view.findViewById<View>(R.id.island_case_cell).visibility)
            assertEquals("65%", view.findViewById<TextView>(R.id.island_left_value).text.toString())
        } finally {
            headphones.forceClose()
        }
    }

    @Test fun passiveBatteryUpdatesKeepTheOriginalDismissalAndDevice() {
        val island = IslandWindow(renderContext(night = false, widthDp = 412))
        try {
            val snapshot = IslandSnapshot("first-pair", "My AirPods", "A3063")
            island.show(snapshot)
            val view = root(island, "view")
            val state = field(island, "state") as IslandState
            val deadline = state.dismissAt
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            island.updateSnapshot(snapshot.copy(batteries = listOf(
                Battery(BatteryComponent.LEFT, 0, BatteryStatus.NOT_CHARGING)
            )))
            assertEquals(deadline, state.dismissAt)
            assertEquals("0%", view.findViewById<TextView>(R.id.island_left_value).text.toString())
            island.updateSnapshot(snapshot.copy(address = "second-pair", name = "Other AirPods"))
            assertEquals("My AirPods", view.findViewById<TextView>(R.id.island_device_name).text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(deadline - SystemClock.uptimeMillis() + 1))
            assertFalse(island.isVisible)
        } finally {
            island.forceClose()
        }
    }

    @Test fun islandWrapsTheCameraPathInsteadOfTheLargerCutoutSafeArea() {
        val context = renderContext(night = false, widthDp = 448)
        val camera = Path().apply { addCircle(448f, 67f, 32f, Path.Direction.CW) }
        val cutout = DisplayCutout.Builder()
            .setBoundingRectTop(Rect(412, 0, 484, 134))
            .setCutoutPath(camera)
            .setSafeInsets(Insets.of(0, 134, 0, 0))
            .build()
        val island = islandWithMetrics(context, Rect(0, 0, 896, 1800), cutout, Insets.of(0, 134, 0, 0))
        try {
            island.show(IslandSnapshot("camera-fixture", "AirPods Pro 3", "A3063"))
            val view = root(island, "view")
            val params = view.layoutParams as WindowManager.LayoutParams
            assertEquals(0, params.fitInsetsTypes)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
            assertEquals(23, params.y)
            assertEquals(100, view.paddingTop)
            render(view, "island-centered-camera", 448)
            renderPlacement(view, params, 896, 1800, camera)

            island.javaClass.getDeclaredField("expansion").apply { isAccessible = true }.setFloat(island, 0f)
            val outline = Outline()
            view.outlineProvider.getOutline(view, outline)
            val bounds = Rect()
            assertTrue(outline.getRect(bounds))
            assertEquals(192, bounds.width())
            assertEquals(88, bounds.height())
            val cameraBounds = RectF().apply { camera.computeBounds(this, true) }
            val screenOutline = RectF(bounds).apply { offset((896 - view.width) / 2f, params.y.toFloat()) }
            assertTrue("Collapsed island must surround the camera", screenOutline.contains(cameraBounds))
        } finally {
            island.forceClose()
        }
    }

    @Test fun noCameraAndLandscapeCutoutsKeepTheIslandBelowTheSafeTop() {
        val context = renderContext(night = false, widthDp = 448)
        val landscapeCutout = DisplayCutout.Builder()
            .setBoundingRectLeft(Rect(0, 412, 134, 484))
            .setCutoutPath(Path().apply { addCircle(67f, 448f, 32f, Path.Direction.CW) })
            .setSafeInsets(Insets.of(134, 0, 0, 0))
            .build()
        for ((bounds, cutout, insets) in listOf(
            Triple(Rect(0, 0, 896, 1800), null, Insets.of(0, 66, 0, 0)),
            Triple(Rect(0, 0, 1800, 896), landscapeCutout, Insets.of(134, 66, 0, 0))
        )) {
            val island = islandWithMetrics(context, bounds, cutout, insets)
            try {
                island.show(IslandSnapshot("safe-top-fixture", "AirPods Pro 3", "A3063"))
                val view = root(island, "view")
                val params = view.layoutParams as WindowManager.LayoutParams
                assertEquals(0, params.fitInsetsTypes)
                assertEquals(90, params.y)
                assertEquals(36, view.paddingTop)
            } finally {
                island.forceClose()
            }
        }
    }

    @Test fun newEventReversesDismissalAndForceCloseRetiresEntrance() {
        val context = renderContext(night = false, widthDp = 412)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val island = IslandWindow(context)
        try {
            val snapshot = IslandSnapshot("test-pair", "AirPods Pro 3", "A3063")
            island.show(snapshot)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            island.close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            island.show(snapshot, message = "Adaptive")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertTrue(island.isVisible)
            assertEquals("Adaptive", root(island, "view").findViewById<TextView>(R.id.island_connected_text).text.toString())
            island.forceClose()
            island.show(snapshot)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            island.forceClose()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
            assertFalse(island.isVisible)
            assertEquals(0L, (field(island, "state") as IslandState).dismissAt)
        } finally {
            island.forceClose()
        }
    }

    private fun renderContext(night: Boolean, widthDp: Int, fontScale: Float = 1f): Context {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h900dp-${if (night) "night" else "notnight"}-xhdpi")
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply { this.fontScale = fontScale }
        return ContextThemeWrapper(application.createConfigurationContext(configuration), R.style.Theme_LibrePods)
    }

    private fun field(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)!!

    private fun root(instance: Any, name: String) = field(instance, name) as View

    private fun assertPairingControlsFit(view: View) {
        for (id in listOf(R.id.name, R.id.pairing_message, R.id.pairing_primary, R.id.close_button)) {
            val control = view.findViewById<View>(id)
            val bounds = Rect()
            control.getDrawingRect(bounds)
            (view as ViewGroup).offsetDescendantRectToMyCoords(control, bounds)
            assertTrue("Pairing control $id is clipped", bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= view.width && bounds.bottom <= view.height)
        }
    }

    private fun islandWithMetrics(context: Context, bounds: Rect, cutout: DisplayCutout?, safeInsets: Insets): IslandWindow {
        val insets = WindowInsets.Builder()
            .setDisplayCutout(cutout)
            .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(), safeInsets)
            .build()
        val metrics = WindowMetrics(bounds, insets)
        val delegate = context.getSystemService(WindowManager::class.java)
        val manager = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            if (method.name == "getCurrentWindowMetrics") metrics else method.invoke(delegate, *(args ?: emptyArray()))
        } as WindowManager
        return IslandWindow(context).also { island ->
            island.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }.set(island, manager)
        }
    }

    private fun renderPlacement(view: View, params: WindowManager.LayoutParams, width: Int, height: Int, camera: Path) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(222, 229, 233))
            canvas.save()
            canvas.translate((width - view.width) / 2f, params.y.toFloat())
            val outline = Outline()
            view.outlineProvider.getOutline(view, outline)
            val bounds = Rect()
            if (outline.getRect(bounds)) canvas.clipPath(Path().apply {
                addRoundRect(RectF(bounds), outline.radius, outline.radius, Path.Direction.CW)
            })
            view.draw(canvas)
            canvas.restore()
            canvas.drawPath(camera, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY })
            val output = File("build/overlay-previews", "island-camera-synthetic-placement.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(view: View, name: String, widthDp: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        val density = view.resources.displayMetrics.density
        val width = view.layoutParams?.width?.takeIf { it > 0 } ?: ((widthDp - 32) * density).roundToInt()
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((850 * density).roundToInt(), View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        assertTrue("Overlay has no content", view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (view.clipToOutline) {
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            val bounds = Rect()
            if (outline.getRect(bounds)) {
                canvas.clipPath(Path().apply {
                    addRoundRect(RectF(bounds), outline.radius, outline.radius, Path.Direction.CW)
                })
            }
        }
        view.draw(canvas)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native renderer produced no artwork or text", pixels.distinct().size > 16)
            view.findViewById<View>(R.id.island_header)?.let { header ->
                var bright = 0
                for (y in header.top.coerceAtLeast(0) until header.bottom.coerceAtMost(bitmap.height)) {
                    for (x in header.left.coerceAtLeast(0) until header.right.coerceAtMost(bitmap.width)) {
                        val pixel = pixels[y * bitmap.width + x]
                        if (Color.alpha(pixel) > 128 && Color.red(pixel) > 160 &&
                            Color.green(pixel) > 160 && Color.blue(pixel) > 160) bright++
                    }
                }
                assertTrue("Island header artwork and text were not rendered", bright > 100)
            }
            val output = File("build/overlay-previews", "$name.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
