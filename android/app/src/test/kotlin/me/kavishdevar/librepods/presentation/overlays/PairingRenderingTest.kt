package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
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
import android.widget.Button
import android.widget.ImageView
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
    @Test fun actionsFitAndNativeCardStaysLightInDarkModeAndLargeText() {
        ShadowSettings.setCanDrawOverlays(true)
        for ((night, width, scale) in listOf(Triple(false, 412, 1f), Triple(true, 320, 1.28f))) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h900dp-${if (night) "night" else "notnight"}-xhdpi")
            val app = RuntimeEnvironment.getApplication()
            val configuration = Configuration(app.resources.configuration).apply { fontScale = scale }
            val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_LibrePods)
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            var connected = 0
            var dismissed = 0
            val popup = PopupWindow(context) { dismissed++ }
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                popup.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf("Connect" to { connected++ }))
                val view = popup.javaClass.getDeclaredField("mView").apply { isAccessible = true }.get(popup) as ViewGroup
                shadowOf(Looper.getMainLooper()).idle()
                val px = ((width - 32) * context.resources.displayMetrics.density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertFalse(view.isForceDarkAllowed)
                val lightTheme = context.resources.getIdentifier("isLightTheme", "attr", "android")
                view.context.obtainStyledAttributes(intArrayOf(lightTheme, android.R.attr.forceDarkAllowed)).let {
                    try { assertFalse(it.getBoolean(0, true)); assertFalse(it.getBoolean(1, true)) } finally { it.recycle() }
                }
                assertTrue(Color.luminance(view.findViewById<TextView>(R.id.name).currentTextColor) < 0.2)
                assertNotNull(view.findViewById<ImageView>(R.id.popup_artwork).drawable)
                for (id in listOf(R.id.name, R.id.close_button, R.id.pairing_primary, R.id.pairing_message)) {
                    val child = view.findViewById<View>(id)
                    val bounds = Rect()
                    child.getDrawingRect(bounds)
                    view.offsetDescendantRectToMyCoords(child, bounds)
                    assertTrue("Control clipped: $id", bounds.left >= 0 && bounds.top >= 0 &&
                        bounds.right <= view.width && bounds.bottom <= view.height)
                }
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val output = File("build/overlay-previews/pairing-${if (night) "dark-large" else "light"}.png")
                    output.parentFile.mkdirs()
                    output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                view.findViewById<Button>(R.id.pairing_primary).performClick()
                assertEquals(1, connected)
                popup.showPairingActions(context.getString(R.string.pairing_double_tap), emptyList())
                assertFalse(view.findViewById<Button>(R.id.pairing_primary).isEnabled)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
                assertEquals(0, dismissed)
                popup.close()
                popup.close()
                assertEquals(1, dismissed)
            } finally { popup.forceClose() }
        }
    }
}
