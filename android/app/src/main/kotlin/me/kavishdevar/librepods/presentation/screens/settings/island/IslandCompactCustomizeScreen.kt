package me.kavishdevar.librepods.presentation.screens.settings.island

import android.content.Intent
import android.provider.Settings
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
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.IslandState
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel

@Composable
fun IslandCompactCustomizeRoute(
    viewModel: AppSettingsViewModel,
    navigateBack: (() -> Unit)?,
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

    IslandCompactCustomizeScreen(
        navigateBack = navigateBack,
        islandSettings = settings.islandSettings,
        navigateToAndroidAccessibility = {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        },
        accessibilityServiceAvailable = uiState.accessibilityServiceAvailable,
        resetSettings = {
            viewModel.updateSettings {
                it.copy(
                    islandSettings = currentSettings
                )
            }
        },
        showIsland = {
            islandWindow.show(
                forcedState = IslandState.COMPACT,
                keepOpen = true
            )
        },
        hideIsland = {
            islandWindow.close()
        },
        updateIslandSettings = { islandSettings ->
            viewModel.updateSettings {
                it.copy(
                    islandSettings = islandSettings
                )
            }
        },
    )
}

@Composable
fun IslandCompactCustomizeScreen(
    navigateBack: (() -> Unit)? = null,
    islandSettings: IslandSettings,
    navigateToAndroidAccessibility: () -> Unit,
    accessibilityServiceAvailable: Boolean,
    resetSettings: () -> Unit,
    updateIslandSettings: (IslandSettings) -> Unit,
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
                    label = stringResource(R.string.opacity) + if (debugMode) " (${islandSettings.compactBackgroundAlpha})" else "",
                    value = islandSettings.compactBackgroundAlpha,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                compactBackgroundAlpha = it
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
                        selected = islandSettings.compactUseMonochrome,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactUseMonochrome = true
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.image),
                        selected = !islandSettings.compactUseMonochrome,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactUseMonochrome = false
                                )
                            )
                        }
                    )
                }

                StyledList(
                    title = stringResource(R.string.press_and_hold),
                    description = if (islandSettings.compactOpenAppOnTap) stringResource(R.string.tap_to_open_something, stringResource(R.string.app_name))
                    else stringResource(R.string.tap_to_expand)
                ) {
                    styledListItem(
                        contentText = stringResource(R.string.open_something, stringResource(R.string.app_name)),
                        selected = !islandSettings.compactOpenAppOnTap,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactOpenAppOnTap = false
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.expand),
                        selected = islandSettings.compactOpenAppOnTap,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactOpenAppOnTap = true
                                )
                            )
                        }
                    )
                }

                StyledList {
                    styledToggle(
                        label = stringResource(R.string.keep_compact_island_visible),
                        description = stringResource(R.string.island_accessibility_service_needed),
                        checked = islandSettings.keepCompactVisible,
                        enabled = accessibilityServiceAvailable,
                        onCheckedChange = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    keepCompactVisible = it
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.accessibility_service),
                        supportingText = if (accessibilityServiceAvailable) stringResource(R.string.available) else stringResource(R.string.not_available),
                        onClick = navigateToAndroidAccessibility,
                    )
                }

                StyledList(
                    title = stringResource(R.string.battery_style)
                ) {
                    styledListItem(
                        contentText = stringResource(R.string.don_t_show),
                        selected = islandSettings.compactBatteryStyle == IslandBatteryStyle.NONE,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactBatteryStyle = IslandBatteryStyle.NONE
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.ring),
                        selected = islandSettings.compactBatteryStyle == IslandBatteryStyle.CIRCLE,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactBatteryStyle = IslandBatteryStyle.CIRCLE
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.text),
                        selected = islandSettings.compactBatteryStyle == IslandBatteryStyle.TEXT,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactBatteryStyle = IslandBatteryStyle.TEXT
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.both),
                        selected = islandSettings.compactBatteryStyle == IslandBatteryStyle.CIRCLE_TEXT,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    compactBatteryStyle = IslandBatteryStyle.CIRCLE_TEXT
                                )
                            )
                        }
                    )
                }


                StyledSlider(
                    label = stringResource(R.string.border_width) + if (debugMode) " (${islandSettings.compactBorderWidth})" else "",
                    value = islandSettings.compactBorderWidth,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                compactBorderWidth = it
                            )
                        )
                    },
                    valueRange = 0f..4f,
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.border_radius) + if (debugMode) " (${islandSettings.compactBorderRadius})" else "",
                    value = islandSettings.compactBorderRadius,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                compactBorderRadius = it
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
fun IslandCompactCustomizeScreenPreview() {
    LibrePodsTheme(
//        designSystem = DesignSystem.Apple,
//        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.Inter),
        designSystem = DesignSystem.Material,
        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.RobotoFlexExpressive),
        darkTheme = true
    ) {
        val islandSettings = remember { mutableStateOf(IslandSettings()) }
        IslandCompactCustomizeScreen(
            islandSettings = islandSettings.value,
            navigateToAndroidAccessibility = {},
            accessibilityServiceAvailable = false,
            resetSettings = {},
            updateIslandSettings = { islandSettings.value = it },
            showIsland = {},
            hideIsland = {}
        )
    }
}
