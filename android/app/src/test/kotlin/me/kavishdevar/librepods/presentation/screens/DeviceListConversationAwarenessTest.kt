package me.kavishdevar.librepods.presentation.screens

import android.app.Application
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceListConversationAwarenessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingTheLiveDeviceToggleInvertsTheSilentChoiceInsteadOfTheOlderReport() {
        val device = mock(AppleDevice::class.java)
        val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        val address = MacAddress("00:11:22:33:44:55")
        val states = MutableStateFlow(AppleState())
        val sent = AtomicReference<Boolean?>()
        `when`(device.macAddress).thenReturn(address)
        `when`(device.state).thenReturn(states)
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings()))
        `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(name = "AirPods Pro 3",
            model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063")))
        doAnswer { call -> sent.set(call.getArgument(1)); true }
            .`when`(device).setControlCommand(eq(id) ?: id, anyBoolean())
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                DeviceListScreen(mapOf(address to device), {}, {}, {})
            }
        }
        val label = ApplicationProvider.getApplicationContext<Application>().getString(R.string.conversation_awareness)
        for (requested in listOf(1, 2)) {
            compose.runOnIdle {
                sent.set(null)
                states.value = AppleState(controlStates = mapOf(id to byteArrayOf((3 - requested).toByte())),
                    requestedControls = mapOf(id to byteArrayOf(requested.toByte())))
            }
            compose.onNodeWithText(label).performScrollTo().performTouchInput { click() }
            compose.waitUntil(2_000) { sent.get() != null }
            assertEquals(requested != 1, sent.get())
        }
    }
}
