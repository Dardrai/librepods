package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.ATTManager
import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [HearingModeSocketShadow::class])
class AacpAttWritesTest {
    @Test fun fittingConfirmsOnlyActualReadbackAndWaitsWithoutRepeatingTheWrite() = runBlocking<Unit> {
        withFixture { device, att, socket ->
            val baseline = hearingRecord()
            val requested = baseline.copyOf().put(40, 0.75f).also { it[2] = 0x64 }
            val confirmed = requested.copyOf().also { it[2] = 0x60 }
            val prepared = requireNotNull(device.aacp.prepareControlSessionRefresh())
            `when`(att.readCharacteristic(ATTHandle.HEARING_AID)).thenReturn(baseline, baseline, confirmed)
            `when`(att.writeCharacteristic(ATTHandle.HEARING_AID, requested)).thenAnswer {
                assertArrayEquals("Sending is not confirmation", baseline, device.state.value.hearingAidData)
                assertFalse(device.aacp.requestMicrophoneStream(socket))
                assertNull(device.aacp.prepareControlSessionRefresh())
                assertFalse(device.aacp.refreshControlSession(prepared) { true })
                true
            }
            assertTrue(device.writeATTCharacteristic(ATTHandle.HEARING_AID, requested, baseline))
            assertArrayEquals(confirmed, device.state.value.hearingAidData)
            verify(att, times(1)).writeCharacteristic(ATTHandle.HEARING_AID, requested)
            verify(att, times(3)).readCharacteristic(ATTHandle.HEARING_AID)
            assertNotNull(device.aacp.prepareControlSessionRefresh())
        }
    }

    @Test fun staleDraftOrFreshDeviceMismatchCannotWrite() = runBlocking<Unit> {
        for (staleCache in listOf(true, false)) withFixture { device, att, _ ->
            val baseline = hearingRecord()
            val changed = baseline.copyOf().put(88, 0.5f)
            val requested = baseline.copyOf().put(40, 0.75f).also { it[2] = 0x64 }
            if (staleCache) seed(device, hearing = changed)
            else `when`(att.readCharacteristic(ATTHandle.HEARING_AID)).thenReturn(changed)
            assertFalse(device.writeATTCharacteristic(ATTHandle.HEARING_AID, requested, baseline))
            if (staleCache) {
                verifyNoInteractions(att)
                assertArrayEquals(changed, device.state.value.hearingAidData)
            } else {
                verify(att, never()).writeCharacteristic(ATTHandle.HEARING_AID, requested)
                assertArrayEquals(byteArrayOf(), device.state.value.hearingAidData)
            }
        }
    }

    @Test fun changedOrMissingConfirmationFailsAndAnUnchangedRecordHasABoundedRetry() = runBlocking<Unit> {
        for (result in listOf("different", "unenrolled", "missing", "unchanged", "write-failed")) withFixture { device, att, _ ->
            val baseline = hearingRecord()
            val requested = baseline.copyOf().put(40, 0.75f).also { it[2] = 0x64 }
            val readback = when (result) {
                "different" -> requested.copyOf().put(88, 0.5f)
                "unenrolled" -> requested.copyOf().also { it[1] = 0 }
                "missing" -> null
                else -> baseline
            }
            `when`(att.readCharacteristic(ATTHandle.HEARING_AID)).thenReturn(baseline, readback)
            `when`(att.writeCharacteristic(ATTHandle.HEARING_AID, requested)).thenReturn(result != "write-failed")
            assertFalse(device.writeATTCharacteristic(ATTHandle.HEARING_AID, requested, baseline))
            assertArrayEquals(byteArrayOf(), device.state.value.hearingAidData)
            verify(att, times(1)).writeCharacteristic(ATTHandle.HEARING_AID, requested)
            verify(att, times(when (result) { "unchanged" -> 6; "write-failed" -> 1; else -> 2 }))
                .readCharacteristic(ATTHandle.HEARING_AID)
            assertNotNull(device.aacp.prepareControlSessionRefresh())
        }
    }

    @Test fun microphoneAdmissionAndAReplacedControlConnectionCannotPublishAnOldRead() = runBlocking<Unit> {
        withFixture { device, att, socket ->
            val baseline = hearingRecord()
            val requested = baseline.copyOf().put(40, 0.75f).also { it[2] = 0x64 }
            device.updateMicrophoneState { it.copy(isActive = true) }
            assertNull(device.readATTCharacteristic(ATTHandle.HEARING_AID))
            assertFalse(device.writeATTCharacteristic(ATTHandle.HEARING_AID, requested, baseline))
            verifyNoInteractions(att)
            device.updateMicrophoneState { it.copy(isActive = false) }
            assertTrue(device.aacp.requestMicrophoneStream(socket))
            assertFalse(device.writeATTCharacteristic(ATTHandle.HEARING_AID, requested, baseline))
            verifyNoInteractions(att)
            device.aacp.endMicrophoneStream(socket)
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            val newer = baseline.copyOf().put(88, 0.5f)
            try {
                `when`(att.readCharacteristic(ATTHandle.HEARING_AID)).thenAnswer {
                    bind(device.aacp, replacement)
                    seed(device, hearing = newer)
                    baseline
                }
                assertNull(device.readATTCharacteristic(ATTHandle.HEARING_AID))
                assertArrayEquals(newer, device.state.value.hearingAidData)
            } finally { device.aacp.disconnect(); replacement.close() }
        }
    }

    @Test fun transparencyNeedsExactBytesAndHearingExclusionWhileLoudSoundUsesItsBooleanFormat() = runBlocking<Unit> {
        for (matches in listOf(true, false)) withFixture { device, att, _ ->
            val baseline = transparencyRecord().put(44, -0.0f)
            seed(device, transparency = baseline)
            val requested = baseline.copyOf().put(0, 1f)
            val readback = requested.copyOf().also { if (!matches) it.put(44, 0f) }
            `when`(att.readCharacteristic(ATTHandle.TRANSPARENCY)).thenReturn(baseline, readback)
            `when`(att.writeCharacteristic(ATTHandle.TRANSPARENCY, requested)).thenReturn(true)
            assertEquals(matches, device.writeATTCharacteristic(ATTHandle.TRANSPARENCY, requested, baseline))
            assertArrayEquals(if (matches) readback else byteArrayOf(), device.state.value.transparencyData)
        }
        withFixture { device, att, socket ->
            val baseline = transparencyRecord()
            reportModes(device, socket, hearing = true)
            assertFalse(device.writeATTCharacteristic(ATTHandle.TRANSPARENCY, baseline.copyOf().put(0, 1f), baseline))
            verifyNoInteractions(att)
            `when`(att.readCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION)).thenReturn(byteArrayOf(0), byteArrayOf(1))
            `when`(att.writeCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1))).thenReturn(true)
            assertFalse(device.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(2)))
            assertTrue(device.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1)))
            assertTrue(device.state.value.loudSoundReductionEnabled)
        }
    }

    @Test fun modeEnablingRequiresAFittedProfileAndAcceptsOnlyKnownInactiveTransparency() = runBlocking<Unit> {
        for (fitted in listOf(true, false)) withFixture { device, att, socket ->
            val profile = hearingRecord().also { if (!fitted) it[1] = 0 }
            `when`(att.readCharacteristic(ATTHandle.HEARING_AID)).thenReturn(profile)
            `when`(att.readCharacteristic(ATTHandle.TRANSPARENCY)).thenReturn(byteArrayOf(2, 0, 0, 0))
            val output = (Shadow.extract<HearingModeSocketShadow>(socket)).output
            output.onPacket = { packet ->
                if (packet.getOrNull(6) == 0x0d.toByte()) reportModes(device, socket, hearing = true)
            }
            assertEquals(fitted, device.setHearingAidEnabled(true))
            assertNull(device.state.value.hearingModes.pending)
            assertNull(device.state.value.hearingModes.uncertain)
            if (!fitted) {
                assertTrue(output.packets.isEmpty())
                verify(att, never()).readCharacteristic(ATTHandle.TRANSPARENCY)
            }
            verify(att, never()).writeCharacteristic(ATTHandle.HEARING_AID, profile)
        }
    }

    private suspend fun withFixture(block: suspend (AppleDevice, ATTManager, BluetoothSocket) -> Unit) {
        mockConstruction(ATTManager::class.java).use { construction ->
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
            seed(device)
            val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            bind(device.aacp, socket)
            reportModes(device, socket)
            try { block(device, construction.constructed().single(), socket) }
            finally { device.aacp.disconnect(); socket.close() }
        }
    }

    private fun seed(device: AppleDevice, hearing: ByteArray = hearingRecord(), transparency: ByteArray = transparencyRecord()) {
        device.loadInitialState(device.state.value.copy(hearingAidData = hearing, transparencyData = transparency),
            AppleSettings(), AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348"))
    }

    private fun reportModes(device: AppleDevice, socket: BluetoothSocket, hearing: Boolean = false) {
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, if (hearing) 1 else 2, 0, 0), socket)
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, if (hearing) 1 else 2, 0, 0, 0), socket)
        device.aacp.processPacket(ByteArray(140).also {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 2, 2).copyInto(it)
        }, socket)
    }

    private fun hearingRecord() = ByteArray(104).also { byteArrayOf(2, 2, 0x60, 0).copyInto(it) }
    private fun transparencyRecord() = ByteArray(104)
    private fun ByteArray.put(offset: Int, value: Float) = apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
    }
    private fun bind(manager: AACPManager, socket: BluetoothSocket) {
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
    }
}
