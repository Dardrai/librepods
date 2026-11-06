package me.kavishdevar.librepods.companion

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompanionPairingTest {
    private val peer = "02:00:00:00:00:11"
    private val device = "02:00:00:00:00:22"
    private val key = ByteArray(32) { 0x33 }

    private class Store : CompanionLinkStorage {
        @Volatile var value: CompanionLink? = null
        var failSave = false
        var failClear = false
        @Synchronized override fun load() = value?.let { it.copy(key = it.key.copyOf()) }
        @Synchronized override fun save(link: CompanionLink) {
            if (failSave) throw IOException("Synthetic storage failure")
            value = link.copy(key = link.key.copyOf())
        }
        @Synchronized override fun clear() { if (failClear) throw IOException("Synthetic cleanup failure"); value = null }
    }

    private inner class Transport(private val silent: Boolean = false) {
        val socket = mock(BluetoothSocket::class.java)
        val output = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        val reading = CountDownLatch(1)
        init {
            val remote = mock(BluetoothDevice::class.java)
            `when`(remote.address).thenReturn(peer)
            `when`(socket.remoteDevice).thenReturn(remote)
            `when`(socket.outputStream).thenReturn(output)
            `when`(socket.inputStream).thenReturn(object : InputStream() {
                var reply: ByteArrayInputStream? = null
                override fun read(): Int {
                    reading.countDown()
                    if (silent) { closed.await(3, TimeUnit.SECONDS); throw IOException("Synthetic close") }
                    if (reply == null) {
                        val request = CompanionProtocol.read(ByteArrayInputStream(output.toByteArray()))
                        CompanionPairingProtocol.validateRequest(request, device)
                        val response = CompanionProtocol.frame("pair_key", device).put("nonce", request.getString("nonce"))
                            .put("key", CompanionProtocol.hex(key))
                        reply = ByteArrayInputStream((response.toString() + "\n").toByteArray())
                    }
                    return reply!!.read()
                }
            })
            doAnswer { closed.countDown(); null }.`when`(socket).close()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Bounded asynchronous condition", condition())
    }

    @Test fun sharedSetupVectorsAreStrictAndNeverAcceptedAsOrdinaryMessages() {
        val fixture = JSONObject(javaClass.getResource("/companion-pairing-v2.json")!!.readText())
        assertEquals(fixture.getString("service"), CompanionPairingProtocol.SERVICE.toString())
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val accepted = runCatching {
                val frame = CompanionProtocol.parse(case.getString("frame"))
                if (case.getString("stage") == "request") CompanionPairingProtocol.validateRequest(frame, fixture.getString("device"))
                else CompanionPairingProtocol.validateReply(frame, fixture.getString("device"), fixture.getString("nonce"))
            }.isSuccess
            assertEquals(case.getString("name"), case.getBoolean("accepted"), accepted)
            val parsed = runCatching { CompanionProtocol.parse(case.getString("frame")) }.getOrNull()
            if (parsed?.optString("type") in setOf("pair_request", "pair_key", "pair_failed")) assertTrue(case.getString("name"), runCatching {
                CompanionProtocol.validate(parsed!!, fixture.getString("device"))
            }.isFailure)
        }
    }

    @Test fun shortCodesNormalizeOnlyAsciiGroupingAndNeverBecomePersistentKeys() {
        assertEquals("00001234", CompanionPairingProtocol.shortCode("0000 12-34"))
        for (bad in listOf("１２３４５６７８", "1234\u00a05678", "1234\t5678", "1234\n5678", "1234567", "123456789", "abcd1234"))
            assertNull(CompanionPairingProtocol.shortCode(bad))
        assertEquals("a".repeat(64), CompanionPairingProtocol.fullCode("A".repeat(64)))
        assertNull(CompanionPairingProtocol.fullCode("12345678"))
    }

    @Test fun validatorsRejectNumericCredentialsEvenWithoutTheRawFramer() {
        val nonce = "1".repeat(64)
        val request = CompanionPairingProtocol.request(device, nonce, "12345678").put("code", 12345678)
        assertTrue(runCatching { CompanionPairingProtocol.validateRequest(request, device) }.isFailure)
        val reply = CompanionProtocol.frame("pair_key", device).put("nonce", nonce)
            .put("key", java.math.BigInteger("2".repeat(64)))
        assertTrue(runCatching { CompanionPairingProtocol.validateReply(reply, device, nonce) }.isFailure)
    }

    @Test fun stagesProtectedCandidateBeforeProofAndPromotesOnlyAfterCommitReceipt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store()
        val transport = Transport()
        var promoted: CompanionLink? = null
        val client = CompanionPairingClient(scope, store, { _, service ->
            assertEquals(CompanionPairingProtocol.SERVICE, service); transport.socket
        }, { _, _ -> true }, { { true } }, authenticate = { _, candidate ->
            assertArrayEquals(key, store.load()!!.key)
            assertArrayEquals(key, candidate.key)
            assertNull(promoted)
        }, promote = { candidate, current, commit -> commit {
            assertTrue(current()); promoted = candidate.copy(key = candidate.key.copyOf()); true
        } })
        try {
            client.start(Any(), peer, device, "1234 5678")
            await { client.state.value.complete }
            await { store.value == null }
            assertArrayEquals(key, promoted!!.key)
            assertNull(store.value)
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
        } finally { client.close(); scope.cancel() }
    }

    @Test fun lostCommitReceiptKeepsInertKeyAcrossRestartAndExplicitRetryUsesNormalService() {
        val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = Transport()
        val earlyPromotions = AtomicInteger()
        val client = CompanionPairingClient(scope, store, { _, _ -> first.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> throw IOException("Synthetic lost final receipt") }, promote = { _, _, _ -> earlyPromotions.incrementAndGet(); true })
        client.start(Any(), peer, device, "12345678")
        await { client.state.value.failed }
        assertArrayEquals(key, store.load()!!.key)
        assertEquals(0, earlyPromotions.get())
        client.close()
        val opened = AtomicInteger()
        val retryTransport = Transport()
        val recovered = CompanionPairingClient(scope, store, { _, service ->
            assertEquals(CompanionProtocol.SERVICE, service); opened.incrementAndGet(); retryTransport.socket
        }, { _, _ -> true }, { { true } }, authenticate = { _, candidate -> assertArrayEquals(key, candidate.key) },
            promote = { _, current, commit -> commit { current() } })
        try {
            await { recovered.state.value.pendingPeer == peer }
            assertEquals(0, opened.get())
            assertFalse(recovered.state.value.complete)
            recovered.retry(Any())
            await { recovered.state.value.complete }
            await { store.value == null }
            assertEquals(1, opened.get())
            assertEquals(0, retryTransport.output.size()) // No new pair_request or code replay on recovery.
            assertNull(store.value)
        } finally { recovered.close(); scope.cancel() }
    }

    @Test fun cancellationClosesCapturedSocketAndLateProofCannotPromote() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store()
        val transport = Transport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val promoted = AtomicInteger()
        val owner = Any()
        val client = CompanionPairingClient(scope, store, { _, _ -> transport.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> entered.countDown(); release.await(3, TimeUnit.SECONDS) },
            promote = { _, _, commit -> commit { promoted.incrementAndGet(); true } })
        try {
            client.start(owner, peer, device, "12345678")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            client.cancel(owner)
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
            release.countDown()
            await { !client.state.value.busy }
            assertEquals(0, promoted.get())
            assertNotNull(store.value)
            assertFalse(client.state.value.complete)
        } finally { release.countDown(); client.close(); scope.cancel() }
    }

    @Test fun currentIntentAndPermissionLossRejectLateCommit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val valid = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val promoted = AtomicInteger()
        val transport = Transport()
        val client = CompanionPairingClient(scope, Store(), { _, _ -> transport.socket }, { _, _ -> valid.get() }, { { valid.get() } },
            authenticate = { _, _ -> entered.countDown(); release.await(3, TimeUnit.SECONDS) },
            promote = { _, _, commit -> commit { promoted.incrementAndGet(); true } })
        try {
            client.start(Any(), peer, device, "12345678")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            valid.set(false); client.bluetoothChanged(); release.countDown()
            await { !client.state.value.busy }
            assertEquals(0, promoted.get())
            assertFalse(client.state.value.complete)
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); client.close(); scope.cancel() }
    }

    @Test fun blockedReadDeadlineClosesSocketWithoutChangingTheWorkingLink() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = Transport(silent = true)
        val promoted = AtomicInteger()
        val authenticated = AtomicInteger()
        val store = Store()
        val client = CompanionPairingClient(scope, store, { _, _ -> transport.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> authenticated.incrementAndGet() }, promote = { _, _, _ -> promoted.incrementAndGet(); true },
            timeoutMs = 100, now = { System.nanoTime() / 1_000_000 })
        try {
            client.start(Any(), peer, device, "12345678")
            await { client.state.value.failed }
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
            assertEquals(0, authenticated.get()); assertEquals(0, promoted.get()); assertNull(store.value)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun pendingStorageFailurePreventsAuthenticationOrPromotion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store().apply { failSave = true }
        val transport = Transport()
        val authenticated = AtomicInteger()
        val promoted = AtomicInteger()
        val client = CompanionPairingClient(scope, store, { _, _ -> transport.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> authenticated.incrementAndGet() }, promote = { _, _, _ -> promoted.incrementAndGet(); true })
        try {
            client.start(Any(), peer, device, "12345678")
            await { client.state.value.failed }
            assertNull(store.value); assertFalse(client.state.value.complete)
            assertEquals(0, authenticated.get()); assertEquals(0, promoted.get())
        } finally { client.close(); scope.cancel() }
    }

    @Test fun deadlineWhileWaitingForPromotionStillFencesTheCommit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store()
        val transport = Transport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val promotions = AtomicInteger()
        val client = CompanionPairingClient(scope, store, { _, _ -> transport.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> }, promote = { _, _, commit ->
                entered.countDown(); release.await(3, TimeUnit.SECONDS)
                commit { promotions.incrementAndGet(); true }
            }, timeoutMs = 200, now = { System.nanoTime() / 1_000_000 })
        try {
            client.start(Any(), peer, device, "12345678")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            await { client.state.value.failed }
            release.countDown()
            assertEquals(0, promotions.get())
            assertNotNull(store.value)
        } finally { release.countDown(); client.close(); scope.cancel() }
    }

    @Test fun monotonicDeadlineRejectsPromotionEvenBeforeTheWatcherRuns() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = AtomicLong(0)
        val store = Store()
        val transport = Transport()
        val promotions = AtomicInteger()
        val client = CompanionPairingClient(scope, store, { _, _ -> transport.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> }, promote = { _, _, commit ->
                clock.set(10_001)
                commit { promotions.incrementAndGet(); true }
            }, timeoutMs = 10_000, now = clock::get)
        try {
            client.start(Any(), peer, device, "12345678")
            await { client.state.value.failed }
            assertEquals(0, promotions.get())
            assertNotNull(store.value)
            assertFalse(client.state.value.complete)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun pendingCleanupFailureDoesNotUndoOrMisreportDurableApproval() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store().apply { failClear = true }
        val transport = Transport()
        val approvals = AtomicInteger()
        val client = CompanionPairingClient(scope, store, { _, _ -> transport.socket }, { _, _ -> true }, { { true } },
            authenticate = { _, _ -> }, promote = { _, _, commit -> commit { approvals.incrementAndGet(); true } })
        try {
            client.start(Any(), peer, device, "12345678")
            await { client.state.value.complete }
            assertEquals(1, approvals.get())
            assertFalse(client.state.value.failed)
            assertFalse(client.state.value.busy)
            assertNull(client.state.value.pendingPeer)
            assertNull(client.state.value.pendingDevice)
            assertNotNull(store.value) // An undeleted, encrypted candidate remains inert.
        } finally { client.close(); scope.cancel() }
    }
}
