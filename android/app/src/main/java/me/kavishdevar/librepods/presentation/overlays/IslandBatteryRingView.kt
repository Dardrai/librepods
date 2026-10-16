/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** A determinate gauge; no continuously running progress indicator or media player. */
class IslandBatteryRingView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()
    private val bolt = Path().apply {
        moveTo(8f, 0f); lineTo(0f, 9f); lineTo(4f, 9f); lineTo(2f, 16f)
        lineTo(10f, 7f); lineTo(6f, 7f); close()
    }
    private var reading = IslandBatteryReading(null, null, false, false)

    internal fun setReading(value: IslandBatteryReading) {
        if (reading == value) return
        reading = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = min(width, height).toFloat()
        val stroke = diameter * 0.09f
        val inset = stroke / 2
        bounds.set((width - diameter) / 2 + inset, (height - diameter) / 2 + inset,
            (width + diameter) / 2 - inset, (height + diameter) / 2 - inset)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = Color.rgb(51, 51, 51)
        canvas.drawOval(bounds, paint)
        paint.color = if (reading.level != null && reading.level!! <= 20) Color.rgb(255, 105, 97) else Color.rgb(48, 209, 88)
        reading.level?.takeIf { it > 0 }?.let { canvas.drawArc(bounds, -90f, it * 3.6f, false, paint) }
        if (reading.charging) {
            paint.style = Paint.Style.FILL
            canvas.save()
            val scale = diameter / 32
            canvas.translate(width / 2f - 5 * scale, height / 2f - 8 * scale)
            canvas.scale(scale, scale)
            canvas.drawPath(bolt, paint)
            canvas.restore()
        }
    }
}
