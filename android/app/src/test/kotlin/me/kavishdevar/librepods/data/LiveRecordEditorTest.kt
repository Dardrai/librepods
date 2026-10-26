package me.kavishdevar.librepods.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LiveRecordEditorTest {
    @Test fun throttlesAt150msAndPreservesTheLatestMultiFieldIntentAcrossItsOwnAck() = runBlocking {
        fixture { rig ->
            val start = System.nanoTime()
            assertTrue(rig.edit(0, 1))
            delay(30)
            assertTrue(rig.calls.tryReceive().isFailure)
            assertTrue(rig.edit(0, 2))
            val first = rig.next()
            assertTrue((first.started - start) / 1_000_000 >= 100)
            assertArrayEquals(record(2), first.value)
            assertTrue(rig.edit(1, 4))
            assertTrue(rig.edit(2, 5))
            assertEquals(1, rig.active.get())
            rig.finish(first)
            val second = rig.next()
            assertTrue((second.started - first.started) / 1_000_000 >= 100)
            assertArrayEquals(first.value, second.expected)
            assertArrayEquals(record(2, 4, 5), second.value)
            assertEquals(1, rig.maximum.get())
            rig.finish(second)
            rig.settled()
            assertArrayEquals(record(2, 4, 5), rig.editor.state.value.data)
        }
    }

    @Test fun sameValuedClonePollingNeverResetsAnUnsentOrInFlightDraft() = runBlocking {
        fixture { rig ->
            val revision = rig.editor.state.value.revision
            rig.edit(0, 1)
            repeat(5) { rig.clonePoll() }
            assertEquals(revision, rig.editor.state.value.revision)
            assertArrayEquals(record(1), rig.editor.state.value.displayed)
            val first = rig.next()
            rig.edit(1, 2)
            repeat(5) { rig.clonePoll() }
            assertArrayEquals(record(1, 2), rig.editor.state.value.displayed)
            rig.finish(first)
            val second = rig.next()
            assertArrayEquals(record(1, 2), second.value)
            rig.finish(second)
            rig.settled()
        }
    }

    @Test fun temporaryBusyOwnWriteAcceptsNewIntentAndWaitsUntilReadyAfterAck() = runBlocking {
        fixture { rig ->
            rig.edit(0, 1)
            val first = rig.next()
            rig.source.set(rig.source.get().copy(writable = false))
            rig.editor.observe()
            assertTrue(rig.edit(1, 2))
            rig.finish(first, writable = false)
            withTimeout(2_000) { while (rig.editor.state.value.pending) delay(5) }
            assertArrayEquals(record(1, 2), rig.editor.state.value.requested)
            delay(200)
            assertTrue(rig.calls.tryReceive().isFailure)
            rig.source.set(rig.source.get().copy(writable = true))
            rig.editor.observe()
            val second = rig.next()
            assertArrayEquals(record(1), second.expected)
            assertArrayEquals(record(1, 2), second.value)
            rig.finish(second)
            rig.settled()
        }
    }

    @Test fun failedCallbackWinsEvenWhenTheReceivedRecordMatchesAndNeverReplaysQueuedIntent() = runBlocking {
        fixture { rig ->
            rig.edit(0, 1)
            val first = rig.next()
            rig.edit(1, 2)
            rig.finish(first, success = false)
            rig.settled()
            assertArrayEquals(record(1), rig.editor.state.value.data)
            assertNull(rig.editor.state.value.requested)
            delay(200)
            assertTrue(rig.calls.tryReceive().isFailure)
        }
    }

    @Test fun foreignOwnerOrRecordRetiresTheOldGestureAndItsCompletionCannotChangeNewState() = runBlocking {
        for (ownerChanged in listOf(true, false)) fixture { rig ->
            rig.edit(0, 1)
            val first = rig.next()
            rig.edit(1, 2)
            val foreign = LiveRecordSource(if (ownerChanged) Any() else rig.source.get().owner, record(8), true)
            rig.source.set(foreign)
            rig.editor.observe()
            val revision = rig.editor.state.value.revision
            assertNull(rig.editor.state.value.requested)
            first.result.complete(false)
            delay(200)
            assertEquals(revision, rig.editor.state.value.revision)
            assertArrayEquals(record(8), rig.editor.state.value.displayed)
            assertTrue(rig.calls.tryReceive().isFailure)
        }
    }

    @Test fun oldCompletionCannotClearAnIdenticalSuccessorAfterSameOwnerReset() = runBlocking {
        fixture { rig ->
            rig.edit(0, 1)
            val retired = rig.next()
            rig.source.set(rig.source.get().copy(data = record(9)))
            rig.editor.observe()
            rig.source.set(rig.source.get().copy(data = record()))
            rig.editor.observe()
            val revision = rig.editor.state.value.revision
            assertTrue(rig.edit(0, 1))
            retired.result.complete(false)
            val successor = rig.next()
            assertEquals(revision, rig.editor.state.value.revision)
            assertArrayEquals(record(), successor.expected)
            assertArrayEquals(retired.value, successor.value)
            rig.finish(successor)
            rig.settled()
        }
    }

    @Test fun canceledQueuedOrPendingChangesNeverSendTheirFinalStaleValue() = runBlocking {
        fixture { rig ->
            rig.edit(0, 1)
            rig.editor.cancel(rig.editor.state.value.revision)
            delay(200)
            assertTrue(rig.calls.tryReceive().isFailure)
            assertArrayEquals(record(), rig.editor.state.value.displayed)
            rig.edit(0, 2)
            val first = rig.next()
            rig.edit(1, 3)
            rig.editor.cancel(rig.editor.state.value.revision)
            rig.finish(first)
            rig.settled()
            delay(200)
            assertTrue(rig.calls.tryReceive().isFailure)
            assertArrayEquals(record(2), rig.editor.state.value.displayed)
        }
    }

    @Test fun staleRevisionInvalidTransformAndReadOnlySourcesCannotEnqueueWrites() = runBlocking {
        fixture { rig ->
            assertFalse(rig.editor.edit(1) { record(1) })
            assertFalse(rig.editor.edit(0) { byteArrayOf() })
            rig.source.set(rig.source.get().copy(writable = false))
            rig.editor.observe()
            assertFalse(rig.edit(0, 1))
            delay(200)
            assertTrue(rig.calls.tryReceive().isFailure)
            assertNull(rig.editor.state.value.requested)
        }
    }

    private class Call(val owner: Any?, val expected: ByteArray, val value: ByteArray,
        val started: Long = System.nanoTime(), val result: CompletableDeferred<Boolean> = CompletableDeferred())

    private class Rig(scope: CoroutineScope) {
        val source = AtomicReference(LiveRecordSource(Any(), record(), true))
        val calls = Channel<Call>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val editor = LiveRecordEditor(scope, source::get,
            valid = { it.size == 3 && it.all { byte -> byte.toInt() in 0..100 } },
            matches = { actual, target -> actual.contentEquals(target) },
            write = { owner, expected, value ->
                val count = active.incrementAndGet()
                maximum.accumulateAndGet(count) { previous, current -> maxOf(previous, current) }
                val call = Call(owner, expected.copyOf(), value.copyOf())
                calls.send(call)
                try { call.result.await() } finally { active.decrementAndGet() }
            })

        fun edit(index: Int, value: Int) = editor.edit(editor.state.value.revision) { it.apply { this[index] = value.toByte() } }
        fun clonePoll() { source.set(source.get().copy(data = source.get().data.copyOf())); editor.observe() }
        suspend fun next() = withTimeout(2_000) { calls.receive() }
        fun finish(call: Call, success: Boolean = true, writable: Boolean = true) {
            source.set(LiveRecordSource(call.owner, call.value.copyOf(), writable))
            call.result.complete(success)
        }
        suspend fun settled() = withTimeout(2_000) {
            while (editor.state.value.pending || editor.state.value.requested != null) delay(5)
        }
    }

    private suspend fun fixture(block: suspend (Rig) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(Rig(scope)) } finally { scope.cancel() }
    }

    companion object {
        private fun record(first: Int = 0, second: Int = 0, third: Int = 0) = byteArrayOf(first.toByte(), second.toByte(), third.toByte())
    }
}
