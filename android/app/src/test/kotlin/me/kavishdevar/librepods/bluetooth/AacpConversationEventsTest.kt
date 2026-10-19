package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [ProtocolReportSocketShadow::class])
class AacpConversationEventsTest {
    @Test fun aBlockedSubscriberReceivesEveryBurstAndRepeatedStageInOrder() = withTransport { device, socket ->
        val subscriber = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<AppleEvent.ConversationAwareness>()
        subscriber.launch(start = CoroutineStart.UNDISPATCHED) {
            device.events.collect { event ->
                if (event is AppleEvent.ConversationAwareness) {
                    events += event
                    if (events.size == 1) resume.await()
                }
            }
        }
        try {
            val stages = listOf(1, 4, 1, 1, 2, 3, 9)
            stages.forEach { device.aacp.processPacket(report(it), socket) }
            assertEquals(listOf(1), events.map { it.stage })
            assertEquals(9, device.state.value.conversationAwarenessState)
            resume.complete(Unit)
            assertEquals(stages, events.map { it.stage })
            assertTrue(events.all { it.sourceSocket === socket })
        } finally { subscriber.cancel() }
    }

    @Test fun retiringATransportCancelsItsQueuedStageAndRejectsItsLaterReports() = withTransport { device, original ->
        val subscriber = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<AppleEvent.ConversationAwareness>()
        val replacement = socket()
        subscriber.launch(start = CoroutineStart.UNDISPATCHED) {
            device.events.collect { event ->
                if (event is AppleEvent.ConversationAwareness) {
                    events += event
                    if (events.size == 1) resume.await()
                }
            }
        }
        try {
            device.aacp.processPacket(report(1), original)
            device.aacp.processPacket(report(4), original)
            assertTrue(device.aacp.disconnect(original))
            bind(device.aacp, replacement)
            val beforeStale = device.state.value
            device.aacp.processPacket(report(1), original)
            assertSame(beforeStale, device.state.value)
            resume.complete(Unit)
            assertEquals(listOf(1), events.map { it.stage })
            device.aacp.processPacket(report(1), replacement)
            assertEquals(listOf(1, 1), events.map { it.stage })
            assertSame(original, events[0].sourceSocket)
            assertSame(replacement, events[1].sourceSocket)
        } finally { subscriber.cancel(); replacement.close() }
    }

    @Test fun malformedAndInactiveTransportReportsCannotPublishAnEvent() = withTransport { device, socket ->
        val subscriber = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = mutableListOf<AppleEvent>()
        subscriber.launch(start = CoroutineStart.UNDISPATCHED) { device.events.collect { events += it } }
        try {
            val beforeMalformed = device.state.value
            for (size in 0 until 10) device.aacp.processPacket(report(1).copyOf(size), socket)
            device.aacp.processPacket(report(1).also { it[0] = 1 }, socket)
            device.aacp.processPacket(report(1).also { it[5] = 1 }, socket)
            assertSame(beforeMalformed, device.state.value)
            assertTrue(events.isEmpty())
            assertTrue(device.aacp.disconnect(socket))
            val beforeInactive = device.state.value
            device.aacp.processPacket(report(1), socket)
            device.aacp.processPacket(report(1), null)
            assertSame(beforeInactive, device.state.value)
            assertTrue(events.isEmpty())
        } finally { subscriber.cancel() }
    }

    @Test fun aNewSubscriberNeverReplaysAnEarlierSpeechStage() = withTransport { device, socket ->
        device.aacp.processPacket(report(1), socket)
        val subscriber = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stages = mutableListOf<Int>()
        subscriber.launch(start = CoroutineStart.UNDISPATCHED) {
            device.events.collect { if (it is AppleEvent.ConversationAwareness) stages += it.stage }
        }
        try {
            assertTrue(stages.isEmpty())
            device.aacp.processPacket(report(4), socket)
            assertEquals(listOf(4), stages)
        } finally { subscriber.cancel() }
    }

    @Test fun everyActiveStageAdvancesTheSessionSequenceAndTerminalStagesRetainIt() = withTransport { device, socket ->
        assertEquals(0L, device.state.value.conversationAwarenessSequence)
        for ((index, stage) in listOf(1, 1, 2, 3).withIndex()) {
            device.aacp.processPacket(report(stage), socket)
            assertEquals(stage, device.state.value.conversationAwarenessState)
            assertEquals((index + 1).toLong(), device.state.value.conversationAwarenessSequence)
        }
        for (stage in listOf(0, 4, 6, 7, 8, 9)) {
            device.aacp.processPacket(report(stage), socket)
            assertEquals(stage, device.state.value.conversationAwarenessState)
            assertEquals("A coalesced terminal must retain evidence of the active speech", 4L,
                device.state.value.conversationAwarenessSequence)
        }
    }

    @Test fun successfulModeWriteMarkersSurviveAcknowledgementAndUnrelatedStateWithFreshIdentityPerWrite() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.LISTENING_MODE
        device.aacp.processPacket(modeReport(2), socket)
        val value = byteArrayOf(3, 0, 0, 0)
        assertTrue(device.aacp.sendControlCommand(id.value, value))
        val marker = requireNotNull(device.state.value.lastListeningModeRequest)
        assertArrayEquals(byteArrayOf(3), marker)
        assertNotSame("The session marker cannot alias a caller-owned array", value, marker)
        value[0] = 4
        assertArrayEquals(byteArrayOf(3), marker)
        assertArrayEquals(byteArrayOf(3), device.state.value.requestedControls[id])
        device.aacp.processPacket(modeReport(3), socket)
        assertFalse(id in device.state.value.requestedControls)
        assertSame("Acknowledgement cannot erase a request before a conflated observer sees it", marker,
            device.state.value.lastListeningModeRequest)
        device.aacp.processPacket(report(1), socket)
        assertTrue(device.aacp.sendControlCommand(ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG.value, true))
        assertSame("Unrelated state updates must preserve request identity", marker, device.state.value.lastListeningModeRequest)
        assertTrue(device.aacp.sendControlCommand(id.value, byteArrayOf(3)))
        val repeated = requireNotNull(device.state.value.lastListeningModeRequest)
        assertArrayEquals(byteArrayOf(3), repeated)
        assertNotSame("A new write of the same mode needs its own consumable marker", marker, repeated)
        assertFalse("A same-mode write need not invent a pending report", id in device.state.value.requestedControls)
        device.aacp.processPacket(modeReport(3), socket)
        assertSame(repeated, device.state.value.lastListeningModeRequest)
    }

    @Test fun anUnacknowledgedModeWriteExpiresItsMarkerWithThePendingRequest() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.LISTENING_MODE
        assertTrue(device.aacp.sendControlCommand(id.value, byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(3), device.state.value.requestedControls[id])
        assertArrayEquals(byteArrayOf(3), device.state.value.lastListeningModeRequest)
        @Suppress("UNCHECKED_CAST")
        val timeouts = AACPManager::class.java.getDeclaredField("controlTimeouts").apply { isAccessible = true }
            .get(device.aacp) as Map<ControlCommandIdentifier, Job>
        val timeout = requireNotNull(timeouts[id])
        runBlocking { withTimeout(12_000) { timeout.join() } }
        assertFalse("An unconfirmed command must leave the pending controls after its timeout", id in device.state.value.requestedControls)
        assertNull("An expired command cannot later classify automatic Transparency as manual", device.state.value.lastListeningModeRequest)
        device.aacp.processPacket(report(1), socket)
        device.aacp.processPacket(modeReport(3), socket)
        assertEquals(1L, device.state.value.conversationAwarenessSequence)
        assertArrayEquals(byteArrayOf(3), device.state.value.controlStates[id])
        assertFalse(id in device.state.value.requestedControls)
        assertNull("Later conversation activity cannot revive an expired request marker", device.state.value.lastListeningModeRequest)
    }

    @Test fun failedModeWritesCannotCreateOrReplaceASuccessfulRequestMarker() = withTransport { device, socket ->
        val id = ControlCommandIdentifier.LISTENING_MODE
        val output = Shadow.extract<ProtocolReportSocketShadow>(socket).output
        val initial = device.state.value
        output.failWrites = true
        assertFalse(device.aacp.sendControlCommand(id.value, byteArrayOf(3)))
        assertSame(initial, device.state.value)
        assertNull(device.state.value.lastListeningModeRequest)
        output.failWrites = false
        assertTrue(device.aacp.sendControlCommand(id.value, byteArrayOf(2)))
        val successful = device.state.value
        output.failWrites = true
        assertFalse(device.aacp.sendControlCommand(id.value, byteArrayOf(4)))
        assertSame(successful, device.state.value)
        assertArrayEquals(byteArrayOf(2), device.state.value.lastListeningModeRequest)
    }

    @Test fun retirementDuringAWriteCannotPublishOrClearReplacementSessionMetadata() = withTransport { device, original ->
        val id = ControlCommandIdentifier.LISTENING_MODE
        val replacement = socket()
        device.aacp.processPacket(report(1), original)
        assertTrue(device.aacp.sendControlCommand(id.value, byteArrayOf(2)))
        val output = Shadow.extract<ProtocolReportSocketShadow>(original).output
        try {
            output.beforeWrite = {
                assertTrue(device.aacp.disconnect(original))
                assertEquals(0L, device.state.value.conversationAwarenessSequence)
                assertNull(device.state.value.lastListeningModeRequest)
                bind(device.aacp, replacement)
                device.aacp.processPacket(report(2), replacement)
                assertTrue(device.aacp.sendControlCommand(id.value, byteArrayOf(4)))
            }
            assertFalse("A write retired before publication cannot publish a request", device.aacp.sendControlCommand(id.value, byteArrayOf(3)))
            val current = device.state.value
            assertEquals(1L, current.conversationAwarenessSequence)
            assertArrayEquals(byteArrayOf(4), current.lastListeningModeRequest)
            device.aacp.processPacket(report(3), original)
            device.aacp.processPacket(modeReport(3), original)
            assertSame("Retired reports cannot mutate replacement metadata", current, device.state.value)
            assertFalse(device.aacp.disconnect(original))
            assertSame("An old retirement cannot clear replacement metadata", current, device.state.value)
            assertTrue(device.aacp.disconnect(replacement))
            assertEquals(0L, device.state.value.conversationAwarenessSequence)
            assertNull(device.state.value.lastListeningModeRequest)
            val disconnected = device.state.value
            assertFalse(device.aacp.sendControlCommand(id.value, byteArrayOf(3)))
            assertSame(disconnected, device.state.value)
        } finally { replacement.close() }
    }

    private fun report(stage: Int) = byteArrayOf(4, 0, 4, 0, 0x4b, 0, 0, 0, 0, stage.toByte())
    private fun modeReport(mode: Int) = byteArrayOf(4, 0, 4, 0, 9, 0,
        ControlCommandIdentifier.LISTENING_MODE.value, mode.toByte(), 0, 0, 0)

    private fun socket(): BluetoothSocket = Shadow.newInstanceOf(BluetoothSocket::class.java).also { it.connect() }

    private fun bind(manager: AACPManager, socket: BluetoothSocket) {
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
        AACPManager::class.java.getDeclaredField("connectionScope").apply { isAccessible = true }
            .set(manager, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    private fun withTransport(test: (AppleDevice, BluetoothSocket) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        val socket = socket()
        bind(device.aacp, socket)
        try { test(device, socket) } finally { device.aacp.disconnect(); socket.close() }
    }
}
