package me.kavishdevar.librepods.dongle

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** A report-level peer: leases protect shared device data, independently of RPC receipts. */
internal class DongleLeaseFirmware {
    var generation = 3L
        set(value) { if (field != value) token = null; field = value }
    var capabilities = 0x30
    var token: Long? = null
        private set
    var closed = false
    var closeCalls = 0
    var rejectRenew = false
    var invalidAcquire = false
    var failRaw = false
    var delayCancelReads = 0
    private var cancelPending = false
    var beforeRawReply: suspend () -> Unit = {}
    var beforeLeaseReply: suspend (Int) -> Unit = {}
    var rawReply: (Int, ByteArray) -> ByteArray = { _, _ -> byteArrayOf(0x0b, 7) }
    var cachedReply: (Int) -> ByteArray = { byteArrayOf() }
    val actions = mutableListOf<Int>()
    val events = mutableListOf<String>()
    val rawRequests = mutableListOf<Pair<Int, ByteArray>>()
    private var serial = 40L
    private var operation = 0
    private val staged = ByteArrayOutputStream()
    private var output = byteArrayOf()
    private var report = DongleProtocol.header(3)

    fun close() { closed = true; token = null; closeCalls++ }
    fun competingEdit(edit: () -> Unit): Boolean = if (token != null) false else { edit(); true }

    suspend fun exchange(operation: Int, id: Int, bytes: ByteArray): ByteArray {
        if (closed) throw IOException("Transport closed")
        if (id == 2) return DongleProtocol.header(2).also {
            it[7] = 3; it[12] = 1; it[42] = capabilities.toByte()
            DongleProtocol.put32(it, 8, generation)
        }
        if (operation == 1) {
            if (cancelPending) {
                if (delayCancelReads-- <= 0) {
                    cancelPending = false; report[21] = 4; report[22] = 8; events += "cancelled"
                }
            }
            return report.copyOf()
        }
        check(!cancelPending) { "A new PDU raced the queued Cancel" }
        report = DongleProtocol.header(3).also {
            DongleProtocol.put32(it, 8, generation)
            DongleProtocol.put32(it, 12, DongleProtocol.u32(bytes, 12))
        }
        when (bytes[7].toInt()) {
            1, 2, 3, 7 -> {
                this.operation = bytes[7].toInt()
                if (DongleProtocol.u16(bytes, 18) == 0) staged.reset()
                staged.write(bytes, 22, bytes[20].toInt() and 255)
                report[21] = 1; report[18] = staged.size().toByte(); report[19] = (staged.size() shr 8).toByte()
            }
            6 -> {
                val request = staged.toByteArray()
                if (this.operation == 7 && request[0] == 10.toByte()) {
                    assertEquals(6, request.size)
                    val action = request[1].toInt()
                    val supplied = DongleProtocol.u32(request, 2)
                    actions += action; events += "lease$action"
                    if (action == 0 && token != null) { fail(2); return report.copyOf() }
                    if (action != 0 && (supplied != token || rejectRenew && action == 1)) {
                        fail(3); return report.copyOf()
                    }
                    output = when (action) {
                        0 -> {
                            assertEquals(0L, supplied)
                            token = ++serial
                            ByteArray(5).also { it[0] = 1; DongleProtocol.put32(it, 1, if (invalidAcquire) 0 else serial) }
                        }
                        1 -> byteArrayOf()
                        2 -> { token = null; byteArrayOf() }
                        else -> error("Unknown lease action")
                    }
                    beforeLeaseReply(action)
                } else if (this.operation in 1..2) {
                    check(token != null) { "Raw request escaped its lease" }
                    rawRequests += this.operation to request
                    events += "raw${this.operation}"
                    beforeRawReply()
                    if (failRaw) { failRaw = false; fail(6); return report.copyOf() }
                    output = rawReply(this.operation, request)
                } else if (this.operation == 3) {
                    events += "cached${request[0]}"
                    output = cachedReply(request[0].toInt())
                } else output = byteArrayOf(1, 0)
                page(0)
            }
            4 -> page(DongleProtocol.u16(bytes, 18))
            5 -> { events += "cancel"; cancelPending = true }
        }
        return report.copyOf()
    }

    private fun fail(reason: Int) { report[21] = 4; report[22] = reason.toByte() }
    private fun page(offset: Int) {
        report[21] = 3
        report[16] = output.size.toByte(); report[17] = (output.size shr 8).toByte()
        report[18] = offset.toByte(); report[19] = (offset shr 8).toByte()
        val count = minOf(42, output.size - offset)
        report[20] = count.toByte(); output.copyInto(report, 22, offset, offset + count)
    }
}

class DongleLeaseTest {
    private val read = byteArrayOf(0x0a, 0x20, 0)
    private fun client(firmware: DongleLeaseFirmware) = DongleSession(firmware::exchange, closeTransport = firmware::close)

    @Test fun oneLeaseCoversReadWriteVerifyAndCachedPollingThenNextCommandGetsANewLease() = runBlocking {
        val firmware = DongleLeaseFirmware()
        val client = client(firmware)
        client.withTarget(client.refresh()) {
            client.transact(1, read)
            assertFalse(firmware.competingEdit { fail("Another client edited a protected baseline") })
            client.cached(2)
            client.transact(2, byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0))
            client.transact(1, read)
            assertNotNull(firmware.token)
        }
        assertEquals(listOf(0, 1, 1, 1, 2), firmware.actions)
        assertNull(firmware.token)
        assertTrue(firmware.competingEdit { })
        client.withTarget(client.refresh()) { client.transact(1, read) }
        assertEquals(2, firmware.actions.count { it == 0 })
        assertEquals(2, firmware.actions.count { it == 2 })
    }

    @Test fun localReadOnlyCommandsStayUnlockedAndOldFirmwareCannotSendRawPackets() = runBlocking {
        val firmware = DongleLeaseFirmware().also { it.capabilities = 0x10 }
        val client = client(firmware)
        client.withTarget(client.refresh()) { client.settings(); client.cached(2) }
        try { client.withTarget(client.refresh()) { client.transact(1, read) }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(firmware.actions.isEmpty()); assertTrue(firmware.rawRequests.isEmpty())
        assertFalse(firmware.closed)
        try { client.transact(1, read); fail() } catch (_: IllegalStateException) { }
    }

    @Test fun busyAcquireNeverFallsBackOrRetriesInsideTheAction() = runBlocking {
        val firmware = DongleLeaseFirmware()
        val first = client(firmware)
        first.withTarget(first.refresh()) {
            first.acquireSettingsLease()
            val second = client(firmware)
            second.withTarget(second.refresh()) {
                try { second.transact(1, read); fail() } catch (_: DongleBusyException) { }
                try { second.transact(1, read); fail() } catch (_: IllegalStateException) { }
            }
            assertNotNull(firmware.token)
        }
        assertEquals(listOf(0, 0, 2), firmware.actions)
        assertTrue(firmware.rawRequests.isEmpty())
        assertFalse(firmware.closed)
    }

    @Test fun invalidOrLostAcquireReceiptsCloseTheClientAndNeverSendRawData() = runBlocking {
        for (lost in listOf(false, true)) {
            val firmware = DongleLeaseFirmware().also {
                it.invalidAcquire = !lost
                if (lost) it.beforeLeaseReply = { action -> if (action == 0) throw IOException("Lost acquire receipt") }
            }
            val client = client(firmware)
            try { client.withTarget(client.refresh()) { client.transact(1, read) }; fail() }
            catch (_: Exception) { }
            assertTrue(firmware.closed); assertNull(firmware.token)
            assertTrue(firmware.rawRequests.isEmpty())
            assertEquals(listOf(0), firmware.actions)
        }
    }

    @Test fun expiredLeaseCannotBeReacquiredDuringTheEdit() = runBlocking {
        val firmware = DongleLeaseFirmware()
        val client = client(firmware)
        client.withTarget(client.refresh()) {
            client.transact(1, read)
            firmware.rejectRenew = true
            try { client.cached(2); fail() } catch (_: IOException) { }
            try { client.transact(1, read); fail() } catch (_: IllegalStateException) { }
        }
        assertEquals(listOf(0, 1, 2), firmware.actions)
        assertEquals(1, firmware.rawRequests.size)
        assertNull(firmware.token)
    }

    @Test fun changedGenerationCannotRenewOrWriteButANewActionCanReadAgain() = runBlocking {
        val firmware = DongleLeaseFirmware()
        val client = client(firmware)
        try {
            client.withTarget(client.refresh()) {
                client.transact(1, read)
                firmware.generation++
                client.transact(1, read)
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(0), firmware.actions)
        assertEquals(1, firmware.rawRequests.size)
        client.withTarget(client.refresh()) { client.transact(1, read) }
        assertEquals(listOf(0, 0, 2), firmware.actions)
    }

    @Test fun ordinaryPduFailureKeepsTheLeaseForRestoration() = runBlocking {
        val firmware = DongleLeaseFirmware().also { it.failRaw = true; it.delayCancelReads = 3 }
        val client = client(firmware)
        client.withTarget(client.refresh()) {
            try { client.transact(1, read); fail() } catch (_: IOException) { }
            assertNotNull(firmware.token)
            client.transact(2, byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0))
        }
        assertEquals(listOf(0, 1, 2), firmware.actions)
        assertEquals(2, firmware.rawRequests.size)
        assertFalse(firmware.closed)
        assertTrue(firmware.events.indexOf("cancelled") < firmware.events.indexOf("lease1"))
    }

    @Test fun cancellationReleasesTheWorkflowAndBlockedReleaseHasABoundedClose() = runBlocking {
        val firmware = DongleLeaseFirmware()
        val entered = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        firmware.beforeRawReply = { entered.complete(Unit); waiting.await() }
        val client = client(firmware)
        val job = launch(Dispatchers.Unconfined) { client.withTarget(client.refresh()) { client.transact(1, read) } }
        entered.await(); job.cancelAndJoin()
        assertEquals(listOf(0, 2), firmware.actions)
        assertNull(firmware.token)

        val blocked = DongleLeaseFirmware()
        blocked.beforeLeaseReply = { action -> if (action == 2) CompletableDeferred<Unit>().await() }
        val blockedClient = client(blocked)
        withTimeout(3_000) { blockedClient.withTarget(blockedClient.refresh()) { blockedClient.transact(1, read) } }
        assertTrue(blocked.closed); assertTrue(blocked.closeCalls >= 1)
    }
}
