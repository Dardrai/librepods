package me.kavishdevar.librepods.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LiveRecordSource(val owner: Any?, val data: ByteArray, val writable: Boolean)
internal data class LiveRecordState(val data: ByteArray = byteArrayOf(), val requested: ByteArray? = null,
    val revision: Long = 0, val pending: Boolean = false) {
    val displayed: ByteArray get() = requested ?: data
}

/** One in-flight full record and one latest intent; received data is never synthesized. */
internal class LiveRecordEditor(scope: CoroutineScope, private val read: () -> LiveRecordSource,
    private val valid: (ByteArray) -> Boolean, private val matches: (ByteArray, ByteArray) -> Boolean,
    private val write: suspend (Any?, ByteArray, ByteArray) -> Boolean, private val intervalMs: Long = 150) {
    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var source = read().snapshot()
    private var confirmed = source.data
    private var desired: ByteArray? = null
    private var inFlight: ByteArray? = null
    private var sequence = 0L
    private var revision = 0L
    private val mutableState = MutableStateFlow(LiveRecordState(source.data.copyOf()))
    val state = mutableState.asStateFlow()

    init { scope.launch {
        for (ignored in signal) {
            var lastStart = 0L
            while (true) {
                val waiting = synchronized(lock) { desired != null }
                if (!waiting) break
                val elapsed = if (lastStart == 0L) 0L else (System.nanoTime() - lastStart) / 1_000_000
                delay((intervalMs - elapsed).coerceAtLeast(0))
                val work = synchronized(lock) {
                    observeLocked(read().snapshot())
                    desired?.takeIf { source.writable && source.owner != null }?.let {
                        inFlight = it.copyOf()
                        publish()
                        Work(source.owner, revision, sequence, confirmed.copyOf(), it.copyOf())
                    }
                } ?: break
                lastStart = System.nanoTime()
                val success = try { write(work.owner, work.expected, work.value) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                synchronized(lock) {
                    val actual = read().snapshot()
                    if (work.owner == source.owner && work.revision == revision && inFlight?.let { matches(it, work.value) } == true) {
                        if (success && actual.owner == work.owner && matches(actual.data, work.value)) {
                            source = actual
                            confirmed = actual.data
                            if (sequence == work.sequence || desired?.let { matches(actual.data, it) } == true) desired = null
                            inFlight = null
                            publish()
                        } else reset(actual)
                    }
                }
            }
        }
    } }

    fun observe() = synchronized(lock) { observeLocked(read().snapshot()) }

    fun flush() = synchronized(lock) { if (desired != null) signal.trySend(Unit) }

    fun cancel(expectedRevision: Long) = synchronized(lock) {
        if (expectedRevision != revision) return@synchronized
        desired = inFlight?.copyOf()
        sequence++
        publish()
    }

    fun edit(expectedRevision: Long, transform: (ByteArray) -> ByteArray?): Boolean = synchronized(lock) {
        observeLocked(read().snapshot())
        if (expectedRevision != revision || source.owner == null || !source.writable && inFlight == null || !valid(confirmed)) return false
        val next = transform((desired ?: confirmed).copyOf())?.copyOf() ?: return false
        if (!valid(next)) return false
        if (matches(desired ?: confirmed, next)) return true
        desired = next
        sequence++
        publish()
        signal.trySend(Unit)
        true
    }

    private fun observeLocked(next: LiveRecordSource) {
        val ownReply = inFlight?.let { matches(next.data, it) } == true
        val becameWritable = !source.writable && next.writable
        if (next.owner != source.owner ||
            !matches(next.data, confirmed) && !ownReply) reset(next)
        else {
            source = next
            if (desired == null && inFlight == null) confirmed = next.data
            publish()
            if (becameWritable && desired != null) signal.trySend(Unit)
        }
    }

    private fun reset(next: LiveRecordSource) {
        source = next
        confirmed = next.data
        desired = null
        inFlight = null
        revision++
        publish()
    }

    private fun publish() {
        val previous = mutableState.value
        if (previous.revision == revision && previous.pending == (inFlight != null) &&
            previous.data.contentEquals(source.data) && previous.requested.contentEquals(desired)) return
        mutableState.value = LiveRecordState(source.data.copyOf(), desired?.copyOf(), revision, inFlight != null)
    }
    private fun LiveRecordSource.snapshot() = copy(data = data.copyOf())
    private data class Work(val owner: Any?, val revision: Long, val sequence: Long,
        val expected: ByteArray, val value: ByteArray)
}
