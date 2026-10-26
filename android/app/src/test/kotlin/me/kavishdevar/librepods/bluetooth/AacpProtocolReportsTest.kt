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
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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

    @Test fun retiredEarReportsCannotReplaceTheCaseStateOnAReplacementConnection() = withTransport { device, socket ->
        val leftOnly = byteArrayOf(4, 0, 4, 0, 6, 0, 0, 2)
        val rightOnly = byteArrayOf(4, 0, 4, 0, 6, 0, 2, 0)
        device.aacp.processPacket(leftOnly, socket)
        assertEquals(setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR),
            DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_CASE)), device.state.value.componentState)
        val replacement = socket()
        try {
            bind(device.aacp, replacement)
            val retained = device.state.value
            device.aacp.processPacket(rightOnly, socket)
            assertSame(retained, device.state.value)
            device.aacp.processPacket(rightOnly, replacement)
            assertEquals(setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_CASE),
                DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_EAR)), device.state.value.componentState)
        } finally { replacement.close() }
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

    @Test fun assistantChoiceDelegatesOnlyHoldsAndPreservesTheOtherBudWithoutANativeReport() = withTransport { device, socket ->
        val bytes = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        assertTrue(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, 8, 0, 0, 0), bytes.toByteArray())
        assertEquals(StemAction.DIGITAL_ASSISTANT, device.settings.value.leftLongPressAction)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, device.settings.value.rightLongPressAction)
        assertEquals(StemAction.DIGITAL_ASSISTANT, device.hostHoldAction(AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.LEFT, socket)))
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, device.hostHoldAction(AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.RIGHT, socket)))
        assertNull(device.hostHoldAction(AppleEvent.StemPress(StemPressType.SINGLE_PRESS, StemPressBud.LEFT, socket)))
        assertTrue(device.setLongPressAction("right", StemAction.DIGITAL_ASSISTANT))
        assertEquals(11, bytes.size())
        assertTrue(device.setLongPressAction("left", StemAction.CYCLE_NOISE_CONTROL_MODES))
        assertEquals(11, bytes.size())
        assertTrue(device.setLongPressAction("right", StemAction.CYCLE_NOISE_CONTROL_MODES))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, 0, 0, 0, 0), bytes.toByteArray().takeLast(11).toByteArray())
        assertNull(device.hostHoldAction(AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.RIGHT, socket)))
    }

    @Test fun failedDisconnectedAndInvalidHoldChangesCannotAlterTheSavedChoice() = withTransport { device, socket ->
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output
        val original = device.settings.value
        output.failWrites = true
        assertFalse(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT))
        assertEquals(original, device.settings.value)
        output.failWrites = false
        assertFalse(device.setLongPressAction("unknown", StemAction.DIGITAL_ASSISTANT))
        assertFalse(device.setLongPressAction("left", StemAction.PLAY_PAUSE))
        assertEquals(0, output.bytes.size())
        device.aacp.disconnect()
        assertFalse(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT))
        assertEquals(original, device.settings.value)
    }

    @Test fun reconnectReappliesSavedHoldChoicesAndRejectsRetiredGestures() = withTransport { device, socket ->
        assertTrue(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT))
        val oldEvent = AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.LEFT, socket)
        val replacement = socket()
        try {
            bind(device.aacp, replacement)
            assertNull(device.hostHoldAction(oldEvent))
            assertTrue(device.configureHoldActions(replacement))
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, 8, 0, 0, 0),
                Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.toByteArray())
            assertEquals(StemAction.DIGITAL_ASSISTANT, device.hostHoldAction(oldEvent.copy(sourceSocket = replacement)))
            device.aacp.disconnect()
            assertNull(device.hostHoldAction(oldEvent.copy(sourceSocket = replacement)))
        } finally { replacement.close() }
    }

    @Test fun queuedHoldPreferencesCannotCrossTheirCapturedSocketOrSession() = withTransport { device, socket ->
        val original = device.settings.value
        val session = device.state.value.controlSession
        val replacement = socket()
        try {
            bind(device.aacp, replacement)
            assertFalse(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT, socket, session))
            assertFalse(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT, replacement, session + 1))
            assertEquals(original, device.settings.value)
            assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
            assertTrue(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT, replacement, session))
            assertEquals(11, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
        } finally { replacement.close() }
    }

    @Test fun otherBudsHoldCyclesNoiseControlOnTheCapturedTransport() = withTransport { device, socket ->
        device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3,
            modelNumber = "A3063", version1 = "9A348"))
        assertTrue(device.setLongPressAction("left", StemAction.DIGITAL_ASSISTANT))
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 2, 0, 0, 0), socket)
        val event = AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.RIGHT, socket)
        assertTrue(device.cycleHoldListeningMode(event))
        val bytes = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 3, 0, 0, 0), bytes.toByteArray().takeLast(11).toByteArray())
        assertFalse(device.cycleHoldListeningMode(event.copy(bud = StemPressBud.LEFT)))
        device.aacp.disconnect()
        assertFalse(device.cycleHoldListeningMode(event))
    }

    @Test fun completeCycleChoiceWritesOnlyNativeMaskPreservesActionsAndWaitsForFreshReadback() = withTransport { device, socket ->
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        val settings = AppleSettings(leftLongPressAction = StemAction.DIGITAL_ASSISTANT,
            rightLongPressAction = StemAction.CYCLE_NOISE_CONTROL_MODES)
        device.loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.ALLOW_OFF_OPTION to byteArrayOf(1))), settings, metadata)
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        assertEquals(0, output.size())
        assertTrue(device.setListeningModeCycle(0x0b, metadata, socket))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1a, 0x0b, 0, 0, 0), output.toByteArray())
        assertEquals(settings.leftLongPressAction, device.settings.value.leftLongPressAction)
        assertEquals(settings.rightLongPressAction, device.settings.value.rightLongPressAction)
        assertNull(device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS])
        assertArrayEquals(byteArrayOf(11), device.state.value.requestedControls[ControlCommandIdentifier.LISTENING_MODE_CONFIGS])
        assertEquals("0b", device.settings.value.savedUnconfirmedChoices["desired_noise_modes_cycle"])
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1a, 11, 0, 0, 0), socket)
        assertNull(device.state.value.requestedControls[ControlCommandIdentifier.LISTENING_MODE_CONFIGS])
        assertTrue(ControlCommandIdentifier.LISTENING_MODE_CONFIGS in device.state.value.liveControlReports)
        assertEquals("A device echo must not issue another write", 11, output.size())
    }

    @Test fun assistantAndCycleSetupUsesTheAppliedMaskBeforeAnyNativeMaskEcho() = withTransport { device, socket ->
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        device.loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.ALLOW_OFF_OPTION to byteArrayOf(1),
            ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(2))),
            AppleSettings(leftLongPressAction = StemAction.DIGITAL_ASSISTANT), metadata)
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 2, 0, 0, 0), socket)
        assertTrue(device.configureHoldActions(socket))
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        output.reset()
        assertTrue(device.setListeningModeCycle(11, metadata, socket))
        assertNull(device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS])
        assertTrue(device.cycleHoldListeningMode(AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.RIGHT, socket)))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1a, 11, 0, 0, 0,
            4, 0, 4, 0, 9, 0, 0x0d, 4, 0, 0, 0), output.toByteArray())
        assertEquals(StemAction.DIGITAL_ASSISTANT, device.settings.value.leftLongPressAction)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, device.settings.value.rightLongPressAction)
    }

    @Test fun invalidCyclesOffPrerequisiteAndStaleModelOrSocketCannotWrite() = withTransport { device, socket ->
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        device.loadInitialState(AppleState(), AppleSettings(), metadata)
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        for (mask in listOf(0, 1, 2, 4, 8, 11, 16, 31)) assertFalse(device.setListeningModeCycle(mask, metadata, socket))
        val replacementMetadata = metadata.copy(model = AirPodsModel.AIRPODS_PRO_1, modelNumber = "A2084")
        device.loadInitialState(AppleState(), AppleSettings(), replacementMetadata)
        assertFalse(device.setListeningModeCycle(6, metadata, socket))
        assertFalse(device.setListeningModeCycle(10, replacementMetadata, socket))
        assertEquals(0, output.size())
        val replacement = socket()
        try {
            bind(device.aacp, replacement)
            assertFalse(device.setListeningModeCycle(6, replacementMetadata, socket))
            assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
            assertTrue(device.setListeningModeCycle(6, replacementMetadata, replacement))
        } finally { replacement.close() }
    }

    @Test fun nativeEndCallMappingPreservesRawHoldDelegationAndSharedCycle() = withTransport { device, socket ->
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        val settings = AppleSettings(leftLongPressAction = StemAction.DIGITAL_ASSISTANT,
            rightLongPressAction = StemAction.CYCLE_NOISE_CONTROL_MODES)
        device.loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE_CONFIGS to byteArrayOf(11))), settings, metadata)
        assertTrue(device.configureHoldActions(socket))
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 3, 0, 0), socket)
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, 8, 0, 0, 0), output.toByteArray())
        output.reset()
        assertTrue(device.setCallControls(true, metadata, socket))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 2, 0, 0), output.toByteArray())
        assertArrayEquals(byteArrayOf(0, 3), device.state.value.controlStates[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG])
        assertArrayEquals(byteArrayOf(0, 2), device.state.value.requestedControls[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG])
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 2, 0, 0), socket)
        assertNull(device.state.value.requestedControls[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG])
        assertTrue(device.setCallControls(false, metadata, socket))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 2, 0, 0,
            4, 0, 4, 0, 9, 0, 0x24, 0, 3, 0, 0), output.toByteArray())
        assertTrue(device.configureHoldActions(socket))
        assertEquals("Call preferences never reconfigure raw hold delegation", 22, output.size())
        assertEquals(StemAction.DIGITAL_ASSISTANT, device.hostHoldAction(AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.LEFT, socket)))
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, device.hostHoldAction(AppleEvent.StemPress(StemPressType.LONG_PRESS, StemPressBud.RIGHT, socket)))
        for (press in listOf(StemPressType.SINGLE_PRESS, StemPressType.DOUBLE_PRESS, StemPressType.TRIPLE_PRESS))
            assertNull(device.hostHoldAction(AppleEvent.StemPress(press, StemPressBud.LEFT, socket)))
        assertArrayEquals(byteArrayOf(11), device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS])
        assertEquals(settings, device.settings.value)
    }

    @Test fun failedOrRetiredNativeCallRequestCannotPublishChoiceOrUseReplacementSocket() = withTransport { device, socket ->
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        device.loadInitialState(AppleState(), AppleSettings(), metadata)
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 3, 0, 0), socket)
        val original = Shadow.extract<ProtocolReportSocketShadow>(socket).output
        original.failWrites = true
        assertFalse(device.setCallControls(true, metadata, socket))
        assertTrue(device.state.value.requestedControls.isEmpty())
        assertTrue(device.settings.value.savedUnconfirmedChoices.isEmpty())
        original.failWrites = false
        val replacement = socket()
        try {
            bind(device.aacp, replacement)
            assertFalse(device.setCallControls(true, metadata, socket))
            assertFalse(device.setCallControls(true, metadata.copy(), replacement))
            assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
            device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata())
            assertFalse(device.setCallControls(true, device.metadata.value, replacement))
            device.loadInitialState(AppleState(), AppleSettings(), metadata)
            device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 3, 0, 0), replacement)
            assertTrue(device.setCallControls(true, metadata, replacement))
        } finally { replacement.close() }
    }

    @Test fun socketRetiredDuringNativeCallWriteCannotPublishAnAppliedChoice() = withTransport { device, socket ->
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        device.loadInitialState(AppleState(), AppleSettings(), metadata)
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 0, 3, 0, 0), socket)
        val replacement = socket()
        try {
            Shadow.extract<ProtocolReportSocketShadow>(socket).output.beforeWrite = { bind(device.aacp, replacement) }
            assertFalse(device.setCallControls(true, metadata, socket))
            assertTrue(device.state.value.requestedControls.isEmpty())
            assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
        } finally { replacement.close() }
    }

    @Test fun disconnectClosesBlockedCycleAndCallWritesWithoutWaitingForTheWriter() {
        for (callControls in listOf(false, true)) withTransport { device, socket ->
            val metadata = prepareGuardedSetting(device)
            val shadow = Shadow.extract<ProtocolReportSocketShadow>(socket)
            val entered = CountDownLatch(1)
            val closed = CountDownLatch(1)
            shadow.onClose = { closed.countDown() }
            shadow.output.beforeWrite = {
                entered.countDown()
                closed.await(10, TimeUnit.SECONDS)
                throw IOException("Socket closed during blocked write")
            }
            val result = AtomicReference<Boolean?>()
            val writer = thread { result.set(writeGuardedSetting(device, socket, metadata, callControls)) }
            var disconnect: Thread? = null
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val retired = CountDownLatch(1)
                disconnect = thread { device.aacp.disconnect(); retired.countDown() }
                assertTrue("Disconnect must close a blocked setting write", retired.await(2, TimeUnit.SECONDS))
                writer.join(2_000)
                assertFalse(writer.isAlive)
                assertEquals(false, result.get())
                assertNull(device.aacp.captureAudioSocket())
                assertTrue(device.state.value.requestedControls.isEmpty())
                assertTrue(device.settings.value.savedUnconfirmedChoices.isEmpty())
            } finally {
                closed.countDown()
                writer.join(2_000)
                disconnect?.join(2_000)
            }
        }
    }

    @Test fun guardedSettingsRejectSocketOrMetadataReplacedWhileWaitingForAdmission() {
        for (callControls in listOf(false, true)) for (replaceSocket in listOf(false, true)) withTransport { device, socket ->
            val metadata = prepareGuardedSetting(device)
            val replacement = socket()
            val result = AtomicReference<Boolean?>()
            val writer = thread(start = false) { result.set(writeGuardedSetting(device, socket, metadata, callControls)) }
            try {
                synchronized(device.aacp) {
                    writer.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals("The setting must wait for admission", Thread.State.BLOCKED, writer.state)
                    if (replaceSocket) bind(device.aacp, replacement)
                    else device.loadInitialState(device.state.value, device.settings.value, metadata.copy(name = "Changed"))
                }
                writer.join(2_000)
                assertFalse(writer.isAlive)
                assertEquals(false, result.get())
                assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes.size())
                assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
                assertTrue(device.state.value.requestedControls.isEmpty())
            } finally {
                writer.join(2_000)
                replacement.close()
            }
        }
    }

    @Test fun guardedSettingsCannotPublishIntoMetadataChangedDuringTheWrite() {
        for (callControls in listOf(false, true)) withTransport { device, socket ->
            val metadata = prepareGuardedSetting(device)
            val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output
            output.beforeWrite = {
                device.loadInitialState(device.state.value, device.settings.value, metadata.copy(name = "Changed"))
            }
            assertFalse(writeGuardedSetting(device, socket, metadata, callControls))
            assertTrue(device.state.value.requestedControls.isEmpty())
            assertTrue(device.settings.value.savedUnconfirmedChoices.isEmpty())
        }
    }

    @Test fun queuedSettingsRecheckMetadataAfterAcquiringTheSocket() {
        for (callControls in listOf(false, true)) rejectQueuedSetting(callControls) { device, metadata, _ ->
            device.loadInitialState(device.state.value, device.settings.value, metadata.copy(name = "Changed"))
        }
    }

    @Test fun queuedCallSettingCannotFlattenANewerPackedCallReport() = rejectQueuedSetting(true) { device, _, socket ->
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24, 1, 0x23, 2, 0), socket)
    }

    @Test fun queuedCycleCannotWriteOffAfterItsPrerequisiteIsDisabled() = rejectQueuedSetting(false) { device, _, socket ->
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x34, 2, 0, 0, 0), socket)
    }

    @Test fun retiredCycleWriteCannotPublishOrSendToAReplacementSocket() = withTransport { device, socket ->
        val metadata = prepareGuardedSetting(device)
        val replacement = socket()
        try {
            Shadow.extract<ProtocolReportSocketShadow>(socket).output.beforeWrite = { bind(device.aacp, replacement) }
            assertFalse(device.setListeningModeCycle(11, metadata, socket))
            assertTrue(device.state.value.requestedControls.isEmpty())
            assertTrue(device.settings.value.savedUnconfirmedChoices.isEmpty())
            assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(replacement).output.bytes.size())
        } finally { replacement.close() }
    }

    @Test fun legacyEndCallWriteRequiresFreshReadbackAndCannotOverwriteOtherCallPayloads() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes
        for (state in listOf(AppleState(), AppleState(controlStates = mapOf(id to byteArrayOf(0, 3))),
            AppleState(requestedControls = mapOf(id to byteArrayOf(0, 2))))) {
            device.loadInitialState(state, AppleSettings(), metadata)
            assertFalse(device.setCallControls(true, metadata, socket))
        }
        for (unsupported in listOf(byteArrayOf(1, 0x23, 2, 0), byteArrayOf(0x10, 3, 0, 0),
            byteArrayOf(0, 2, 1, 0), byteArrayOf(0, 3, 0, 1))) {
            device.loadInitialState(AppleState(), AppleSettings(), metadata)
            device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x24) + unsupported, socket)
            val received = device.state.value.controlStates[id]
            assertFalse(device.setCallControls(true, metadata, socket))
            assertSame("Unsupported complete control value must remain untouched", received, device.state.value.controlStates[id])
            assertTrue(device.state.value.requestedControls.isEmpty())
        }
        assertEquals(0, output.size())
    }

    private fun rejectQueuedSetting(callControls: Boolean, invalidate: (AppleDevice, AppleMetadata, BluetoothSocket) -> Unit) =
        withTransport { device, socket ->
            val metadata = prepareGuardedSetting(device)
            val result = AtomicReference<Boolean?>()
            val writer = thread(start = false) { result.set(writeGuardedSetting(device, socket, metadata, callControls)) }
            try {
                synchronized(socket) {
                    writer.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals("The admitted setting must wait for the socket", Thread.State.BLOCKED, writer.state)
                    invalidate(device, metadata, socket)
                }
                writer.join(2_000)
                assertFalse(writer.isAlive)
                assertEquals(false, result.get())
                assertEquals(0, Shadow.extract<ProtocolReportSocketShadow>(socket).output.bytes.size())
                assertTrue(device.state.value.requestedControls.isEmpty())
                assertTrue(device.settings.value.savedUnconfirmedChoices.isEmpty())
            } finally { writer.join(2_000) }
        }

    private fun prepareGuardedSetting(device: AppleDevice) =
        AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348").also { metadata ->
            val call = ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG
            device.loadInitialState(AppleState(controlStates = mapOf(call to byteArrayOf(0, 3),
                ControlCommandIdentifier.ALLOW_OFF_OPTION to byteArrayOf(1)), liveControlReports = setOf(call)),
                AppleSettings(), metadata)
        }

    private fun writeGuardedSetting(device: AppleDevice, socket: BluetoothSocket, metadata: AppleMetadata, callControls: Boolean) =
        if (callControls) device.setCallControls(true, metadata, socket) else device.setListeningModeCycle(11, metadata, socket)

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
class ProtocolReportSocketShadow : ClosingBluetoothSocketShadow() {
    val output = ProtocolReportOutput()
    var onClose: (() -> Unit)? = null
    init { setOutputStream(output) }
    protected override fun onClosing() { onClose?.invoke() }
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
