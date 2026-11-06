package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DongleHardwareVolumeControlsTest {
    @get:Rule val compose = createComposeRule()
    private val current = mutableStateOf(DongleState(connected = true, usb = true,
        status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 0, listOf(null, null, null), 3, 0x90),
        audio = DongleProtocol.AudioProfile(3, 3, 0, 256000, 256000, false, 0, 0, 8, null)))
    private val editors = DongleControlTestEditors(current)
    @After fun close() = editors.close()
    private fun show() = compose.setContent {
        LibrePodsTheme(designSystem = DesignSystem.Apple) {
            Column(Modifier.verticalScroll(rememberScrollState())) { DongleControls(current.value, editors.controller) }
        }
    }
    @Test fun hardwareVolumeStaysOffAndDisabledUntilTheRootPolicyIsAvailable() {
        show()
        compose.onNodeWithText("Hardware volume").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(usbVolumeAvailable = true) }
        compose.onNodeWithText("Hardware volume").performScrollTo().assertIsEnabled().performClick()
        verify(editors.controller).usbHardwareVolume(true)
    }
    @Test fun anActiveOwnerCanBeDisabledDuringPlayback() {
        current.value = current.value.copy(usbVolumeAvailable = true, usbVolumeEnabled = true,
            status = current.value.status?.copy(flags = 7), audio = current.value.audio?.copy(flags = 10))
        show()
        compose.onNodeWithText("Hardware volume").performScrollTo().assertIsEnabled().performClick()
        verify(editors.controller).usbHardwareVolume(false)
    }
}
