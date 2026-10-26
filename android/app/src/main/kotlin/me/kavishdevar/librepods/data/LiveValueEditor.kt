package me.kavishdevar.librepods.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LiveValueSource<T>(val owner: Any?, val value: T?, val writable: Boolean)
internal data class LiveValueState<T>(val value: T?, val requested: T? = null, val revision: Long = 0,
    val pending: Boolean = false) {
    val displayed: T? get() = requested ?: value
}

/** Ordinary settings keep one submitted value and one replaceable next choice. */
internal class LiveValueEditor<T : Any>(scope: CoroutineScope, private val read: () -> LiveValueSource<T>,
    private val valid: (T) -> Boolean, private val write: suspend (Any, T) -> Boolean,
    private val intervalMs: Long = 150, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val equivalent: (T, T) -> Boolean = { a, b -> a == b }) {
    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var source = read()
    private var desired: T? = null
    private var desiredForced = false
    private var inFlight: T? = null
    private var sequence = 0L
    private var revision = 0L
    private val mutableState = MutableStateFlow(LiveValueState(source.value))
    val state = mutableState.asStateFlow()

    init { scope.launch {
        for (ignored in signal) {
        var lastStart: Long? = null
        while (true) {
            if (synchronized(lock) { desired == null }) break
            delay(lastStart?.let { (intervalMs - (now() - it)).coerceAtLeast(0) } ?: intervalMs)
            val work = synchronized(lock) {
                observeLocked(read())
                desired?.takeIf { source.writable && source.owner != null }?.let {
                    inFlight = it
                    publish()
                    Work(checkNotNull(source.owner), revision, sequence, it)
                }
            } ?: break
            lastStart = now()
            val success = try { write(work.owner, work.value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            synchronized(lock) {
                val actual = read()
                if (source.owner == work.owner && revision == work.revision && inFlight == work.value) {
                    if (success && actual.owner == work.owner && same(actual.value, work.value)) {
                        source = actual
                        if (sequence == work.sequence || !desiredForced && same(desired, actual.value)) { desired = null; desiredForced = false }
                        inFlight = null
                        publish()
                    } else reset(actual)
                }
            }
        }
    } } }

    fun observe() = synchronized(lock) { observeLocked(read()) }
    fun edit(expectedRevision: Long, value: T?, force: Boolean = false): Boolean = synchronized(lock) {
        observeLocked(read())
        if (expectedRevision != revision || source.owner == null || !source.writable && inFlight == null && desired == null) return false
        if (value == null || !valid(value)) { desired = null; desiredForced = false; sequence++; publish(); return false }
        desired = value.takeUnless { !force && it == (inFlight ?: source.value) }
        desiredForced = force && desired != null
        sequence++
        publish()
        if (desired != null) signal.trySend(Unit)
        true
    }
    fun flush() { signal.trySend(Unit) }
    fun cancel(expectedRevision: Long) = synchronized(lock) {
        if (expectedRevision != revision) return@synchronized
        desired = null; desiredForced = false; sequence++; publish()
    }
    private fun observeLocked(next: LiveValueSource<T>) {
        if (next.owner != source.owner ||
            !same(next.value, source.value) && !same(next.value, inFlight)) reset(next)
        else { source = next; publish() }
        if (source.writable && desired != null) signal.trySend(Unit)
    }
    private fun reset(next: LiveValueSource<T>) {
        source = next; desired = null; desiredForced = false; inFlight = null; sequence++; revision++; publish()
    }
    private fun publish() { mutableState.value = LiveValueState(source.value, desired ?: inFlight, revision, inFlight != null) }
    private fun same(a: T?, b: T?): Boolean = if (a == null || b == null) a == b else equivalent(a, b)
    private data class Work<T>(val owner: Any, val revision: Long, val sequence: Long, val value: T)
}
