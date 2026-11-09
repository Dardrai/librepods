package me.kavishdevar.librepods.services

import android.app.Application
import android.bluetooth.BluetoothSocket
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import me.kavishdevar.librepods.utils.NativeGameModeController
import me.kavishdevar.librepods.LibrePodsApplication
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SettingsDiagnosticsTest {
    private lateinit var service: LibrePodsService
    private val device = mock(AppleDevice::class.java)
    private val aacp = mock(AACPManager::class.java)

    @Before fun setup() {
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        ReflectionHelpers.setField(service, "mApplication", LibrePodsApplication())
        val awareness = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        val volume = ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG
        val ear = ControlCommandIdentifier.EAR_DETECTION_CONFIG
        val profile = ByteArray(104).also { "PRIVATE_HEARING_PROFILE".toByteArray().copyInto(it) }
        `when`(device.state).thenReturn(MutableStateFlow(AppleState(controlSession = 42,
            controlStates = mapOf(awareness to byteArrayOf(1), volume to byteArrayOf(2), ear to byteArrayOf(2),
                ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3)), liveControlReports = setOf(awareness),
            requestedControls = mapOf(volume to byteArrayOf(1)), hearingAidData = profile,
            hearingAidReadStatus = SettingsReadStatus.READING, loudSoundReductionReadStatus = SettingsReadStatus.WAITING,
            hearingModes = HearingModes.empty().withRequestState(HearingMode.HEARING_AID, null))))
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(
            savedUnconfirmedChoices = mapOf("desired_ear_detection" to "off"))))
        `when`(device.macAddress).thenReturn(MacAddress("AA:BB:CC:DD:EE:FF"))
        `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(name = "PRIVATE_AIRPODS_NAME")))
        `when`(device.aacp).thenReturn(aacp)
        `when`(device.settingsSocketReady).thenReturn(true)
        `when`(device.settingsOperationPending).thenReturn(true)
        `when`(aacp.captureAudioSocket()).thenReturn(mock(BluetoothSocket::class.java))
        @Suppress("UNCHECKED_CAST")
        val devices = LibrePodsService::class.java.getDeclaredField("_devices").apply { isAccessible = true }
            .get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(MacAddress("AA:BB:CC:DD:EE:FF") to device)
    }

    @Test fun explicitSettingsDumpShowsFixedStateFieldsWithoutIdentityOrFittingDataAndNeverWrites() {
        val output = dump("--settings")
        assertTrue(output.startsWith("settings devices=1"))
        assertTrue(output.contains("connection=CONNECTED session=42 audio_socket_ready=true settings_socket_ready=true"))
        assertTrue(output.contains("settings_operation_pending=true"))
        assertTrue(output.contains("control=CONVERSATION_DETECT_CONFIG received=on live_report=true requested=unknown preferred=on"))
        assertTrue(output.contains("control=ADAPTIVE_VOLUME_CONFIG received=off live_report=false requested=on preferred=on"))
        assertTrue(output.contains("control=EAR_DETECTION_CONFIG received=on live_report=false requested=unknown preferred=off"))
        assertTrue(output.contains("profile_read=READING"))
        assertTrue(output.contains("loud_sound_read=WAITING loud_sound_value=unknown"))
        assertTrue(output.contains("pending=HEARING_AID"))
        assertTrue(output.contains("settings_game device_index=0 desired_preference=off controller_present=false desired_controller=unknown active=unknown"))
        assertTrue(output.contains("settings_route device_index=0 bridge_connected=false route_matches=false"))
        assertFalse(output.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(output.contains("PRIVATE_AIRPODS_NAME"))
        assertFalse(output.contains("PRIVATE_HEARING_PROFILE"))
        assertTrue(mockingDetails(aacp).invocations.all { it.method.name in setOf("captureAudioSocket", "isMicrophoneRequested") })
        verify(device, never()).metadata
    }

    @Test fun gameDiagnosticsKeepThePreferenceSeparateFromControllerProgressWithoutRequestingAChange() {
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(nativeGameModeEnabled = true)))
        val controller = NativeGameModeController.State(desiredOn = true, active = false, available = true,
            restoring = false, remoteRestorePending = true, issue = NativeGameModeController.Issue.WAITING_FOR_AUDIO)
        clearInvocations(device, aacp)
        val output = StringWriter().also { dumpNativeSettings(PrintWriter(it), 0, device, controller) }.toString()
        assertTrue(output.contains("desired_preference=on controller_present=true desired_controller=on active=off available=on"))
        assertTrue(output.contains("restoring=off remote_restore_pending=on issue=WAITING_FOR_AUDIO"))
        assertFalse(output.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(output.contains("PRIVATE_HEARING_PROFILE"))
        assertTrue(mockingDetails(aacp).invocations.all { it.method.name in setOf("captureAudioSocket", "isMicrophoneRequested") })
        assertTrue(mockingDetails(device).invocations.none { it.method.name.startsWith("set") || it.method.name.startsWith("update") })
    }

    @Test fun ordinaryDumpDoesNotCollectSettingsOrInitializeControlWork() {
        clearInvocations(device, aacp)
        assertEquals("", dump())
        verifyNoInteractions(device, aacp)
    }

    private fun dump(vararg args: String): String = StringWriter().also { output ->
        LibrePodsService::class.java.getDeclaredMethod("dump", FileDescriptor::class.java, PrintWriter::class.java,
            Array<String>::class.java).apply { isAccessible = true }.invoke(service, FileDescriptor(), PrintWriter(output), args)
    }.toString()
}
