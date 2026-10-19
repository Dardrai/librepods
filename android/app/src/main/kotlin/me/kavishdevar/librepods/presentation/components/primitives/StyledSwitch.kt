/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.components.primitives

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.presentation.design.LocalAccessibilitySettings
import kotlin.math.abs

@Composable
fun StyledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val haptics = LocalHapticFeedback.current
    val currentChecked by rememberUpdatedState(checked)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val onUserChange = { value: Boolean ->
        if (currentEnabled && value != currentChecked) {
            haptics.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            currentOnCheckedChange(value)
        }
    }

    val differentiateWithoutColor = LocalAccessibilitySettings.current.differentiateWithoutColor

    val onColor = if (enabled) Color(0xFF34C759) else if (isDarkTheme) Color(0xFF5B5B5E) else Color(0xFFD1D1D6)
    val offColor = if (enabled) if (isDarkTheme) Color(0xFF5B5B5E) else Color(0xFFD1D1D6) else if (isDarkTheme) Color(
        0x805B5B5E
    ) else Color(0xFFD1D1D6)

    val trackWidth = 64.dp
    val trackHeight = 28.dp
    val thumbHeight = 24.dp
    val thumbWidth = 39.dp

    val switchBackdrop = rememberLayerBackdrop()
    val fraction by remember {
        derivedStateOf { if (checked) 1f else 0f }
    }
    val animatedFraction = remember { Animatable(fraction) }
    val trackWidthPx = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progressAnimationSpec = spring(0.5f, 300f, 0.001f)
    val progressAnimation = remember { Animatable(0f) }
    val targetColor = if (checked) onColor else offColor
    val animatedTrackColor by animateColorAsState(targetColor)
    val totalDrag = remember { mutableFloatStateOf(0f) }
    val dragChecked = remember { mutableStateOf(checked) }
    val dragCanceled = remember { mutableStateOf(false) }
    // Draggable calls onDragStopped for cancellation too; a canceled press is not a tap.
    val dragInteractions = remember {
        val source = MutableInteractionSource()
        object : MutableInteractionSource {
            override val interactions = source.interactions
            override suspend fun emit(interaction: Interaction) {
                dragCanceled.value = interaction is DragInteraction.Cancel
                source.emit(interaction)
            }
            override fun tryEmit(interaction: Interaction): Boolean {
                dragCanceled.value = interaction is DragInteraction.Cancel
                return source.tryEmit(interaction)
            }
        }
    }
    val tapThreshold = 10f
    val isFirstComposition = remember { mutableStateOf(true) }
    LaunchedEffect(checked) {
        if (!isFirstComposition.value) {
            coroutineScope {
                launch {
                    val targetFrac = if (checked) 1f else 0f
                    animatedFraction.animateTo(targetFrac, progressAnimationSpec)
                }
                if (progressAnimation.value > 0f) return@coroutineScope
                launch {
                    progressAnimation.animateTo(1f, tween(175, easing = FastOutSlowInEasing))
                    progressAnimation.animateTo(0f, tween(175, easing = FastOutSlowInEasing))
                }
            }
        }
        isFirstComposition.value = false
    }

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onUserChange,
            )
            .testTag("styled-switch"),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .layerBackdrop(switchBackdrop)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(animatedTrackColor)
                .width(trackWidth)
                .height(trackHeight)
                .onSizeChanged { trackWidthPx.floatValue = it.width.toFloat() }
        )
        if (differentiateWithoutColor) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .graphicsLayer {
                        translationX = if (checked) 0f else (trackWidthPx.floatValue - with(density) { (trackWidth - thumbWidth).toPx() + 4.dp.toPx()} )
                    }
                    .width(trackWidth - thumbWidth)
                    .height(trackWidth)
            ) {
                // no matching symbols to use as icon
                if (checked) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(2.dp)
                            .height(trackHeight / 3)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(trackHeight / 3)
                            .border(
                                width = 1.5.dp,
                                color = Color.Gray,
                                shape = CircleShape
                            )
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .graphicsLayer {
                    translationX = animatedFraction.value * (trackWidthPx.floatValue - with(density) { thumbWidth.toPx() + 4.dp.toPx() })
                }
                .then(if (enabled) Modifier.draggable(
                    rememberDraggableState { delta ->
                        if (trackWidthPx.floatValue > 0f) {
                            val newFraction = (animatedFraction.value + delta / trackWidthPx.floatValue).fastCoerceIn(-0.3f, 1.3f)
                            scope.launch {
                                animatedFraction.snapTo(newFraction)
                            }
                            totalDrag.floatValue += abs(delta)
                            val newChecked = newFraction >= 0.5f
                            if (newChecked != dragChecked.value) {
                                dragChecked.value = newChecked
                                if (currentEnabled) {
                                    haptics.performHapticFeedback(if (newChecked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    currentOnCheckedChange(newChecked)
                                }
                            }
                        }
                    },
                    Orientation.Horizontal,
                    interactionSource = dragInteractions,
                    startDragImmediately = true,
                    onDragStarted = {
                        totalDrag.floatValue = 0f
                        dragChecked.value = currentChecked
                        scope.launch {
                            progressAnimation.animateTo(1f, progressAnimationSpec)
                        }
                    },
                    onDragStopped = {
                            if (dragCanceled.value) {
                                coroutineScope {
                                    launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
                                    launch { animatedFraction.animateTo(if (dragChecked.value) 1f else 0f, progressAnimationSpec) }
                                }
                            } else if (totalDrag.floatValue < tapThreshold) {
                                val newChecked = !currentChecked
                                onUserChange(newChecked)
                                val snappedFraction = if (newChecked) 1f else 0f
                                coroutineScope {
                                    launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
                                    launch { animatedFraction.animateTo(snappedFraction, progressAnimationSpec) }
                                }
                            } else {
                                val snappedFraction = if (animatedFraction.value >= 0.5f) 1f else 0f
                                if ((snappedFraction >= 0.5f) != dragChecked.value) {
                                    val newChecked = snappedFraction >= 0.5f
                                    if (currentEnabled) {
                                        haptics.performHapticFeedback(if (newChecked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                        currentOnCheckedChange(newChecked)
                                    }
                                }
                                coroutineScope {
                                    launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
                                    launch { animatedFraction.animateTo(snappedFraction, progressAnimationSpec) }
                                }
                            }
                    }
                ) else Modifier)
                .drawBackdrop(
                    switchBackdrop,
                    { RoundedCornerShape(thumbHeight / 2) },
                    highlight = {
                        val progress = progressAnimation.value.fastCoerceIn(0f, 1f)
                        Highlight.Ambient.copy(
                            width = 0.65.dp,
                            alpha = if (!enabled) 0.25f else lerp(0.5f, 0.8f, progress)
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 3.dp,
                            offset = DpOffset(0.dp, 1.dp),
                            color = Color.Black.copy(alpha = if (!enabled) 0.06f else if (isDarkTheme) 0.25f else 0.16f)
                        )
                    },
                    layerBlock = {
                        val progress = progressAnimation.value.fastCoerceIn(0f, 1f)
                        val scale = lerp(1f, 1.12f, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawBackdrop = { drawScope ->
                        drawRect(backgroundColor)
                        drawScope()
                    },
                    onDrawSurface = {
                        val progress = progressAnimation.value.fastCoerceIn(0f, 1f)
                        drawRect(Color.White.copy(alpha = if (!enabled) 0.72f else lerp(0.9f, 0.74f, progress)))
                    },
                    effects = {
                        blur(0.5.dp.toPx())
                        lens(
                            refractionHeight = 4.dp.toPx(),
                            refractionAmount = 5.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = false
                        )
                    }
                )
                .width(thumbWidth)
                .height(thumbHeight)
                .testTag("styled-switch-thumb")
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun StyledSwitchPreview() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .width(100.dp)
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        val checked = remember { mutableStateOf(true) }
        StyledSwitch(
            checked = checked.value,
            onCheckedChange = {
                checked.value = it
            },
            enabled = true,
        )
//        LaunchedEffect(Unit) {
//            delay(1.seconds)
//            checked.value = false
//            delay(1.seconds)
//            checked.value = true
//        }
    }
}
