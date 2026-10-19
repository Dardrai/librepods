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

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.presentation.icons.LocalIcons

@Composable
fun StyledIconButton(
    modifier: Modifier = Modifier,
    surfaceColor: Color = Color.Unspecified,
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    onClick: () -> Unit,
    enabled: Boolean = true,
    materialButtonStyle: MaterialButtonStyle = MaterialButtonStyle.Normal,
    content: @Composable () -> Unit,
) {
    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            when (materialButtonStyle) {
                MaterialButtonStyle.Tonal -> {
                    FilledTonalIconButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.size(52.dp)
                    ) {
                        content()
                    }
                }
                MaterialButtonStyle.Filled -> {
                    FilledIconButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.size(52.dp)
                    ) {
                        content()
                    }
                }
                MaterialButtonStyle.Outlined -> {
                    OutlinedIconButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.size(52.dp)
                    ) {
                        content()
                    }
                }
                MaterialButtonStyle.Normal -> {
                    IconButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.size(52.dp)
                    ) {
                        content()
                    }
                }
            }
        }
        DesignSystem.Apple -> {
            val haptics = LocalHapticFeedback.current
            val interactions = remember { MutableInteractionSource() }
            val pressed by interactions.collectIsPressedAsState()
            val pressProgress by animateFloatAsState(
                targetValue = if (enabled && pressed) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                label = "glassIconPress",
            )
            Box(
                modifier = modifier
                    .appleGlassSurface(backdrop, enabled, surfaceColor, pressProgress = pressProgress)
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onClick()
                    }
                    .size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    content()
                }
            }
        }
    }
}

@Preview(uiMode = UI_MODE_NIGHT_NO, name = "Light")
@Preview(uiMode = UI_MODE_NIGHT_YES, name = "Dark")
@Composable
fun StyledIconButtonPreview() {
    Box(modifier = Modifier
        .height(120.dp)
        .width(200.dp)
        .background(
            MaterialTheme.colorScheme.surfaceContainer,
            RoundedCornerShape(28.dp)
        ), contentAlignment = Alignment.Center) {
        StyledIconButton(onClick = { }) {
            Icon(imageVector = LocalIcons.current.Settings, contentDescription = "Settings")
        }
    }
}
