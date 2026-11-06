/*
    LibrePods - AirPods liberated from Appleâ€™s ecosystem
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.effectiveControl
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.math.roundToInt

@Composable
fun AccessibilitySettingsScreen(viewModel: AppleViewModel, navigateBack: (() -> Unit)?,
    navigateToHeadphoneAccommodations: () -> Unit) {
    val ui by viewModel.uiState.collectAsState()
    AccessibilitySettingsContent(ui, navigateBack, navigateToHeadphoneAccommodations,
        setInt = { id, value ->
            if (id == ControlCommandIdentifier.CHIME_VOLUME)
                viewModel.setControlCommand(id, byteArrayOf(value.toByte(), 0x50))
            else viewModel.setControlCommand(id, value)
        },
        setBoolean = { id, value -> viewModel.setControlCommand(id, value) },
        setLoudSoundReduction = { viewModel.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(if (it) 1 else 0)) })
}

internal val volumeSwipeIntervalValues = listOf(0, 1, 2)

@Composable
internal fun AccessibilitySettingsContent(ui: AppleUiState, navigateBack: (() -> Unit)?,
    navigateToHeadphoneAccommodations: () -> Unit,
    setInt: (ControlCommandIdentifier, Int) -> Unit,
    setBoolean: (ControlCommandIdentifier, Boolean) -> Unit,
    setLoudSoundReduction: (Boolean) -> Unit = {},
    canWriteControl: (ControlCommandIdentifier) -> Boolean = { true }, dongle: Boolean = false,
    toneVolume: @Composable (() -> Unit)? = null, caseVolume: @Composable (() -> Unit)? = null) {
    val state = ui.state
    val capabilities = AirPodsSpecs.getSpec(ui.metadata.model).baseCapabilities
    fun value(id: ControlCommandIdentifier) = state.effectiveControl(id, ui.settings)?.firstOrNull()?.toInt()?.and(255)
    StyledScaffold(title = stringResource(R.string.accessibility), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(top))
            for ((id, title, description) in listOf(
                Triple(ControlCommandIdentifier.DOUBLE_CLICK_INTERVAL, R.string.press_speed, R.string.press_speed_description),
                Triple(ControlCommandIdentifier.CLICK_HOLD_INTERVAL, R.string.press_and_hold_duration, R.string.press_and_hold_duration_description))) {
                StyledList(title = stringResource(title), description = stringResource(description), key = value(id)) {
                    listOf(R.string.default_option, R.string.slower, R.string.slowest).forEachIndexed { index, label ->
                        styledListItem(contentText = stringResource(label), selected = value(id) == index,
                            enabled = canWriteControl(id), onClick = { setInt(id, index) })
                    }
                }
            }
            val oneBud = ControlCommandIdentifier.ONE_BUD_ANC_MODE
            StyledToggle(title = stringResource(R.string.noise_control),
                label = stringResource(R.string.noise_cancellation_single_airpod),
                description = stringResource(R.string.noise_cancellation_single_airpod_description),
                checked = value(oneBud) == 1, enabled = canWriteControl(oneBud),
                onCheckedChange = { setBoolean(oneBud, it) })
            if (!dongle && BaseCapability.LOUD_SOUND_REDUCTION in capabilities && ui.vendorIdHook)
                StyledToggle(label = stringResource(R.string.loud_sound_reduction),
                    description = stringResource(R.string.loud_sound_reduction_description),
                    checked = state.loudSoundReductionEnabled, onCheckedChange = setLoudSoundReduction)
            StyledListItem(contentText = stringResource(R.string.headphone_accommodations), onClick = navigateToHeadphoneAccommodations)
            if (toneVolume != null) toneVolume() else {
                val id = ControlCommandIdentifier.CHIME_VOLUME
                StyledSlider(label = stringResource(R.string.tone_volume), description = stringResource(R.string.tone_volume_description),
                    value = value(id)?.toFloat() ?: 75f, valueRange = 0f..100f, snapPoints = listOf(75f), independent = true,
                    enabled = canWriteControl(id), startImageVector = LocalIcons.current.SpeakerMin, endImageVector = LocalIcons.current.SpeakerMax,
                    onValueChange = { setInt(id, it.roundToInt()) })
            }
            if (BaseCapability.SWIPE_FOR_VOLUME in capabilities) {
                val id = ControlCommandIdentifier.VOLUME_SWIPE_MODE
                StyledToggle(label = stringResource(R.string.volume_control), description = stringResource(R.string.volume_control_description),
                    checked = value(id) == 1, enabled = canWriteControl(id), onCheckedChange = { setBoolean(id, it) })
                val interval = ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL
                StyledList(title = stringResource(R.string.volume_swipe_speed),
                    description = stringResource(R.string.volume_swipe_speed_description), key = value(interval)) {
                    listOf(R.string.default_option, R.string.longer, R.string.longest).forEachIndexed { index, label ->
                        val wireValue = volumeSwipeIntervalValues[index]
                        styledListItem(contentText = stringResource(label), selected = value(interval) == wireValue,
                            enabled = canWriteControl(interval), onClick = { setInt(interval, wireValue) })
                    }
                }
            }
            val sounds = ControlCommandIdentifier.IN_CASE_TONE_CONFIG
            if (state.effectiveControl(sounds, ui.settings) != null || dongle) {
                StyledToggle(label = stringResource(R.string.dongle_case_sounds), checked = value(sounds) == 1,
                    enabled = canWriteControl(sounds), onCheckedChange = { setBoolean(sounds, it) })
                caseVolume?.invoke()
            }
            Spacer(Modifier.height(bottom))
        }
    }
}
