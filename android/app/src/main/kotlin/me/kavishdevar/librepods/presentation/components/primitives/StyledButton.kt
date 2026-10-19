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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem

enum class MaterialButtonStyle {
    Tonal,
    Normal,
    Outlined,
    Filled
}

@Composable
fun StyledButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    backdrop: Backdrop = rememberLayerBackdrop(),
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    maxScale: Float = 0.1f,
    enabled: Boolean = true,
    materialButtonStyle: MaterialButtonStyle = MaterialButtonStyle.Tonal,
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val onUserClick = {
        if (enabled) {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        }
    }
    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            when (materialButtonStyle) {
                MaterialButtonStyle.Filled -> {
                    Button(
                        modifier = modifier.height(48.dp),
                        onClick = onUserClick,
                        content = content,
                        enabled = enabled,
                    )
                }
                MaterialButtonStyle.Tonal -> {
                    FilledTonalButton(
                        modifier = modifier.height(48.dp),
                        onClick = onUserClick,
                        content = content,
                        enabled = enabled,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = surfaceColor)
                    )
                }

                MaterialButtonStyle.Outlined -> {
                    OutlinedButton(
                        modifier = modifier.height(48.dp),
                        onClick = onUserClick,
                        content = content,
                        enabled = enabled,
                    )
                }

                MaterialButtonStyle.Normal -> {
                    TextButton(
                        modifier = modifier.height(48.dp),
                        onClick = onUserClick,
                        content = content,
                        enabled = enabled,
                    )
                }
            }
        }
        DesignSystem.Apple -> {
            val interactions = remember { MutableInteractionSource() }
            val pressed by interactions.collectIsPressedAsState()
            val pressProgress by animateFloatAsState(
                targetValue = if (enabled && pressed) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                label = "glassButtonPress",
            )
            Row(
                modifier = modifier
                    .appleGlassSurface(
                        backdrop = backdrop,
                        enabled = enabled,
                        surfaceColor = surfaceColor,
                        tint = tint,
                        pressProgress = pressProgress,
                        pressScale = if (isInteractive) maxScale.coerceIn(0f, 0.08f) else 0f,
                    )
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClick = onUserClick,
                    )
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
