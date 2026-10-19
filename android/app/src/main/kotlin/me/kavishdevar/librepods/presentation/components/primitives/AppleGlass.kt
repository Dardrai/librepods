package me.kavishdevar.librepods.presentation.components.primitives

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/** Floating controls share one material; the resting tint also works without a captured backdrop. */
@Composable
internal fun Modifier.appleGlassSurface(
    backdrop: Backdrop,
    enabled: Boolean = true,
    surfaceColor: Color = Color.Unspecified,
    tint: Color = Color.Unspecified,
    pressProgress: Float = 0f,
    pressScale: Float = 0.08f,
): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val restingColor = if (dark) Color(0xFF202024).copy(alpha = 0.72f)
        else Color.White.copy(alpha = 0.78f)
    return graphicsLayer {
        val scale = 1f + pressScale * pressProgress
        scaleX = scale
        scaleY = scale
        alpha = if (enabled) 1f else 0.45f
    }.drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(percent = 50) },
        effects = {
            vibrancy()
            blur(3.dp.toPx())
            lens(
                refractionHeight = 4.dp.toPx(),
                refractionAmount = 8.dp.toPx(),
                depthEffect = true,
                chromaticAberration = false,
            )
        },
        highlight = { Highlight.Default.copy(alpha = if (dark) 0.55f else 0.4f) },
        shadow = {
            Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp),
                color = Color.Black.copy(alpha = if (dark) 0.16f else 0.09f))
        },
        onDrawSurface = {
            drawRect(if (surfaceColor.isSpecified) surfaceColor else restingColor)
            if (tint.isSpecified) {
                drawRect(tint, blendMode = BlendMode.Hue)
                drawRect(tint.copy(alpha = 0.75f))
            }
            drawRect(Color.White.copy(alpha = 0.08f * pressProgress))
        },
    )
}
