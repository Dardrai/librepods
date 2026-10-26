package me.kavishdevar.librepods.presentation.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private enum class Direction {
    Forward,
    Back,
    None
}

class SwipeBackSceneStrategy<T : Any>(
    private val backStack: List<T>,
    private val enabled: Boolean,
    private val backRequests: Channel<CompletableDeferred<Unit>>,
    private val onDismiss: () -> Unit
) : SceneStrategy<T> {

    private var previousBackStack = backStack.toList()
    private var direction = Direction.None

    override fun SceneStrategyScope<T>.calculateScene(
        entries: List<NavEntry<T>>
    ): Scene<T>? {
        if (entries.isEmpty()) return null

        val currentEntry = entries.last()
        val previousEntry = entries.getOrNull(entries.lastIndex - 1)

        // NavDisplay also calculates speculative prior scenes; only the real stack defines navigation.
        val stack = backStack.toList()
        if (stack != previousBackStack) {
            direction = when {
                previousBackStack.isEmpty() -> Direction.None
                stack.lastOrNull() in previousBackStack -> Direction.Back
                else -> Direction.Forward
            }
            previousBackStack = stack
        }
        val isCurrentScene = entries.size == stack.size
        val sceneDirection = if (isCurrentScene) direction else Direction.None
        val visibleEntries = listOfNotNull(previousEntry, currentEntry)
        val priorEntries = entries.dropLast(1)

        return object : Scene<T> {
            override val key: Any
                get() = currentEntry.contentKey

            override val entries: List<NavEntry<T>>
                get() = visibleEntries

            override val previousEntries: List<NavEntry<T>>
                get() = priorEntries

            override val content: @Composable () -> Unit
                get() = {
                    SwipeBackSceneContent(
                        previousEntry = previousEntry,
                        currentEntry = currentEntry,
                        direction = sceneDirection,
                        swipeEnabled = enabled,
                        backRequests = backRequests,
                        onDismiss = {
                            if (isCurrentScene && backStack.toList() == stack) onDismiss()
                        }
                    )
                }
        }
    }
}

@Composable
private fun <T : Any> SwipeBackSceneContent(
    previousEntry: NavEntry<T>?,
    currentEntry: NavEntry<T>,
    direction: Direction,
    swipeEnabled: Boolean,
    backRequests: Channel<CompletableDeferred<Unit>>,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalWindowInfo.current.containerDpSize.width.toPx()
    }.coerceAtLeast(1f)

    val animatedOffset = remember(currentEntry.contentKey) {
        Animatable(
            if (direction == Direction.Forward) {
                screenWidthPx
            } else {
                0f
            }
        )
    }

    val scope = rememberCoroutineScope()
    val canNavigateBack by rememberUpdatedState(previousEntry != null)
    val dismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(currentEntry.contentKey) {
        if (direction == Direction.Forward) {
            animatedOffset.animateTo(
                0f,
                tween(220)
            )
        }
    }

    LaunchedEffect(Unit) {
        for (completed in backRequests) {
            try {
                if (canNavigateBack) {
                    animatedOffset.animateTo(screenWidthPx, tween(150))
                    dismiss()
                }
            } catch (_: CancellationException) {
                // A gesture may interrupt the animation without disposing this scene's receiver.
                currentCoroutineContext().ensureActive()
            } finally {
                completed.complete(Unit)
            }
        }
    }

    PredictiveBackHandler(enabled = previousEntry != null) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                val progress = backEvent.progress

                animatedOffset.snapTo(
                    progress * screenWidthPx
                )
            }

            animatedOffset.animateTo(
                screenWidthPx,
                tween(150)
            )

            dismiss()
        } catch (_: CancellationException) {
            animatedOffset.animateTo(
                0f,
                tween(150)
            )
        }
    }

    fun settleSwipe(offset: Float, velocity: Float, cancelled: Boolean) {
        scope.launch {
            val shouldDismiss = !cancelled &&
                (offset > screenWidthPx * 0.35f || velocity > 1000f)
            animatedOffset.animateTo(
                targetValue = if (shouldDismiss) screenWidthPx else 0f,
                animationSpec = tween(150),
                initialVelocity = velocity,
            )
            if (shouldDismiss) dismiss()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = (animatedOffset.value / screenWidthPx).coerceIn(0f, 1f)
                    translationX = -(screenWidthPx / 3f) * (1f - progress)
                }
        ) {
            previousEntry?.Content()
        }
        Box(
            Modifier
                .fillMaxSize()
                // Keep the edge hit area fixed while the page slides.
                .pointerInput(swipeEnabled, previousEntry?.contentKey, screenWidthPx) {
                    if (!swipeEnabled || previousEntry == null) return@pointerInput
                    val edgeWidth = 24.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Main-pass child consumption keeps buttons, switches and sliders in charge.
                        if (down.isConsumed || down.position.x !in 0f..edgeWidth) return@awaitEachGesture
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPointerInputChange(down)
                        var dragging = false
                        var offset = 0f
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.size != 1 || change.isConsumed) break
                                velocityTracker.addPointerInputChange(change)
                                if (!change.pressed) {
                                    if (dragging) {
                                        change.consume()
                                        settleSwipe(offset, velocityTracker.calculateVelocity().x, false)
                                        dragging = false
                                    }
                                    break
                                }
                                if (!dragging) {
                                    val travel = change.position - down.position
                                    val slop = viewConfiguration.touchSlop
                                    if (abs(travel.x) <= slop && abs(travel.y) <= slop) continue
                                    if (travel.x <= slop || travel.x <= abs(travel.y) * 1.5f) break
                                    dragging = true
                                    offset = (animatedOffset.value + travel.x - slop).coerceIn(0f, screenWidthPx)
                                } else {
                                    offset = (offset + change.positionChange().x).coerceIn(0f, screenWidthPx)
                                }
                                change.consume()
                                val nextOffset = offset
                                scope.launch { animatedOffset.snapTo(nextOffset) }
                            }
                        } finally {
                            if (dragging) settleSwipe(offset, 0f, true)
                        }
                    }
                }
                .graphicsLayer {
                    translationX = animatedOffset.value
                }
        ) {
            currentEntry.Content()
        }
    }
}
