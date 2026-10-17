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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun HearingProtectionScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
) {
    val backdrop = rememberLayerBackdrop()
    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    StyledScaffold(
        title = stringResource(R.string.hearing_protection),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            if (uiState.vendorIdHook) {
                StyledToggle(
                    title = stringResource(R.string.environmental_noise),
                    label = stringResource(R.string.loud_sound_reduction),
                    description = stringResource(R.string.loud_sound_reduction_description),
                    checked = state.loudSoundReductionEnabled,
                    onCheckedChange = {
                        viewModel.writeATTCharacteristic(
                            ATTHandle.LOUD_SOUND_REDUCTION,
                            byteArrayOf(if (it) 1.toByte() else 0.toByte())
                        )
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
            StyledToggle(
                title = stringResource(R.string.workspace_use),
                label = stringResource(R.string.ppe),
                description = stringResource(R.string.workspace_use_description),
                checked = state.controlStates[ControlCommandIdentifier.PPE_TOGGLE_CONFIG]?.getOrNull(
                    0
                )?.toInt() == 1,
                onCheckedChange = {
                    viewModel.setControlCommand(
                        ControlCommandIdentifier.PPE_TOGGLE_CONFIG, it
                    )
                },
            )
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
