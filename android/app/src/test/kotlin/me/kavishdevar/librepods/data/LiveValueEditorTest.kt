package me.kavishdevar.librepods.data

import java.util.concurrent.atomic.AtomicReference
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

class LiveValueEditorTest {
    @Test fun sustainedEditsSendDuringTheGestureAndPreserveOnlyTheLatestSuccessor() = runBlocking {
        rig { source, editor, calls ->
            val revision = editor.state.value.revision
            val start = System.nanoTime()
            assertTrue(editor.edit(revision, 1))
            repeat(4) { delay(30); editor.edit(revision, it + 2) }
            val first = withTimeout(2_000) { calls.receive() }
            assertTrue((first.started - start) / 1_000_000 >= 100)
            assertTrue(first.value in 4..5)
            editor.edit(revision, 8); editor.edit(revision, 9)
            assertTrue(calls.tryReceive().isFailure)
            source.set(source.get().copy(value = first.value)); editor.observe(); first.done.complete(true)
            val last = withTimeout(2_000) { calls.receive() }
            assertEquals(9, last.value)
            assertTrue((last.started - first.started) / 1_000_000 >= 100)
            source.set(source.get().copy(value = 9)); editor.observe(); last.done.complete(true)
            withTimeout(2_000) { while (editor.state.value.pending) delay(5) }
            assertNull(editor.state.value.requested)
        }
    }
    @Test fun noHydrationWriteAndAnInvalidCombinedChoiceCancelsTheQueuedValue() = runBlocking {
        rig { _, editor, calls ->
            editor.observe(); delay(200); assertTrue(calls.tryReceive().isFailure)
            val revision = editor.state.value.revision
            editor.edit(revision, 3); editor.edit(revision, null)
            delay(200); assertTrue(calls.tryReceive().isFailure)
        }
    }
    @Test fun writeFailureWinsOverMatchingEchoAndDiscardsTheSuccessor() = runBlocking {
        rig { source, editor, calls ->
            editor.edit(editor.state.value.revision, 1)
            val first = withTimeout(2_000) { calls.receive() }
            editor.edit(editor.state.value.revision, 9)
            source.set(source.get().copy(value = 1)); editor.observe(); first.done.complete(false)
            withTimeout(2_000) { while (editor.state.value.pending) delay(5) }
            assertNull(editor.state.value.requested)
            delay(200); assertTrue(calls.tryReceive().isFailure)
        }
    }
    @Test fun ownerReplacementRetiresTheOldGestureAndLateCompletion() = runBlocking {
        rig { source, editor, calls ->
            val staleRevision = editor.state.value.revision
            editor.edit(staleRevision, 1)
            val first = withTimeout(2_000) { calls.receive() }
            editor.edit(staleRevision, 9)
            source.set(LiveValueSource(Any(), 7, true)); editor.observe()
            assertFalse(editor.edit(staleRevision, 8))
            first.done.complete(true)
            delay(200)
            assertEquals(7, editor.state.value.displayed)
            assertTrue(calls.tryReceive().isFailure)
            editor.edit(editor.state.value.revision, 6)
            val fresh = withTimeout(2_000) { calls.receive() }
            source.set(source.get().copy(value = 6)); fresh.done.complete(true)
        }
    }
    @Test fun matchingAckWhileTemporarilyBusyKeepsTheLatestValueUntilReady() = runBlocking {
        rig { source, editor, calls ->
            editor.edit(editor.state.value.revision, 1)
            val first = withTimeout(2_000) { calls.receive() }
            source.set(source.get().copy(writable = false)); editor.observe()
            assertTrue(editor.edit(editor.state.value.revision, 9))
            source.set(source.get().copy(value = 1)); editor.observe(); first.done.complete(true)
            withTimeout(2_000) { while (editor.state.value.pending) delay(5) }
            assertEquals(9, editor.state.value.requested)
            delay(200); assertTrue(calls.tryReceive().isFailure)
            source.set(source.get().copy(writable = true)); editor.observe()
            val last = withTimeout(2_000) { calls.receive() }
            assertEquals(9, last.value)
            source.set(source.get().copy(value = 9)); last.done.complete(true)
        }
    }
    @Test fun explicitReselectRunsEvenForEqualValuesAndSurvivesAnIdenticalOwnAck() = runBlocking {
        rig { source, editor, calls ->
            editor.edit(editor.state.value.revision, 0, force = true)
            val first = withTimeout(2_000) { calls.receive() }
            editor.edit(editor.state.value.revision, 0, force = true)
            first.done.complete(true)
            val second = withTimeout(2_000) { calls.receive() }
            assertEquals(0, second.value)
            assertEquals(0, source.get().value)
            second.done.complete(true)
        }
    }
    @Test fun cancelWhileBusyDiscardsOnlyTheSuccessorAndNeverReplaysItAfterResume() = runBlocking {
        rig { source, editor, calls ->
            val revision = editor.state.value.revision
            editor.edit(revision, 1)
            val first = withTimeout(2_000) { calls.receive() }
            source.set(source.get().copy(writable = false)); editor.observe()
            editor.edit(revision, 9)
            editor.cancel(revision)
            assertTrue(editor.state.value.pending)
            assertEquals(1, editor.state.value.displayed)
            source.set(source.get().copy(value = 1)); editor.observe(); first.done.complete(true)
            withTimeout(2_000) { while (editor.state.value.pending) delay(5) }
            source.set(source.get().copy(writable = true)); editor.observe()
            delay(200)
            assertNull(editor.state.value.requested)
            assertTrue(calls.tryReceive().isFailure)
        }
    }
    private data class Call(val value: Int, val started: Long = System.nanoTime(), val done: CompletableDeferred<Boolean> = CompletableDeferred())
    private suspend fun rig(test: suspend (AtomicReference<LiveValueSource<Int>>, LiveValueEditor<Int>, Channel<Call>) -> Unit) {
        val source = AtomicReference(LiveValueSource(Any(), 0, true))
        val calls = Channel<Call>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val editor = LiveValueEditor(scope, source::get, { it in 0..100 }, write = { _, value ->
            Call(value).let { calls.send(it); it.done.await() }
        })
        try { test(source, editor, calls) } finally { scope.cancel() }
    }
}
