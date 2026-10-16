package me.kavishdevar.librepods.presentation.overlays

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import me.kavishdevar.librepods.R
import org.junit.Assert.*
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PairingRenderingTest {
    private data class Profile(val label: String, val width: Int, val height: Int, val fontScale: Float, val night: Boolean = false)

    @Test fun phasesRemainLegibleAndActionsStayReachableWithLargeTextAndShortScreens() {
        for (profile in listOf(Profile("light", 412, 900, 1f), Profile("dark-large", 320, 900, 1.3f, true),
            Profile("landscape-large", 800, 360, 2f))) {
            val context = context(profile)
            var clicked = 0
            var dismissed = 0
            val popup = PopupWindow(context) { dismissed++ }
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                invoke(popup, "cancelAnimation")
                render(popup, 1f)
                val view = view(popup)
                val button = view.findViewById<Button>(R.id.pairing_primary)
                for (stage in PopupWindow.Stage.entries) {
                    val busy = stage == PopupWindow.Stage.SETUP || stage == PopupWindow.Stage.CONNECTING
                    val message = when (stage) {
                        PopupWindow.Stage.NEARBY -> context.getString(R.string.pairing_nearby_hint)
                        PopupWindow.Stage.SETUP -> context.getString(R.string.pairing_double_tap)
                        PopupWindow.Stage.CONNECTING -> context.getString(R.string.pairing_confirm_system)
                        PopupWindow.Stage.COMPLETE -> context.getString(R.string.pairing_complete)
                        PopupWindow.Stage.RETRY -> context.getString(R.string.pairing_double_tap) + "\n\n" +
                            context.getString(R.string.pairing_bond_failed)
                    }
                    val label = when (stage) {
                        PopupWindow.Stage.COMPLETE -> "Continue"
                        PopupWindow.Stage.RETRY -> context.getString(R.string.pairing_retry)
                        else -> context.getString(R.string.pairing_connect)
                    }
                    val actions: List<Pair<String, () -> Unit>> = if (busy) emptyList() else listOf(label to { clicked += 1 })
                    popup.showPairingActions(message, actions, busy, stage)
                    layout(popup)
                    render(popup, 1f)
                    view.scrollTo(0, 0)
                    assertFalse(view.isForceDarkAllowed)
                    val lightTheme = context.resources.getIdentifier("isLightTheme", "attr", "android")
                    view.context.obtainStyledAttributes(intArrayOf(lightTheme, android.R.attr.forceDarkAllowed)).let {
                        try { assertFalse(it.getBoolean(0, true)); assertFalse(it.getBoolean(1, true)) } finally { it.recycle() }
                    }
                    val name = view.findViewById<TextView>(R.id.name)
                    val body = view.findViewById<TextView>(R.id.pairing_message)
                    assertTrue(Color.luminance(name.currentTextColor) < 0.2)
                    val headline = requireNotNull(name.layout)
                    for (line in 0 until headline.lineCount)
                        assertEquals("The actual pairing headline must remain complete", 0, headline.getEllipsisCount(line))
                    assertTrue("Body text contrast", 1.05 / (Color.luminance(body.currentTextColor) + 0.05) >= 4.5)
                    assertEquals(message, body.text.toString())
                    assertNotNull(view.findViewById<ImageView>(R.id.popup_artwork).drawable)
                    val close = view.findViewById<View>(R.id.close_button)
                    assertTrue(close.width >= 48 * context.resources.displayMetrics.density)
                    assertTrue(close.height >= 48 * context.resources.displayMetrics.density)
                    assertContained(view, close)
                    assertContained(view, name)
                    assertEquals(if (busy) View.GONE else View.VISIBLE, button.visibility)
                    assertEquals(if (busy) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.pairing_progress).visibility)
                    assertEquals(!busy, button.isEnabled)
                    assertEquals(if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                        button.importantForAccessibility)
                    val preview = "pairing-" + profile.label + "-" + stage.name.lowercase()
                    save(view, preview)
                    if (profile.height == 360) {
                        assertTrue("Short screens must scroll", view.getChildAt(0).height > view.height)
                        view.scrollTo(0, (view.getChildAt(0).height - view.height).coerceAtLeast(0))
                        assertTrue(view.scrollY > 0)
                        assertContained(view, view.findViewById(R.id.pairing_actions))
                        if (!busy) assertContained(view, button)
                        save(view, preview + "-scrolled")
                        view.scrollTo(0, 0)
                        assertContained(view, close)
                    } else {
                        for (id in listOf(R.id.popup_artwork_container, R.id.pairing_message, R.id.pairing_actions))
                            assertContained(view, view.findViewById(id))
                        if (!busy) assertContained(view, button)
                    }
                    if (!busy) {
                        assertTrue(button.height >= 48 * context.resources.displayMetrics.density)
                        val before = clicked
                        button.performClick()
                        assertEquals(before + 1, clicked)
                    }
                    val previousHeight = view.height
                    val previousArtwork = bounds(view, view.findViewById(R.id.popup_artwork_container))
                    popup.showPairingActions(message, actions, busy, stage)
                    layout(popup)
                    assertEquals("Duplicate status must retain its layout", previousHeight, view.height)
                    assertEquals(previousArtwork, bounds(view, view.findViewById(R.id.popup_artwork_container)))
                    assertEquals("Status must not replay entrance", 0f, view.translationY, 0f)
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
                assertEquals("Active pairing must not retain invitation expiry", 0, dismissed)
                popup.close()
                popup.close()
                assertEquals(1, dismissed)
                val before = clicked
                button.performClick()
                assertEquals("Closing must retire the action", before, clicked)
            } finally { popup.forceClose() }
        }
    }

    @Test fun theWholeCardMovesWithoutPayloadFadesOrReflow() {
        val profile = Profile("motion", 412, 900, 1f)
        val popup = PopupWindow(context(profile))
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            assertEquals(420L, (field(popup, "animation") as ValueAnimator).duration)
            invoke(popup, "cancelAnimation")
            popup.showPairingActions("AirPods are nearby.", listOf("Connect" to {}), stage = PopupWindow.Stage.NEARBY)
            layout(popup)
            val view = view(popup)
            val payloads = listOf(R.id.name, R.id.popup_artwork_container, R.id.pairing_message, R.id.pairing_actions)
                .map { view.findViewById<View>(it) }
            val settled = payloads.map { bounds(view, it) }
            for ((label, amount) in listOf("enter-start" to 0f, "enter-middle" to .5f, "entered" to 1f,
                "exit-middle" to .5f, "exit-end" to 0f)) {
                render(popup, amount)
                assertEquals((view.measuredHeight + 16 * view.resources.displayMetrics.density) * (1 - amount), view.translationY, 0.5f)
                assertEquals(1f, view.alpha, 0f)
                assertEquals(1f, view.scaleX, 0f)
                assertEquals(1f, view.scaleY, 0f)
                for ((index, child) in payloads.withIndex()) {
                    assertEquals("Content moves with the card", 1f, child.alpha, 0f)
                    assertEquals("Endpoint layout survives motion", settled[index], bounds(view, child))
                }
                saveOnScreen(popup, profile, "pairing-motion-" + label)
            }
            render(popup, 1f)
            popup.close()
            assertEquals(220L, (field(popup, "animation") as ValueAnimator).duration)
        } finally { popup.forceClose() }
    }

    @Test fun reducedMotionSnapsAndDismissesImmediately() {
        val context = context(Profile("reduced", 412, 900, 1f), motionScale = 0f)
        var dismissed = 0
        val popup = PopupWindow(context) { dismissed++ }
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            layout(popup)
            assertNull(field(popup, "animation"))
            assertEquals(0f, view(popup).translationY, 0f)
            assertEquals(1f, view(popup).findViewById<View>(R.id.popup_artwork_container).alpha, 0f)
            popup.close()
            assertFalse(field(popup, "attached") as Boolean)
            assertEquals(1, dismissed)
        } finally { popup.forceClose() }
    }

    private fun context(profile: Profile, motionScale: Float = 1f): Context {
        ShadowSettings.setCanDrawOverlays(true)
        RuntimeEnvironment.setQualifiers("w" + profile.width + "dp-h" + profile.height + "dp-" +
            (if (profile.width > profile.height) "land" else "port") + "-" + (if (profile.night) "night" else "notnight") + "-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { fontScale = profile.fontScale }
        val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_LibrePods)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, motionScale)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(null, motionScale)
        return context
    }

    private fun field(popup: PopupWindow, name: String): Any? = PopupWindow::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(popup)
    private fun view(popup: PopupWindow) = field(popup, "mView") as ScrollView
    private fun invoke(popup: PopupWindow, name: String) = PopupWindow::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(popup)
    private fun render(popup: PopupWindow, amount: Float) = PopupWindow::class.java.getDeclaredMethod("render", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(popup, amount)

    private fun layout(popup: PopupWindow) {
        shadowOf(Looper.getMainLooper()).idle()
        val params = field(popup, "params") as WindowManager.LayoutParams
        view(popup).apply {
            measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    private fun bounds(root: ViewGroup, child: View) = Rect().also {
        child.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(child, it)
        it.offset(-root.scrollX, -root.scrollY)
    }
    private fun assertContained(root: ViewGroup, child: View) {
        val visible = bounds(root, child)
        assertTrue("Control outside viewport: " + child.id + " " + visible + " in " + root.width + "x" + root.height,
            visible.left >= 0 && visible.top >= 0 && visible.right <= root.width && visible.bottom <= root.height)
    }

    private fun save(view: View, label: String) = bitmap(view.width, view.height, label) {
        // View.draw expects the parent renderer to have applied the viewport's scroll offset.
        it.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(it)
    }
    private fun saveOnScreen(popup: PopupWindow, profile: Profile, label: String) {
        val view = view(popup)
        val density = view.resources.displayMetrics.density
        val width = (profile.width * density).toInt()
        val height = (profile.height * density).toInt()
        val params = field(popup, "params") as WindowManager.LayoutParams
        bitmap(width, height, label) {
            it.drawColor(Color.rgb(38, 38, 38))
            it.translate((width - view.width) / 2f, height - params.y - view.height + view.translationY)
            view.draw(it)
        }
    }

    private fun bitmap(width: Int, height: Int, label: String, draw: (Canvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            val output = File("build/overlay-previews/" + label + ".png")
            output.parentFile.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
