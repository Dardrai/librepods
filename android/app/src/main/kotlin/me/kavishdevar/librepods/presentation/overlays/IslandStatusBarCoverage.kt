/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import kotlin.math.sqrt

internal fun islandCoversStatusBar(widthPx: Float, heightPx: Float, minimumWidthPx: Float,
                                  iconCenterYpx: Float, density: Float, background: Color,
                                  cornerRadiusPx: Float = 0f): Boolean {
    if (!widthPx.isFinite() || !heightPx.isFinite() || !minimumWidthPx.isFinite() ||
        !iconCenterYpx.isFinite() || !density.isFinite() || !cornerRadiusPx.isFinite() ||
        widthPx <= 0f || heightPx <= 0f || minimumWidthPx <= 0f || density <= 0f ||
        cornerRadiusPx < 0f) return false
    val clearance = 8.0 * density
    if (widthPx < minimumWidthPx - 0.5f || iconCenterYpx < clearance ||
        heightPx < iconCenterYpx + clearance || !background.isSpecified ||
        !(background.alpha >= 0.9f && background.luminance() <= 0.18f)) return false
    val radius = minOf(cornerRadiusPx, minOf(widthPx, heightPx) / 2f).toDouble()
    // The nearer end of the icon band has the largest circular-corner inset.
    val edgeDistance = minOf(iconCenterYpx - clearance,
        heightPx.toDouble() - iconCenterYpx - clearance)
    val inset = if (edgeDistance >= radius) 0.0
        else radius - sqrt(edgeDistance * (2.0 * radius - edgeDistance))
    return inset <= clearance
}
