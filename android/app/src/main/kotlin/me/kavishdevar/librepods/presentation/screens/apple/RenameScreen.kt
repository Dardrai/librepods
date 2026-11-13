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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RenamePacket
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledInputField
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun RenameScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
    refreshDeviceSettings: suspend (AppleDevice) -> DeviceSettingsRefreshResult,
) {
    val uiState by viewModel.uiState.collectAsState()
    RenameContent(uiState.metadata.name, navigateBack) { viewModel.renameDevice(it, refreshDeviceSettings) }
}

@Composable
internal fun RenameContent(
    initialName: String,
    navigateBack: (() -> Unit)?,
    rename: suspend (String) -> Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val textFieldState = rememberTextFieldState(initialText = initialName)
    var baseline by remember { mutableStateOf(initialName) }
    var saving by remember { mutableStateOf(false) }
    var failedName by remember { mutableStateOf<String?>(null) }
    val name = textFieldState.text.toString()
    val valid = runCatching { RenamePacket.create(name) }.isSuccess
    val nameLabel = stringResource(R.string.name)

    LaunchedEffect(initialName) {
        if (textFieldState.text.toString() == baseline) textFieldState.setTextAndPlaceCursorAtEnd(initialName)
        baseline = initialName
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val submit: () -> Unit = {
        val proposed = textFieldState.text.toString()
        if (!saving && runCatching { RenamePacket.create(proposed) }.isSuccess) {
            if (proposed == initialName) {
                keyboardController?.hide()
                navigateBack?.invoke()
            } else {
                saving = true
                failedName = null
                scope.launch {
                    try {
                        if (rename(proposed)) {
                            keyboardController?.hide()
                            navigateBack?.invoke()
                        } else failedName = proposed
                    } finally { saving = false }
                }
            }
        }
    }

    StyledScaffold(
        title = stringResource(R.string.name),
        navigateBack = navigateBack,
        actionButtons = listOf({ backdrop ->
            StyledButton(onClick = submit, backdrop = backdrop, enabled = valid && !saving) {
                Text(stringResource(R.string.rename_done), fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }),
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            StyledInputField(
                textFieldState,
                focusRequester,
                modifier = Modifier.semantics { contentDescription = nameLabel },
                enabled = !saving,
                onDone = submit,
            )

            val error = when {
                failedName == name -> R.string.rename_not_sent
                name.isBlank() -> R.string.rename_name_required
                name.toByteArray(Charsets.UTF_8).size > 64 -> R.string.rename_name_too_long
                !valid -> R.string.rename_name_invalid
                else -> null
            }
            error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }

            Spacer(modifier = Modifier.height(bottomPadding))

        }
    }
}
