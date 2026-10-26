package me.kavishdevar.librepods.audio

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ExperimentalSettingsRefreshTest {
    @Test fun refreshReservationExcludesRealMicrophoneStartsUntilReleased() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(modelNumber = "A3063"))
        val controller = ExperimentalAudioController(context, device) {}
        try {
            assertTrue(controller.tryBeginSettingsRefresh())
            assertFalse(controller.tryBeginSettingsRefresh())
            assertFalse(controller.startRecording())
            var liveCloses = 0
            assertFalse(controller.startLive(object : LivePcmConsumer {
                override fun onFormat(format: PcmFormat) = Unit
                override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) = Unit
                override fun close() { liveCloses++ }
            }))
            assertEquals("Rejected live starts retain caller ownership", 0, liveCloses)
            assertTrue(controller.message.value.contains("refresh"))
            assertEquals(MicrophonePhase.IDLE, controller.microphone.state.value.phase)
            assertNull(device.aacp.captureAudioSocket())
            controller.endSettingsRefresh()
            assertTrue(controller.tryBeginSettingsRefresh())
            controller.endSettingsRefresh()

            @Suppress("UNCHECKED_CAST")
            val microphoneState = AirPodsMicrophone::class.java.getDeclaredField("mutableState")
                .apply { isAccessible = true }.get(controller.microphone) as MutableStateFlow<MicrophoneState>
            for (phase in listOf(MicrophonePhase.STARTING, MicrophonePhase.CAPTURING,
                MicrophonePhase.STOPPING, MicrophonePhase.RECOVERING)) {
                microphoneState.value = MicrophoneState(phase)
                assertFalse("Refresh must not interrupt $phase", controller.tryBeginSettingsRefresh())
            }
            microphoneState.value = MicrophoneState()
            assertTrue(controller.tryBeginSettingsRefresh())
            controller.close()
            assertFalse(controller.tryBeginSettingsRefresh())
            assertFalse(controller.bridge.tryBeginSettingsRefresh())
        } finally { controller.close() }
    }
}
