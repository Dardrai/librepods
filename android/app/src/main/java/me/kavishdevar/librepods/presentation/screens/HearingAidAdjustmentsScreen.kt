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

package me.kavishdevar.librepods.presentation.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.HearingAidSettings
import me.kavishdevar.librepods.data.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.data.sendHearingAidSettings
import me.kavishdevar.librepods.data.hearingAidSettingsWritable
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun HearingAidAdjustmentsScreen(viewModel: AirPodsViewModel) {
    val verticalScrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()

    val deviceAddress = state.deviceAddress
    val data = state.requestedATT[ATTHandles.HEARING_AID] ?: state.hearingAidData
    val writable = hearingAidSettingsWritable(data)
    val settings = remember(deviceAddress, data) {
        mutableStateOf(parseHearingAidSettingsResponse(data))
    }
    fun updateSettings(edit: (HearingAidSettings) -> HearingAidSettings) {
        if (!writable) return
        val current = settings.value ?: return
        val updated = edit(current)
        if (updated == current) return
        settings.value = updated
        sendHearingAidSettings(data, updated) { handle, value ->
            viewModel.setATTCharacteristicValue(handle, value, deviceAddress)
        }
    }
    val current = settings.value

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(verticalScrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        if (!writable) {
            Text(stringResource(R.string.hearing_settings_read_only), style = MaterialTheme.typography.bodySmall)
        }

        StyledSlider(
            label = stringResource(R.string.amplification),
            enabled = writable,
            valueRange = -1f..1f,
            value = (current?.netAmplification ?: 0f).coerceIn(-1f, 1f),
            onValueChange = {
                updateSettings { settings -> settings.withAmplification(amplification = it) }
            },
            startIcon = "􀊥",
            endIcon = "􀊩",
            independent = true,
        )

        StyledToggle(
            label = stringResource(R.string.swipe_to_control_amplification),
            enabled = writable,
            checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.HPS_GAIN_SWIPE]?.getOrNull(0) == 0x01.toByte(),
            onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.HPS_GAIN_SWIPE, it) },
            description = stringResource(R.string.swipe_amplification_description)
        )

        StyledSlider(
            label = stringResource(R.string.balance),
            enabled = writable,
            valueRange = -1f..1f,
            value = (current?.balance ?: 0f).coerceIn(-1f, 1f),
            onValueChange = {
                updateSettings { settings -> settings.withAmplification(balance = it) }
            },
            snapPoints = listOf(-1f, 0f, 1f),
            startLabel = stringResource(R.string.left),
            endLabel = stringResource(R.string.right),
            independent = true,
        )

        StyledSlider(
            label = stringResource(R.string.tone),
            enabled = writable,
            valueRange = -1f..1f,
            value = (current?.leftTone ?: 0f).coerceIn(-1f, 1f),
            onValueChange = {
                updateSettings { settings -> settings.copy(leftTone = it, rightTone = it) }
            },
            startLabel = stringResource(R.string.darker),
            endLabel = stringResource(R.string.brighter),
            independent = true,
        )

        StyledSlider(
            label = stringResource(R.string.ambient_noise_reduction),
            enabled = writable,
            valueRange = 0f..1f,
            value = (current?.leftAmbientNoiseReduction ?: 0f).coerceIn(0f, 1f),
            onValueChange = {
                updateSettings { settings -> settings.copy(leftAmbientNoiseReduction = it, rightAmbientNoiseReduction = it) }
            },
            startLabel = stringResource(R.string.less),
            endLabel = stringResource(R.string.more),
            independent = true,
        )

        StyledToggle(
            label = stringResource(R.string.conversation_boost),
            enabled = writable,
            checked = current?.leftConversationBoost ?: false,
            onCheckedChange = { value -> updateSettings { it.copy(leftConversationBoost = value, rightConversationBoost = value) } },
            description = stringResource(R.string.conversation_boost_description)
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
