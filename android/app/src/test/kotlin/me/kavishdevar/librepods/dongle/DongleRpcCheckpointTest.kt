package me.kavishdevar.librepods.dongle

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DongleRpcCheckpointTest {
    private val read = byteArrayOf(0x0a, 0x20, 0)
    private val write = byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0)

    /** Delay a selected RPC receipt without bypassing the fixture's staging or Cancel rules. */
    private class Peer(useBoundedIo: Boolean = false) {
        data class Budget(val operation: Int, val report: Int, val opcode: Int?, val remaining: Long, val at: Long)
        val firmware = DongleLeaseFirmware()
        var clock = 0L
        var advanceCommittedReadMs = 0L
        var requireBoundedIo = false
        val budgets = mutableListOf<Budget>()
        var holdOperation: Int? = null
        private var heldOnce = false
        private var heldSequence: Long? = null
        private var heldCommitted = false
        private var unreadCommittedSequence: Long? = null
        private var cancelSequence: Long? = null
        var cancelConfirmed = false
            private set
        var rawSequence: Long? = null
            private set
        val pages = mutableMapOf<Long, MutableList<Int>>()
        var reportWrites = 0
            private set
        val client = DongleSession({ operation, id, bytes ->
            check(!requireBoundedIo) { "Scoped I/O bypassed the bounded transport" }
            exchange(operation, id, bytes)
        }, now = { clock }, wait = { clock += 100 }, closeTransport = firmware::close,
            boundedIo = if (useBoundedIo) ::exchangeBounded else null)

        private suspend fun exchangeBounded(operation: Int, id: Int, bytes: ByteArray, remaining: Long): ByteArray {
            budgets += Budget(operation, id, bytes.takeIf { operation == 2 && id == 3 }?.get(7)?.toInt(), remaining, clock)
            return exchange(operation, id, bytes)
        }

        private suspend fun exchange(operation: Int, id: Int, bytes: ByteArray): ByteArray {
            if (id == 3 && operation == 2) {
                reportWrites++
                val opcode = bytes[7].toInt()
                val sequence = DongleProtocol.u32(bytes, 12)
                if (opcode in 1..2 && DongleProtocol.u16(bytes, 18) == 0) rawSequence = sequence
                if (!heldOnce && opcode == holdOperation && DongleProtocol.u16(bytes, 18) == 0) {
                    heldOnce = true; heldSequence = sequence
                }
                if (opcode == 6) {
                    unreadCommittedSequence = sequence
                    if (sequence == heldSequence) heldCommitted = true
                }
                if (opcode == 5) { cancelSequence = sequence; heldSequence = null; heldCommitted = false }
            }
            val reply = firmware.exchange(operation, id, bytes)
            if (id == 3 && operation == 1) {
                val sequence = DongleProtocol.u32(reply, 12)
                if (sequence == cancelSequence && reply[21] == 4.toByte() && reply[22] == 8.toByte())
                    cancelConfirmed = true
                if (sequence == unreadCommittedSequence && reply[21] == 3.toByte()) {
                    unreadCommittedSequence = null
                    clock += advanceCommittedReadMs
                }
                if (sequence == heldSequence && heldCommitted) reply[21] = 2
                if (reply[21] == 3.toByte() && reply[20].toInt() and 255 != 0)
                    pages.getOrPut(sequence) { mutableListOf() }.add(DongleProtocol.u16(reply, 18))
            }
            return reply
        }
    }

    @Test fun paginatedAttRepliesAreFullyDrainedBeforeTheCheckpointReusesReportThree() = runBlocking {
        for (size in listOf(256, 512)) {
            val peer = Peer()
            val expected = ByteArray(size) { (it * 37 + 11).toByte() }
            peer.firmware.rawReply = { _, _ -> expected.copyOf() }
            peer.firmware.cachedReply = { byteArrayOf(it.toByte()) }
            var checkedCompletedReply = false
            peer.client.withTarget(peer.client.refresh()) {
                val actual = peer.client.withRpcCheckpoints({
                    peer.rawSequence?.let { sequence ->
                        assertEquals((0 until size step 42).toList(), peer.pages[sequence])
                        checkedCompletedReply = true
                    }
                    // This replaces the fixture's shared response buffer with another RPC.
                    assertArrayEquals(byteArrayOf(5), peer.client.transact(3, byteArrayOf(5)))
                    5_000L
                }) { peer.client.transact(1, read) }
                assertArrayEquals(expected, actual)
                assertNotNull(peer.firmware.token)
            }
            assertTrue(checkedCompletedReply)
            assertEquals(1, peer.firmware.rawRequests.size)
            assertNull(peer.firmware.token)
            assertFalse(peer.firmware.closed)
        }
    }

    @Test fun checkpointsKeepOneSettingsLeaseAcrossReadWriteVerifyAndTheirOwnRpc() = runBlocking {
        val peer = Peer()
        val seenTokens = mutableListOf<Long>()
        peer.client.withTarget(peer.client.refresh()) {
            peer.client.withRpcCheckpoints({
                val token = peer.firmware.token
                if (token != null) {
                    seenTokens += token
                    assertFalse(peer.firmware.competingEdit { fail("A competing edit crossed the checkpoint") })
                }
                peer.client.cached(5)
                assertEquals(token, peer.firmware.token)
                5_000L
            }) {
                peer.client.transact(1, read)
                val token = peer.firmware.token
                peer.client.transact(2, write)
                peer.client.transact(1, read)
                assertEquals(token, peer.firmware.token)
                assertFalse(peer.firmware.competingEdit { fail("The workflow released its settings lease") })
            }
        }
        assertTrue(seenTokens.isNotEmpty())
        assertEquals(1, seenTokens.distinct().size)
        assertEquals(listOf(1, 2, 1), peer.firmware.rawRequests.map { it.first })
        assertEquals(1, peer.firmware.actions.count { it == 0 })
        assertEquals(1, peer.firmware.actions.count { it == 2 })
        assertNull(peer.firmware.token)
    }

    @Test fun theCheckpointsOwnRpcDoesNotRecursivelyInvokeTheCheckpoint() = runBlocking {
        val peer = Peer()
        var calls = 0
        peer.client.withRpcCheckpoints({
            calls++
            check(calls <= 2) { "The checkpoint recursively invoked itself" }
            peer.client.cached(5)
            5_000L
        }) { peer.client.cached(2) }
        assertEquals(2, calls)
        assertEquals(listOf("cached5", "cached2", "cached5"), peer.firmware.events)
        assertTrue(peer.firmware.actions.isEmpty())
    }

    @Test fun insufficientRemainingLeaseFailsBeforeAnyMainRequestAndDoesNotLeakTheHook() = runBlocking {
        val peer = Peer()
        var calls = 0
        try {
            peer.client.withTarget(peer.client.refresh()) {
                peer.client.withRpcCheckpoints({ calls++; 1_600L }) { peer.client.transact(2, write) }
            }
            fail("Settings were sent without enough time for cleanup and renewal")
        } catch (_: IllegalStateException) { }
        assertEquals(1, calls)
        assertEquals(0, peer.reportWrites)
        assertTrue(peer.firmware.rawRequests.isEmpty())
        peer.client.cached(2)
        assertEquals(1, calls)
        assertFalse(peer.firmware.closed)
    }

    @Test fun aFreshCheckpointAfterAcquireStopsTheMainPduButReleasesTheKnownLease() = runBlocking {
        val peer = Peer()
        var sawAcquiredLease = false
        try {
            peer.client.withTarget(peer.client.refresh()) {
                peer.client.withRpcCheckpoints({
                    if (peer.firmware.token == null) 5_000L else { sawAcquiredLease = true; 1_600L }
                }) { peer.client.transact(1, read) }
            }
            fail("The main PDU ignored the shorter lease after settings acquisition")
        } catch (_: IllegalStateException) { }
        assertTrue(sawAcquiredLease)
        assertTrue(peer.firmware.rawRequests.isEmpty())
        assertEquals(listOf(0, 2), peer.firmware.actions)
        assertNull(peer.firmware.token)
        assertFalse("A valid acquire receipt must not be treated as a lost receipt", peer.firmware.closed)
    }

    @Test fun aBoundedMainTimeoutConfirmsCancelBeforeTheNextCheckpointAndNeverReplaysTheWrite() = runBlocking {
        val peer = Peer().also { it.holdOperation = 2; it.firmware.delayCancelReads = 2 }
        var failedWrite = false
        peer.client.withTarget(peer.client.refresh()) {
            peer.client.withRpcCheckpoints({
                if (failedWrite) assertTrue("A checkpoint raced queued Cancel", peer.cancelConfirmed)
                peer.client.cached(5)
                2_500L
            }) {
                try { peer.client.transact(2, write); fail("A held receipt exceeded its volume budget") }
                catch (_: IOException) { failedWrite = true }
                assertTrue(peer.cancelConfirmed)
                assertTrue("The 1.5-second reserve was consumed by the main RPC", peer.clock in 1_000L..1_300L)
                peer.client.transact(1, read)
            }
        }
        assertEquals(listOf(2, 1), peer.firmware.rawRequests.map { it.first })
        assertArrayEquals(write, peer.firmware.rawRequests.first().second)
        assertTrue(peer.firmware.events.indexOf("cancelled") < peer.firmware.events.indexOf("raw1"))
        assertEquals(1, peer.firmware.events.count { it == "cancel" })
        assertFalse(peer.firmware.closed)
    }

    @Test fun aCheckpointRpcHasItsOwnOneSecondBudgetAndCannotSendTheMainRequestAfterFailure() = runBlocking {
        val peer = Peer().also { it.holdOperation = 3 }
        var calls = 0
        try {
            peer.client.withRpcCheckpoints({ calls++; peer.client.cached(5); 5_000L }) { peer.client.cached(2) }
            fail("The checkpoint RPC used the generic six-second timeout")
        } catch (_: IOException) { }
        assertEquals(1, calls)
        assertEquals(1_000L, peer.clock)
        assertTrue(peer.cancelConfirmed)
        assertEquals(listOf("cached5", "cancel", "cancelled"), peer.firmware.events)
        peer.client.cached(2)
        assertEquals(1, calls)
        assertFalse(peer.firmware.closed)
    }

    @Test fun successfulAndExceptionalScopesDoNotRetainAHookForTheNextOwner() = runBlocking {
        val peer = Peer()
        var oldCalls = 0
        peer.client.withTarget(peer.client.refresh()) {
            peer.client.withRpcCheckpoints({ oldCalls++; 5_000L }) { peer.client.cached(2) }
            peer.client.cached(3)
        }
        assertEquals(2, oldCalls)
        try {
            peer.client.withRpcCheckpoints({ oldCalls++; 5_000L }) {
                peer.client.cached(2)
                throw IOException("The old owner stopped")
            }
        } catch (_: IOException) { }
        assertEquals(4, oldCalls)
        peer.firmware.generation++
        var newCalls = 0
        peer.client.withTarget(peer.client.refresh()) {
            peer.client.withRpcCheckpoints({ newCalls++; 5_000L }) { peer.client.cached(4) }
            peer.client.cached(3)
        }
        assertEquals(4, oldCalls)
        assertEquals(2, newCalls)
        assertFalse(peer.firmware.closed)
    }

    @Test fun leavingTheCheckpointScopeRestoresTheGenericRpcDeadline() = runBlocking {
        val peer = Peer()
        var calls = 0
        peer.client.withRpcCheckpoints({ calls++; 2_500L }) { peer.client.cached(2) }
        peer.holdOperation = 3
        try { peer.client.cached(3); fail("An unanswered RPC never timed out") }
        catch (_: IOException) { }
        assertEquals(6_000L, peer.clock)
        assertEquals(2, calls)
        assertTrue(peer.cancelConfirmed)
        assertEquals(1, peer.firmware.events.count { it == "cached3" })
        assertFalse(peer.firmware.closed)
    }

    @Test fun aValidReplyReturnedAfterBlockingIoExceedsTheLeaseBudgetIsNotAccepted() = runBlocking {
        val peer = Peer().also { it.advanceCommittedReadMs = 3_000 }
        var checkpoints = 0
        try {
            peer.client.withRpcCheckpoints({ checkpoints++; 2_500L }) { peer.client.cached(2) }
            fail("A late complete report was accepted after the volume lease deadline")
        } catch (_: IOException) { }
        assertEquals(3_000L, peer.clock)
        assertEquals("A timed-out RPC must not run its successful post-checkpoint", 1, checkpoints)
        assertEquals(listOf("cached2", "cancel", "cancelled"), peer.firmware.events)
        assertTrue(peer.cancelConfirmed)
        assertFalse(peer.firmware.closed)
    }

    @Test fun remainingBudgetReachesTheTransportAndCancelGetsAFreshCleanupBudget() = runBlocking {
        val peer = Peer(useBoundedIo = true).also {
            it.advanceCommittedReadMs = 3_000
            it.requireBoundedIo = true
        }
        var checkpoints = 0
        try {
            peer.client.withRpcCheckpoints({ checkpoints++; 2_500L }) { peer.client.cached(2) }
            fail("The bounded transport returned a late result as success")
        } catch (_: IOException) { }
        val main = peer.budgets.filter { it.at < 3_000 }
        assertTrue(main.isNotEmpty())
        assertTrue(main.all { it.remaining in 1L..1_000L })
        assertTrue(main.any { it.operation == 1 && it.report == 3 })
        val cancel = peer.budgets.single { it.opcode == 5 }
        assertEquals(3_000L, cancel.at)
        assertEquals("Expired main budget must not suppress confirmed Cancel", 1_000L, cancel.remaining)
        assertTrue(peer.cancelConfirmed)
        assertEquals(1, checkpoints)
        assertEquals(1, peer.firmware.events.count { it == "cached2" })
        assertFalse(peer.firmware.closed)
    }

    @Test fun recursiveCheckpointRpcsShareOneAggregateSecondRatherThanResettingTheBudget() = runBlocking {
        val peer = Peer(useBoundedIo = true).also { it.advanceCommittedReadMs = 600 }
        var checkpoints = 0
        try {
            peer.client.withRpcCheckpoints({
                checkpoints++
                peer.client.cached(5)
                peer.client.cached(5)
                5_000L
            }) { peer.client.cached(2) }
            fail("Two nested RPCs each received a fresh checkpoint second")
        } catch (_: IOException) { }
        assertEquals(1, checkpoints)
        assertEquals(1_200L, peer.clock)
        val second = peer.budgets.filter { it.at == 600L }
        assertTrue(second.isNotEmpty())
        assertTrue(second.all { it.remaining in 1L..400L })
        assertTrue(peer.cancelConfirmed)
        assertEquals(listOf("cached5", "cached5", "cancel", "cancelled"), peer.firmware.events)
        assertEquals(1_000L, peer.budgets.single { it.opcode == 5 }.remaining)
        assertFalse(peer.firmware.closed)
    }
}
