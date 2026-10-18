package me.kavishdevar.librepods.presentation.activities

import android.app.Activity
import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Window
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RefreshRatePreferenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun togglingPreferenceUpdatesTheWindowAndDisablingRestoresTheSystemChoice() {
        val enabled = mutableStateOf(false)
        lateinit var window: Window
        var highest = 0f
        compose.setContent {
            val activity = LocalView.current.context as Activity
            window = activity.window
            highest = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                .supportedModes.maxOf { it.refreshRate }
            RefreshRatePreference(enabled.value)
        }
        compose.runOnIdle {
            assertEquals(0f, window.attributes.preferredRefreshRate, 0.01f)
            enabled.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(highest, window.attributes.preferredRefreshRate, 0.01f)
            enabled.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, window.attributes.preferredRefreshRate, 0.01f) }
    }
}
