package me.kavishdevar.librepods.presentation.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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

    val draggableState = rememberDraggableState { delta ->
        scope.launch {
            val offset =
                (animatedOffset.value + delta)
                    .coerceIn(0f, screenWidthPx)

            animatedOffset.snapTo(offset)
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
                .graphicsLayer {
                    translationX = animatedOffset.value
                }
                .draggable(
                    enabled = swipeEnabled && previousEntry != null,
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val currentOffset = animatedOffset.value
                        val shouldDismiss = currentOffset > screenWidthPx * 0.35f || velocity > 1000f

                        scope.launch {
                            if (shouldDismiss) {
                                animatedOffset.animateTo(
                                    targetValue = screenWidthPx,
                                    animationSpec = tween(150),
                                    initialVelocity = velocity
                                )
                                dismiss()
                            } else {
                                animatedOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(150),
                                    initialVelocity = velocity
                                )
                            }
                        }
                    }
                )
        ) {
            currentEntry.Content()
        }
    }
}
