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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun HearingAidScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
    navigateToHearingAidAdjustments: () -> Unit,
    navigateToHearingTest: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val state = uiState.state
    val modes = state.hearingModes
    val canReadProfile = viewModel.transparencyTransportAvailable && uiState.metadata.model != AirPodsModel.UNKNOWN &&
        connection == ConnectionState.CONNECTED && parseHearingAidSettingsResponse(state.hearingAidData) != null &&
        parseTransparencySettingsResponse(state.transparencyData)?.enabled == false
    StyledScaffold(title = stringResource(R.string.hearing_aid), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(top))
            val status = when {
                modes.pending != null -> R.string.hearing_mode_pending
                modes.uncertain != null -> R.string.hearing_mode_unconfirmed
                connection != ConnectionState.CONNECTED -> R.string.settings_connect_airpods
                state.hearingAidReadStatus == SettingsReadStatus.UNAVAILABLE ||
                    state.transparencyReadStatus == SettingsReadStatus.UNAVAILABLE -> R.string.accommodations_unavailable
                modes.hearingAidEnabled == null || modes.mediaAssistEnabled == null -> R.string.hearing_modes_unknown
                parseTransparencySettingsResponse(state.transparencyData)?.enabled == true -> R.string.hearing_transparency_off_required
                !canReadProfile -> settingsReadMessage(state.hearingAidReadStatus, true)
                else -> null
            }
            status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            StyledList(title = stringResource(R.string.hearing_aid), key = Triple(modes, connection, canReadProfile)) {
                HearingModeRows(modes, connection == ConnectionState.CONNECTED, canReadProfile,
                    onEnable = { mode -> viewModel.setHearingMode(mode, true) },
                    onDisable = { mode -> viewModel.setHearingMode(mode, false) })
                styledListItem(contentText = stringResource(R.string.adjustments), onClick = navigateToHearingAidAdjustments)
            }
            StyledListItem(contentText = stringResource(R.string.update_hearing_test), onClick = navigateToHearingTest)
            Spacer(Modifier.height(bottom))
        }
    }

}

@Composable
internal fun StyledListScope.HearingModeRows(modes: HearingModes, connected: Boolean, canReadProfile: Boolean,
    onEnable: (HearingMode) -> Unit, onDisable: (HearingMode) -> Unit) {
    HearingMode.entries.forEach { mode ->
        val reported = if (mode == HearingMode.HEARING_AID) modes.hearingAidEnabled else modes.mediaAssistEnabled
        val label = stringResource(if (mode == HearingMode.HEARING_AID) R.string.hearing_aid else R.string.media_assist)
        when {
            modes.uncertain == mode -> styledListItem(
                contentText = stringResource(if (mode == HearingMode.HEARING_AID)
                    R.string.hearing_aid_recovery_off else R.string.media_assist_recovery_off),
                enabled = connected && modes.canChange(mode, false), onClick = { onDisable(mode) })
            reported == null -> styledListItem(contentText = label)
            else -> styledToggle(label = label,
                description = stringResource(if (mode == HearingMode.HEARING_AID) R.string.hearing_aid_description else R.string.media_assist_description),
                checked = reported, enabled = connected && modes.canChange(mode, !reported) && (reported || canReadProfile),
                onCheckedChange = { enabled -> if (enabled) onEnable(mode) else onDisable(mode) })
        }
    }
}
