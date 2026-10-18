package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.IslandState
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandCompactCustomizeRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandCompactSizeAndPositionRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandExpandedCustomizeRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandExpandedSizeAndPositionRoute
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class IslandCustomizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expandedSizeResetsToEntrySettingsAndHidesItsPreview() = checkRoute(0)
    @Test fun compactSizeResetsToEntrySettingsAndHidesItsPreview() = checkRoute(1)
    @Test fun expandedCustomizationResetsToEntrySettingsAndHidesItsPreview() = checkRoute(2)
    @Test fun compactCustomizationResetsToEntrySettingsAndHidesItsPreview() = checkRoute(3)
    @Test fun mainSettingsHidesItsPreviewAndRestoresTheEntryStateOnExit() = checkRoute(4)

    private fun checkRoute(route: Int) {
        val initial = IslandSettings(fontSize = 29f)
        val state = MutableStateFlow(AppUiState(settings = AppSettingsEntity(islandSettings = initial)))
        val visible = mutableStateOf(true)
        val originalState = if (route == 4) IslandState.COMPACT else IslandState.CLOSE
        val islandState = mutableStateOf(originalState)
        // Kotlin's default show arguments read private delegated fields before Mockito intercepts it.
        val window = mock(IslandWindow::class.java, withSettings()
            .useConstructor(ApplicationProvider.getApplicationContext<Application>(), AppSettingsEntity())
            .defaultAnswer { call ->
            when (call.method.name) {
                "getIslandState" -> islandState.value
                "changeState" -> { islandState.value = call.getArgument(0); null }
                "close" -> { islandState.value = IslandState.CLOSE; null }
                "show" -> { islandState.value = call.getArgument<IslandState?>(12) ?: IslandState.EXPANDED; null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        })
        val app = mock(LibrePodsApplication::class.java) { call ->
            if (call.method.name == "getIslandWindow") window else Answers.RETURNS_DEFAULTS.answer(call)
        }
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getApplicationContext(): Context = app
        }
        val viewModel = mock(AppSettingsViewModel::class.java) { call ->
            when (call.method.name) {
                "getUiState" -> state
                "updateSettings" -> {
                    val transform = call.getArgument<(AppSettingsEntity) -> AppSettingsEntity>(0)
                    state.value = state.value.copy(settings = transform(state.value.settings))
                    null
                }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    if (visible.value) when (route) {
                        0 -> IslandExpandedSizeAndPositionRoute(viewModel, null)
                        1 -> IslandCompactSizeAndPositionRoute(viewModel, null)
                        2 -> IslandExpandedCustomizeRoute(viewModel, null)
                        3 -> IslandCompactCustomizeRoute(viewModel, null)
                        else -> IslandSettingsRoute(viewModel, null, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.show_island)).performClick()
        compose.runOnIdle {
            state.value = state.value.copy(settings = state.value.settings.copy(islandSettings = initial.copy(fontSize = 33f)))
        }
        if (route != 4) {
            compose.onNodeWithText(text(R.string.reset)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("Reset must use the settings captured before editing", initial, state.value.settings.islandSettings)
        }
        compose.onNodeWithText(text(R.string.hide_island)).performClick()
        compose.waitForIdle()
        assertEquals("Hide must close even when the preview started visible", IslandState.CLOSE, islandState.value)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertEquals("Leaving settings restores the actual entry state", originalState, islandState.value)
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Application>().getString(id)
}
