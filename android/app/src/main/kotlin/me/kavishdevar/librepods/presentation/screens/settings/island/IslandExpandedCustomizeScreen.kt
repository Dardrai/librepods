package me.kavishdevar.librepods.presentation.screens.settings.island

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.FontFamilyOption
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.IslandState
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel

@Composable
fun IslandExpandedCustomizeRoute(
    viewModel: AppSettingsViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

    val context = LocalContext.current
    val islandWindow = (context.applicationContext as LibrePodsApplication).islandWindow

    val previousState = remember(islandWindow) { islandWindow.islandState }

    val currentSettings = remember { settings.islandSettings }

    DisposableEffect(islandWindow) {
        onDispose {
            if (previousState == IslandState.CLOSE) islandWindow.close()
            else islandWindow.show(forcedState = previousState)
        }
    }

    IslandExpandedSettingsScreen(
        navigateBack = navigateBack,
        islandSettings = settings.islandSettings,
        updateIslandSettings = { islandSettings ->
            viewModel.updateSettings {
                it.copy(
                    islandSettings = islandSettings
                )
            }
        },
        resetSettings = {
            viewModel.updateSettings {
                it.copy(
                    islandSettings = currentSettings
                )
            }
        },
        showIsland = {
            islandWindow.show(
                forcedState = IslandState.EXPANDED,
                keepOpen = true
            )
        },
        hideIsland = {
            islandWindow.close()
        }
    )
}

@Composable
fun IslandExpandedSettingsScreen(
    navigateBack: (() -> Unit)? = null,
    islandSettings: IslandSettings,
    updateIslandSettings: (IslandSettings) -> Unit,
    resetSettings: () -> Unit,
    showIsland: () -> Unit,
    hideIsland: () -> Unit
) {
    val scrollState = rememberScrollState()

    val debugMode = LocalDebugMode.current

    StyledScaffold(
        title = stringResource(R.string.customize),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

//            StyledList(
//                title = "Background color"
//            ) {
//                styledListItem(
//                    contentText = "Surface",
//                    selected = islandSettings.backgroundColor is IslandColor.Surface,
//                    onClick = {
//                        updateIslandSettings(
//                            islandSettings.copy(
//                                backgroundColor = IslandColor.Surface
//                            )
//                        )
//                    }
//                )
//                styledListItem(
//                    contentText = "Surface Container",
//                    selected = islandSettings.backgroundColor is IslandColor.SurfaceContainer,
//                    onClick = {
//                        updateIslandSettings(
//                            islandSettings.copy(
//                                backgroundColor = IslandColor.SurfaceContainer
//                            )
//                        )
//                    }
//                )
//                styledListItem(
//                    contentText = "Custom",
//                    selected = islandSettings.backgroundColor is IslandColor.Custom,
//                    onClick = {
//                        updateIslandSettings(
//                            islandSettings.copy(
//                                backgroundColor = IslandColor.Custom(0xFF000000)
//                            )
//                        )
//                    }
//                )
//            }

                // show color picker for custom color

                StyledSlider(
                    label = stringResource(R.string.opacity) + if (debugMode) " (${islandSettings.expandedBackgroundAlpha})" else "",
                    value = islandSettings.expandedBackgroundAlpha,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                expandedBackgroundAlpha = it
                            )
                        )
                    },
                    valueRange = 0f..1f,
                    independent = true
                )

                StyledList(
                    title = stringResource(R.string.icon)
                ) {
                    styledListItem(
                        contentText = stringResource(R.string.monochrome),
                        selected = islandSettings.expandedUseMonochrome,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedUseMonochrome = true
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.image),
                        selected = !islandSettings.expandedUseMonochrome,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedUseMonochrome = false
                                )
                            )
                        }
                    )
                }

                StyledToggle(
                    label = stringResource(R.string.listening_mode_controls),
                    checked = islandSettings.showListeningModeControl,
                    onCheckedChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                showListeningModeControl = it
                            )
                        )
                    }
                )

                StyledList {
                    styledListItem(
                        contentText = stringResource(R.string.long_press_to_open_something, stringResource(R.string.app_name)),
                        selected = !islandSettings.expandedOpenAppOnTap,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedOpenAppOnTap = false
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.tap_to_open_something, stringResource(R.string.app_name)),
                        selected = islandSettings.expandedOpenAppOnTap,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedOpenAppOnTap = true
                                )
                            )
                        }
                    )
                }

                StyledList(
                    title = stringResource(R.string.battery_style)
                ) {
                    styledListItem(
                        contentText = stringResource(R.string.don_t_show),
                        selected = islandSettings.expandedBatteryStyle == IslandBatteryStyle.NONE,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedBatteryStyle = IslandBatteryStyle.NONE
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.ring),
                        selected = islandSettings.expandedBatteryStyle == IslandBatteryStyle.CIRCLE,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedBatteryStyle = IslandBatteryStyle.CIRCLE
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.text),
                        selected = islandSettings.expandedBatteryStyle == IslandBatteryStyle.TEXT,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedBatteryStyle = IslandBatteryStyle.TEXT
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.both),
                        selected = islandSettings.expandedBatteryStyle == IslandBatteryStyle.CIRCLE_TEXT,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    expandedBatteryStyle = IslandBatteryStyle.CIRCLE_TEXT
                                )
                            )
                        }
                    )
                }

                StyledSlider(
                    label = stringResource(R.string.font_size) + if (debugMode) " (${islandSettings.fontSize})" else "",
                    value = islandSettings.fontSize,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                fontSize = it
                            )
                        )
                    },
                    valueRange = 12f..28f,
                    snapPoints = listOf(12f, 16f, 20f, 24f, 28f),
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.border_width) + if (debugMode) " (${islandSettings.expandedBorderWidth})" else "",
                    value = islandSettings.expandedBorderWidth,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                expandedBorderWidth = it
                            )
                        )
                    },
                    valueRange = 0f..4f,
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.border_radius) + if (debugMode) " (${islandSettings.expandedBorderRadius})" else "",
                    value = islandSettings.expandedBorderRadius,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                expandedBorderRadius = it
                            )
                        )
                    },
                    valueRange = 0f..150f,
                    independent = true
                )

                StyledButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { resetSettings() },
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        text = stringResource(R.string.reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StyledButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        hideIsland()
                    },
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        text = stringResource(R.string.hide_island),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                StyledButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showIsland()
                    },
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        text = stringResource(R.string.show_island),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Preview
@Composable
fun IslandExpandedSettingsScreenPreview() {
    LibrePodsTheme(
//        designSystem = DesignSystem.Apple,
//        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.Inter),
        designSystem = DesignSystem.Material,
        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.RobotoFlexExpressive),
        darkTheme = true
    ) {
        val islandSettings = remember { mutableStateOf(IslandSettings()) }
        IslandExpandedSettingsScreen(
            islandSettings = islandSettings.value,
            updateIslandSettings = { islandSettings.value = it },
            resetSettings = {},
            showIsland = {},
            hideIsland = {}
        )
    }
}
