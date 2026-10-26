/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsReadMessageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparencyDistinguishesWaitingReadingUnavailableAndDisconnectWithoutInventingControls() {
        val status = mutableStateOf(SettingsReadStatus.WAITING)
        val connected = mutableStateOf(true)
        var edits = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            Column { TransparencyControls(byteArrayOf(), 1, writable = false, canEnable = false,
                transportAvailable = false, result = null, onEdit = { edits++ },
                readStatus = status.value, connected = connected.value) }
        } }
        compose.onNodeWithText("Waiting for settings…").assertExists()
        compose.runOnIdle { status.value = SettingsReadStatus.READING }
        compose.onNodeWithText("Reading settings…").assertExists()
        compose.runOnIdle { status.value = SettingsReadStatus.UNAVAILABLE }
        compose.onNodeWithText("These settings are unavailable on this connection.").assertExists()
        compose.onNodeWithText("Reading settings…").assertDoesNotExist()
        compose.onNodeWithTag("styled-slider-thumb", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithText("Connect your AirPods to use these settings.").assertExists()
        compose.onAllNodesWithText("Custom Transparency changes are unavailable on this connection.").assertCountEquals(0)
        assertEquals(0, edits)
    }

    @Test fun missingHearingProfileStopsWaitingAfterFailedReadAndNeverOffersEditableDefaults() {
        val status = mutableStateOf(SettingsReadStatus.READING)
        var edits = 0
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Material) {
            Column { HearingAidAdjustmentControls(byteArrayOf(), 2, enabled = true, gainSwipe = false,
                onGainSwipe = { edits++ }, onEdit = { edits++ }, readStatus = status.value) }
        } }
        compose.onNodeWithText("Reading settings…").assertExists()
        compose.runOnIdle { status.value = SettingsReadStatus.UNAVAILABLE }
        compose.onNodeWithText("These settings are unavailable on this connection.").assertExists()
        compose.onNodeWithText("Reading settings…").assertDoesNotExist()
        compose.onNodeWithText("Amplification").assertDoesNotExist()
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Read settings").assertDoesNotExist()
        assertEquals(0, edits)
    }
}
