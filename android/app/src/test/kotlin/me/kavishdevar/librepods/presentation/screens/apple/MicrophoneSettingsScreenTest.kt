package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class MicrophoneSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appleMenuContainsOnlyTheThreeNativeMicrophoneChoices() = nativeChoices(DesignSystem.Apple)

    @Test fun materialMenuContainsOnlyTheThreeNativeMicrophoneChoices() = nativeChoices(DesignSystem.Material)

    private fun nativeChoices(theme: DesignSystem) {
        val context = RuntimeEnvironment.getApplication()
        val selected = mutableStateOf(0)
        val writes = mutableListOf<Int>()
        compose.setContent { LibrePodsTheme(designSystem = theme) {
            MicrophoneSettingsScreen(null, selected.value) { writes += it; selected.value = it }
        } }
        compose.onNodeWithText(context.getString(R.string.microphone_mode)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        assertTrue("Opening the menu must not change the microphone", writes.isEmpty())
        listOf(R.string.microphone_automatic, R.string.microphone_always_right,
            R.string.microphone_always_left).forEachIndexed { mode, label ->
            val row = compose.onNodeWithText(context.getString(label))
            row.assertIsDisplayed().performClick().assertIsSelected()
            assertEquals(mode, writes.last())
        }
        assertEquals(listOf(0, 1, 2), writes)
    }
}
