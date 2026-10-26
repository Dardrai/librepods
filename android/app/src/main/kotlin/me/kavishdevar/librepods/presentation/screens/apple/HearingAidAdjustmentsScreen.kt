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

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun HearingAidAdjustmentsScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val verticalScrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val state = uiState.state
    val editor = remember(viewModel) { viewModel.liveATTEditor(ATTHandle.HEARING_AID) }
    val live by editor.state.collectAsState()
    StyledScaffold(
        title = stringResource(R.string.adjustments),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))
            key(viewModel) {
                HearingAidAdjustmentControls(live.displayed, state.controlSession, live.revision,
                    enabled = connection == ConnectionState.CONNECTED && !state.microphoneState.isActive &&
                        !state.accommodationsRequest.pending && state.hearingModes.pending == null &&
                        state.hearingModes.uncertain == null && state.hearingModes.enrolled == true &&
                        state.hearingModes.clinicalReportsKnown,
                    gainSwipe = state.controlStates[ControlCommandIdentifier.HPS_GAIN_SWIPE]?.getOrNull(0) == 0x01.toByte(),
                    onGainSwipe = { viewModel.setControlCommand(ControlCommandIdentifier.HPS_GAIN_SWIPE, it) },
                    onEdit = { editor.edit(live.revision, it) }, onFinished = { editor.flush() },
                    onCanceled = { editor.cancel(live.revision) }, readStatus = state.hearingAidReadStatus,
                    connected = connection == ConnectionState.CONNECTED)
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Composable
internal fun HearingAidAdjustmentControls(data: ByteArray, session: Long, revision: Long = 0,
    enabled: Boolean, gainSwipe: Boolean, onGainSwipe: (Boolean) -> Unit,
    onEdit: ((ByteArray) -> ByteArray?) -> Unit, onFinished: () -> Unit = {}, onCanceled: () -> Unit = {},
    readStatus: SettingsReadStatus = SettingsReadStatus.WAITING, connected: Boolean = true) {
    val original = remember(data.toList()) { parseHearingAidSettingsResponse(data) }
    val writable = enabled && hearingAidSettingsWritable(data)
    fun edit(transform: (HearingAidSettings) -> HearingAidSettings) {
        onEdit { current ->
            val parsed = parseHearingAidSettingsResponse(current)
            if (parsed == null || !hearingAidSettingsWritable(current)) null else {
                var value: ByteArray? = null
                sendHearingAidSettings(current, transform(parsed)) { _, requested, _ -> value = requested }
                value
            }
        }
    }
    if (original == null) {
        Text(stringResource(settingsReadMessage(readStatus, connected)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (!hearingAidSettingsWritable(data)) Text(stringResource(R.string.hearing_settings_read_only), style = MaterialTheme.typography.bodySmall)
    key(session, revision) {
        StyledSlider(enabled = writable, label = stringResource(R.string.amplification), valueRange = -1f..1f,
            value = original.netAmplification.coerceIn(-1f, 1f), independent = true,
            startImageVector = LocalIcons.current.SpeakerMin, endImageVector = LocalIcons.current.SpeakerMax,
            onValueChange = { value -> edit { it.withAmplification(amplification = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledToggle(enabled = writable, label = stringResource(R.string.swipe_to_control_amplification),
            checked = gainSwipe, onCheckedChange = onGainSwipe,
            description = stringResource(R.string.swipe_amplification_description))
        StyledSlider(enabled = writable, label = stringResource(R.string.balance), valueRange = -1f..1f,
            value = original.balance.coerceIn(-1f, 1f), independent = true, snapPoints = listOf(-1f, 0f, 1f),
            startLabel = stringResource(R.string.left), endLabel = stringResource(R.string.right),
            onValueChange = { value -> edit { it.withAmplification(balance = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledSlider(enabled = writable, label = stringResource(R.string.tone), valueRange = -1f..1f,
            value = original.leftTone.coerceIn(-1f, 1f), independent = true,
            startLabel = stringResource(R.string.darker), endLabel = stringResource(R.string.brighter),
            onValueChange = { value -> edit { it.copy(leftTone = value, rightTone = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledSlider(enabled = writable, label = stringResource(R.string.ambient_noise_reduction), valueRange = 0f..1f,
            value = original.leftAmbientNoiseReduction.coerceIn(0f, 1f), independent = true,
            startLabel = stringResource(R.string.less), endLabel = stringResource(R.string.more),
            onValueChange = { value -> edit { it.copy(leftAmbientNoiseReduction = value, rightAmbientNoiseReduction = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledToggle(enabled = writable, label = stringResource(R.string.conversation_boost),
            checked = original.leftConversationBoost,
            description = stringResource(R.string.conversation_boost_description),
            onCheckedChange = { value -> edit { it.copy(leftConversationBoost = value, rightConversationBoost = value) } })
    }
}
