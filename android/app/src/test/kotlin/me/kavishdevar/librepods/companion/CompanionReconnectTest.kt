package me.kavishdevar.librepods.companion

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CompanionReconnectTest {
    @get:Rule val temporary = TemporaryFolder()
    private val peerAddress = "11:22:33:44:55:66"
    private val device = "AA:BB:CC:DD:EE:FF"
    private val key = ByteArray(32)

    @Test fun transportBackoffIsCappedAndSuccessfulAuthenticationResetsIt() = runBlocking<Unit> {
        val wires = List(6) { Wire(connectFailure = true) } + Wire()
        val fixture = Fixture(wires)
        try {
            fixture.controller.reconnect()
            for ((index, expected) in listOf(1000L, 2000L, 4000L, 8000L, 15000L, 30000L).withIndex()) {
                val wait = fixture.nextWait()
                assertEquals(expected, wait.first)
                assertEquals(index + 1, fixture.attempts.get())
                verify(wires[index].socket, atLeastOnce()).close()
                wait.second.complete(Unit)
            }
            fixture.connected()
            wires.last().eof()
            assertEquals(1000L, fixture.nextWait().first)
            assertEquals(0, fixture.audioOperations.get())
        } finally { fixture.close() }
    }

    @Test fun reconnectRetiresTransactionsAndStartsANewMediaBaselineWithoutAudioReplay() = runBlocking<Unit> {
        val first = Wire()
        val second = Wire()
        val fixture = Fixture(listOf(first, second))
        try {
            fixture.controller.reconnect(); fixture.connected()
            first.send("media_query") { put("probe", "0123456789abcdef") }
            first.awaitFrame("media_state")
            fixture.playing = true
            first.send("media_query") { put("probe", "1123456789abcdef") }
            first.awaitFrame("media_state") { it.optString("probe") == "1123456789abcdef" }
            assertEquals("0000000000000001", first.frames.last { it.optString("type") == "media_state" }.getString("event"))
            fixture.controller.transfer("pc"); first.awaitFrame("switch")
            first.eof()
            fixture.nextWait().second.complete(Unit)
            fixture.connected()
            second.send("media_query") { put("probe", "2123456789abcdef") }
            val baseline = second.awaitFrame("media_state")
            assertEquals(CompanionProtocol.HANDSHAKE_TX, baseline.getString("event"))
            assertTrue(baseline.getBoolean("playing"))
            assertFalse(second.frames.any { it.optString("type") in setOf("switch", "call_begin", "release", "acquire", "cancel") })
            assertEquals(0, fixture.audioOperations.get())
        } finally { fixture.close() }
    }

    @Test fun incomingCallAndItsOriginCannotSurviveAControlReconnect() = runBlocking<Unit> {
        val first = Wire()
        val second = Wire()
        val fixture = Fixture(listOf(first, second))
        try {
            fixture.controller.reconnect(); fixture.connected()
            first.send("call_origin") { put("ready", true).put("origin_token", "ab".repeat(16)) }
            delay(30)
            fixture.controller.onCallState("ringing", true)
            first.awaitFrame("call_begin")
            first.eof(); fixture.nextWait().second.complete(Unit); fixture.connected()
            assertFalse(fixture.controller.hasCallReservation)
            fixture.controller.onCallState("active", false)
            second.send("call_origin") { put("ready", true).put("origin_token", "bc".repeat(16)) }
            delay(30)
            assertFalse(second.frames.any { it.optString("type") == "call_begin" })
            assertEquals(0, fixture.audioOperations.get())
        } finally { fixture.close() }
    }

    @Test fun explicitReconnectAndNewLocalIntentSupersedeTheOldWaitingOwner() = runBlocking<Unit> {
        for (manualIntent in listOf(false, true)) {
            val first = Wire(connectFailure = true)
            val second = Wire()
            val fixture = Fixture(listOf(first, second))
            try {
                fixture.controller.reconnect()
                val oldWait = fixture.nextWait()
                if (manualIntent) fixture.controller.beginLocalIntent() else fixture.controller.reconnect()
                fixture.connected()
                oldWait.second.complete(Unit)
                delay(30)
                assertEquals(2, fixture.attempts.get())
                assertTrue(fixture.controller.state.value.connected)
                verify(second.socket, never()).close()
                assertEquals(0, fixture.audioOperations.get())
            } finally { fixture.close() }
        }
    }

    @Test fun permissionBondUnlinkAndShutdownLossStopTheWaitingOwner() = runBlocking<Unit> {
        for (reason in listOf("permission", "bond", "unlink", "close")) {
            val fixture = Fixture(listOf(Wire(connectFailure = true)))
            try {
                fixture.controller.reconnect()
                val wait = fixture.nextWait()
                when (reason) {
                    "permission" -> { fixture.permission.set(false); fixture.controller.bluetoothChanged() }
                    "bond" -> { fixture.bonded.set(false); fixture.controller.bluetoothChanged() }
                    "unlink" -> fixture.controller.unlink()
                    else -> fixture.controller.close()
                }
                wait.second.complete(Unit)
                delay(30)
                assertEquals(reason, 1, fixture.attempts.get())
                assertFalse(fixture.controller.state.value.connected)
                assertEquals(0, fixture.audioOperations.get())
            } finally { fixture.close() }
        }
    }

    @Test fun authenticationSchemaAndUtf8FailuresDoNotEnterTheTransportRetryLoop() = runBlocking<Unit> {
        for (failure in listOf("key", "schema", "utf8")) {
            val fixture = Fixture(listOf(Wire(failure = failure)))
            try {
                fixture.controller.reconnect()
                withTimeout(2000) { while (!fixture.controller.state.value.detail.contains("again")) delay(10) }
                assertFalse(fixture.controller.state.value.connected)
                assertTrue(fixture.waits.isEmpty())
                assertEquals(1, fixture.attempts.get())
                assertEquals(0, fixture.audioOperations.get())
            } finally { fixture.close() }
        }
    }

    @Test fun aLateProtocolWriterFailureDominatesEofAndSkipsTheQueuedIoWriter() = runBlocking<Unit> {
        val wire = Wire()
        val fixture = Fixture(listOf(wire))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val writes = AtomicInteger()
        try {
            fixture.controller.reconnect(); fixture.connected(); wire.awaitFrame("status")
            `when`(wire.socket.outputStream).thenReturn(object : OutputStream() {
                override fun write(value: Int) {
                    if (writes.incrementAndGet() == 1) {
                        entered.countDown()
                        check(finish.await(2, TimeUnit.SECONDS))
                        throw IllegalArgumentException("test protocol writer failure")
                    }
                    throw IOException("test queued writer lost its socket")
                }
            })
            fixture.controller.transfer("pc")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.controller.cancelTransfer()
            wire.eof()
            withTimeout(2000) { wire.closedSignal.await() }
            finish.countDown()
            withTimeout(2000) {
                while (!fixture.controller.state.value.detail.contains("could not be verified")) delay(10)
            }
            assertTrue(fixture.waits.isEmpty())
            assertEquals(1, writes.get())
            assertEquals(1, fixture.attempts.get())
            assertEquals(0, fixture.audioOperations.get())
        } finally { finish.countDown(); fixture.close() }
    }

    private inner class Fixture(private val wires: List<Wire>) {
        val attempts = AtomicInteger()
        val audioOperations = AtomicInteger()
        val permission = AtomicBoolean(true)
        val bonded = AtomicBoolean(true)
        val waits = LinkedBlockingQueue<Pair<Long, CompletableDeferred<Unit>>>()
        @Volatile var playing = false
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller: CompanionHandoffController
        init {
            val context = mock(Context::class.java)
            val manager = mock(BluetoothManager::class.java)
            val adapter = mock(BluetoothAdapter::class.java)
            val peer = mock(BluetoothDevice::class.java)
            val airpods = mock(BluetoothDevice::class.java)
            `when`(context.noBackupFilesDir).thenReturn(temporary.newFolder())
            `when`(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)).thenAnswer {
                if (permission.get()) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            `when`(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
            `when`(manager.adapter).thenReturn(adapter)
            `when`(adapter.isEnabled).thenReturn(true)
            `when`(adapter.bondedDevices).thenAnswer { if (bonded.get()) setOf(peer, airpods) else emptySet<BluetoothDevice>() }
            `when`(peer.address).thenReturn(peerAddress)
            `when`(peer.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
            `when`(airpods.address).thenReturn(device)
            `when`(peer.createRfcommSocketToServiceRecord(CompanionProtocol.SERVICE)).thenAnswer {
                wires[attempts.getAndIncrement()].also { `when`(it.socket.remoteDevice).thenReturn(peer) }.socket
            }
            controller = CompanionHandoffController(context, scope,
                { _, _ -> CompanionStatus(false, false, true) },
                { _, _, _ -> audioOperations.incrementAndGet(); "failed" },
                callAllowed = { true }, callReceipt = { _, _ -> 8 },
                media = { CompanionMediaSample(known = true, playing = playing, available = true,
                    player = if (playing) "test-player" else "") },
                retryWait = { ms -> CompletableDeferred<Unit>().also { waits.add(ms to it) }.await() })
            CompanionHandoffController::class.java.getDeclaredField("link").apply { isAccessible = true }
                .set(controller, CompanionLink(peerAddress, device, key.copyOf()))
        }
        suspend fun connected() = withTimeout(2000) { while (!controller.state.value.connected) delay(10) }
        suspend fun nextWait(): Pair<Long, CompletableDeferred<Unit>> = withTimeout(2000) {
            var found = waits.poll()
            while (found == null) { delay(10); found = waits.poll() }
            found
        }
        fun close() { controller.close(); scope.cancel() }
    }

    private inner class Wire(val connectFailure: Boolean = false, private val failure: String = "") {
        val socket = mock(BluetoothSocket::class.java)
        val frames = CopyOnWriteArrayList<JSONObject>()
        val closedSignal = CompletableDeferred<Unit>()
        private val input = LinkedBlockingQueue<Int>()
        private val closed = AtomicBoolean()
        private var sid = ""
        init {
            `when`(socket.inputStream).thenReturn(object : InputStream() {
                override fun read(): Int = input.take()
            })
            `when`(socket.outputStream).thenReturn(object : OutputStream() {
                private val line = StringBuilder()
                override fun write(value: Int) {
                    if (value != 10) { line.append(value.toChar()); return }
                    val frame = CompanionProtocol.parse(line.toString()); line.clear(); frames.add(frame)
                    when (frame.getString("type")) {
                        "hello" -> {
                            if (failure == "utf8") { input.addAll(listOf(0xc0, 0xaf, 10)); return }
                            val nonce = "cd".repeat(32)
                            val transcript = CompanionProtocol.transcript(device, frame.getString("nonce"), nonce)
                            sid = CompanionProtocol.sid(transcript)
                            val challenge = CompanionProtocol.frame("challenge", device, sid = sid)
                                .put("nonce", nonce).put("caps", CompanionProtocol.CAPS)
                                .put("proof", if (failure == "key") "00".repeat(32) else CompanionProtocol.proof(key, "server", transcript))
                            if (failure == "schema") challenge.put("unknown", true)
                            enqueue(challenge)
                        }
                        "auth" -> enqueue(CompanionProtocol.frame("authenticated", device, sid = sid))
                    }
                }
            })
            doAnswer { if (connectFailure) throw IOException("test connection loss"); null }.`when`(socket).connect()
            doAnswer {
                if (closed.compareAndSet(false, true)) { closedSignal.complete(Unit); input.add(-1) }
                null
            }.`when`(socket).close()
        }
        private fun enqueue(frame: JSONObject) { input.addAll((frame.toString() + "\n").toByteArray(Charsets.UTF_8).map { it.toInt() and 255 }) }
        fun send(type: String, fill: JSONObject.() -> Unit = {}) = enqueue(CompanionProtocol.frame(type, device, sid = sid).apply(fill))
        fun eof() { input.add(-1) }
        suspend fun awaitFrame(type: String, predicate: (JSONObject) -> Boolean = { true }): JSONObject = withTimeout(2000) {
            while (frames.none { it.optString("type") == type && predicate(it) }) delay(10)
            frames.last { it.optString("type") == type && predicate(it) }
        }
    }
}
