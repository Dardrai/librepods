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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.presentation.icons.LocalIcons

@Composable
fun StyledScaffold(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    title: String,
    navigateBack: (() -> Unit)?,
    actionButtons: List<@Composable (backdrop: LayerBackdrop) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (topPadding: Dp, bottomPadding: Dp) -> Unit
) {
    val hazeState = rememberHazeState(blurEnabled = true)

    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
                    ) {
                        TopAppBar(
                            navigationIcon = {
                                if (navigateBack != null) {
                                    Row {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        FilledTonalIconButton(
                                            onClick = navigateBack,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .size(
                                                    IconButtonDefaults.mediumContainerSize(
                                                        IconButtonDefaults.IconButtonWidthOption.Narrow
                                                    )
                                                ),
                                            shape = IconButtonDefaults.mediumRoundShape
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Default.ArrowBack,
                                                contentDescription = "",
                                                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                                            )
                                        }
                                    }
                                }
                            },
                            title = {
                                Crossfade(targetState = title) {
                                    Text(
                                        text = it,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = if (navigateBack != null) 8.dp else 12.dp, end = 12.dp),
                                        style = MaterialTheme.typography.titleLargeEmphasized
                                    )
                                }
                            },
                            actions = {
                                actionButtons.forEach { actionButton ->
                                    actionButton(rememberLayerBackdrop())
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        )
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = modifier
                        .then(
                            if (visible) Modifier.padding(
                                start = paddingValues.calculateStartPadding(
                                    LocalLayoutDirection.current
                                ),
                                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current)
                            ) else Modifier
                        )
                        .fillMaxSize()
                        .hazeSource(hazeState)
                ) {
                    content(paddingValues.calculateTopPadding(), paddingValues.calculateBottomPadding())
                }
            }
        }
        DesignSystem.Apple -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier
                    .then(
                        if (MaterialTheme.colorScheme.surface.luminance() > 0.5) Modifier.shadow(
                            elevation = 36.dp,
                            shape = RoundedCornerShape(52.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        ) else Modifier
                    )
            ) { paddingValues ->
                val topPadding = paddingValues.calculateTopPadding()
                val bottomPadding = paddingValues.calculateBottomPadding()
                val startPadding = paddingValues.calculateLeftPadding(LocalLayoutDirection.current)
                val endPadding = paddingValues.calculateRightPadding(LocalLayoutDirection.current)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(52.dp))
                        .padding(start = startPadding, end = endPadding)
                ) {
                    val backdrop = rememberLayerBackdrop()
                    val bgColor = MaterialTheme.colorScheme.surfaceContainer

                    val density = LocalDensity.current
                    val showBackButton = navigateBack != null
                    var actionsWidth by remember(actionButtons.size) {
                        mutableStateOf(48.dp * actionButtons.size + 8.dp * (actionButtons.size - 1).coerceAtLeast(0))
                    }
                    val titleInset = maxOf(
                        if (showBackButton) 72.dp else 16.dp,
                        if (actionButtons.isNotEmpty()) actionsWidth + 24.dp else 16.dp
                    )

                    if (visible && showBackButton) {
                        Box(
                            modifier = Modifier
                                .zIndex(3f)
                                .padding(top = topPadding + 8.dp, start = 16.dp)
                                .align(Alignment.TopStart)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitFirstDown(requireUnconsumed = false)

                                            do {
                                                val event = awaitPointerEvent()

                                                event.changes.forEach { change ->
                                                    change.consume()
                                                }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    }
                                }
                        ) {
                            StyledIconButton(
                                onClick = { navigateBack?.invoke() },
                                backdrop = backdrop
                            ) {
                                Icon(
                                    imageVector = LocalIcons.current.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn() + scaleIn(
                            initialScale = 0f,
                            animationSpec = tween()
                        ),
                        exit = fadeOut() + scaleOut(
                            targetScale = 0.5f,
                            animationSpec = tween(100)
                        ),
                        modifier = Modifier
                            .zIndex(2f)
                            .height(64.dp + topPadding)
                            .fillMaxWidth()
                    ){
                        val scrimColor = MaterialTheme.colorScheme.scrim

                        Box(
                            modifier = Modifier.hazeEffect(
                                state = hazeState,
                            ) {
                                backgroundColor = bgColor
                                tints = listOf(
                                    HazeTint(scrimColor)
                                )
                                blurRadius = 6.dp
                            }
                        ) {

                            Box(
                                modifier = Modifier.fillMaxSize().padding(top = topPadding),
                                contentAlignment = Alignment.Center
                            ) {
                                Crossfade(targetState = title) {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = titleInset),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = visible && actionButtons.isNotEmpty(),
                        enter = fadeIn() + scaleIn(
                            initialScale = 0f,
                            animationSpec = tween()
                        ),
                        exit = fadeOut() + scaleOut(
                            targetScale = 0.5f,
                            animationSpec = tween(100)
                        ),
                        modifier = Modifier
                            .zIndex(3f)
                            .padding(top = topPadding + 8.dp, end = 16.dp)
                            .align(Alignment.TopEnd)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitFirstDown(requireUnconsumed = false)

                                        do {
                                            val event = awaitPointerEvent()

                                            event.changes.forEach { change ->
                                                change.consume()
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                            }
                    ) {
                        Row(
                            Modifier.onSizeChanged { actionsWidth = with(density) { it.width.toDp() } },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            actionButtons.forEach { actionButton ->
                                actionButton(backdrop)
                            }
                        }
                    }

                    Box(
                        modifier = modifier
                            // Capture only the scrolling body; sampling the sibling chrome avoids a glass feedback loop.
                            .layerBackdrop(backdrop)
                            .background(bgColor)
                            .hazeSource(hazeState)
                            .fillMaxSize()
                    ) {
                        content(topPadding + 64.dp, bottomPadding)
                    }
                }
            }
        }
    }
}
