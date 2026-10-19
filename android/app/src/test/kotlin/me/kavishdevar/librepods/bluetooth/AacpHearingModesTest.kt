package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
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
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [HearingModeSocketShadow::class])
class AacpHearingModesTest {
    @Test fun currentCompleteReportsOwnHearingStateAndUnknownFlagsInvalidateKnownValues() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            assertEquals(true, fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertEquals(false, fixture.device.state.value.hearingModes.mediaAssistEnabled)
            fixture.device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 0x53, 0, 1, 1), fixture.socket)
            assertEquals(false, fixture.device.state.value.hearingModes.mediaAssistEnabled)
            fixture.device.aacp.processPacket(control(0x2c, 0, 0), fixture.socket)
            assertNull(fixture.device.state.value.hearingModes.enrolled)
            assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertFalse(fixture.device.state.value.hearingModes.customTransparencyAllowed)
        }
    }

    @Test fun genericControlsCannotBypassTheHearingModeTransaction() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = false, media = false)
            assertFalse(fixture.device.setControlCommand(ControlCommandIdentifier.HEARING_AID, byteArrayOf(1, 1)))
            assertFalse(fixture.device.setControlCommand(ControlCommandIdentifier.HEARING_ASSIST_CONFIG, true))
            assertTrue(fixture.shadow.output.packets.isEmpty())
        }
    }

    @Test fun unavailableAndroidFittingReadCannotEnableModesOrWriteFittingData() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = false, media = false)
            for (mode in HearingMode.entries) {
                assertFalse(when (mode) {
                    HearingMode.HEARING_AID -> fixture.device.setHearingAidEnabled(true)
                    HearingMode.MEDIA_ASSIST -> fixture.device.setMediaAssistEnabled(true)
                })
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.state.value.hearingModes.uncertain)
                assertArrayEquals(byteArrayOf(), fixture.device.state.value.hearingAidData)
                assertTrue(fixture.shadow.output.packets.isEmpty())
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
            }
        }
    }

    @Test fun independentOffPreservesTheOtherModeAndEveryPmeCurveWithoutAttTraffic() = runBlocking<Unit> {
        mockConstruction(ATTManager::class.java).use { construction ->
            for (mode in HearingMode.entries) withFixture { fixture ->
                fixture.report(hearing = true, media = true)
                fixture.shadow.output.onPacket = { fixture.report(hearing = mode != HearingMode.HEARING_AID,
                    media = mode != HearingMode.MEDIA_ASSIST) }
                assertTrue(when (mode) {
                    HearingMode.HEARING_AID -> fixture.device.setHearingAidEnabled(false)
                    HearingMode.MEDIA_ASSIST -> fixture.device.setMediaAssistEnabled(false)
                })
                val written = fixture.shadow.output.packets.single()
                if (mode == HearingMode.HEARING_AID) {
                    assertArrayEquals(control(0x2c, 1, 2), written)
                    assertEquals(true, fixture.device.state.value.hearingModes.mediaAssistEnabled)
                } else {
                    assertArrayEquals(pme(enabled = false), written)
                    assertEquals(true, fixture.device.state.value.hearingModes.hearingAidEnabled)
                }
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.state.value.hearingModes.uncertain)
                verifyNoInteractions(construction.constructed().last())
                assertEquals(0, fixture.shadow.closes)
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
            }
        }
    }

    @Test fun unknownOrActiveHearingModesBlockCustomTransparencyBeforeAttWrite() = runBlocking<Unit> {
        val customTransparency = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).array()
        mockConstruction(ATTManager::class.java).use { construction ->
            for (state in listOf("unknown", "hearing", "media")) withFixture { fixture ->
                if (state != "unknown") fixture.report(hearing = state == "hearing", media = state == "media")
                val att = construction.constructed().last()
                `when`(att.writeCharacteristic(ATTHandle.TRANSPARENCY, customTransparency)).thenReturn(true)
                assertFalse(fixture.device.writeATTCharacteristic(ATTHandle.TRANSPARENCY, customTransparency))
                verifyNoInteractions(att)
                assertTrue(fixture.shadow.output.packets.isEmpty())
            }
        }
    }

    @Test fun retiringControlSessionClearsHearingKnowledgeAndOldSocketCannotRestoreIt() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = true)
            assertTrue(fixture.device.aacp.disconnect())
            assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertNull(fixture.device.state.value.hearingModes.mediaAssistEnabled)
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            bind(fixture.device.aacp, replacement)
            try {
                fixture.report(hearing = true, media = true)
                assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
                assertFalse(fixture.device.state.value.hearingModes.canChange(HearingMode.HEARING_AID, true))
                assertFalse(fixture.device.state.value.hearingModes.customTransparencyAllowed)
            } finally { fixture.device.aacp.disconnect(); replacement.close() }
        }
    }

    @Test fun explicitOffDuringMicrophoneCapturePreservesTheTransportAndMicrophoneRequest() = runBlocking<Unit> {
        mockConstruction(ATTManager::class.java).use { construction ->
            withFixture { fixture ->
                fixture.report(hearing = true, media = true)
                fixture.device.updateMicrophoneState { it.copy(isActive = true) }
                assertTrue(fixture.device.aacp.requestMicrophoneStream(fixture.socket))
                fixture.shadow.output.packets.clear()
                fixture.shadow.output.onPacket = { fixture.report(hearing = false, media = true) }
                assertTrue(fixture.device.setHearingAidEnabled(false))
                assertArrayEquals(control(0x2c, 1, 2), fixture.shadow.output.packets.single())
                assertTrue(fixture.device.state.value.microphoneState.isActive)
                assertTrue(fixture.device.aacp.isMicrophoneRequested())
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                assertEquals(0, fixture.shadow.closes)
                verifyNoInteractions(construction.constructed().single())
            }
        }
    }

    @Test fun partialOffFailureRemainsUncertainUntilExplicitOffHasFreshMatchingReports() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            fixture.shadow.output.onPacket = {
                if (fixture.shadow.output.packets.size == 2) throw IOException("Rejected second mode write")
            }
            assertFalse(fixture.device.setHearingAidEnabled(false))
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
            assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertFalse(fixture.device.state.value.hearingModes.customTransparencyAllowed)
            fixture.shadow.output.packets.clear()
            fixture.shadow.output.onPacket = { packet ->
                if (packet.getOrNull(6) == 0x2c.toByte()) fixture.report(hearing = false, media = false)
            }
            assertTrue(fixture.device.setHearingAidEnabled(false))
            assertEquals(2, fixture.shadow.output.packets.size)
            assertArrayEquals(control(0x33, 2), fixture.shadow.output.packets[0])
            assertArrayEquals(control(0x2c, 1, 2), fixture.shadow.output.packets[1])
            assertNull(fixture.device.state.value.hearingModes.uncertain)
            assertEquals(false, fixture.device.state.value.hearingModes.hearingAidEnabled)
        }
    }

    @Test fun pendingHearingModeRejectsNewAndPreviouslyPreparedManualRefreshes() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = true)
            val prepared = requireNotNull(fixture.device.aacp.prepareControlSessionRefresh())
            var confirmed: Boolean? = null
            val request = launch(Dispatchers.Unconfined) { confirmed = fixture.device.setHearingAidEnabled(false) }
            try {
                assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.aacp.prepareControlSessionRefresh())
                assertFalse(fixture.device.aacp.refreshControlSession(prepared) { true })
                assertEquals(0, fixture.shadow.closes)
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                fixture.report(hearing = false, media = true)
                request.join()
                assertEquals(true, confirmed)
                assertNull(fixture.device.state.value.hearingModes.pending)
            } finally { request.cancelAndJoin() }
        }
    }

    @Test fun cancellingAnUnconfirmedRequestReleasesPendingAndAllowsExplicitOffRecovery() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            val request = launch(Dispatchers.Unconfined) { fixture.device.setHearingAidEnabled(false) }
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.pending)
            request.cancelAndJoin()
            assertNull(fixture.device.state.value.hearingModes.pending)
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
            fixture.shadow.output.packets.clear()
            fixture.shadow.output.onPacket = { packet ->
                if (packet.getOrNull(6) == 0x2c.toByte()) fixture.report(hearing = false, media = false)
            }
            assertTrue(fixture.device.setHearingAidEnabled(false))
            assertEquals(2, fixture.shadow.output.packets.size)
            assertNull(fixture.device.state.value.hearingModes.pending)
            assertNull(fixture.device.state.value.hearingModes.uncertain)
            assertEquals(false, fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertEquals(0, fixture.shadow.closes)
            assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3,
            modelNumber = "A3063", version1 = "9A348"))
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        bind(device.aacp, socket)
        val fixture = Fixture(device, socket, Shadow.extract(socket))
        try { block(fixture) } finally { device.aacp.disconnect(); socket.close() }
    }

    private class Fixture(val device: AppleDevice, val socket: BluetoothSocket, val shadow: HearingModeSocketShadow) {
        fun report(hearing: Boolean, media: Boolean) {
            device.aacp.processPacket(control(0x2c, 1, if (hearing) 1 else 2), socket)
            device.aacp.processPacket(control(0x33, if (hearing || media) 1 else 2), socket)
            device.aacp.processPacket(pme(media), socket)
        }
    }

    companion object {
        private fun control(id: Int, first: Int, second: Int = 0) =
            byteArrayOf(4, 0, 4, 0, 9, 0, id.toByte(), first.toByte(), second.toByte(), 0, 0)
        private fun pme(enabled: Boolean) = ByteArray(140) { (it * 7).toByte() }.apply {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1,
                (if (enabled) 1 else 2).toByte(), (if (enabled) 1 else 2).toByte()).copyInto(this)
        }
        private fun bind(manager: AACPManager, socket: BluetoothSocket) {
            AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
        }
    }
}

@Implements(BluetoothSocket::class)
class HearingModeSocketShadow : ClosingBluetoothSocketShadow() {
    val output = HearingModeOutput()
    var closes = 0
    init { setOutputStream(output) }
    protected override fun onClosing() {
        closes++
    }
}

class HearingModeOutput : PipedOutputStream() {
    val packets = mutableListOf<ByteArray>()
    var onPacket: ((ByteArray) -> Unit)? = null
    override fun write(data: ByteArray, offset: Int, length: Int) {
        val packet = data.copyOfRange(offset, offset + length)
        packets += packet
        onPacket?.invoke(packet)
    }
    override fun flush() = Unit
}
