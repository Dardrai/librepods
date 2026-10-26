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

package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun equalizerBand(offset: Float, maxOffset: Float): Int =
    (100 - ((offset / (2 * maxOffset) + 0.5f) * 100).roundToInt()).coerceIn(0, 100)

@Composable
fun EqualizerRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val live by viewModel.liveCustomEqEditor().state.collectAsState()
    EqualizerScreen(
        uiState = uiState.copy(state = uiState.state.copy(requestedCustomEq = live.displayed)),
        navigateBack = navigateBack,
        setCustomEqEnabled = { viewModel.setCustomEqEnabled(it, live.revision) },
        setCustomEq = { low, mid, high -> viewModel.setCustomEq(low, mid, high, live.revision) },
        editorRevision = live.revision,
        controlsEnabled = connection == ConnectionState.CONNECTED
    )
}
@Composable
fun EqualizerScreen(
    uiState: AppleUiState,
    navigateBack: (() -> Unit)?,
    setCustomEqEnabled: (Boolean) -> Unit,
    setCustomEq: (Int, Int, Int) -> Unit,
    editorRevision: Long = 0,
    controlsEnabled: Boolean = true
) {
    val state = uiState.state

    val customEq = state.requestedCustomEq ?: state.customEq

    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.equalizer),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val height = 200.dp
            val maxOffset = with(LocalDensity.current) { height.toPx() } / 2

            val offsets = remember(state.controlSession, editorRevision, maxOffset) {
                listOf(
                    mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.low.toFloat() / 100)),
                    mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.mid.toFloat() / 100)),
                    mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.high.toFloat() / 100))
                )
            }

            fun bandsChanged() {
                val values = offsets.map { equalizerBand(it.floatValue, maxOffset) }
                setCustomEq(values[0], values[1], values[2])
            }
            LaunchedEffect(customEq, offsets) {
                val current = offsets.map { equalizerBand(it.floatValue, maxOffset) }
                if (current != listOf(customEq.low, customEq.mid, customEq.high)) {
                    listOf(customEq.low, customEq.mid, customEq.high).forEachIndexed { index, value ->
                        offsets[index].floatValue = lerp(maxOffset, -maxOffset, value / 100f)
                    }
                }
            }

            Spacer(modifier = Modifier.height(topPadding))

            val enabled = customEq.isEnabled()

            StyledList {
                styledListItem(
                    contentText = stringResource(R.string.recommended),
                    selected = !enabled,
                    enabled = controlsEnabled,
                    onClick = { setCustomEqEnabled(false) }
                )

                styledListItem(
                    contentText = stringResource(R.string.custom),
                    selected = enabled,
                    enabled = controlsEnabled,
                    onClick = { setCustomEqEnabled(true) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Crossfade (
                customEq.isEnabled()
            ) { visible ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .visible(visible),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    key(state.controlSession, editorRevision) {
                        EqualizerCard(
                            lowOffset = offsets[0], midOffset = offsets[1], highOffset = offsets[2],
                            enabled = controlsEnabled && visible && enabled, onBandsChanged = ::bandsChanged
                        )
                    }

                    val resetButtonEnabled = remember { derivedStateOf { !offsets.all { it.floatValue == 0f } } }

                    StyledButton(
                        onClick = {
                            offsets[0].floatValue = 0f
                            offsets[1].floatValue = 0f
                            offsets[2].floatValue = 0f
                            bandsChanged()
                        },
                        backdrop = rememberLayerBackdrop(),
                        modifier = Modifier.fillMaxWidth(),
                        isInteractive = false,
                        enabled = controlsEnabled && visible && enabled && resetButtonEnabled.value
                    ) {
                        Text(
                            text = stringResource(R.string.reset),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}


@Composable
fun EqualizerCard(
    lowOffset: MutableState<Float>,
    midOffset: MutableState<Float>,
    highOffset: MutableState<Float>,
    enabled: Boolean = true,
    onBandsChanged: () -> Unit = {}
) {
    val height = 200.dp
    val maxOffset = with(LocalDensity.current) { height.toPx() } / 2
    val haptics = LocalHapticFeedback.current

    val dashColor = MaterialTheme.colorScheme.onSurface.copy(0.2f)

    val backdrop = rememberLayerBackdrop()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
    ) {
        Spacer(modifier = Modifier.height(42.dp))
        //                Row(
        //                    modifier = Modifier
        //                        .fillMaxWidth()
        //                        .padding(18.dp),
        //                    verticalAlignment = Alignment.CenterVertically,
        //                    horizontalArrangement = Arrangement.spacedBy(12.dp)
        //                ) {
        //                    Box(
        //                        modifier = Modifier
        //                            .size(64.dp)
        //                            .background(if (isSystemInDarkTheme()) Color.DarkGray else Color.LightGray, RoundedCornerShape(12.dp))
        //                    )
        //                    Column(
        //                        modifier = Modifier
        //                            .weight(1f),
        //                        verticalArrangement = Arrangement.Center
        //                    ) {
        //                        Text(
        //                            text = "Written into Changes",
        //                            style = TextStyle(
        //                                fontSize = 16.sp,
        //                                fontFamily = FontFamily(Font(R.font.sf_pro)),
        //                                fontWeight = FontWeight.Bold,
        //                                color = if (isSystemInDarkTheme()) Color.White else Color.Black
        //                            )
        //                        )
        //                        Spacer(modifier = Modifier.height(4.dp))
        //                        Text(
        //                            text = "Avalon Emerson",
        //                            style = TextStyle(
        //                                fontSize = 14.sp,
        //                                fontFamily = FontFamily(Font(R.font.sf_pro)),
        //                                fontWeight = FontWeight.Normal,
        //                                color = if (isSystemInDarkTheme()) Color.White else Color.Black
        //                            )
        //                        )
        //                    }
        //                    val paused = remember { mutableStateOf(false) }
        //                    Box(
        //                        modifier = Modifier
        //                            .size(48.dp)
        //                            .background(Color(0x600091FF), CircleShape)
        //                            .clickable(
        //                                interactionSource = remember { MutableInteractionSource() },
        //                                indication = null,
        //                            ) {
        //                                paused.value = !paused.value
        //                            },
        //                        contentAlignment = Alignment.Center
        //                    ) {
        //                        Crossfade(
        //                            targetState = paused.value,
        //                            label = "media_icon"
        //                        ) { p ->
        //                            Text(
        //                                text = if (p) "􀊄" else "􀊆",
        //                                style = TextStyle(
        //                                    fontSize = 24.sp,
        //                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
        //                                    fontWeight = FontWeight.Normal,
        //                                    color = Color(0xFF0091FF),
        //                                    textAlign = TextAlign.Center
        //                                )
        //                            )
        //                        }
        //                    }
        //                }
        //
        //                HorizontalDivider(
        //                    thickness = 1.dp,
        //                    color = Color(0x40888888),
        //                    modifier = Modifier
        //                        .padding(horizontal = 20.dp)
        //                        .padding(bottom = 16.dp)
        //                )

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            fun colorFromY(y: Float): Color {
                val f = ((y + maxOffset) / (2f * maxOffset)).coerceIn(0f, 1f)
                val stops = listOf(
                    0.0f to Color(0xFFFFA300),
                    0.25f to Color(0xFFFCE600),
                    0.5f to Color(0xFF00FAAF),
                    0.75f to Color(0xFF00FAFF),
                    1.0f to Color(0xFF00B5FF)
                )
                val (start, end) = stops.zipWithNext()
                    .first { f <= it.second.first }
                val c = (f - start.first) / (end.first - start.first)
                return lerp(start.second, end.second, c)
            }

            fun pathBrush(
                startY: Float,
                endY: Float,
            ): Brush {
                val stops = (0..20).map { i ->
                    val t = i / 20f
                    val y = lerp(startY, endY, t)
                    t to colorFromY(y)
                }

                return Brush.linearGradient(
                    colorStops = stops.toTypedArray()
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .layerBackdrop(backdrop)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .padding(horizontal = 20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        val dashCount = (height / 10.dp).toInt()
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    for (i in 1..(dashCount)) {
                                        val t = i.toFloat() / dashCount
                                        val centerDistance = abs(0.5f - t)
                                        val alpha = 1f - (centerDistance * 2f)
                                        Box(
                                            modifier = Modifier
                                                .height(9.dp)
                                                .width(0.75.dp)
                                                .background(
                                                    dashColor.copy(alpha),
                                                    RoundedCornerShape(28.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        val canvasWidth = size.width

                        drawLine(
                            color = backgroundColor,
                            start = Offset(
                                x = 0f,
                                y = lowOffset.value + maxOffset
                            ),
                            end = Offset(
                                x = 1 / 6f * canvasWidth,
                                y = lowOffset.value + maxOffset
                            ),
                            strokeWidth = 10f
                        )
                        drawLine(
                            color = colorFromY(lowOffset.value),
                            start = Offset(
                                x = 0f,
                                y = lowOffset.value + maxOffset
                            ),
                            end = Offset(
                                x = 1 / 6f * canvasWidth,
                                y = lowOffset.value + maxOffset
                            ),
                            strokeWidth = 8f
                        )

                        val lowToMidPath = Path()
                        lowToMidPath.moveTo(
                            x = 1 / 6f * canvasWidth,
                            y = lowOffset.value + maxOffset
                        )
                        lowToMidPath.cubicTo(
                            x1 = canvasWidth * 1 / 6f + 108.dp.value,
                            y1 = lowOffset.value + maxOffset,
                            x2 = canvasWidth * 0.5f - 108.dp.value,
                            y2 = midOffset.value + maxOffset,
                            x3 = canvasWidth * 0.5f,
                            y3 = midOffset.value + maxOffset
                        )
                        drawPath(
                            color = backgroundColor,
                            path = lowToMidPath,
                            style = Stroke(width = 10f)
                        )
                        drawPath(
                            brush = pathBrush(
                                lowOffset.value,
                                midOffset.value
                            ),
                            path = lowToMidPath,
                            style = Stroke(width = 8f)
                        )

                        val midToHighPath = Path()
                        midToHighPath.moveTo(
                            x = 0.5f * canvasWidth,
                            y = midOffset.value + maxOffset
                        )
                        midToHighPath.cubicTo(
                            x1 = canvasWidth * 0.5f + 108.dp.value,
                            y1 = midOffset.value + maxOffset,
                            x2 = canvasWidth * 5 / 6f - 108.dp.value,
                            y2 = highOffset.value + maxOffset,
                            x3 = canvasWidth * 5 / 6f,
                            y3 = highOffset.value + maxOffset
                        )
                        drawPath(
                            color = backgroundColor,
                            path = midToHighPath,
                            style = Stroke(width = 10f)
                        )
                        drawPath(
                            brush = pathBrush(
                                midOffset.value,
                                highOffset.value
                            ),
                            path = midToHighPath,
                            style = Stroke(width = 8f)
                        )
                        drawLine(
                            color = backgroundColor,
                            start = Offset(
                                x = 5 / 6f * canvasWidth,
                                y = highOffset.value + maxOffset
                            ),
                            end = Offset(
                                x = 1f * canvasWidth,
                                y = highOffset.value + maxOffset
                            ),
                            strokeWidth = 10f
                        )
                        drawLine(
                            color = colorFromY(highOffset.value),
                            start = Offset(
                                x = 5 / 6f * canvasWidth,
                                y = highOffset.value + maxOffset
                            ),
                            end = Offset(
                                x = 1f * canvasWidth,
                                y = highOffset.value + maxOffset
                            ),
                            strokeWidth = 8f
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.eq_low).uppercase(),
                            style = MaterialTheme.typography.labelSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.eq_mid).uppercase(),
                            style = MaterialTheme.typography.labelSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.eq_high).uppercase(),
                            style = MaterialTheme.typography.labelSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .padding(horizontal = 20.dp),

                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0..2) {
                    Row(
                        modifier = Modifier
                            .weight(1f),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val pressed = remember { mutableStateOf(false) }
                        val bandOffset = when (i) { 0 -> lowOffset; 1 -> midOffset; else -> highOffset }
                        val bandLabel = stringResource(when (i) { 0 -> R.string.eq_low; 1 -> R.string.eq_mid; else -> R.string.eq_high })
                        fun moveBand(offset: Float): Boolean {
                            if (!enabled || !offset.isFinite()) return false
                            val next = offset.coerceIn(-maxOffset, maxOffset)
                            if (next == bandOffset.value) return false
                            val previousBand = equalizerBand(bandOffset.value, maxOffset)
                            bandOffset.value = next
                            if (equalizerBand(next, maxOffset) != previousBand) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            onBandsChanged()
                            return true
                        }
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        x = 0,
                                        y = when (i) { 0 -> lowOffset.value; 1 -> midOffset.value; 2-> highOffset.value else -> 0f}.roundToInt()
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                pressed.value
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .then(
                                            if (it) {
                                                Modifier.drawBackdrop(
                                                    backdrop = backdrop,
                                                    shape = { CircleShape },
                                                    highlight = {
                                                        Highlight.Ambient
                                                    },
                                                    onDrawSurface = {
                                                        drawCircle(
                                                            color = Color.White.copy(
                                                                0.2f
                                                            ),
                                                            radius = size.height
                                                        )
                                                        drawCircle(
                                                            color = colorFromY(
                                                                when (i) {
                                                                    0 -> lowOffset.value; 1 -> midOffset.value; 2 -> highOffset.value
                                                                    else -> 0f
                                                                }
                                                            ),
                                                            style = Stroke(2.dp.value),
                                                            radius = size.height / 2
                                                        )
                                                    },
                                                    effects = {
                                                        lens(
                                                            refractionHeight = 32f.dp.value,
                                                            refractionAmount = size.height
                                                        )
                                                    }
                                                )
                                            } else Modifier
                                        )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("equalizer-band-$i")
                                    .semantics {
                                        contentDescription = bandLabel
                                        progressBarRangeInfo = ProgressBarRangeInfo(equalizerBand(bandOffset.value, maxOffset).toFloat(), 0f..100f)
                                        if (!enabled) disabled()
                                        else setProgress { target ->
                                            target.isFinite() && moveBand(lerp(maxOffset, -maxOffset, target.coerceIn(0f, 100f) / 100f))
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (!enabled || event.type != KeyEventType.KeyDown) false else {
                                            val current = equalizerBand(bandOffset.value, maxOffset)
                                            val target = when (event.key) {
                                                Key.DirectionUp, Key.DirectionRight -> current + 1
                                                Key.DirectionDown, Key.DirectionLeft -> current - 1
                                                Key.MoveHome -> 0
                                                Key.MoveEnd -> 100
                                                else -> return@onKeyEvent false
                                            }
                                            moveBand(lerp(maxOffset, -maxOffset, target.coerceIn(0, 100) / 100f))
                                            true
                                        }
                                    }
                                    .focusable(enabled)
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        enabled = enabled,
                                        state = rememberDraggableState { delta ->
                                            moveBand(bandOffset.value + delta)
                                        },
                                        onDragStarted = {
                                            pressed.value = true
                                        },
                                        onDragStopped = {
                                            pressed.value = false
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(18.dp)
                                    .background(colorFromY(bandOffset.value), CircleShape)
                                    .border(2.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Apple")
@Composable
fun EqualizerScreenPreviewApple() {
    LibrePodsTheme(
        designSystem = DesignSystem.Apple
    ) {
        val state = remember { mutableStateOf(AppleUiState()) }

        EqualizerScreen(
            uiState = state.value,
            navigateBack = null,
            setCustomEqEnabled = { state.value = state.value.copy(state = state.value.state.copy(customEq = state.value.state.customEq.copy(state = if (it) 2 else 1))) },
            setCustomEq = {low, mid, high -> state.value = state.value.copy(state = state.value.state.copy(customEq = state.value.state.customEq.copy(low = low, mid = mid, high = high)))}
        )
    }
}

@Preview(name = "Material")
@Composable
fun EqualizerScreenPreviewMaterial() {
    LibrePodsTheme(
        designSystem = DesignSystem.Material
    ) {
        val state = remember { mutableStateOf(AppleUiState()) }

        EqualizerScreen(
            uiState = state.value,
            navigateBack = null,
            setCustomEqEnabled = { state.value = state.value.copy(state = state.value.state.copy(customEq = state.value.state.customEq.copy(state = if (it) 2 else 1))) },
            setCustomEq = {low, mid, high -> state.value = state.value.copy(state = state.value.state.copy(customEq = state.value.state.customEq.copy(low = low, mid = mid, high = high)))}
        )
    }
}
