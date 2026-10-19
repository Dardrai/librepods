package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.util.Log
import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.data.app.resolve
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import java.util.concurrent.TimeUnit

private val islandBatteryLabelLineHeight = 16.sp
private val islandBatteryAgeLineHeight = 14.sp
private val islandBatteryPercentageLineHeight = 24.sp

internal fun islandInitialSize(settings: IslandSettings, state: IslandState, motionEnabled: Boolean,
    cameraCapsuleWidth: Float = settings.compactHeight): Pair<Float, Float> = when {
    state == IslandState.CLOSE -> minOf(settings.compactWidth, maxOf(settings.compactHeight, cameraCapsuleWidth)) to settings.compactHeight
    motionEnabled -> minOf(settings.compactWidth, maxOf(settings.compactHeight, cameraCapsuleWidth)) to settings.compactHeight
    state == IslandState.EXPANDED -> settings.expandedWidth to settings.expandedHeight
    else -> settings.compactWidth to settings.compactHeight
}

/** The shell reveals one fixed endpoint layout; text and artwork never reflow during a fold. */
@Composable
internal fun IslandPayload(
    widthPx: Int,
    heightPx: Int,
    revealProgress: Float,
    cameraSafeTopPx: Float = 0f,
    cameraGapPx: Float = 0f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = revealProgress.coerceIn(0f, 1f)
    val lift = with(LocalDensity.current) { 8.dp.toPx() } * (1f - progress)
    Layout(content = content, modifier = modifier.fillMaxSize().drawWithContent {
        clipRect(top = cameraSafeTopPx.coerceIn(0f, size.height)) {
            if (cameraGapPx > 0f) {
                clipRect(left = (size.width - cameraGapPx) / 2f,
                    right = (size.width + cameraGapPx) / 2f, clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
            } else this@drawWithContent.drawContent()
        }
    }) { measurables, constraints ->
        val child = measurables.single().measure(Constraints.fixed(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1)))
        val fittingBody = cameraSafeTopPx > 0f
        val bodyScale = if (fittingBody) minOf(constraints.maxWidth / child.width.toFloat(),
            (constraints.maxHeight - cameraSafeTopPx) / (child.height - cameraSafeTopPx).coerceAtLeast(1f))
            .coerceIn(0f, 1f) else 0.96f + 0.04f * progress
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.placeRelativeWithLayer((constraints.maxWidth - child.width) / 2, 0) {
                transformOrigin = TransformOrigin(0.5f, if (fittingBody) cameraSafeTopPx / child.height else 0f)
                scaleX = bodyScale
                scaleY = bodyScale
                translationY = if (fittingBody) 0f else -lift
            }
        }
    }
}

@Composable
fun IslandContent(
    name: String,
    type: IslandType,
    batteryLevel: Int,
    reversed: Boolean,
    otherDeviceName: String?,

    islandSettings: IslandSettings,
    islandState: IslandState,

    openApp: () -> Unit = {},
    changeState: (IslandState, Boolean) -> Unit = { _, _ ->},
    onClosed: () -> Unit = {},
    resetTimer: () -> Unit = {},

    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    adaptiveStrength: Int = 0,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    requestedAdaptiveStrength: Int? = null,
    model: AirPodsModel = AirPodsModel.UNKNOWN,
    batteries: Set<Battery> = emptySet(),
    batteryOwner: String? = null,
    batteryNow: Long = System.currentTimeMillis(),
    paired: Boolean = false,
    nearby: Boolean = false,
    cameraPaddingPx: Int = 0,
    maximumHeightPx: Int = Int.MAX_VALUE,
    cameraCapsuleWidthPx: Float = islandSettings.compactHeight,
    statusText: String? = null,
    transitionGeneration: Long = 0L,
    onTransitionFinished: (Long, IslandState) -> Unit = { _, _ -> },
    onConnect: (() -> Unit)? = null,
    connectState: IslandConnectState = IslandConnectState.IDLE,
    listeningControlsEnabled: Boolean = true,
    minimumStatusBarWidthPx: Float = Float.POSITIVE_INFINITY,
    statusBarIconCenterYpx: Float = -1f,
    onStatusBarCoverageChanged: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val cells = islandBatteryCells(model, batteries, batteryNow)
    val compactCell = cells.filter { it.component != BatteryComponent.CASE }.filter { it.live && it.level != null }
        .minByOrNull { it.level!! } ?: cells.firstOrNull { it.component != BatteryComponent.CASE && it.level != null }
    val displayBatteryLevel = if (paired) compactCell?.level else batteryLevel
    val batteryLive = !paired || compactCell?.live == true
    val spec = AirPodsSpecs.getSpec(model)
    val showControls = islandSettings.showListeningModeControl &&
        (!paired || BaseCapability.LISTENING_MODE in spec.baseCapabilities)
    val captionLineHeight = maxOf(MaterialTheme.typography.bodyMedium.lineHeight.value,
        islandSettings.fontSize * 0.65f * 1.4f).sp
    val titleLineHeight = maxOf(MaterialTheme.typography.titleLarge.lineHeight.value,
        islandSettings.fontSize * 1.4f).sp
    val headerMinimum = with(density) { maxOf(64.dp.toPx(), titleLineHeight.toPx() +
        if (islandSettings.fontSize > 15) captionLineHeight.toPx() else 0f).toDp() }
    val separateBatteries = paired && islandSettings.expandedBatteryStyle != IslandBatteryStyle.NONE
    // The endpoint fits the largest supported payload, independent of reading freshness or connection status.
    val minimumExpandedHeight = with(density) {
        val batteryStyle = islandSettings.expandedBatteryStyle
        val ringHeight = if (batteryStyle == IslandBatteryStyle.CIRCLE || batteryStyle == IslandBatteryStyle.CIRCLE_TEXT) 28.dp.toPx() else 0f
        val percentageHeight = if (batteryStyle == IslandBatteryStyle.TEXT || batteryStyle == IslandBatteryStyle.CIRCLE_TEXT)
            islandBatteryPercentageLineHeight.toPx() else 0f
        val batteriesHeight = if (separateBatteries) maxOf(ringHeight, percentageHeight) + 4.dp.toPx() +
            islandBatteryLabelLineHeight.toPx() + 6.dp.toPx() + islandBatteryAgeLineHeight.toPx() else 0f
        val controlsHeight = if (showControls) 64.dp.toPx() else 0f
        val gaps = (if (separateBatteries) 12.dp.toPx() else 0f) +
            if (showControls) 12.dp.toPx() else 0f
        maxOf(16.dp.toPx(), cameraPaddingPx.toFloat()) + 16.dp.toPx() + headerMinimum.toPx() + batteriesHeight + controlsHeight + gaps
    }
    val naturalExpandedHeight = maxOf(islandSettings.expandedHeight, kotlin.math.ceil(minimumExpandedHeight))
    val expandedHeight = naturalExpandedHeight.coerceAtMost(maximumHeightPx.coerceAtLeast(1).toFloat())
    val scrollExpandedContent = expandedHeight < naturalExpandedHeight
    val bodyScroll = rememberScrollState()

    val initialMotionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val initialSize = remember { islandInitialSize(islandSettings.copy(expandedHeight = expandedHeight), islandState, initialMotionEnabled, cameraCapsuleWidthPx) }
    val animWidth = remember { Animatable(initialSize.first) }
    val animHeight = remember { Animatable(initialSize.second) }
    val animRadius = remember { Animatable(if (!initialMotionEnabled && islandState == IslandState.EXPANDED) islandSettings.expandedBorderRadius else islandSettings.compactBorderRadius) }
    val animBorderWidth = remember { Animatable(if (!initialMotionEnabled && islandState == IslandState.EXPANDED) islandSettings.expandedBorderWidth else islandSettings.compactBorderWidth) }
    var contentState by remember { mutableStateOf(if (islandState == IslandState.COMPACT) IslandState.COMPACT else IslandState.EXPANDED) }
    val expandedContent = contentState == IslandState.EXPANDED
    val payloadWidth = if (expandedContent) islandSettings.expandedWidth else islandSettings.compactWidth
    val payloadHeight = if (expandedContent) expandedHeight else islandSettings.compactHeight
    val capsuleWidth = minOf(islandSettings.compactWidth, maxOf(islandSettings.compactHeight, cameraCapsuleWidthPx))
    val revealProgress = if (expandedContent)
        ((animHeight.value - islandSettings.compactHeight) / (payloadHeight - islandSettings.compactHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
    else ((animWidth.value - capsuleWidth) / (payloadWidth - capsuleWidth).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val finished by rememberUpdatedState(onTransitionFinished)
    val closed by rememberUpdatedState(onClosed)

    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )

    LaunchedEffect(
        islandState,
        transitionGeneration,
        cameraCapsuleWidthPx,
        islandSettings.expandedWidth,
        expandedHeight,
        islandSettings.compactWidth,
        islandSettings.compactHeight,
        islandSettings.expandedBorderRadius,
        islandSettings.compactBorderRadius,
        islandSettings.expandedBorderWidth,
        islandSettings.compactBorderWidth
    ) {
        val (targetW, targetH) = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedWidth to expandedHeight
            IslandState.COMPACT -> islandSettings.compactWidth to islandSettings.compactHeight
            IslandState.CLOSE -> capsuleWidth to islandSettings.compactHeight
        }

        val targetRadius = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedBorderRadius
            IslandState.COMPACT -> islandSettings.compactBorderRadius
            IslandState.CLOSE -> islandSettings.compactBorderRadius
        }

        val targetBorderWidth = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedBorderWidth
            IslandState.COMPACT -> islandSettings.compactBorderWidth
            IslandState.CLOSE -> islandSettings.compactBorderWidth
        }

        if (islandState == IslandState.EXPANDED) contentState = IslandState.EXPANDED
        val opening = targetW > animWidth.value || targetH > animHeight.value
        val motion = tween<Float>(if (opening) 320 else 220, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
        // Establish the dark status-bar backdrop before the native icon tint finishes fading.
        val widthMotion = if (opening) tween<Float>(64, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)) else motion
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        val wJob = launch { if (motionEnabled) animWidth.animateTo(targetW, widthMotion) else animWidth.snapTo(targetW) }
        val hJob = launch { if (motionEnabled) animHeight.animateTo(targetH, motion) else animHeight.snapTo(targetH) }
        val rJob = launch { if (motionEnabled) animRadius.animateTo(targetRadius, motion) else animRadius.snapTo(targetRadius) }
        val bJob = launch { if (motionEnabled) animBorderWidth.animateTo(targetBorderWidth, motion) else animBorderWidth.snapTo(targetBorderWidth) }

        wJob.join()
        hJob.join()
        rJob.join()
        bJob.join()

        if (islandState == IslandState.COMPACT) contentState = IslandState.COMPACT
        finished(transitionGeneration, islandState)
        if (islandState == IslandState.CLOSE) closed()
    }

    val scale = remember { Animatable(0f) }

    val widthDp = with(density) { animWidth.value.coerceAtLeast(1f).toDp() * (1f + scale.value * if (islandState == IslandState.EXPANDED) 0.01f else 0.1f) }
    val heightDp = with(density) { (animHeight.value.coerceAtLeast(1f) *
        (1f + scale.value * if (islandState == IslandState.EXPANDED) 0.01f else 0.05f))
        .coerceAtMost(maximumHeightPx.coerceAtLeast(1).toFloat()).toDp() }
    val borderWidthDp = with(density) { animBorderWidth.value.toDp() }

    val padding = if (expandedContent) 16.dp else 8.dp
    val compactWingWidth = (with(density) { ((islandSettings.compactWidth - capsuleWidth) / 2f).toDp() } - 8.dp)
        .coerceAtLeast(0.dp)
    val compactBatterySize = minOf(compactWingWidth, with(density) { islandSettings.compactHeight.toDp() } - 16.dp)
        .coerceAtLeast(0.dp)

    val backgroundColor = if (paired && islandSettings.expandedBackgroundColor == IslandSettings().expandedBackgroundColor)
        Color.Black else islandSettings.expandedBackgroundColor.resolve()

    val backgroundColorWithAlpha = animateColorAsState(
        targetValue = if (expandedContent) backgroundColor.copy(alpha = islandSettings.expandedBackgroundAlpha) else Color.Black,
        animationSpec = tween(100)
    )

    val reportStatusBarCoverage by rememberUpdatedState(onStatusBarCoverageChanged)
    val coversStatusBar = expandedContent && islandCoversStatusBar(
        animWidth.value, animHeight.value, minimumStatusBarWidthPx, statusBarIconCenterYpx,
        density.density, backgroundColorWithAlpha.value, animRadius.value)
    var reportedStatusBarCoverage by remember { mutableStateOf<Boolean?>(null) }
    SideEffect {
        if (reportedStatusBarCoverage != coversStatusBar) {
            reportedStatusBarCoverage = coversStatusBar
            reportStatusBarCoverage(coversStatusBar)
        }
    }
    DisposableEffect(Unit) { onDispose { reportStatusBarCoverage(false) } }

    val textColor = if (!expandedContent || backgroundColor.luminance() < 0.5f) Color.White else contentColorFor(backgroundColor)
    val shape = RoundedCornerShape(with(density) { animRadius.value.toDp() } )

    val showConnectAction = paired && type == IslandType.CONNECTED && statusText == null &&
        onConnect != null && (nearby || connectState != IslandConnectState.IDLE)
    val reserveConnectAction = paired && type == IslandType.CONNECTED && statusText == null
    val actionIconOnly = with(density) { islandSettings.expandedWidth.toDp() } < 340.dp
    val actionContentHeight = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }.coerceAtLeast(20.dp)
    val actionMinimumHeight = maxOf(48.dp, actionContentHeight + 16.dp)
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val expandConnectLabel = stringResource(R.string.island_show_connection_controls, name)
    val typeText = if (showConnectAction && connectState == IslandConnectState.CONNECTING)
        stringResource(R.string.island_connecting)
    else if (showConnectAction && connectState == IslandConnectState.FAILED)
        stringResource(R.string.island_connect_failed)
    else statusText ?: when (type) {
        IslandType.CONNECTED -> stringResource(if (nearby) R.string.island_nearby else R.string.island_connected_text)
        IslandType.TAKING_OVER -> stringResource(R.string.island_taking_over_text)
        IslandType.MOVED_TO_REMOTE -> stringResource(R.string.island_moved_to_remote_text)
        IslandType.MOVED_TO_OTHER_DEVICE -> {
            if (otherDeviceName.isNullOrEmpty()) {
                Log.w("IslandWindow", "Other device name is null or empty for MOVED_TO_OTHER_DEVICE type")
            }
            if (reversed) {
                stringResource(R.string.island_moved_to_other_device_reversed_text)
            } else {
                stringResource(R.string.island_moved_to_other_device_text, otherDeviceName ?: "")
            }
        }
    }

    val animationCoroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .requiredSize(width = widthDp, height = heightDp).testTag("island-shell"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(backgroundColorWithAlpha.value, shape)
                .border(
                    width = borderWidthDp,
                    color = textColor.copy(if (borderWidthDp > 0.dp) 0.2f else 0f),
                    shape = shape
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            resetTimer()
                        }
                    }
                }
                .pointerInput(scrollExpandedContent, expandedContent) {
                    if (scrollExpandedContent && expandedContent) return@pointerInput
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 0) changeState(IslandState.EXPANDED, true)
                        else changeState(
                            if (islandSettings.keepCompactVisible) IslandState.COMPACT else IslandState.CLOSE,
                            true
                        )
                    }
                }
        ) {
            key(contentState) {
                IslandPayload(payloadWidth.toInt(), payloadHeight.toInt(), revealProgress,
                    cameraSafeTopPx = if (expandedContent) cameraPaddingPx.toFloat() else 0f,
                    cameraGapPx = if (expandedContent) 0f else (cameraCapsuleWidthPx - with(density) { 12.dp.toPx() }).coerceAtLeast(0f),
                    modifier = if (islandState == contentState) Modifier else Modifier.clearAndSetSemantics { }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .padding(start = padding, end = padding, bottom = padding,
                                top = if (expandedContent) maxOf(padding, with(density) { cameraPaddingPx.toDp() }) else padding)
                            .then(if (expandedContent && scrollExpandedContent) Modifier
                                .testTag("island-scroll-content")
                                .verticalScroll(bodyScroll)
                                .height(with(density) { (naturalExpandedHeight - maxOf(padding.toPx(), cameraPaddingPx.toFloat()) - padding.toPx())
                                    .coerceAtLeast(1f).toDp() }) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (expandedContent) Modifier.height(headerMinimum) else Modifier.weight(1f))
                                .testTag("island-header")
                                .then(if (!expandedContent && showConnectAction)
                                    Modifier.combinedClickable(role = Role.Button, onClickLabel = expandConnectLabel,
                                        onLongClick = {
                                            if (!islandSettings.compactOpenAppOnTap) openApp()
                                            else changeState(IslandState.EXPANDED, true)
                                        },
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            changeState(IslandState.EXPANDED, true)
                                        }
                                    ).semantics { contentDescription = expandConnectLabel }
                                else Modifier.pointerInput(islandState, showConnectAction, islandSettings) {
                                    detectTapGestures(
                                        onPress = {
                                            scale.animateTo(1f, springSpec)
                                            tryAwaitRelease()
                                            scale.animateTo(0f)
                                        },
                                        onTap = {
                                            if (islandState == IslandState.COMPACT || islandState == IslandState.EXPANDED &&
                                                (islandSettings.expandedOpenAppOnTap || islandSettings.keepCompactVisible))
                                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            when (islandState) {
                                                IslandState.EXPANDED -> {
                                                    if (islandSettings.expandedOpenAppOnTap) openApp()
                                                    else if (islandSettings.keepCompactVisible) changeState(
                                                        IslandState.COMPACT,
                                                        false
                                                    )
                                                }

                                                IslandState.COMPACT -> {
                                                    if (islandSettings.compactOpenAppOnTap) openApp()
                                                    else changeState(IslandState.EXPANDED, true)
                                                }

                                                else -> {}
                                            }
                                        },
                                        onLongPress = {
                                            if (islandState == IslandState.COMPACT || islandState == IslandState.EXPANDED &&
                                                (!islandSettings.expandedOpenAppOnTap || islandSettings.keepCompactVisible))
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            animationCoroutineScope.launch { scale.animateTo(0f) }
                                            when (islandState) {
                                                IslandState.EXPANDED -> {
                                                    if (!islandSettings.expandedOpenAppOnTap) openApp()
                                                    else if (islandSettings.keepCompactVisible) changeState(
                                                        IslandState.COMPACT,
                                                        false
                                                    )
                                                }

                                                IslandState.COMPACT -> {
                                                    if (!islandSettings.compactOpenAppOnTap) openApp()
                                                    else changeState(IslandState.EXPANDED, true)
                                                }

                                                else -> {}
                                            }
                                        }
                                    )
                                }),
                            verticalAlignment = if (expandedContent) Alignment.Top else Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val useMonochrome = when (contentState) {
                                IslandState.EXPANDED -> islandSettings.expandedUseMonochrome
                                IslandState.COMPACT -> islandSettings.compactUseMonochrome
                                IslandState.CLOSE -> islandSettings.compactUseMonochrome
                            }
                            AnimatedContent(
                                targetState = useMonochrome,
                                modifier = if (expandedContent) Modifier.testTag("island-artwork") else Modifier.width(compactWingWidth)
                                    .fillMaxHeight().testTag("island-compact-artwork"),
                                contentAlignment = if (expandedContent) Alignment.TopStart else Alignment.Center,
                            ) {
                                if (it) {
                                    Icon(
                                        imageVector = LocalIcons.current.fromName(spec.genericIconName) ?: LocalIcons.current.Headphones,
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = if (expandedContent) Modifier.size(64.dp) else Modifier.fillMaxHeight(0.8f)
                                    )
                                } else {
                                    Image(
                                        bitmap = ImageBitmap.imageResource(spec.primaryImageRes),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .heightIn(max = 64.dp)
                                            .fillMaxHeight(0.8f)
                                    )
                                }
                            }

                            if (expandedContent) Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                    if (islandSettings.fontSize > 15) {
                                        val captionModifier = Modifier.fillMaxWidth().height(with(density) { captionLineHeight.toDp() })
                                            .clipToBounds().testTag("island-status")
                                        val captionContent: @Composable (String) -> Unit = { caption ->
                                            Text(text = caption,
                                                modifier = if (caption == typeText) Modifier else Modifier.clearAndSetSemantics { },
                                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = captionLineHeight),
                                                fontSize = islandSettings.fontSize.sp * 0.65f,
                                                fontWeight = FontWeight.SemiBold,
                                                color = textColor.copy(0.6f), maxLines = 1, softWrap = false,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                        if (motionEnabled) AnimatedContent(targetState = typeText,
                                            modifier = captionModifier,
                                            transitionSpec = {
                                                (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up,
                                                        tween(200, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) togetherWith
                                                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Up,
                                                            tween(200, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))))
                                                    .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> snap() }))
                                            }, label = "island-status") { captionContent(it) }
                                        else Box(captionModifier) { captionContent(typeText) }
                                    }

                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontSize = islandSettings.fontSize.sp,
                                        lineHeight = titleLineHeight,
                                        color = textColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                            }

                            val currentBatteryStyle = when (contentState) {
                                IslandState.EXPANDED -> islandSettings.expandedBatteryStyle
                                IslandState.COMPACT -> islandSettings.compactBatteryStyle
                                IslandState.CLOSE -> islandSettings.compactBatteryStyle
                            }

                            val showRing = currentBatteryStyle == IslandBatteryStyle.CIRCLE || currentBatteryStyle == IslandBatteryStyle.CIRCLE_TEXT
                            val showText = currentBatteryStyle == IslandBatteryStyle.TEXT || currentBatteryStyle == IslandBatteryStyle.CIRCLE_TEXT

                            if (!paired || !expandedContent) BoxWithConstraints(
                                modifier = if (!expandedContent) Modifier.size(compactBatterySize).testTag("island-compact-battery") else Modifier
                                    .heightIn(max = 64.dp)
                                    .fillMaxHeight(0.8f)
                                    .aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val isDarkTheme = backgroundColor.luminance() < 0.5f
                                val batteryColor =
                                    if (!batteryLive || displayBatteryLevel == null) textColor.copy(alpha = 0.5f)
                                    else if (displayBatteryLevel > 25) if (isDarkTheme) Color(0xFF2ED158) else Color(0xFF35C759)
                                    else if (isDarkTheme) Color(0xFFFC4244) else Color(0xFFfe373C)

                                this@Row.AnimatedVisibility(
                                    visible = showRing,
                                    enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                                    exit = fadeOut(animationSpec = tween(100)),
                                ) {
                                    val strokeWidthPx = with(LocalDensity.current) { (maxHeight / 10f).toPx() }

                                    val progress = (displayBatteryLevel ?: 0) / 100f

                                    Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        val startAngle = -90f
                                        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

                                        drawArc(
                                            color = batteryColor.copy(0.2f),
                                            startAngle = 0f,
                                            sweepAngle = 360f,
                                            useCenter = false,
                                            style = stroke
                                        )

                                        drawArc(
                                            color = batteryColor,
                                            startAngle = startAngle,
                                            sweepAngle = 360f * progress,
                                            useCenter = false,
                                            style = stroke
                                        )
                                    }
                                }
                                this@Row.AnimatedVisibility(
                                    visible = showText,
                                    enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                                    exit = fadeOut(animationSpec = tween(100)),
                                ) {
                                    val fontSize = with(LocalDensity.current) {
                                        (maxHeight / 2.5f).toSp()
                                    }
                                    Text(
                                        text = displayBatteryLevel?.toString() ?: "\u2014",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = fontSize,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = batteryColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            if (expandedContent && reserveConnectAction) {
                                // Connection changes keep the name and artwork in their established positions.
                                Box(Modifier.width(if (actionIconOnly) 48.dp else 108.dp).heightIn(min = actionMinimumHeight)
                                    .testTag("island-action-slot"), contentAlignment = Alignment.Center) {
                                    val actionContent: @Composable () -> Unit = {
                                        IslandConnectButton(name, connectState, textColor, actionIconOnly, actionContentHeight,
                                            visible = showConnectAction, motionEnabled = motionEnabled) {
                                            if (showConnectAction && connectState != IslandConnectState.CONNECTING)
                                                onConnect?.invoke()
                                        }
                                    }
                                    if (motionEnabled) androidx.compose.animation.AnimatedVisibility(visible = showConnectAction,
                                        enter = fadeIn(tween(140)), exit = fadeOut(tween(100)),
                                        modifier = if (showConnectAction) Modifier else Modifier.clearAndSetSemantics { }) {
                                        actionContent()
                                    }
                                    else if (showConnectAction) actionContent()
                                }
                            }
                        }

                        if (paired && expandedContent && islandSettings.expandedBatteryStyle != IslandBatteryStyle.NONE) {
                            val commonAge = islandCommonAge(cells)
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    cells.forEach { cell ->
                                        IslandBatteryIndicator(cell, islandSettings.expandedBatteryStyle, textColor,
                                            owner = batteryOwner,
                                            motionEnabled = motionEnabled, frozen = islandState == IslandState.CLOSE,
                                            modifier = Modifier.weight(1f).testTag("island-battery-${cell.component.name.lowercase()}"))
                                    }
                                }
                                // One age line keeps the header and artwork still when readings become cached.
                                Box(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                                    Text(commonAge?.let { stringResource(R.string.island_battery_last_seen, islandAgeText(it)) } ?: "",
                                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, lineHeight = islandBatteryAgeLineHeight, maxLines = 1)
                                    if (commonAge == null) Row(Modifier.fillMaxWidth()) {
                                        cells.forEach { cell ->
                                            val age = cell.ageSeconds?.takeIf { !cell.live && cell.level != null }
                                            Text(age?.let { islandAgeText(islandAgeBucket(it)) } ?: "",
                                                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                                                color = textColor.copy(alpha = 0.5f), fontSize = 11.sp, lineHeight = islandBatteryAgeLineHeight, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showControls && expandedContent,
                            enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                            exit = fadeOut(animationSpec = tween(100)),
                            modifier = Modifier
                                .testTag("island-listening-controls")
                                .height(64.dp)
                                .fillMaxWidth()
                        ) {
                            NoiseControlSettings(
                                modifier = Modifier.fillMaxHeight(),
                                showOffListeningMode = showOffListeningMode,
                                showAdaptiveMode = BaseCapability.ADAPTIVE_AUDIO in spec.baseCapabilities,
                                noiseControlModeValue = noiseControlModeValue,
                                onNoiseControlModeChanged = onNoiseControlModeChanged,
                                adaptiveStrength = adaptiveStrength,
                                requestedAdaptiveStrength = requestedAdaptiveStrength,
                                onAdaptiveStrengthChanged = onAdaptiveStrengthChanged,
                                showLabels = false,
                                enabled = listeningControlsEnabled && !nearby
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IslandConnectButton(name: String, state: IslandConnectState, textColor: Color, iconOnly: Boolean, contentHeight: Dp,
    visible: Boolean, motionEnabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val connecting = state == IslandConnectState.CONNECTING
    val description = stringResource(if (connecting) R.string.island_connecting_device else R.string.island_connect_device, name)
    val label = if (connecting) null else if (iconOnly) "" else
        stringResource(if (state == IslandConnectState.FAILED) R.string.island_connect_retry else R.string.island_connect)
    Button(
        onClick = {
            if (visible && !connecting) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
        },
        enabled = visible && !connecting,
        modifier = Modifier.width(if (iconOnly) 48.dp else 108.dp).heightIn(min = 48.dp).testTag("island-connect")
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = textColor.copy(alpha = 0.12f), contentColor = textColor,
            disabledContainerColor = textColor.copy(alpha = 0.12f), disabledContentColor = textColor,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val labelModifier = Modifier.fillMaxWidth()
            .height(contentHeight)
            .testTag("island-connect-content")
        val labelContent: @Composable (String?) -> Unit = { content ->
            Box(modifier = if (content == label) Modifier else Modifier.clearAndSetSemantics { },
                contentAlignment = Alignment.Center) {
                when (content) {
                    null -> CircularProgressIndicator(modifier = Modifier.size(18.dp), color = textColor, strokeWidth = 2.dp)
                    "" -> Icon(LocalIcons.current.Bluetooth, null, modifier = Modifier.size(20.dp))
                    else -> Text(content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (motionEnabled) AnimatedContent(targetState = label,
            modifier = labelModifier,
            contentAlignment = Alignment.Center,
            transitionSpec = {
                (fadeIn(tween(120, delayMillis = 80)) togetherWith fadeOut(tween(80)))
                    .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> snap() }))
            }, label = "island-connect-label") { labelContent(it) }
        else Box(labelModifier, contentAlignment = Alignment.Center) { labelContent(label) }
    }
}

@Composable
private fun islandAgeText(age: Pair<Long, TimeUnit>): String = when (age.second) {
    TimeUnit.MINUTES -> stringResource(R.string.island_battery_age_minutes, age.first)
    TimeUnit.HOURS -> stringResource(R.string.island_battery_age_hours, age.first)
    TimeUnit.DAYS -> stringResource(R.string.island_battery_age_days, age.first)
    else -> stringResource(R.string.island_battery_age_recent)
}

@Composable
internal fun rememberIslandBatteryGauge(component: BatteryComponent, level: Int?, motionEnabled: Boolean,
    frozen: Boolean = false, owner: String? = null): Float {
    val gauge = remember(owner, component) { Animatable(level?.toFloat() ?: 0f) }
    var previousLevel by remember(owner, component) { mutableStateOf(level) }
    LaunchedEffect(owner, component, level, motionEnabled, frozen) {
        // Keep the outgoing ring still while the capsule folds away.
        if (frozen) return@LaunchedEffect
        val hadReading = previousLevel != null
        previousLevel = level
        val target = level?.toFloat() ?: 0f
        if (!motionEnabled || !hadReading || level == null || gauge.value == target) gauge.snapTo(target)
        else gauge.animateTo(target, tween(280, easing = Easing { progress ->
            val remaining = 1f - progress
            1f - remaining * remaining * remaining
        }))
    }
    return when {
        level == null -> 0f
        !frozen && (previousLevel == null || !motionEnabled) -> level.toFloat()
        else -> gauge.value
    }
}

@Composable
private fun IslandBatteryIndicator(cell: IslandBatteryCell, style: IslandBatteryStyle, textColor: Color,
    owner: String?, motionEnabled: Boolean, frozen: Boolean, modifier: Modifier) {
    val gaugeLevel = if (style == IslandBatteryStyle.CIRCLE || style == IslandBatteryStyle.CIRCLE_TEXT)
        rememberIslandBatteryGauge(cell.component, cell.level, motionEnabled, frozen, owner) else cell.level?.toFloat() ?: 0f
    val label = stringResource(when (cell.component) {
        BatteryComponent.LEFT -> R.string.island_battery_left
        BatteryComponent.RIGHT -> R.string.island_battery_right
        BatteryComponent.CASE -> R.string.island_battery_case
        BatteryComponent.HEADSET -> R.string.island_battery_headset
    })
    val percentage = cell.level?.let { "$it%" } ?: "\u2014"
    val age = if (!cell.live && cell.ageSeconds != null)
        islandAgeText(islandAgeBucket(cell.ageSeconds)) else null
    val freshness = stringResource(when {
        cell.level == null -> R.string.island_battery_unavailable
        cell.live -> R.string.island_battery_current
        else -> R.string.island_battery_cached
    })
    val description = "$label, $percentage, $freshness" + (age?.let { ", ${stringResource(R.string.island_battery_last_seen, it)}" } ?: "") +
        if (cell.charging) ", ${stringResource(R.string.island_battery_charging)}" else ""
    val color = if (!cell.live || cell.level == null) textColor.copy(alpha = 0.5f)
        else if (cell.level <= 25) Color(0xFFFF453A) else Color(0xFF00D352)
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (style == IslandBatteryStyle.CIRCLE || style == IslandBatteryStyle.CIRCLE_TEXT) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = stroke)
                        if (cell.level != null) drawArc(color, -90f, 3.6f * gaugeLevel, false, style = stroke)
                    }
                    if (cell.charging) Icon(LocalIcons.current.Bolt, null, tint = color, modifier = Modifier.size(16.dp))
                }
            }
            if (style == IslandBatteryStyle.TEXT || style == IslandBatteryStyle.CIRCLE_TEXT)
                Text(percentage, color = if (cell.live) textColor else color, fontSize = 17.sp,
                    lineHeight = islandBatteryPercentageLineHeight, maxLines = 1)
        }
        Text(label, color = textColor.copy(alpha = 0.65f), fontSize = 12.sp,
            lineHeight = islandBatteryLabelLineHeight, maxLines = 1)
    }
}

@Preview
@Composable
fun IslandContentPreview(){
    IslandContent(
        name = "Kavish's AirPods Pro 3",
        type = IslandType.CONNECTED,
        batteryLevel = 75,
        reversed = false,
        otherDeviceName = null,

        islandState = IslandState.EXPANDED,
        islandSettings = IslandSettings(),

        openApp = {},
        changeState = { _, _ -> },
        onClosed = {},
        resetTimer = {},

        showOffListeningMode = false,
        noiseControlModeValue = 0,
        onNoiseControlModeChanged = {},
        adaptiveStrength = 0,
        onAdaptiveStrengthChanged = {}
    )
}
