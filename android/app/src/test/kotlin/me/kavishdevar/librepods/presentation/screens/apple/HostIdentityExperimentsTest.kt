package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.ExperimentalSerializationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalSerializationApi::class)
class HostIdentityExperimentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingSavedSettingsDoNotEnableExperimentsAndNewChoicesRoundTrip() {
        val original = Cbor.decodeFromByteArray(AppleSettings.serializer(), byteArrayOf(0xa0.toByte()))
        assertFalse(original.experimentalHearingCapability)
        assertFalse(original.experimentalMacSourceAnnouncement)
        val changed = original.copy(experimentalHearingCapability = true)
        assertEquals(changed, Cbor.decodeFromByteArray(AppleSettings.serializer(), Cbor.encodeToByteArray(AppleSettings.serializer(), changed)))
        assertFalse(changed.experimentalMacSourceAnnouncement)
    }

    @Test fun choicesRemainIndependentAndDoNotSendPacketsOnTheCurrentConnection() {
        var settings by mutableStateOf(AppleSettings())
        var packets = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                DebugScreen(AppleUiState(settings = settings), null, { packets++; true }, { settings = it(settings) })
            }
        }
        compose.onNodeWithText("Advertise hearing support").performClick()
        compose.runOnIdle { assertTrue(settings.experimentalHearingCapability); assertFalse(settings.experimentalMacSourceAnnouncement) }
        compose.onNodeWithText("Announce Mac source").performClick()
        compose.runOnIdle { assertTrue(settings.experimentalHearingCapability); assertTrue(settings.experimentalMacSourceAnnouncement); assertEquals(0, packets) }
    }
}
