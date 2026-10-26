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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendTransparencySettings
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.screens.settings.ObserveDongleControls
import me.kavishdevar.librepods.presentation.screens.settings.rememberLibrePodsService
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun TransparencySettingsScreen(viewModel: AppleViewModel, navigateBack: (() -> Unit)?) {
    val ui by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val editor = remember(viewModel) { viewModel.liveATTEditor(ATTHandle.TRANSPARENCY) }
    val live by editor.state.collectAsState()
    TransparencyPage(navigateBack) {
        TransparencyControls(live.displayed, ui.state.controlSession, live.revision,
            writable = viewModel.transparencyTransportAvailable && !ui.state.accommodationsRequest.pending && !ui.state.microphoneState.isActive,
            canEnable = ui.state.hearingModes.customTransparencyAllowed,
            transportAvailable = viewModel.transparencyTransportAvailable, result = null,
            onEdit = { editor.edit(live.revision, it) }, onFinished = { editor.flush() },
            onCanceled = { editor.cancel(live.revision) }, readStatus = ui.state.transparencyReadStatus,
            connected = connection == ConnectionState.CONNECTED)
    }
}

@Composable
internal fun DongleTransparencyScreen(navigateBack: (() -> Unit)?) {
    val controller = rememberLibrePodsService()?.dongleControls ?: return
    val state by controller.state.collectAsState()
    ObserveDongleControls(controller)
    val editor = remember(controller) { controller.liveTransparencyEditor() }
    val live by editor.state.collectAsState()
    TransparencyPage(navigateBack) {
        TransparencyControls(live.displayed, state.controlSession, live.revision,
            writable = state.connected && (!state.busy || live.pending) && state.status?.microphoneActive == false,
            canEnable = state.hearingModes.customTransparencyAllowed,
            transportAvailable = true, result = state.transparencyResult,
            onEdit = { editor.edit(live.revision, it) }, onFinished = { editor.flush() },
            onCanceled = { editor.cancel(live.revision) }, pending = live.pending, error = state.error)
    }
}

@Composable
private fun TransparencyPage(navigateBack: (() -> Unit)?, content: @Composable () -> Unit) {
    StyledScaffold(title = stringResource(R.string.transparency_mode), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(top)); content(); Spacer(Modifier.height(bottom))
        }
    }
}

@Composable
internal fun TransparencyControls(data: ByteArray, session: Long, revision: Long = 0,
    writable: Boolean, canEnable: Boolean, transportAvailable: Boolean,
    result: AccommodationsResult?, onEdit: ((ByteArray) -> ByteArray?) -> Unit,
    onFinished: () -> Unit = {}, onCanceled: () -> Unit = {}, pending: Boolean = false, error: String? = null,
    readStatus: SettingsReadStatus? = null, connected: Boolean = true) {
    val original = remember(data.toList()) { parseTransparencySettingsResponse(data)?.takeIf { data.size == 104 } }
    fun edit(transform: (me.kavishdevar.librepods.core.bluetooth.att.types.TransparencySettings) -> me.kavishdevar.librepods.core.bluetooth.att.types.TransparencySettings) {
        onEdit { current ->
            val parsed = parseTransparencySettingsResponse(current)?.takeIf { current.size == 104 }
            if (parsed == null) null else {
                var value: ByteArray? = null
                sendTransparencySettings(current, transform(parsed)) { _, requested, _ -> value = requested }
                value
            }
        }
    }
    if (original != null && !transportAvailable) Text(stringResource(R.string.transparency_transport_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (original != null) key(session, revision) {
        val gain = original.netAmplification
        val pan = original.balance
        val canonical = gain in -1f..1f && pan in -1f..1f
        val sliderEnabled = writable && original.enabled && canEnable
        StyledToggle(label = stringResource(R.string.transparency_mode), checked = original.enabled,
            enabled = writable && (original.enabled || canEnable),
            description = stringResource(R.string.customize_transparency_mode_description),
            onCheckedChange = { value -> edit { it.copy(enabled = value) } })
        StyledSlider(label = stringResource(R.string.amplification), value = gain.coerceIn(-1f, 1f), valueRange = -1f..1f,
            enabled = sliderEnabled && canonical, independent = true, startImageVector = LocalIcons.current.SpeakerMin,
            endImageVector = LocalIcons.current.SpeakerMax,
            onValueChange = { value -> edit { it.withAmplification(value, it.balance) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledSlider(label = stringResource(R.string.balance), value = pan.coerceIn(-1f, 1f), valueRange = -1f..1f,
            enabled = sliderEnabled && canonical, independent = true, snapPoints = listOf(-1f, 0f, 1f),
            startLabel = stringResource(R.string.left), endLabel = stringResource(R.string.right),
            onValueChange = { value -> edit { it.withAmplification(it.netAmplification, value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledSlider(label = stringResource(R.string.tone), value = (original.leftTone + original.rightTone) / 2,
            valueRange = -1f..1f, enabled = sliderEnabled, independent = true,
            startLabel = stringResource(R.string.darker), endLabel = stringResource(R.string.brighter),
            onValueChange = { value -> edit { it.copy(leftTone = value, rightTone = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledSlider(label = stringResource(R.string.ambient_noise_reduction),
            value = (original.leftAmbientNoiseReduction + original.rightAmbientNoiseReduction) / 2,
            valueRange = 0f..1f, enabled = sliderEnabled, independent = true,
            startLabel = stringResource(R.string.less), endLabel = stringResource(R.string.more),
            onValueChange = { value -> edit { it.copy(leftAmbientNoiseReduction = value, rightAmbientNoiseReduction = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledSlider(label = stringResource(R.string.own_voice_amplification), value = original.ownVoiceAmplification!!,
            valueRange = 0f..1f, enabled = sliderEnabled, independent = true,
            startImageVector = LocalIcons.current.SpeakerMin, endImageVector = LocalIcons.current.SpeakerMax,
            onValueChange = { value -> edit { it.copy(ownVoiceAmplification = value) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledToggle(label = stringResource(R.string.conversation_boost),
            checked = original.leftConversationBoost && original.rightConversationBoost, enabled = sliderEnabled,
            description = stringResource(R.string.conversation_boost_description),
            onCheckedChange = { value -> edit { it.copy(leftConversationBoost = value, rightConversationBoost = value) } })
    } else Text(stringResource(readStatus?.let { settingsReadMessage(it, connected) }
        ?: if (transportAvailable) R.string.settings_waiting else R.string.transparency_transport_unavailable),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    when {
        result == AccommodationsResult.UNCONFIRMED -> Text(stringResource(R.string.att_setting_not_confirmed), color = MaterialTheme.colorScheme.error)
        error != null -> Text(error, color = MaterialTheme.colorScheme.error)
    }
}
