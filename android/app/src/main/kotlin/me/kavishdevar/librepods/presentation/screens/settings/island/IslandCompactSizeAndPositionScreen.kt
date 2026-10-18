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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.FontFamilyOption
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.IslandState
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel

@Composable
fun IslandCompactSizeAndPositionRoute(
    viewModel: AppSettingsViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

    val context = LocalContext.current
    val islandWindow = (context.applicationContext as LibrePodsApplication).islandWindow // I know, not a good way. TODO

    val previousState = remember(islandWindow) { islandWindow.islandState }

    val currentSettings = remember { settings.islandSettings }

    DisposableEffect(islandWindow) {
        onDispose {
            if (previousState == IslandState.CLOSE) islandWindow.close()
            else islandWindow.show(forcedState = previousState)
        }
    }

    IslandCompactSizeAndPositionScreen(
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
                forcedState = IslandState.COMPACT,
                keepOpen = true
            )
        },
        hideIsland = {
            islandWindow.close()
        }
    )
}

@Composable
fun IslandCompactSizeAndPositionScreen(
    navigateBack: (() -> Unit)? = null,
    islandSettings: IslandSettings,
    updateIslandSettings: (IslandSettings) -> Unit,
    resetSettings: () -> Unit,
    showIsland: () -> Unit,
    hideIsland: () -> Unit
) {
    val scrollState = rememberScrollState()

    val debugMode = LocalDebugMode.current

    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidth = containerSize.width
    val screenHeight = containerSize.height

    StyledScaffold(
        title = stringResource(R.string.size_and_position),
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
                StyledSlider(
                    label = stringResource(R.string.horizontal_offset) + if (debugMode) " (${islandSettings.offsetX})" else "",
                    value = islandSettings.offsetX,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                offsetX = it
                            )
                        )
                    },
                    valueRange = -(screenWidth / 2f)..(screenWidth / 2f),
                    snapPoints = listOf(0f),
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.vertical_offset) + if (debugMode) " (${islandSettings.offsetY})" else "",
                    value = islandSettings.offsetY,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                offsetY = it
                            )
                        )
                    },
                    snapPoints = listOf(40f),
                    valueRange = 0f..(screenHeight / 4f),
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.height) + if (debugMode) " (${islandSettings.compactHeight})" else "",
                    value = islandSettings.compactHeight,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                compactHeight = it
                            )
                        )
                    },
                    valueRange = 0f..(screenHeight / 4f),
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.width) + if (debugMode) " (${islandSettings.compactWidth})" else "",
                    value = islandSettings.compactWidth,
                    onValueChange = {
                        updateIslandSettings(
                            islandSettings.copy(
                                compactWidth = it
                            )
                        )
                    },
                    valueRange = 0f..screenWidth.toFloat(),
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
fun IslandCompactSizeAndPositionScreenPreview() {
    LibrePodsTheme(
//        designSystem = DesignSystem.Apple,
//        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.Inter),
        designSystem = DesignSystem.Material,
        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.RobotoFlexExpressive),
        darkTheme = true
    ) {
        val islandSettings = remember { mutableStateOf(IslandSettings()) }
        IslandCompactSizeAndPositionScreen(
            islandSettings = islandSettings.value,
            updateIslandSettings = { islandSettings.value = it },
            resetSettings = {},
            showIsland = {},
            hideIsland = {}
        )
    }
}
