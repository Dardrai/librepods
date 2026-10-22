package me.kavishdevar.librepods.presentation.screens

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceListConnectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nearbyAndDisconnectedRowsAndIconsInvokeExplicitPhoneConnectionWithoutAPc() {
        fun pod(name: String, address: String, state: ConnectionState): AppleDevice {
            val bluetooth = mock(BluetoothDevice::class.java)
            `when`(bluetooth.address).thenReturn(address)
            `when`(bluetooth.name).thenReturn(name)
            return AppleDevice(ApplicationProvider.getApplicationContext(), mock(BluetoothAdapter::class.java), bluetooth, state)
        }
        val nearby = pod("Nearby AirPods", "AA:BB:CC:DD:EE:01", ConnectionState.AVAILABLE)
        val disconnected = pod("Saved AirPods", "AA:BB:CC:DD:EE:02", ConnectionState.DISCONNECTED)
        val connections = mutableListOf<AppleDevice>()
        var navigation = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Box(Modifier.requiredSize(448.dp, 998.dp)) {
                    DeviceListScreen(mapOf(nearby.macAddress to nearby, disconnected.macAddress to disconnected),
                        navigateToAppSettings = {}, navigateToDevice = { navigation++ }, navigateToHeartRateScreen = {},
                        onConnectDevice = { connections += it })
                }
            }
        }
        compose.onNodeWithText("Nearby AirPods").performClick()
        compose.onNodeWithContentDescription("Connect Nearby AirPods", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Saved AirPods").performClick()
        compose.onNodeWithContentDescription("Connect Saved AirPods", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(listOf(nearby, nearby, disconnected, disconnected), connections)
            assertEquals(0, navigation)
        }
    }
}
