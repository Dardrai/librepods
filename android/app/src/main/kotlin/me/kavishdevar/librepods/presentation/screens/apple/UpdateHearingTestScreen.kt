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

package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun UpdateHearingTestRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val editor = remember(viewModel) { viewModel.liveATTEditor(ATTHandle.HEARING_AID) }
    val live by editor.state.collectAsState()
    val state = uiState.state

    UpdateHearingTestScreen(
        data = live.displayed, session = state.controlSession, revision = live.revision,
        enabled = connection == ConnectionState.CONNECTED && !state.microphoneState.isActive &&
            !state.recordingState.isRecording && !state.accommodationsRequest.pending &&
            state.hearingModes.pending == null && state.hearingModes.uncertain == null &&
            state.hearingModes.enrolled == true && state.hearingModes.clinicalReportsKnown,
        navigateBack = navigateBack,
        onEdit = { editor.edit(live.revision, it) }
    )
}

@Composable
fun UpdateHearingTestScreen(
    data: ByteArray,
    session: Long,
    revision: Long = 0,
    enabled: Boolean = false,
    navigateBack: (() -> Unit)?,
    onEdit: ((ByteArray) -> ByteArray?) -> Unit
) {
    val verticalScrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.update_hearing_test),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Text(
                text = stringResource(R.string.hearing_test_value_instruction),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            val writable = enabled && hearingAidSettingsWritable(data)
            val settings = remember(data.toList()) { parseHearingAidSettingsResponse(data) }
            if (settings == null) Text(stringResource(R.string.accommodations_waiting), style = MaterialTheme.typography.bodySmall)
            else if (!hearingAidSettingsWritable(data)) Text(stringResource(R.string.hearing_settings_read_only), style = MaterialTheme.typography.bodySmall)
            val frequencies =
                listOf("250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.width(60.dp))
                Text(
                    text = stringResource(R.string.left),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
                Text(
                    text = stringResource(R.string.right),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }

            frequencies.forEachIndexed { index, freq ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = freq,
                        modifier = Modifier
                            .width(60.dp)
                            .align(Alignment.CenterVertically),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelSmall
                    )
                    HearingThresholdField(settings?.leftEQ?.get(index), session, revision, writable,
                        "${stringResource(R.string.left)} $freq", Modifier.weight(1f)) { value ->
                        onEdit { current -> updateHearingThreshold(current, index, true, value) }
                    }
                    HearingThresholdField(settings?.rightEQ?.get(index), session, revision, writable,
                        "${stringResource(R.string.right)} $freq", Modifier.weight(1f)) { value ->
                        onEdit { current -> updateHearingThreshold(current, index, false, value) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

internal fun updateHearingThreshold(data: ByteArray, index: Int, left: Boolean, value: Float): ByteArray? {
    if (index !in 0..7 || !value.isFinite() || value !in 0f..90f || !hearingAidSettingsWritable(data)) return null
    val current = parseHearingAidSettingsResponse(data) ?: return null
    val updated = if (left) current.copy(leftEQ = current.leftEQ.copyOf().also { it[index] = value })
        else current.copy(rightEQ = current.rightEQ.copyOf().also { it[index] = value })
    var result: ByteArray? = null
    sendHearingAidSettings(data, updated) { _, requested, _ -> result = requested }
    return result
}

private val thresholdText = Regex("(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)")

@Composable
internal fun HearingThresholdField(value: Float?, session: Long, revision: Long, enabled: Boolean,
    label: String, modifier: Modifier = Modifier, onCommit: (Float) -> Unit) {
    var text by remember(session, revision) { mutableStateOf(value?.toString().orEmpty()) }
    var dirty by remember(session, revision) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, enabled) {
        if (!enabled) dirty = false
        if (!dirty) text = value?.toString().orEmpty()
    }
    fun settle() {
        if (!dirty) return
        val parsed = text.trim().takeIf { thresholdText.matches(it) }?.replace(',', '.')
            ?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..90f }
        dirty = false
        if (enabled && parsed != null && parsed != value) onCommit(parsed)
        else text = value?.toString().orEmpty()
    }
    OutlinedTextField(value = text, readOnly = !enabled, singleLine = true,
        onValueChange = { if (enabled) { text = it; dirty = true } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { settle(); focus.clearFocus() }),
        textStyle = MaterialTheme.typography.labelSmall,
        modifier = modifier.semantics { contentDescription = label }.onFocusChanged {
            val lostFocus = focused && !it.isFocused
            focused = it.isFocused
            if (lostFocus) settle()
        })
}

@Preview(name = "Apple")
@Composable
fun UpdateHearingTestScreenPreviewApple() {
    LibrePodsTheme(
        designSystem = DesignSystem.Apple
    ) {
        Box (
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                data = byteArrayOf(), session = 0,
                navigateBack = null,
                onEdit = {}
            )
        }
    }
}

@Preview(name = "Material")
@Composable
fun UpdateHearingTestScreenPreviewMaterial() {
    LibrePodsTheme(
        designSystem = DesignSystem.Material
    ) {
        Box (
            modifier = Modifier
                .wrapContentHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                data = byteArrayOf(), session = 0,
                navigateBack = null,
                onEdit = {}
            )
        }
    }
}

