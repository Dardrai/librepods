/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.NativeHeadTrackingState
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemContent
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggleContent

@Composable
internal fun StyledListScope.NativeHeadTrackingSetting(
    state: NativeHeadTrackingState, connected: Boolean, change: (Boolean) -> Unit, setup: () -> Unit,
) {
    item { index, count -> NativeHeadTrackingSettingContent(state, connected, change, setup, index, count) }
}

@Composable
internal fun NativeHeadTrackingSettingContent(
    state: NativeHeadTrackingState, connected: Boolean, change: (Boolean) -> Unit, setup: () -> Unit,
    index: Int = 0, count: Int = 1,
) {
    val title = stringResource(R.string.spatial_calibration_title)
    if (!state.calibrated && state.enabled != true) {
        StyledListItemContent(content = {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }, supportingContent = {
            Text(stringResource(if (connected) R.string.spatial_calibration_entry_description else R.string.spatial_audio_connect),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }, orientation = StyledListItemOrientation.Vertical, enabled = connected && !state.busy,
            index = index, count = count, onClick = setup)
    } else {
        val description = when {
            !connected -> R.string.spatial_audio_connect
            state.reason == "motion_changed" -> R.string.spatial_head_tracking_paused
            !state.calibrated -> R.string.spatial_calibration_entry_description
            state.enabled == null -> R.string.spatial_audio_loading
            !state.available && state.enabled != true -> if (state.reason == "spatial_disabled")
                R.string.spatial_head_tracking_needs_spatial else R.string.spatial_audio_unavailable
            else -> R.string.spatial_head_tracking_description
        }
        StyledToggleContent(Modifier, label = title, description = stringResource(description),
            checked = state.enabled == true,
            enabled = connected && !state.busy && state.enabled != null && (state.available || state.enabled == true),
            onCheckedChange = change, index = index, count = count)
    }
}
