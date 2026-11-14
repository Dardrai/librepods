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

package me.kavishdevar.librepods.presentation.screens.settings

import androidx.compose.runtime.getValue
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.common.AppInfoCard
import me.kavishdevar.librepods.presentation.components.common.DeviceInfoCard
import me.kavishdevar.librepods.presentation.components.primitives.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledConfirmationDialog
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.overlays.QuickSetting
import me.kavishdevar.librepods.presentation.overlays.QuickSettingsChoices
import me.kavishdevar.librepods.presentation.overlays.quickSettingsOrder
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.utils.XposedState
import me.kavishdevar.librepods.utils.BluetoothIdentitySettings
import me.kavishdevar.librepods.utils.IdentityUpdateResult
import me.kavishdevar.librepods.utils.QuickSettingsPreferences
import me.kavishdevar.librepods.utils.rememberHiddenQuickSettings

@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel = viewModel(),
    navigateBack: (() -> Unit)?,
    navigateToOpenSourceLicenses: () -> Unit,
    navigateToReleaseNotesScreen: () -> Unit,
    navigateToBleSettingsScreen: () -> Unit,
    navigateToAppAppearanceScreen: () -> Unit,
    navigateToAppAccessibilityScreen: () -> Unit,
    navigateToIslandSettingsScreen: () -> Unit,
    navigateToDongleSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(XposedState.isAvailable) { BluetoothIdentitySettings.initialize(context) }

    val backdrop = rememberLayerBackdrop()

    val quickSettingsSheet = remember { mutableStateOf(false) }
    val hiddenQuickSettings by rememberHiddenQuickSettings()

    StyledScaffold(
        title = stringResource(R.string.settings),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layerBackdrop(backdrop)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Spacer(modifier = Modifier.height(20.dp))

            CompanionSettingsSection()
            Spacer(modifier = Modifier.height(16.dp))
            PcControlsVisibilitySetting()
            Spacer(modifier = Modifier.height(16.dp))
            PrivilegedAccessSetting()
            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(contentText = stringResource(R.string.dongle_title), onClick = navigateToDongleSettings)
            Spacer(modifier = Modifier.height(16.dp))

            if (state.state.hasConnectedToAACP) {
                StyledListItem(
                    contentText = stringResource(R.string.appearance),
                    onClick = navigateToAppAppearanceScreen
                )

                Spacer(modifier = Modifier.height(16.dp))

                StyledList(title = stringResource(R.string.interaction)) {
                    styledToggle(
                        label = stringResource(R.string.swipe_anywhere_to_go_back),
                        checked = state.settings.swipeAnywhereForBack,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings {
                                it.copy(swipeAnywhereForBack = checked)
                            }
                        },
                    )

                    styledToggle(
                        label = stringResource(R.string.use_highest_refresh_rate),
                        checked = state.settings.useHighestRefreshRate,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings {
                                it.copy(useHighestRefreshRate = checked)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(
                contentText = stringResource(R.string.accessibility),
                onClick = navigateToAppAccessibilityScreen
            )

            Spacer(modifier = Modifier.height(16.dp))
            StyledListItem(contentText = stringResource(R.string.quick_settings_title),
                onClick = { quickSettingsSheet.value = true })

            if (state.state.hasConnectedToAACP) {
                Spacer(modifier = Modifier.height(16.dp))

                StyledListItem(
                    contentText = stringResource(R.string.island_settings),
                    onClick = navigateToIslandSettingsScreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(
                title = stringResource(R.string.advanced_options),
                description = stringResource(R.string.do_not_change)
            ) {
                if (BluetoothIdentitySettings.usesNativeProperties || XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                    val restartBluetoothText =
                        stringResource(R.string.found_offset_restart_bluetooth)
                    val identityFailureText = stringResource(if (BluetoothIdentitySettings.usesNativeProperties)
                        R.string.bluetooth_identity_root_setup_required else R.string.bluetooth_identity_change_failed)
                    styledToggle(
                        label = stringResource(R.string.act_as_an_apple_device) + " (${
                            stringResource(
                                if (BluetoothIdentitySettings.usesNativeProperties) R.string.requires_root else R.string.requires_xposed
                            )
                        })",
                        description = stringResource(R.string.act_as_an_apple_device_description),
                        checked = state.vendorIdHook,
                        enabled = !state.vendorIdHookBusy,
                        onCheckedChange = { checked ->
                            viewModel.setVendorIdHook(checked) { result ->
                                when (result) {
                                    IdentityUpdateResult.UPDATED -> Toast.makeText(context, restartBluetoothText, Toast.LENGTH_SHORT).show()
                                    IdentityUpdateResult.FAILED -> Toast.makeText(context, identityFailureText, Toast.LENGTH_LONG).show()
                                    IdentityUpdateResult.BUSY -> Unit
                                }
                            }
                        }
                    )
                }

                styledToggle(
                    label = stringResource(R.string.enable_debug_mode),
                    description = stringResource(R.string.debug_mode_description),
                    checked = state.settings.debugMode,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings {
                            it.copy(debugMode = checked)
                        }
                    }
                )

                styledListItem(
                    contentText = stringResource(R.string.ble_settings),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = navigateToBleSettingsScreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(title = stringResource(R.string.contact)) {
                val errorOpeningGitHubLink = stringResource(R.string.error_opening_github_link)

                styledListItem(
                    contentText =  stringResource(R.string.contact_github_title),
                    supportingText = stringResource(R.string.contact_github_description),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/Ripthulhu/librepods".toUri()
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(
                                context,
                                errorOpeningGitHubLink,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            DeviceInfoCard()

            Spacer(modifier = Modifier.height(16.dp))

            AppInfoCard(navigateToReleaseNotesScreen)

            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(
                contentText =  stringResource(R.string.open_source_licenses),
                onClick = navigateToOpenSourceLicenses,
            )

            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(
                contentText =  stringResource(R.string.reset_app_settings),
                onClick = viewModel::showResetDialog,
            )


            Spacer(modifier = Modifier.height(bottomPadding))

//        if (state.showCameraDialog) {
//            AlertDialog(onDismissRequest = { viewModel.setShowCameraDialog(false) }, title = {
//                Text(
//                    stringResource(R.string.set_custom_camera_package),
//                    style = MaterialTheme.typography.titleSmall,
//                )
//            }, text = {
//                Column {
//                    Text(
//                        stringResource(R.string.enter_custom_camera_package),
//                        style = MaterialTheme.typography.bodyMedium,
//                        modifier = Modifier.padding(bottom = 8.dp)
//                    )
//
//                    OutlinedTextField(
//                        value = state.cameraPackageValue,
//                        onValueChange = {
//                            viewModel.setCameraPackageValue(it)
//                            viewModel.setCameraPackageError(null)
//                        },
//                        modifier = Modifier.fillMaxWidth(),
//                        isError = state.cameraPackageError != null,
//                        keyboardOptions = KeyboardOptions(
//                            keyboardType = KeyboardType.Ascii,
//                            capitalization = KeyboardCapitalization.None
//                        ),
//                        colors = OutlinedTextFieldDefaults.colors(
//                            focusedBorderColor = if (isDarkTheme) Color(0xFF007AFF) else Color(
//                                0xFF3C6DF5
//                            ),
//                            unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray
//                        ),
//                        supportingText = {
//                            if (state.cameraPackageError != null) {
//                                Text(
//                                    state.cameraPackageError ?: "",
//                                    color = MaterialTheme.colorScheme.error
//                                )
//                            }
//                        },
//                        label = { Text(stringResource(R.string.custom_camera_package)) })
//                }
//            }, confirmButton = {
//                val successText = stringResource(R.string.custom_camera_package_set_success)
//                TextButton(
//                    onClick = {
//                        viewModel.saveCameraPackage()
//                        Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
//                    }) {
//                    Text(
//                        "Save",
//                        style = MaterialTheme.typography.labelMedium
//                    )
//                }
//            }, dismissButton = {
//                TextButton(
//                    onClick = { viewModel.setShowCameraDialog(false) }) {
//                    Text(
//                        "Cancel",
//                        style = MaterialTheme.typography.labelMedium
//                    )
//                }
//            })
//        }
        }
    }

    val showResetDialog = remember(state.showResetDialog) { mutableStateOf(state.showResetDialog) }

    StyledConfirmationDialog(
        showDialog = showResetDialog,
        title = stringResource(R.string.reset_app_settings),
        message = stringResource(R.string.reset_app_settings_description),
        confirmText = stringResource(R.string.reset),
        dismissText = stringResource(R.string.cancel),
        onConfirm = viewModel::resetAppSettings,
        onDismiss = viewModel::resetDialogDismissed,
        backdrop = backdrop
    )

    StyledBottomSheet(
        visible = quickSettingsSheet.value,
        onDismiss = { quickSettingsSheet.value = false },
        backdrop = backdrop
    ) { innerBackdrop, _ ->
        val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceAtLeast(160.dp)
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).testTag("quick-settings-configuration").verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.quick_settings_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                StyledButton(onClick = { quickSettingsSheet.value = false }, backdrop = innerBackdrop) {
                    Text(stringResource(R.string.quick_settings_done), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(stringResource(R.string.quick_settings_visibility_description),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            QuickSettingsChoices(quickSettingsOrder, QuickSetting.entries.filter { it.name in hiddenQuickSettings }.toSet(),
                onVisibilityChanged = { setting, visible -> QuickSettingsPreferences.setHidden(context, setting.name, !visible) },
                backdrop = innerBackdrop)
        }
    }

}
