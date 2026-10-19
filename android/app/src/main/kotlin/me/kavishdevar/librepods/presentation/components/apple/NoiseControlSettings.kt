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

package me.kavishdevar.librepods.presentation.components.apple

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.FontFamilyOption
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.rememberMomentumAnimation
import me.kavishdevar.librepods.presentation.components.primitives.removeLensMatte
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NoiseControlSettings(
    modifier: Modifier = Modifier,
    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    adaptiveStrength: Int = 0,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    showLabels: Boolean = true,
    requestedAdaptiveStrength: Int? = null,
    showAdaptiveMode: Boolean = true,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    var dragFeedbackMode by remember { mutableStateOf<Int?>(null) }
    var dragStrengthFeedbackSent by remember { mutableStateOf(false) }
    val currentModeChanged by rememberUpdatedState<(Int) -> Unit>({ mode ->
        if (enabled && mode in 1..4 && (mode != 4 || showAdaptiveMode) &&
            (mode != 1 || showOffListeningMode)) {
            // Reports can arrive during a drag; only the finger crossing a segment produces feedback.
            if (mode != (dragFeedbackMode ?: noiseControlModeValue)) {
                if (dragFeedbackMode != null) dragFeedbackMode = mode
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            onNoiseControlModeChanged(mode)
        }
    })
    val currentStrengthChanged by rememberUpdatedState<(Int) -> Unit>({ strength ->
        if (enabled && showAdaptiveMode) {
            val value = strength.coerceIn(0, 100)
            if (value != (requestedAdaptiveStrength ?: adaptiveStrength).coerceIn(0, 100) &&
                (dragFeedbackMode == null || !dragStrengthFeedbackSent)) {
                if (dragFeedbackMode != null) dragStrengthFeedbackSent = true
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            onAdaptiveStrengthChanged(value)
        }
    })
    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            val options = buildList {
                if (showOffListeningMode) {
                    add(
                        Triple(
                            NoiseControlMode.OFF,
                            R.string.off,
                            R.drawable.ic_noise_cancellation
                        )
                    )
                }

                add(
                    Triple(
                        NoiseControlMode.TRANSPARENCY,
                        R.string.transparency,
                        R.drawable.ic_transparency
                    )
                )
                if (showAdaptiveMode) add(
                    Triple(
                        NoiseControlMode.ADAPTIVE,
                        R.string.adaptive,
                        R.drawable.ic_adaptive
                    )
                )
                add(
                    Triple(
                        NoiseControlMode.NOISE_CANCELLATION,
                        R.string.noise_cancellation,
                        R.drawable.ic_noise_cancellation
                    )
                )
            }

            val selectedMode = NoiseControlMode.entries[(noiseControlModeValue - 1).coerceIn(0, NoiseControlMode.entries.lastIndex)]

            Column(modifier = modifier) {
                if (showLabels) {
                    Text(
                        text = stringResource(R.string.noise_control),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmallEmphasized,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 12.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    options.forEachIndexed { index, (mode, labelRes, iconRes) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            ToggleButton(
                                checked = selectedMode == mode,
                                enabled = enabled,
                                onCheckedChange = {
                                    if (it) {
                                        currentModeChanged(mode.ordinal + 1)
                                    }
                                },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                colors = ToggleButtonDefaults.colors(containerColor = if (showLabels) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    bitmap = ImageBitmap.imageResource(iconRes),
                                    contentDescription = stringResource(labelRes),
                                    modifier = Modifier.size(42.dp),
                                    tint = if (selectedMode == mode) MaterialTheme.colorScheme.onPrimary else if (showLabels) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            if (showLabels) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        DesignSystem.Apple -> {
            val enabledAlpha by animateFloatAsState(
                targetValue = if (enabled) 1f else 0.4f,
                animationSpec = tween(120),
                label = "listening-control-enabled"
            )
            val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val railShape = RoundedCornerShape(percent = 100)
            val railColor = if (isDarkTheme) Color(0xFF787880).copy(alpha = 0.12f) else Color(0xFF787880).copy(alpha = 0.08f)
            val railWash = Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (isDarkTheme) 0.025f else 0.12f),
                Color.White.copy(alpha = if (isDarkTheme) 0.01f else 0.03f)
            ))
            val railRim = Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (isDarkTheme) 0.16f else 0.68f),
                Color.Black.copy(alpha = if (isDarkTheme) 0.12f else 0.07f)
            ))

            val density = LocalDensity.current
            val adaptiveAudioLabel = stringResource(R.string.adaptive_audio)
            val labelLineHeight = MaterialTheme.typography.labelSmall.lineHeight
            val labelRowHeight = with(density) { maxOf(44.dp, labelLineHeight.toDp() * 3 + 10.dp) }

            val backdrop = rememberLayerBackdrop()

            val iconRowHeight = remember { mutableStateOf(0.dp) }
            val width = remember { mutableStateOf(0.dp) }

            val currentMode by rememberUpdatedState(noiseControlModeValue)
            val currentStrength by rememberUpdatedState((requestedAdaptiveStrength ?: adaptiveStrength).coerceIn(0, 100))
            val currentShowOff by rememberUpdatedState(showOffListeningMode)
            val currentShowAdaptive by rememberUpdatedState(showAdaptiveMode)
            val currentWidth by rememberUpdatedState(width.value.value)

            val offset = remember { Animatable(-1f) }
            var isInitialized by remember { mutableStateOf(false) }

            val offOffset = if (currentShowOff  && currentMode != 4) 1f else 0f

            val adaptiveSelected = showAdaptiveMode && currentMode == 4
            val targetModeWidth = currentWidth / if (adaptiveSelected) 5f else (2f + (if (showAdaptiveMode) 1f else 0f) + offOffset)
            val targetAdaptiveWidth = if (!showAdaptiveMode) 0f else targetModeWidth * if (adaptiveSelected) 3f else 1f
            val targetOffWidth = if (currentShowOff && currentMode != 4) targetModeWidth else 0f
            val motion = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

            val modeWidth by animateFloatAsState(
                targetValue = targetModeWidth,
                animationSpec = if (!isInitialized) snap() else motion,
            )
            val adaptiveWidth by animateFloatAsState(
                targetValue = targetAdaptiveWidth,
                animationSpec = if (!isInitialized) snap() else motion,
            )
            val offWidth by animateFloatAsState(
                targetValue = targetOffWidth,
                animationSpec = if (!isInitialized) snap() else motion,
            )

            val currentModeWidth by rememberUpdatedState(modeWidth)
            val currentTransparencyStart by rememberUpdatedState(offWidth)
            val currentAdaptiveStart by rememberUpdatedState(offWidth + modeWidth)
            val currentAncStart by rememberUpdatedState(offWidth + modeWidth + adaptiveWidth)

            val targetTransparencyStart by rememberUpdatedState(targetOffWidth)
            val targetAdaptiveStart by rememberUpdatedState(targetOffWidth + targetModeWidth)
            val targetAncStart by rememberUpdatedState(targetOffWidth + targetModeWidth + targetAdaptiveWidth)
            val currentTargetModeWidth by rememberUpdatedState(targetModeWidth)

            val momentum = rememberMomentumAnimation(maxScale = 78f / 56f,
                scaleXAnimationSpec = spring(0.6f, 250f, 0.001f),
                scaleYAnimationSpec = spring(0.7f, 250f, 0.001f))
            val lensScaleX by remember {
                derivedStateOf {
                    val restingWidth = currentModeWidth - 8f
                    val edgeClearance = minOf(offset.value.coerceAtLeast(0f),
                        (currentWidth - currentModeWidth - offset.value).coerceAtLeast(0f))
                    // Leave 2dp inside the popup's 16dp outer buffer at the ends of the rail.
                    if (restingWidth <= 0f) 1f else minOf(momentum.scaleX,
                        1f + 2f * (edgeClearance + 4f + 14f) / restingWidth)
                }
            }
            LaunchedEffect(enabled, momentum) {
                if (!enabled) {
                    dragFeedbackMode = null
                    dragStrengthFeedbackSent = false
                    momentum.endPressingAnimation()
                }
            }

            val animationCoroutineScope = rememberCoroutineScope()

            var isDragging by remember { mutableStateOf(false) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var dragStartOffset by remember { mutableFloatStateOf(0f) }
            var thumbCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

            LaunchedEffect(width.value, noiseControlModeValue, adaptiveStrength, requestedAdaptiveStrength, showOffListeningMode, showAdaptiveMode, isDragging) {
                if (!isDragging && width.value > 0.dp) {
                    val targetOffset = when (noiseControlModeValue) {
                        1 -> 0f
                        3 -> targetTransparencyStart
                        4 -> targetAdaptiveStart + currentStrength * (targetAncStart - targetAdaptiveStart - currentTargetModeWidth) / 100f
                        2 -> targetAncStart
                        else -> 0f
                    }
                    if (!isInitialized) {
                        offset.snapTo(targetOffset)
                    } else {
                        offset.animateTo(targetOffset, motion)
                    }
                }
            }

            LaunchedEffect(width.value) {
                if (width.value > 0.dp && !isInitialized) {
                    delay(50.milliseconds)
                    isInitialized = true
                }
            }

            val targetMode = remember { mutableIntStateOf(0) }
            val targetStrength = remember { mutableIntStateOf(0) }

            LaunchedEffect(Unit) {
                launch {
                    snapshotFlow { offset.value }
                        .map { currentOffset ->
                            if (currentWidth == 0f) return@map null
                            val thumbCenter = currentOffset + (currentModeWidth / 2f)

                            if (currentShowOff) {
                                when {
                                    thumbCenter < currentTransparencyStart -> 1
                                    thumbCenter < currentAdaptiveStart -> 3
                                    currentShowAdaptive && thumbCenter < currentAncStart -> 4
                                    else -> 2
                                }
                            } else {
                                when {
                                    thumbCenter < currentAdaptiveStart -> 3
                                    currentShowAdaptive && thumbCenter < currentAncStart -> 4
                                    else -> 2
                                }
                            }
                        }
                        .distinctUntilChanged()
                        .collect { mode ->
                            mode?.let {
                                targetMode.intValue = it
                                if (isDragging) {
                                    currentModeChanged(it)
                                }
                            }
                        }
                }

                launch {
                    snapshotFlow { offset.value }
                        .map { currentOffset ->
                            if (currentMode == 4) {
                                val maxTravel = currentAncStart - currentAdaptiveStart - currentModeWidth
                                if (maxTravel <= 0f) 0 else {
                                    ((currentOffset - currentAdaptiveStart) / maxTravel * 100f)
                                        .roundToInt()
                                        .coerceIn(0, 100)
                                }
                            } else null
                        }
                        .distinctUntilChanged()
                        .collect { currentAdaptiveStrength ->
                            targetStrength.intValue = currentAdaptiveStrength ?: -1
                            if (isDragging && currentAdaptiveStrength != null) {
                                currentStrengthChanged(currentAdaptiveStrength)
                            }
                        }
                }
            }

            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .alpha(if (width.value > 0.dp) enabledAlpha else 0f)
            ) {
                if (LocalDebugMode.current) {
                    Text(
                        text = "[$noiseControlModeValue][${targetMode.intValue}][strength = ${targetStrength.intValue}]\nmodeWidth = ${modeWidth}\noffset = ${offset.value}\ntransparencyStart = $currentTransparencyStart adaptiveStart = $currentAdaptiveStart ancStart = $currentAncStart",
                        color = Color.Red.copy(0.85f),
                        modifier = Modifier.zIndex(999f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(64.dp)
                            .fillMaxWidth()
                            .drawWithContent {
                                if (modeWidth <= 8f) {
                                    drawContent()
                                } else {
                                    val restingWidth = (modeWidth - 8f).dp.toPx()
                                    val restingHeight = 56.dp.toPx()
                                    val scaleX = lensScaleX
                                    val scaleY = momentum.scaleY
                                    val lensWidth = restingWidth * scaleX
                                    val lensHeight = restingHeight * scaleY
                                    val centerX = offset.value.coerceAtLeast(0f).dp.toPx() + modeWidth.dp.toPx() / 2f
                                    val centerY = size.height / 2f
                                    val radius = minOf(restingWidth, restingHeight) / 2f
                                    val path = Path().apply {
                                        addRoundRect(RoundRect(Rect(centerX - lensWidth / 2f,
                                            centerY - lensHeight / 2f, centerX + lensWidth / 2f,
                                            centerY + lensHeight / 2f), CornerRadius(radius * scaleX, radius * scaleY)))
                                    }
                                    // Hide only the unmagnified copy; the inner capture keeps the complete track.
                                    clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                                }
                            }
                            .layerBackdrop(backdrop)
                            .background(railColor, railShape)
                            .background(railWash, railShape)
                            .border(0.5.dp, railRim, railShape)
                            .drawWithContent {
                                val pressed = momentum.progress.coerceIn(0f, 1f)
                                if (pressed > 0f) {
                                    val radius = size.height / 2f
                                    val outline = Path().apply {
                                        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
                                    }
                                    // Capture the reference's moving press light beneath the fixed icons.
                                    clipPath(outline) {
                                        drawRect(Color.White.copy(alpha = 0.08f * pressed), blendMode = BlendMode.Plus)
                                        drawRect(Brush.radialGradient(
                                            0f to Color.White.copy(alpha = 0.15f * pressed),
                                            0.5f to Color.White.copy(alpha = 0.15f * pressed),
                                            1f to Color.Transparent,
                                            center = Offset(offset.value.coerceAtLeast(0f).dp.toPx() + modeWidth.dp.toPx() / 2f, radius),
                                            radius = size.height * 1.5f
                                        ), blendMode = BlendMode.Plus)
                                    }
                                }
                                drawContent()
                            }
                            .selectableGroup()
                            .onGloballyPositioned {
                                with(density) {
                                    iconRowHeight.value = it.size.height.toDp()
                                    width.value = it.size.width.toDp()
                                }
                            }
                    ) {
                        if (offWidth > 0f) Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .modeOption(1, currentMode, stringResource(R.string.off), enabled && showOffListeningMode && !adaptiveSelected, currentModeChanged)
                                .width(offWidth.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                bitmap = ImageBitmap.imageResource(R.drawable.ic_noise_cancellation),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).testTag("noise-control-icon-1"),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(modeWidth.dp)
                                .modeOption(3, currentMode, stringResource(R.string.transparency), enabled, currentModeChanged),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                bitmap = ImageBitmap.imageResource(R.drawable.ic_transparency),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).testTag("noise-control-icon-3"),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (showAdaptiveMode) Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(adaptiveWidth.dp)
                                .modeOption(4, currentMode, stringResource(R.string.adaptive), enabled, currentModeChanged)
                                .pointerInput(enabled) {
                                    if (!enabled) return@pointerInput
                                    detectTapGestures { tapOffset ->
                                        if (currentMode != 4) {
                                            currentModeChanged(4)
                                        } else {
                                            val tapXInDp =
                                                with(density) { tapOffset.x.toDp().value }

                                            val maxTravel =
                                                targetAncStart - targetAdaptiveStart - currentTargetModeWidth

                                            if (maxTravel > 0f) {
                                                val thumbLeftEdge =
                                                    tapXInDp - (currentTargetModeWidth / 2f)

                                                val newStrength =
                                                    ((thumbLeftEdge / maxTravel) * 100f)
                                                        .roundToInt()
                                                        .coerceIn(0, 100)

                                                currentStrengthChanged(newStrength)
                                            }
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .background(
                                        if (noiseControlModeValue == 4) MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDarkTheme) 0.045f else 0.035f) else Color.Transparent,
                                        RoundedCornerShape(percent = 100)
                                    )

                                    .zIndex(999f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AnimatedVisibility(
                                    visible = noiseControlModeValue == 4,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.padding(start = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.CircleDotted,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Icon(
                                    bitmap = ImageBitmap.imageResource(R.drawable.ic_adaptive),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp).testTag("noise-control-icon-4"),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                AnimatedVisibility(
                                    visible = noiseControlModeValue == 4,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.padding(end = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.Circle,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(modeWidth.dp)
                                .modeOption(2, currentMode, stringResource(R.string.noise_cancellation), enabled, currentModeChanged),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                bitmap = ImageBitmap.imageResource(R.drawable.ic_noise_cancellation),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).testTag("noise-control-icon-2"),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (showLabels) {
                        Row (
                            modifier = Modifier
                                .heightIn(min = labelRowHeight)
                                .padding(top = 10.dp)
                                .background(
                                    @Suppress("KotlinConstantConditions")
                                    if (showLabels) MaterialTheme.colorScheme.surfaceContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                ), // else backdrop doesn't work
                        ) {
                            if (showOffListeningMode && offWidth > 0f) {
                                Text(
                                    text = stringResource(R.string.off),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(offWidth.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = stringResource(R.string.transparency),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(modeWidth.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (showAdaptiveMode) Text(
                                text = stringResource(R.string.adaptive),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(adaptiveWidth.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.noise_cancellation),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(modeWidth.dp),
                                minLines = 2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = with(density) { offset.value.coerceAtLeast(0f).dp.toPx() }
                        }
                        .width(modeWidth.dp)
                        .height(iconRowHeight.value)
                        .onGloballyPositioned { thumbCoordinates = it }
                        .testTag("noise-control-thumb")
                        .semantics {
                            if (!enabled) disabled()
                            if (currentMode == 4 && showAdaptiveMode) {
                                contentDescription = adaptiveAudioLabel
                                progressBarRangeInfo = ProgressBarRangeInfo(currentStrength.toFloat(), 0f..100f)
                                setProgress { value ->
                                    if (!enabled || !value.isFinite()) false else {
                                        currentStrengthChanged(value.coerceIn(0f, 100f).roundToInt())
                                        true
                                    }
                                }
                            }
                        }
                        .pointerInput(enabled, showAdaptiveMode) {
                            if (!enabled) return@pointerInput
                            var dragStartRootX: Float? = null
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragFeedbackMode = currentMode
                                    dragStrengthFeedbackSent = false
                                    dragOffset = offset.value
                                    dragStartOffset = dragOffset
                                    dragStartRootX = null
                                    isDragging = true
                                },
                                onDragEnd = {
                                    val thumbCenter = dragOffset + currentModeWidth / 2f
                                    val finalMode = when {
                                        currentShowOff && thumbCenter < currentTransparencyStart -> 1
                                        thumbCenter < currentAdaptiveStart -> 3
                                        currentShowAdaptive && thumbCenter < currentAncStart -> 4
                                        else -> 2
                                    }
                                    val finalStrength = if (currentMode == 4 && finalMode == 4) {
                                        val travel = currentAncStart - currentAdaptiveStart - currentModeWidth
                                        if (travel <= 0f) currentStrength else
                                            ((dragOffset - currentAdaptiveStart) / travel * 100f).roundToInt().coerceIn(0, 100)
                                    } else currentStrength
                                    // The final offset may not reach snapshotFlow before the gesture ends.
                                    if (finalMode != currentMode) currentModeChanged(finalMode)
                                    if (currentMode == 4 && finalMode == 4) currentStrengthChanged(finalStrength)
                                    dragFeedbackMode = null
                                    dragStrengthFeedbackSent = false
                                    isDragging = false

                                    val finalTarget = when (finalMode) {
                                        1 -> 0f
                                        3 -> targetTransparencyStart
                                        4 -> targetAdaptiveStart + finalStrength * (targetAncStart - targetAdaptiveStart - currentTargetModeWidth) / 100f
                                        2 -> targetAncStart
                                        else -> 0f
                                    }

                                    animationCoroutineScope.launch { offset.animateTo(finalTarget, motion) }
                                },
                                onDragCancel = {
                                    dragFeedbackMode = null
                                    dragStrengthFeedbackSent = false
                                    isDragging = false
                                    val restoredOffset = when (currentMode) {
                                        1 -> 0f
                                        3 -> targetTransparencyStart
                                        4 -> targetAdaptiveStart + currentStrength * (targetAncStart - targetAdaptiveStart - currentTargetModeWidth) / 100f
                                        2 -> targetAncStart
                                        else -> 0f
                                    }
                                    animationCoroutineScope.launch { offset.animateTo(restoredOffset, motion) }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                val coordinates = thumbCoordinates?.takeIf { it.isAttached }
                                    ?: return@detectHorizontalDragGestures
                                // The pointer's local position changes as the thumb follows it.
                                val rootX = coordinates.localToRoot(change.position).x
                                // The first callback includes the movement beyond touch slop.
                                val startRootX = dragStartRootX
                                    ?: coordinates.localToRoot(change.position - Offset(dragAmount, 0f)).x
                                dragStartRootX = startRootX
                                val newOffset = dragStartOffset + with(density) { (rootX - startRootX).toDp().value }
                                val maxTravel = (currentWidth - currentModeWidth).coerceAtLeast(0f)
                                val safeOffset = newOffset.coerceIn(0f, maxTravel)
                                dragOffset = safeOffset

                                animationCoroutineScope.launch {
                                    offset.snapTo(safeOffset)
                                }
                            }
                        }
                        .then(if (enabled) momentum.modifier else Modifier)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(percent = 100) },
                            highlight = {
                                Highlight.Default.copy(alpha = momentum.progress.coerceIn(0f, 1f))
                            },
                            shadow = { Shadow(alpha = momentum.progress.coerceIn(0f, 1f)) },
                            innerShadow = {
                                val pressed = momentum.progress.coerceIn(0f, 1f)
                                InnerShadow(radius = 8.dp * pressed, alpha = pressed)
                            },
                            layerBlock = {
                                scaleX = lensScaleX
                                scaleY = momentum.scaleY
                            },
                            effects = {
                                val pressed = momentum.progress.coerceIn(0f, 1f)
                                vibrancy()
                                lens(
                                    // The resting selection is flat; pressing raises the refractive waterdrop.
                                    refractionHeight = 10.dp.toPx() * pressed,
                                    refractionAmount = 14.dp.toPx() * pressed,
                                    chromaticAberration = true
                                )
                                removeLensMatte(isDarkTheme)
                            },
                            onDrawBackdrop = { drawBackdrop ->
                                // Dispersion needs RGB contrast: black icons on transparent pixels only split alpha.
                                drawRect(if (isDarkTheme) Color.Black else Color.White)
                                // Magnification settles with the light and refraction, without moving the source icons.
                                val magnification = 1f + 0.2f * momentum.progress.coerceIn(0f, 1f)
                                scale(magnification, magnification) { drawBackdrop() }
                            },
                            onDrawSurface = {
                                val pressed = momentum.progress.coerceIn(0f, 1f)
                                drawRect((if (isDarkTheme) Color.White else Color.Black)
                                    .copy(alpha = 0.1f * (1f - pressed)))
                                drawRect(Color.Black.copy(alpha = 0.03f * pressed))
                            },
                        ).testTag("noise-control-bubble"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.modeOption(mode: Int, selectedMode: Int, label: String,
    enabled: Boolean, onSelected: (Int) -> Unit): Modifier =
    selectable(selected = mode == selectedMode, enabled = enabled,
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        role = Role.RadioButton, onClick = { if (enabled) onSelected(mode) })
        .semantics { contentDescription = label }

@Composable
internal fun AdaptiveAudioStrengthSlider(
    adaptiveStrength: Int,
    requestedAdaptiveStrength: Int?,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    enabled: Boolean
) {
    StyledSlider(
        value = 100f - (requestedAdaptiveStrength ?: adaptiveStrength).coerceIn(0, 100),
        onValueChange = { onAdaptiveStrengthChanged((100f - it).roundToInt().coerceIn(0, 100)) },
        valueRange = 0f..100f,
        snapPoints = listOf(0f, 50f, 100f),
        startImageVector = LocalIcons.current.SpeakerMin,
        endImageVector = LocalIcons.current.SpeakerMax,
        independent = true,
        description = stringResource(R.string.adaptive_audio_description),
        enabled = enabled
    )
}

@Preview
@Composable
fun NoiseControlSettingsPreview() {
    val showOffListeningMode = remember { mutableStateOf(false) }
    val mode = remember { mutableIntStateOf(2) }
    val strength = remember { mutableIntStateOf(30) }
    val debug = remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalDebugMode provides debug.value
    ) {
        LibrePodsTheme(
            darkTheme = true,
            designSystem = DesignSystem.Apple,
            fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.Inter)
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .fillMaxSize()
                    .padding(vertical = 32.dp, horizontal = 16.dp)
            ) {
                Box(
                    modifier = Modifier.align(Alignment.Center)
                ){
                    NoiseControlSettings(
                        showOffListeningMode = showOffListeningMode.value,
                        noiseControlModeValue = mode.intValue,
                        onNoiseControlModeChanged = { mode.intValue = it },
                        adaptiveStrength = strength.intValue,
                        onAdaptiveStrengthChanged = { strength.intValue = it }
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row {
                        StyledButton(onClick = { debug.value = !debug.value }) {
                            Text(
                                text = "debug (${debug.value})",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        StyledButton(onClick = {
                            showOffListeningMode.value = !showOffListeningMode.value
                        }) {
                            Text(
                                text = "allow off (${showOffListeningMode.value})",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(text = "trigger external changes (other device or stem press):", color = MaterialTheme.colorScheme.onSurface)

                    Spacer(modifier = Modifier.height(4.dp))

                    Row {
                        AnimatedVisibility(
                            visible = showOffListeningMode.value,
                            enter = expandHorizontally { 0 } + fadeIn(),
                            exit = fadeOut() + shrinkHorizontally { 0 }
                        ) {
                            StyledButton(onClick = { mode.intValue = 1 }) {
                                Text(text = "off", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        StyledButton(onClick = { mode.intValue = 3 }) {
                            Text(text = "transparency", color = MaterialTheme.colorScheme.onSurface)
                        }
                        StyledButton(onClick = { mode.intValue = 4 }) {
                            Text(text = "adaptive", color = MaterialTheme.colorScheme.onSurface)
                        }
                        StyledButton(onClick = { mode.intValue = 2 }) {
                            Text(text = "anc", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val sliderValue = remember { mutableFloatStateOf(strength.intValue.toFloat()) }

                    LaunchedEffect(strength.intValue) {
                        sliderValue.floatValue = strength.intValue.toFloat()
                    }

                    LaunchedEffect(Unit) {
                        snapshotFlow { sliderValue.floatValue.toInt() }
                            .debounce(50.milliseconds)
                            .collect {
                                sliderValue.floatValue = it.toFloat()
                                strength.intValue = it
                            }
                    }

                    StyledSlider(
                        label = "adaptive strength",
                        value = sliderValue.floatValue,
                        onValueChange = { sliderValue.floatValue = it },
                        valueRange = 0f..100f,
                        independent = true
                    )
                }
            }
        }
    }
}
