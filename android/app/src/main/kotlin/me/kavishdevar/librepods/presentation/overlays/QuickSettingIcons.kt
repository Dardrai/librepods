/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object QuickSettingIcons {
    val Conversation = outline("Conversation") {
        moveTo(5f, 4f); lineTo(19f, 4f); curveTo(21f, 4f, 21f, 6f, 21f, 8f)
        lineTo(21f, 13f); curveTo(21f, 16f, 20f, 17f, 17f, 17f)
        lineTo(10f, 17f); lineTo(5f, 21f); lineTo(5f, 17f)
        curveTo(3f, 17f, 3f, 15f, 3f, 13f); lineTo(3f, 8f); curveTo(3f, 5f, 3f, 4f, 5f, 4f); close()
        moveTo(7f, 9f); lineTo(17f, 9f); moveTo(7f, 13f); lineTo(13f, 13f)
    }
    val Ear = outline("Ear") {
        moveTo(5f, 10f); curveTo(5f, 1f, 19f, 1f, 19f, 10f)
        curveTo(19f, 15f, 15f, 15f, 14f, 19f); curveTo(13f, 23f, 7f, 22f, 7f, 18f)
        moveTo(9f, 10f); curveTo(9f, 5f, 15f, 6f, 15f, 10f)
        curveTo(15f, 12f, 11f, 12f, 11f, 15f)
    }
    val Game = outline("Game") {
        moveTo(7f, 6f); lineTo(17f, 6f); curveTo(20f, 6f, 21f, 10f, 22f, 16f)
        curveTo(23f, 21f, 19f, 21f, 16f, 17f); lineTo(8f, 17f)
        curveTo(5f, 21f, 1f, 21f, 2f, 16f); curveTo(3f, 10f, 4f, 6f, 7f, 6f); close()
        moveTo(7f, 10f); lineTo(7f, 14f); moveTo(5f, 12f); lineTo(9f, 12f)
        moveTo(16f, 11f); lineTo(16.1f, 11f); moveTo(19f, 14f); lineTo(19.1f, 14f)
    }
    val Protection = outline("Protection") {
        moveTo(12f, 2f); curveTo(15f, 4f, 18f, 5f, 21f, 5f)
        lineTo(21f, 10f); curveTo(21f, 16f, 17f, 20f, 12f, 22f)
        curveTo(7f, 20f, 3f, 16f, 3f, 10f); lineTo(3f, 5f)
        curveTo(6f, 5f, 9f, 4f, 12f, 2f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }
    val Hearing = outline("Hearing") {
        moveTo(4f, 9f); lineTo(4f, 15f); moveTo(8f, 5f); lineTo(8f, 19f)
        moveTo(12f, 2f); lineTo(12f, 22f); moveTo(16f, 7f); lineTo(16f, 17f)
        moveTo(20f, 10f); lineTo(20f, 14f)
    }
}

private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
