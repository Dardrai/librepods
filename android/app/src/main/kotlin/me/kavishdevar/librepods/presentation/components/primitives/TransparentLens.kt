package me.kavishdevar.librepods.presentation.components.primitives

import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect

/** Apply after lens(); its captured input must be backed by white (light) or black (dark). */
internal fun BackdropEffectScope.removeLensMatte(isDarkTheme: Boolean) {
    runtimeShaderEffect(
        key = "librepods-lens-unmatte",
        shaderString = LensUnmatteShader,
        uniformShaderName = "content",
    ) {
        setFloatUniform("dark", if (isDarkTheme) 1f else 0f)
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("rimDistances", 3.dp.toPx(), 6.dp.toPx())
    }
}

private const val LensUnmatteShader = """
uniform shader content;
uniform float dark;
uniform float2 size;
uniform float2 rimDistances;

half4 main(float2 coord) {
    half4 sample = content.eval(coord);
    if (sample.a <= 0.0) return half4(0.0);
    half3 rgb = clamp(sample.rgb / sample.a, half3(0.0), half3(1.0));
    half minimum = min(rgb.r, min(rgb.g, rgb.b));
    half maximum = max(rgb.r, max(rgb.g, rgb.b));
    half3 chromatic = rgb - half3(minimum);
    half alpha;
    if (dark > 0.5) {
        alpha = maximum;
    } else {
        alpha = 1.0 - minimum;
        rgb = chromatic;
    }

    float2 halfSize = size * 0.5;
    float radius = min(halfSize.x, halfSize.y);
    float2 q = abs(coord - halfSize) - (halfSize - float2(radius));
    float distance = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    half rim = 1.0 - smoothstep(rimDistances.x, rimDistances.y, -distance);
    // Neutral rim caustics look like a second selection bar; keep only real separated color there.
    return mix(half4(rgb, alpha), half4(chromatic, maximum - minimum), rim) * sample.a;
}
"""
