/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Xml
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import me.kavishdevar.librepods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingContrastTest {
    @Test fun actionLabelsKeepTheirContrastThroughoutThePressedRippleInBothAppearances() {
        val application = RuntimeEnvironment.getApplication()
        val pressed = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
        for (night in listOf(false, true)) {
            val configuration = Configuration(application.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = ContextThemeWrapper(application.createConfigurationContext(configuration), R.style.Theme_LibrePods_Pairing)
            val ripple = context.getDrawable(R.drawable.pairing_primary_background) as RippleDrawable
            val background = ripple.findDrawableByLayerId(android.R.id.background) as GradientDrawable
            val rippleColor = context.resources.getXml(R.drawable.pairing_primary_background).use { xml ->
                while (xml.eventType != XmlPullParser.START_TAG && xml.eventType != XmlPullParser.END_DOCUMENT) xml.next()
                assertEquals("ripple", xml.name)
                val attributes = context.obtainStyledAttributes(Xml.asAttributeSet(xml), intArrayOf(android.R.attr.color))
                try { requireNotNull(attributes.getColorStateList(0)).getColorForState(pressed, Color.TRANSPARENT) }
                finally { attributes.recycle() }
            }
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try {
                background.setBounds(0, 0, bitmap.width, bitmap.height)
                val canvas = Canvas(bitmap)
                for (complete in listOf(false, true)) {
                    val surface = context.getColor(if (complete) R.color.pairing_action_complete else R.color.pairing_action)
                    val label = context.getColor(if (complete) R.color.pairing_text_primary else R.color.pairing_action_text)
                    if (complete) background.setColor(surface)
                    // Raster the actual XML surface; blend its ripple at every opacity without depending on animation timing.
                    for (opacity in 0..255) {
                        bitmap.eraseColor(Color.TRANSPARENT)
                        background.draw(canvas)
                        if (opacity == 0) assertEquals("The button surface resolves the same palette", surface, bitmap.getPixel(24, 24))
                        canvas.drawColor(ColorUtils.setAlphaComponent(rippleColor, Color.alpha(rippleColor) * opacity / 255))
                        val contrast = ColorUtils.calculateContrast(label, bitmap.getPixel(24, 24))
                        assertTrue("night=$night complete=$complete ripple=$opacity contrast=$contrast", contrast >= 4.5)
                    }
                }
            } finally { bitmap.recycle() }
        }
    }
}
