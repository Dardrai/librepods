package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.lang.reflect.InvocationTargetException
import java.io.IOException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [HearingModeSocketShadow::class])
class AacpInitialAttReadTest {
    @Test fun successfulWritesWaitForAcceptedCurrentServiceAndDuplicateRejectionCannotRevokeIt() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val scope = CoroutineScope(coroutineContext + Job())
            val worker = async { startup(device.aacp, socket, scope, peerReady = false) }
            try {
                delay(1_000)
                assertTrue(script.reads.isEmpty())
                assertNull(settingsSocket(device.aacp))
                for (response in listOf(readyResponse().also { it[4] = 0x85.toByte() }, readyResponse().copyOf(17),
                    readyResponse().also { it[2] = 0 }, byteArrayOf(4, 0, 4, 0, 2, 0, 1))) device.aacp.processPacket(response, socket)
                delay(50)
                assertTrue(script.reads.isEmpty())
                assertNull(settingsSocket(device.aacp))
                device.aacp.processPacket(readyResponse(), socket)
                withTimeout(1_000) { while (script.reads.size != 2) delay(5) }
                assertSame(socket, settingsSocket(device.aacp))
                device.aacp.processPacket(readyResponse().also { it[4] = 0x85.toByte() }, socket)
                assertSame(socket, settingsSocket(device.aacp))
                assertEquals(0, Shadow.extract<HearingModeSocketShadow>(socket).closes)
            } finally { scope.cancel(); worker.cancelAndJoin() }
        }
    }

    @Test fun validCapabilitiesOrLegacySuccessAllowInitialReadsWithoutAssumingProThreeReports() = runBlocking<Unit> {
        for (model in listOf(AirPodsModel.AIRPODS_PRO_3, AirPodsModel.AIRPODS_PRO_1)) fixture(model) { device, script, socket ->
            val scope = CoroutineScope(coroutineContext + Job())
            val worker = async { startup(device.aacp, socket, scope, peerReady = false) }
            try {
                val response = if (model == AirPodsModel.AIRPODS_PRO_1) readyResponse().also { it[8] = 2 }
                    else byteArrayOf(4, 0, 4, 0, 2, 0, 1, 0x11, 1)
                device.aacp.processPacket(response, socket)
                assertNull("A reply alone cannot replace completed initialization", settingsSocket(device.aacp))
                val count = if (model == AirPodsModel.AIRPODS_PRO_1) 1 else 2
                withTimeout(2_000) { while (script.reads.size != count) delay(5) }
                assertSame(socket, settingsSocket(device.aacp))
                assertEquals(ATTHandle.TRANSPARENCY, script.reads.last())
                assertEquals(model != AirPodsModel.AIRPODS_PRO_1, ATTHandle.HEARING_AID in script.reads)
            } finally { scope.cancel(); worker.cancelAndJoin() }
        }
    }

    @Test fun oldSocketReadinessCannotAuthorizeTheReplacementSession() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val scope = CoroutineScope(coroutineContext + Job())
            val worker = async { startup(device.aacp, socket, scope, peerReady = false) }
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            try {
                delay(1_000)
                AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, replacement)
                state(device).value = device.state.value.copy(controlSession = 2)
                device.aacp.processPacket(readyResponse(), socket)
                device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 2, 0, 0), socket)
                delay(100)
                assertTrue(script.reads.isEmpty())
                assertNull(settingsSocket(device.aacp))
                val next = async { startup(device.aacp, replacement, scope, peerReady = false) }
                try {
                    device.aacp.processPacket(readyResponse(), replacement)
                    withTimeout(2_000) { while (script.reads.size != 2) delay(5) }
                    assertSame(replacement, settingsSocket(device.aacp))
                } finally { next.cancelAndJoin() }
            } finally { scope.cancel(); worker.cancelAndJoin(); replacement.close() }
        }
    }

    @Test fun missingPeerReadinessTimesOutAndLateReplyAutomaticallyLoadsOnceAfterBusyAudioEnds() = runBlocking<Unit> {
        for (response in listOf(readyResponse(), byteArrayOf(4, 0, 4, 0, 2, 0, 1, 0x11, 1))) fixture { device, script, socket ->
            val scope = CoroutineScope(coroutineContext + Job())
            val worker = async { startup(device.aacp, socket, scope, peerReady = false) }
            try {
                withTimeout(14_000) { while (device.state.value.hearingAidReadStatus != SettingsReadStatus.UNAVAILABLE) delay(20) }
                assertTrue(script.reads.isEmpty())
                assertNull(settingsSocket(device.aacp))
                device.updateMicrophoneState { it.copy(isActive = true) }
                repeat(3) { device.aacp.processPacket(response, socket) }
                assertSame(socket, settingsSocket(device.aacp))
                delay(50)
                assertTrue("Late initialization must preserve the busy-audio admission policy", script.reads.isEmpty())
                device.updateMicrophoneState { it.copy(isActive = false) }
                withTimeout(1_000) { while (script.reads.size != 2) delay(5) }
                assertArrayEquals(profile(), device.state.value.hearingAidData)
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
                repeat(3) { device.aacp.processPacket(response, socket) }
                delay(50)
                assertEquals(2, script.reads.size)
                assertEquals(0, Shadow.extract<HearingModeSocketShadow>(socket).closes)
            } finally { scope.cancel(); worker.cancelAndJoin(); device.updateMicrophoneState { it.copy(isActive = false) } }
        }
    }

    @Test fun cancelledReadinessWaitNeverStartsAnAutomaticReadAfterALateReply() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val scope = CoroutineScope(coroutineContext + Job())
            val worker = async { startup(device.aacp, socket, scope, peerReady = false) }
            try {
                withTimeout(14_000) { while (device.state.value.hearingAidReadStatus != SettingsReadStatus.UNAVAILABLE) delay(20) }
                scope.cancel()
                worker.cancelAndJoin()
                device.aacp.processPacket(readyResponse(), socket)
                device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 2, 0, 0), socket)
                delay(100)
                assertTrue(script.reads.isEmpty())
            } finally { scope.cancel(); worker.cancelAndJoin() }
        }
    }

    @Test fun firstCompleteStartupStartsAttBeforeTheRepeatAndOnlyOnce() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val output = Shadow.extract<HearingModeSocketShadow>(socket).output
            var passes = 0
            output.onPacket = { packet ->
                if (passes == 0) assertTrue("ATT must wait for the final required write", script.reads.isEmpty())
                if (packet[4] == 0x30.toByte()) passes++
            }
            val scope = this
            val worker = async { startup(device.aacp, socket, scope) }
            try {
                withTimeout(2_000) { while (script.reads.size != 2) delay(5) }
                assertEquals(1, passes)
                assertFalse(worker.isCompleted)
                withTimeout(6_000) { worker.await() }
                assertEquals(2, passes)
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun initialAttWaitsForEveryRequiredStartupPacketWithoutBlockingTheRepeat() = runBlocking<Unit> {
        fixture(AirPodsModel.UNKNOWN) { device, script, socket ->
            val output = Shadow.extract<HearingModeSocketShadow>(socket).output
            val scope = this
            val worker = async { startup(device.aacp, socket, scope) }
            try {
                delay(100)
                assertEquals(listOf(1), output.packets.map { it[4].toInt() and 255 })
                assertTrue(script.reads.isEmpty())
                withTimeout(6_000) { worker.await() }
                assertEquals("A deferred ATT reader must not block the three-second repeat", 2,
                    output.packets.count { it[0] == 0.toByte() && it[4] == 1.toByte() })
                assertTrue(script.reads.isEmpty())
                val required = listOf(1, 0x4d, 0x0f, 1, 9, 0x29, 0x30)
                assertEquals(required, output.packets.take(required.size).map { it[4].toInt() and 255 })
                metadata(device).value = device.metadata.value.copy(model = AirPodsModel.AIRPODS_PRO_3)
                withTimeout(2_000) { while (script.reads.size != 2) delay(5) }
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
            } finally {
                metadata(device).value = device.metadata.value.copy(model = AirPodsModel.AIRPODS_PRO_3)
                worker.cancelAndJoin()
            }
        }
    }

    @Test fun failedFirstStartupCannotStartAttUntilTheCompleteRepeatSucceeds() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val output = Shadow.extract<HearingModeSocketShadow>(socket).output
            var failed = false
            output.onPacket = { packet ->
                if (!failed && packet[4] == 0x4d.toByte()) {
                    failed = true
                    throw IOException("Source features not sent")
                }
                assertTrue("No ATT read before the first successful complete pass", script.reads.isEmpty())
            }
            val scope = this
            val worker = async { startup(device.aacp, socket, scope) }
            try {
                delay(350)
                assertTrue(failed)
                assertTrue(script.reads.isEmpty())
                withTimeout(6_000) { worker.await() }
                withTimeout(2_000) { while (script.reads.size != 2) delay(5) }
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
                assertEquals(1, output.packets.count { it[4] == 0x30.toByte() })
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun failedFinalPacketNeverStartsAnAttReader() = runBlocking<Unit> {
        fixture { device, script, socket ->
            Shadow.extract<HearingModeSocketShadow>(socket).output.onPacket = { packet ->
                if (packet[4] == 0x30.toByte()) throw IOException("Key request not sent")
            }
            withTimeout(6_000) { startup(device.aacp, socket, this) }
            delay(20)
            assertTrue(script.reads.isEmpty())
            assertEquals(SettingsReadStatus.WAITING, device.state.value.hearingAidReadStatus)
        }
    }

    @Test fun cancellationOrOwnerReplacementAtTheLastInitPacketCannotStartAtt() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) fixture { device, script, socket ->
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            var worker: Job? = null
            Shadow.extract<HearingModeSocketShadow>(socket).output.onPacket = { packet ->
                if (packet[4] == 0x30.toByte()) {
                    if (cancel) worker!!.cancel()
                    else AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, replacement)
                }
            }
            val scope = this
            try {
                worker = launch { startup(device.aacp, socket, scope) }
                withTimeout(2_000) { worker.join() }
                delay(20)
                assertTrue(script.reads.isEmpty())
            } finally { worker?.cancelAndJoin(); replacement.close() }
        }
    }

    @Test fun firstReadPublishesCompleteProfileAndSourceOnceWithoutWritesOrReconnecting() = runBlocking<Unit> {
        fixture { device, script, socket ->
            initialize(device, socket)
            assertArrayEquals(profile(), device.state.value.hearingAidData)
            assertArrayEquals(ByteArray(104), device.state.value.transparencyData)
            assertNotNull(device.state.value.accommodationsAudiogramSource)
            assertEquals(SettingsReadStatus.AVAILABLE, device.state.value.hearingAidReadStatus)
            assertEquals(SettingsReadStatus.AVAILABLE, device.state.value.transparencyReadStatus)
            assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
            initialize(device, socket)
            assertEquals(2, script.reads.size)
            assertSame(socket, device.aacp.captureAudioSocket())
            assertEquals(0, Shadow.extract<HearingModeSocketShadow>(socket).closes)
            assertTrue(Shadow.extract<HearingModeSocketShadow>(socket).output.packets.isEmpty())
            assertFalse(mockingDetails(script.manager).invocations.any { it.method.name.startsWith("writeCharacteristic") })
        }
    }

    @Test fun transientInitialReadRetriesAfterCompleteStartupWithoutReopeningAacp() = runBlocking<Unit> {
        fixture { device, script, socket ->
            var failed = false
            script.read = { handle ->
                if (handle == ATTHandle.HEARING_AID && !failed) {
                    failed = true
                    script.retryable = true
                    null
                } else {
                    script.retryable = false
                    if (handle == ATTHandle.HEARING_AID) profile() else ByteArray(104)
                }
            }
            val scope = this
            val worker = async { startup(device.aacp, socket, scope) }
            try {
                withTimeout(2_000) { while (device.state.value.transparencyReadStatus != SettingsReadStatus.AVAILABLE) delay(5) }
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
                assertArrayEquals(profile(), device.state.value.hearingAidData)
                assertSame(socket, device.aacp.captureAudioSocket())
                assertEquals(0, Shadow.extract<HearingModeSocketShadow>(socket).closes)
                assertEquals(1, Shadow.extract<HearingModeSocketShadow>(socket).output.packets.count { it[4] == 0x30.toByte() })
                assertFalse(mockingDetails(script.manager).invocations.any { it.method.name.startsWith("writeCharacteristic") })
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun exhaustedTransientRetryStopsAndDoesNotConsumeAnotherSettingsWorker() = runBlocking<Unit> {
        fixture { device, script, socket ->
            script.read = { handle ->
                script.retryable = handle == ATTHandle.HEARING_AID && script.reads.count { it == handle } == 1
                null
            }
            withTimeout(2_000) { initialize(device, socket) }
            initialize(device, socket)
            assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
            assertEquals(SettingsReadStatus.UNAVAILABLE, device.state.value.hearingAidReadStatus)
            assertEquals(SettingsReadStatus.UNAVAILABLE, device.state.value.transparencyReadStatus)
            assertSame(socket, device.aacp.captureAudioSocket())
        }
    }

    @Test fun retryBackoffHonorsCancellationBusyAudioAndReplacementGeneration() = runBlocking<Unit> {
        for (replace in listOf(false, true)) fixture { device, script, socket ->
            script.read = { script.retryable = true; null }
            val first = async { initialize(device, socket) }
            withTimeout(1_000) { while (script.reads.isEmpty()) delay(5) }
            if (replace) state(device).value = device.state.value.copy(controlSession = 2)
            else first.cancelAndJoin()
            withTimeout(1_000) { first.join() }
            assertEquals(listOf(ATTHandle.HEARING_AID), script.reads)
            device.updateMicrophoneState { it.copy(isActive = true) }
            script.read = { script.retryable = false; if (it == ATTHandle.HEARING_AID) profile() else ByteArray(104) }
            val resumed = async { initialize(device, socket) }
            try {
                delay(50)
                assertEquals(1, script.reads.size)
                device.updateMicrophoneState { it.copy(isActive = false) }
                withTimeout(2_000) { resumed.await() }
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
                assertArrayEquals(profile(), device.state.value.hearingAidData)
            } finally { resumed.cancelAndJoin(); device.updateMicrophoneState { it.copy(isActive = false) } }
        }
    }

    @Test fun microphoneCallsRecordingAndPendingAttDeferWithoutConsumingTheFirstAttempt() = runBlocking<Unit> {
        for (busy in listOf("microphone", "call", "voip", "recording", "att")) fixture { device, script, socket ->
            val audio = device.context.getSystemService(AudioManager::class.java)
            fun setBusy(on: Boolean) {
                when (busy) {
                    "microphone" -> device.updateMicrophoneState { it.copy(isActive = on) }
                    "recording" -> device.updateRecordingState { it.copy(isRecording = on) }
                    "att" -> AppleDevice::class.java.getDeclaredField("attOperationPending").apply { isAccessible = true }.setBoolean(device, on)
                    else -> audio.mode = if (!on) AudioManager.MODE_NORMAL else if (busy == "call") AudioManager.MODE_IN_CALL else AudioManager.MODE_IN_COMMUNICATION
                }
            }
            try {
                setBusy(true)
                val initial = async { initialize(device, socket) }
                delay(30)
                assertTrue(script.reads.isEmpty())
                assertEquals(SettingsReadStatus.WAITING, device.state.value.hearingAidReadStatus)
                assertFalse(initial.isCompleted)
                setBusy(false)
                withTimeout(2_000) { initial.await() }
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
                assertArrayEquals(profile(), device.state.value.hearingAidData)
            } finally { setBusy(false) }
        }
    }

    @Test fun callStartingAtAttAdmissionDoesNotConsumeAnUnstartedRead() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val audio = device.context.getSystemService(AudioManager::class.java)
            var first = true
            script.beforeAdmission = {
                if (first) { first = false; audio.mode = AudioManager.MODE_IN_CALL }
            }
            try {
                val initial = async { initialize(device, socket) }
                delay(50)
                assertTrue(script.reads.isEmpty())
                assertEquals(SettingsReadStatus.WAITING, device.state.value.hearingAidReadStatus)
                audio.mode = AudioManager.MODE_NORMAL
                withTimeout(2_000) { initial.await() }
                assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
                assertEquals(SettingsReadStatus.AVAILABLE, device.state.value.hearingAidReadStatus)
            } finally { audio.mode = AudioManager.MODE_NORMAL }
        }
    }

    @Test fun callAfterAdmittedReadFinishesAsUnavailableWithoutRetryAndDisconnectResetsLoading() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val audio = device.context.getSystemService(AudioManager::class.java)
            script.read = {
                assertEquals(SettingsReadStatus.READING, device.state.value.hearingAidReadStatus)
                audio.mode = AudioManager.MODE_IN_CALL
                null
            }
            val initial = async { initialize(device, socket) }
            try {
                withTimeout(1_000) {
                    while (device.state.value.hearingAidReadStatus != SettingsReadStatus.UNAVAILABLE) delay(5)
                }
                audio.mode = AudioManager.MODE_NORMAL
                script.read = { ByteArray(104) }
                withTimeout(2_000) { initial.await() }
                assertEquals(1, script.reads.count { it == ATTHandle.HEARING_AID })
                assertEquals(SettingsReadStatus.UNAVAILABLE, device.state.value.hearingAidReadStatus)
                device.aacp.disconnect()
                assertEquals(SettingsReadStatus.WAITING, device.state.value.hearingAidReadStatus)
                assertEquals(SettingsReadStatus.WAITING, device.state.value.transparencyReadStatus)
            } finally { audio.mode = AudioManager.MODE_NORMAL; initial.cancelAndJoin() }
        }
    }

    @Test fun duplicateWorkersCoalesceAndCancellationBeforeIoCanResumeTheSameSession() = runBlocking<Unit> {
        fixture { device, script, socket ->
            device.updateMicrophoneState { it.copy(isActive = true) }
            val first = async { initialize(device, socket) }
            delay(30)
            withTimeout(1_000) { initialize(device, socket) }
            assertTrue(script.reads.isEmpty())
            first.cancelAndJoin()
            device.updateMicrophoneState { it.copy(isActive = false) }
            withTimeout(2_000) { initialize(device, socket) }
            assertEquals(2, script.reads.size)
        }
    }

    @Test fun malformedOrFailedActualReadsAreNotRetriedInTheSameSession() = runBlocking<Unit> {
        for (failure in listOf<ByteArray?>(null, ByteArray(103), profile().also { it[1] = 1 })) fixture { device, script, socket ->
            script.read = { handle -> if (handle == ATTHandle.HEARING_AID) failure else ByteArray(104) }
            initialize(device, socket)
            initialize(device, socket)
            assertTrue(device.state.value.hearingAidData.isEmpty())
            assertNull(device.state.value.accommodationsAudiogramSource)
            assertEquals(SettingsReadStatus.UNAVAILABLE, device.state.value.hearingAidReadStatus)
            assertEquals(1, script.reads.count { it == ATTHandle.HEARING_AID })
            assertEquals(1, script.reads.count { it == ATTHandle.TRANSPARENCY })
        }
    }

    @Test fun replacedSessionCannotPublishItsOldReadAndTheNewSessionInitializesSeparately() = runBlocking<Unit> {
        fixture { device, script, socket ->
            val replacement = profile().also { it[4] = 0x40 }
            val session = device.state.value.controlSession
            script.read = { handle ->
                if (handle == ATTHandle.HEARING_AID) {
                    state(device).value = device.state.value.copy(controlSession = session + 1, hearingAidData = replacement,
                        hearingAidReadStatus = SettingsReadStatus.AVAILABLE)
                    profile()
                } else ByteArray(104)
            }
            initialize(device, socket)
            assertArrayEquals(replacement, device.state.value.hearingAidData)
            assertEquals(SettingsReadStatus.AVAILABLE, device.state.value.hearingAidReadStatus)
            assertEquals(listOf(ATTHandle.HEARING_AID), script.reads)
            script.read = { handle -> if (handle == ATTHandle.HEARING_AID) replacement else ByteArray(104) }
            initialize(device, socket)
            assertArrayEquals(replacement, device.state.value.hearingAidData)
            assertEquals(listOf(ATTHandle.HEARING_AID, ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY), script.reads)
        }
    }

    @Test fun unknownModelWaitsAndOlderBudsNeverReceiveUnsupportedProfileReads() = runBlocking<Unit> {
        fixture(AirPodsModel.UNKNOWN) { device, script, socket ->
            val initial = async { initialize(device, socket) }
            delay(30)
            assertTrue(script.reads.isEmpty())
            metadata(device).value = device.metadata.value.copy(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063")
            withTimeout(2_000) { initial.await() }
            assertEquals(2, script.reads.size)
        }
        for (model in listOf(AirPodsModel.AIRPODS_1, AirPodsModel.AIRPODS_PRO_1)) fixture(model) { device, script, socket ->
            initialize(device, socket)
            assertEquals(if (model == AirPodsModel.AIRPODS_PRO_1) listOf(ATTHandle.TRANSPARENCY) else emptyList<ATTHandle>(), script.reads)
            assertEquals(SettingsReadStatus.UNAVAILABLE, device.state.value.hearingAidReadStatus)
            assertEquals(if (model == AirPodsModel.AIRPODS_PRO_1) SettingsReadStatus.AVAILABLE else SettingsReadStatus.UNAVAILABLE,
                device.state.value.transparencyReadStatus)
        }
    }

    private class Script {
        lateinit var manager: ATTManager
        val reads = mutableListOf<ATTHandle>()
        var beforeAdmission: () -> Unit = {}
        var retryable = false
        var read: (ATTHandle) -> ByteArray? = { if (it == ATTHandle.HEARING_AID) profile() else ByteArray(104) }
        fun answer(call: org.mockito.invocation.InvocationOnMock): Any? = if (call.method.name.startsWith("readCharacteristic")) {
            val handle = call.arguments[0] as ATTHandle
            beforeAdmission()
            if (!(call.arguments[1] as () -> Boolean).invoke()) null else {
                (call.arguments[2] as () -> Unit).invoke()
                reads += handle
                read(handle)
            }
        } else if (call.method.name == "canRetryRead") retryable else RETURNS_DEFAULTS.answer(call)
    }

    private suspend fun fixture(model: AirPodsModel = AirPodsModel.AIRPODS_PRO_3,
        block: suspend (AppleDevice, Script, BluetoothSocket) -> Unit) {
        val script = Script()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer { script.answer(it) }).use { built ->
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
            device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(model = model, modelNumber = "A3063", version1 = "9A348"))
            val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
            state(device).value = device.state.value.copy(controlSession = 1)
            script.manager = built.constructed().single()
            try { block(device, script, socket) } finally { device.aacp.disconnect(); socket.close() }
        }
    }

    private suspend fun initialize(device: AppleDevice, socket: BluetoothSocket): Unit = suspendCoroutine { continuation ->
        try {
            val method = AppleDevice::class.java.declaredMethods.single { it.name.startsWith("readInitialATTCharacteristics") && it.parameterCount == 2 &&
                it.parameterTypes.last().name == "kotlin.coroutines.Continuation" }
            method.isAccessible = true
            if (method.invoke(device, socket, continuation) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } catch (error: InvocationTargetException) { continuation.resumeWithException(error.targetException) }
    }

    private suspend fun startup(manager: AACPManager, socket: BluetoothSocket, scope: CoroutineScope,
        peerReady: Boolean = true): Unit = suspendCoroutine { continuation ->
        try {
            if (peerReady) manager.processPacket(readyResponse(), socket)
            val method = AACPManager::class.java.declaredMethods.single { it.name == "initializeConnection" && it.parameterCount == 3 }
            method.isAccessible = true
            if (method.invoke(manager, socket, scope, continuation) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } catch (error: InvocationTargetException) { continuation.resumeWithException(error.targetException) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(device: AppleDevice) = AppleDevice::class.java.getDeclaredField("_state").apply { isAccessible = true }
        .get(device) as MutableStateFlow<AppleState>
    @Suppress("UNCHECKED_CAST")
    private fun metadata(device: AppleDevice) = AppleDevice::class.java.getDeclaredField("_metadata").apply { isAccessible = true }
        .get(device) as MutableStateFlow<AppleMetadata>

    private fun settingsSocket(manager: AACPManager): BluetoothSocket? = AACPManager::class.java.declaredMethods
        .single { it.name.startsWith("captureSettingsSocket") && it.parameterCount == 0 }.invoke(manager) as BluetoothSocket?

    companion object {
        private fun profile() = ByteArray(104).also { byteArrayOf(2, 2, 0x60, 0).copyInto(it) }
        private fun readyResponse() = byteArrayOf(1, 0, 4, 0, 0, 0, 1, 0, 3, 0) + ByteArray(8)
    }
}
