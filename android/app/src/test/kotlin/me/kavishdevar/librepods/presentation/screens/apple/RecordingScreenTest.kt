package me.kavishdevar.librepods.presentation.screens.apple

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.audio.MicrophoneState
import me.kavishdevar.librepods.audio.AirPodsMicrophone
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ExperimentalAudioController>()
    private val sockets = mutableListOf<BluetoothSocket>()

    private class Permissions : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var requestCode = -1
        var permission: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
            permission = input
        }
        fun answer(granted: Boolean) { assertTrue(dispatchResult(requestCode, granted)) }
    }

    @After fun close() {
        controllers.forEach { it.close() }
        sockets.forEach { it.close() }
    }

    @Test fun startRequestsPermissionAndReportsDenialBeforeItCanRecord() {
        val controller = controller()
        bridge(controller).value = BridgeState(connected = true)
        val permissions = Permissions()
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, controller)
                }
            }
        }
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(Manifest.permission.RECORD_AUDIO, permissions.permission)
            permissions.answer(false)
        }
        compose.onNodeWithText(text(R.string.experimental_audio_permission)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recorder_start)).performClick()
        compose.runOnIdle {
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            permissions.answer(true)
        }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun recorderShowsShizukuSetupAndCaptureFailuresWithoutStartingAnything() {
        val controller = controller()
        var starts = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, controller)
            }
        }
        compose.onNodeWithText(text(R.string.experimental_shizuku_connect)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, starts)
            bridge(controller).value = BridgeState(connected = true)
            microphone(controller).value = MicrophoneState(MicrophonePhase.ERROR, message = "Decoder unavailable")
        }
        compose.onNodeWithText("Decoder unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsEnabled()
    }

    @Test fun permissionResultCannotStartAReplacementDevicesRecording() {
        val first = controller()
        val replacement = controller()
        bridge(first).value = BridgeState(connected = true)
        bridge(replacement).value = BridgeState(connected = true)
        val selected = mutableStateOf(first)
        val permissions = Permissions()
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, selected.value)
                }
            }
        }
        compose.onNodeWithText(text(R.string.recorder_start)).performClick()
        compose.runOnIdle { selected.value = replacement }
        compose.waitForIdle()
        compose.runOnIdle { permissions.answer(true) }
        compose.runOnIdle { assertEquals(0, starts) }
    }

    private fun controller(): ExperimentalAudioController {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(modelNumber = "A3063"))
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        sockets += socket
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        return ExperimentalAudioController(context, device) {}.also { controllers += it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bridge(controller: ExperimentalAudioController) = ShizukuAudioClient::class.java
        .getDeclaredField("mutableState").apply { isAccessible = true }.get(controller.bridge) as MutableStateFlow<BridgeState>

    @Suppress("UNCHECKED_CAST")
    private fun microphone(controller: ExperimentalAudioController) = AirPodsMicrophone::class.java
        .getDeclaredField("mutableState").apply { isAccessible = true }.get(controller.microphone) as MutableStateFlow<MicrophoneState>

    private fun text(id: Int) = context.getString(id)
}
