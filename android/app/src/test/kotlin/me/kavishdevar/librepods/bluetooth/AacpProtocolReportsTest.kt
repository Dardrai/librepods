package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.Capability
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [ProtocolReportSocketShadow::class])
class AacpProtocolReportsTest {
    private val capabilities = "04000400020029010e0202040100000010024001030200000006040000000a010f0109010b01070100000030010000005002a0029001b0011803c002d00260011101f001e00212011301140115011701230124012501210122011901280126012901310136013701".hexToByteArray()
    private val hostHeader = byteArrayOf(4, 0, 4, 0, 0x2e, 0, 1, 0)
    private val host = byteArrayOf(1, 2, 3, 4, 5, 6, 2, 6)

    @Test fun capturedCapabilitiesPreserveAllRawValuesIncludingAffirmativeTwo() {
        val device = device()
        val parsed = device.aacp.parseCapabilitiesResponse(capabilities)
        assertEquals(41, parsed.size)
        assertArrayEquals(byteArrayOf(2), parsed.single { it.capability == Capability.UNKNOWN_HEARING_AID_2 }.value)
        assertArrayEquals(byteArrayOf(2), parsed.single { it.capability == Capability.HEARING_TEST }.value)
        assertArrayEquals(byteArrayOf(14), parsed.single { it.capability == Capability.UNKNOWN_01 }.value)
    }

    @Test fun allWideCapabilityIdsPreserveLittleEndianValuesAndDoNotShiftFollowingEntries() {
        val device = device()
        for (id in listOf(3, 4, 5, 6, 7, 8, 0x30)) {
            val packet = byteArrayOf(4, 0, 4, 0, 2, 0, 2, id.toByte(), 0x98.toByte(),
                0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), 0x11, 0xa5.toByte())
            val parsed = device.aacp.parseCapabilitiesResponse(packet).toList()
            assertEquals(id.toByte(), parsed[0].capability.value)
            assertArrayEquals(packet.copyOfRange(8, 12), parsed[0].value)
            assertEquals(Capability.PERSONAL_MEDICAL_EQUIPMENT, parsed[1].capability)
            assertArrayEquals(byteArrayOf(0xa5.toByte()), parsed[1].value)
        }
        val byteIds = listOf(0x0c, 0x0d, 0x32, 0x33, 0x80)
        val packet = byteArrayOf(4, 0, 4, 0, 2, 0, byteIds.size.toByte()) +
            byteIds.flatMap { listOf(it.toByte(), 2.toByte()) }.toByteArray()
        assertEquals(byteIds, device.aacp.parseCapabilitiesResponse(packet).map { it.capability.value.toInt() and 255 })
    }

    @Test fun invalidCapabilitiesCannotReplaceAValidReportOrAddPacketHistory() = withTransport { device, socket ->
        device.aacp.processPacket(capabilities, socket)
        val previous = device.state.value
        assertThrows(IllegalArgumentException::class.java) {
            device.aacp.parseCapabilitiesResponse(capabilities.copyOf().also { it[4] = 1 })
        }
        val malformed = (0 until capabilities.size).map { capabilities.copyOf(it) }.toMutableList()
        for ((offset, value) in listOf(0 to 0, 2 to 0, 5 to 1, 6 to 40, 6 to 42, 7 to 0xff)) {
            malformed += capabilities.copyOf().also { it[offset] = value.toByte() }
        }
        malformed += capabilities + byteArrayOf(0)
        malformed += byteArrayOf(4, 0, 4, 0, 2, 0, 2, 0x11, 1, 0x11, 2)
        for (packet in malformed) {
            assertThrows(IllegalArgumentException::class.java) { device.aacp.parseCapabilitiesResponse(packet) }
            device.aacp.processPacket(packet, socket)
            assertSame(previous, device.state.value)
        }
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 2, 0, 0), socket)
        assertTrue(device.state.value.capabilities.isEmpty())
    }

    @Test fun connectedHostsRejectTrailingDuplicateAndAliasedReportsWithoutReplacingState() = withTransport { device, socket ->
        val valid = hostHeader + byteArrayOf(1) + host
        device.aacp.processPacket(valid, socket)
        val previous = device.state.value
        assertEquals("01:02:03:04:05:06", previous.connectedDevices.single().macAddress.value)
        for (packet in listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            hostHeader + byteArrayOf(0, 0), hostHeader + byteArrayOf(2) + host + host,
            (hostHeader + byteArrayOf(0)).also { it[5] = 1 })) {
            device.aacp.processPacket(packet, socket)
            assertSame(previous, device.state.value)
        }
        device.aacp.processPacket(hostHeader + byteArrayOf(0), socket)
        assertTrue(device.state.value.connectedDevices.isEmpty())
    }

    @Test fun capabilityRequestUsesCapturedOpcodeAndSelector() = withTransport { device, socket ->
        assertTrue(device.aacp.sendCapabilitiesRequest(socket))
        val shadow = Shadow.extract<ProtocolReportSocketShadow>(socket)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 1, 0, 8), shadow.output.bytes.toByteArray())
    }

    @Test fun booleanSendPathsUseEarSpecificEncodingWithoutReversingOtherTogglesOrRawValues() = withTransport { device, socket ->
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        for (id in listOf(ControlCommandIdentifier.EAR_DETECTION_CONFIG,
            ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG, ControlCommandIdentifier.ALLOW_OFF_OPTION)) {
            for (enabled in listOf(true, false)) {
                val expected = if (id == ControlCommandIdentifier.EAR_DETECTION_CONFIG) {
                    if (enabled) 2 else 1
                } else if (enabled) 1 else 2
                val packet = byteArrayOf(4, 0, 4, 0, 9, 0, id.value, expected.toByte(), 0, 0, 0)
                output.reset()
                assertTrue(device.setControlCommand(id, enabled))
                assertArrayEquals(packet, output.toByteArray())
                assertArrayEquals(byteArrayOf(expected.toByte()), device.state.value.requestedControls[id])
                output.reset()
                assertTrue(device.aacp.sendControlCommand(id.value, enabled))
                assertArrayEquals(packet, output.toByteArray())
                assertArrayEquals(byteArrayOf(expected.toByte()), device.state.value.requestedControls[id])
            }
        }
        output.reset()
        assertTrue(device.setControlCommand(ControlCommandIdentifier.EAR_DETECTION_CONFIG, 1))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0a, 1, 0, 0, 0), output.toByteArray())
    }

    @Test fun conversationActivityCannotChangeTheEnabledSettingOrConfirmAPendingChoice() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        for (enabled in listOf(1, 2)) {
            val requested = if (enabled == 1) 2 else 1
            device.loadInitialState(AppleState(controlStates = mapOf(id to byteArrayOf(enabled.toByte())),
                requestedControls = mapOf(id to byteArrayOf(requested.toByte()))), AppleSettings(), AppleMetadata())
            for (stage in listOf(1, 2, 3, 4, 6, 7, 8, 9)) {
                device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 0x4b, 0, 0, 0, 0, stage.toByte()), socket)
                assertEquals(stage, device.state.value.conversationAwarenessState)
                assertArrayEquals(byteArrayOf(enabled.toByte()), device.state.value.controlStates[id])
                assertArrayEquals(byteArrayOf(requested.toByte()), device.state.value.requestedControls[id])
            }
        }
    }

    @Test fun invalidConversationSettingsPreserveTheLastReportAndPendingChoice() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        val header = byteArrayOf(4, 0, 4, 0, 9, 0, 0x28)
        device.aacp.processPacket(header + byteArrayOf(1, 0, 0, 0), socket)
        val confirmed = device.state.value
        device.loadInitialState(confirmed.copy(requestedControls = mapOf(id to byteArrayOf(2))),
            AppleSettings(), AppleMetadata())
        val previous = device.state.value
        val malformed = listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(3, 0, 0, 0),
            byteArrayOf(0xff.toByte(), 0, 0, 0), byteArrayOf(1, 1, 0, 0), byteArrayOf(2, 0, 0, 1))
        for (value in malformed) {
            device.aacp.processPacket(header + value, socket)
            assertSame(previous, device.state.value)
        }
        for (length in 0 until 11) {
            device.aacp.processPacket((header + byteArrayOf(2, 0, 0, 0)).copyOf(length), socket)
            assertSame(previous, device.state.value)
        }
        device.aacp.processPacket(header + byteArrayOf(2, 0, 0, 0), socket)
        assertArrayEquals(byteArrayOf(2), device.state.value.controlStates[id])
        assertFalse(id in device.state.value.requestedControls)
        device.aacp.processPacket(header + byteArrayOf(1, 0, 0, 0), socket)
        assertArrayEquals(byteArrayOf(1), device.state.value.controlStates[id])
    }

    @Test fun malformedKnownControlReportsCannotReplaceConfirmedOrPendingValues() = withTransport { device, socket ->
        for ((id, baseline, pending) in listOf(
            Triple(ControlCommandIdentifier.AUTO_ANC_STRENGTH, 50, 25),
            Triple(ControlCommandIdentifier.ALLOW_OFF_OPTION, 1, 2),
            Triple(ControlCommandIdentifier.EAR_DETECTION_CONFIG, 1, 2))) {
            val header = byteArrayOf(4, 0, 4, 0, 9, 0, id.value)
            for (request in listOf(null, pending)) {
                device.loadInitialState(AppleState(controlStates = mapOf(id to byteArrayOf(baseline.toByte())),
                    requestedControls = request?.let { mapOf(id to byteArrayOf(it.toByte())) }.orEmpty()),
                    AppleSettings(), AppleMetadata())
                val previous = device.state.value
                for (invalid in listOf(byteArrayOf((if (baseline == 50) 101 else 3).toByte(), 0, 0, 0),
                    byteArrayOf(0xff.toByte(), 0, 0, 0), byteArrayOf(baseline.toByte(), 1, 0, 0))) {
                    device.aacp.processPacket(header + invalid, socket)
                    assertSame(previous.controlStates, device.state.value.controlStates)
                    assertSame(previous.requestedControls, device.state.value.requestedControls)
                }
                if (id == ControlCommandIdentifier.EAR_DETECTION_CONFIG && request != null) {
                    device.aacp.processPacket(header + byteArrayOf(baseline.toByte(), 0, 0, 0), socket)
                    assertArrayEquals(byteArrayOf(request.toByte()), device.state.value.requestedControls[id])
                }
                device.aacp.processPacket(header + byteArrayOf(pending.toByte(), 0, 0, 0), socket)
                assertArrayEquals(byteArrayOf(pending.toByte()), device.state.value.controlStates[id])
                assertFalse(id in device.state.value.requestedControls)
            }
        }
    }

    @Test fun retiringConversationActivityLetsTheSameStageStartOnTheReplacementTransport() {
        val device = device()
        val first = socket()
        val replacement = socket()
        val speechStart = byteArrayOf(4, 0, 4, 0, 0x4b, 0, 0, 0, 0, 1)
        bind(device.aacp, first)
        try {
            device.aacp.processPacket(speechStart, first)
            assertEquals(1, device.state.value.conversationAwarenessState)
            assertTrue(device.aacp.disconnect())
            assertEquals(0, device.state.value.conversationAwarenessState)
            bind(device.aacp, replacement)
            val beforeReplacement = device.state.value
            device.aacp.processPacket(speechStart, first)
            assertSame(beforeReplacement, device.state.value)
            device.aacp.processPacket(speechStart, replacement)
            assertEquals(1, device.state.value.conversationAwarenessState)
            assertTrue(device.state.value.controlStates.isEmpty())
        } finally { device.aacp.disconnect(); first.close(); replacement.close() }
    }

    @Test fun aSilentConversationChoiceSurvivesTheGenericTenSecondTimeoutUntilAFreshReportOrDisconnect() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        fun report(value: Int) = device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x28, value.toByte(), 0, 0, 0), socket)
        report(2)
        assertTrue(device.aacp.sendControlCommand(id.value, true))
        Thread.sleep(11_000)
        assertArrayEquals(byteArrayOf(2), device.state.value.controlStates[id])
        assertArrayEquals(byteArrayOf(1), device.state.value.requestedControls[id])
        report(2)
        assertFalse(id in device.state.value.requestedControls)
        assertTrue(device.aacp.sendControlCommand(id.value, false))
        assertArrayEquals(byteArrayOf(2), device.state.value.requestedControls[id])
        assertTrue(device.aacp.sendControlCommand(id.value, true))
        report(1)
        assertFalse(id in device.state.value.requestedControls)
        assertTrue(device.aacp.sendControlCommand(id.value, false))
        assertArrayEquals(byteArrayOf(2), device.state.value.requestedControls[id])
        device.aacp.disconnect()
        assertTrue(device.state.value.requestedControls.isEmpty())
    }

    @Test fun failedConversationWritesKeepThePreviousChoiceAndReportsDuringWritesWin() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
        val report = byteArrayOf(4, 0, 4, 0, 9, 0, 0x28, 2, 0, 0, 0)
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output
        device.aacp.processPacket(report, socket)
        assertTrue(device.aacp.sendControlCommand(id.value, true))
        val beforeFailure = device.state.value
        output.failWrites = true
        assertFalse(device.aacp.sendControlCommand(id.value, false))
        assertSame(beforeFailure, device.state.value)
        output.failWrites = false
        output.beforeWrite = { device.aacp.processPacket(report, socket) }
        assertTrue(device.aacp.sendControlCommand(id.value, true))
        assertArrayEquals(byteArrayOf(2), device.state.value.controlStates[id])
        assertFalse(id in device.state.value.requestedControls)
    }

    @Test fun retiringAndAdoptingControlSessionsClearPreviousCapabilities() {
        val device = device()
        val first = socket()
        bind(device.aacp, first)
        device.aacp.processPacket(capabilities, first)
        val cached = device.state.value.capabilities
        assertTrue(device.aacp.disconnect())
        assertTrue(device.state.value.capabilities.isEmpty())
        device.loadInitialState(AppleState(capabilities = cached), AppleSettings(), AppleMetadata())
        val replacement = socket(connected = false)
        val manager = AACPManager(device) { replacement }
        try {
            assertTrue(manager.connect())
            assertTrue(device.state.value.capabilities.isEmpty())
        } finally { manager.disconnect(); first.close(); replacement.close() }
    }

    private fun device(): AppleDevice {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        return AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
    }

    private fun socket(connected: Boolean = true): BluetoothSocket =
        Shadow.newInstanceOf(BluetoothSocket::class.java).also { if (connected) it.connect() }

    private fun bind(manager: AACPManager, socket: BluetoothSocket) {
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
    }

    private fun withTransport(test: (AppleDevice, BluetoothSocket) -> Unit) {
        val device = device()
        val socket = socket()
        bind(device.aacp, socket)
        try { test(device, socket) } finally { device.aacp.disconnect(); socket.close() }
    }
}

@Implements(BluetoothSocket::class)
class ProtocolReportSocketShadow : ShadowBluetoothSocket() {
    val output = ProtocolReportOutput()
    init { setOutputStream(output) }
    @Implementation public override fun close() {
        super.close()
        inputStreamFeeder.close()
        outputStreamSink.close()
    }
}

class ProtocolReportOutput : PipedOutputStream() {
    val bytes = ByteArrayOutputStream()
    var failWrites = false
    var beforeWrite: (() -> Unit)? = null
    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (failWrites) throw IOException("Test write failure")
        beforeWrite?.also { beforeWrite = null; it() }
        bytes.write(data, offset, length)
    }
    override fun flush() = Unit
}
