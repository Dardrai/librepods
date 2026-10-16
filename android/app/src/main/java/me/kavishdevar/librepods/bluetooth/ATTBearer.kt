package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ATT replies omit the handle, so one bearer may have only one outstanding transaction.
internal class ATTBearer(
    private val send: (ByteArray) -> Unit,
    private val close: () -> Unit,
    private val isTransportCurrent: () -> Boolean
) {
    private class Pending(val request: ByteArray, val opcode: Byte) {
        val completed = CountDownLatch(1)
        @Volatile var response: ByteArray? = null
    }

    private val transaction = ReentrantLock()
    private val retired = AtomicBoolean()
    private var pending: Pending? = null
    val isCurrent: Boolean get() = !retired.get() && isTransportCurrent()

    fun command(pdu: ByteArray, canSend: () -> Boolean = { true }): Boolean = transaction.withLock {
        if (!isCurrent || !canSend()) return false
        try {
            send(pdu)
            isCurrent
        } catch (_: Exception) {
            retire()
            false
        }
    }

    fun request(pdu: ByteArray, responseOpcode: Byte, timeoutMillis: Long,
        canSend: () -> Boolean = { true }): ByteArray? = transaction.withLock {
        if (!isCurrent || !canSend()) return null
        val waiting = Pending(pdu.copyOf(), responseOpcode)
        synchronized(this) { pending = waiting }
        try {
            if (!isCurrent || !canSend()) return null
            send(pdu)
            if (!waiting.completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                // A late reply cannot be distinguished from the next request's reply.
                retire()
                return null
            }
            return waiting.response?.takeIf { isCurrent }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            retire()
            return null
        } catch (_: Exception) {
            retire()
            return null
        } finally {
            synchronized(this) { if (pending === waiting) pending = null }
        }
    }

    @Synchronized
    fun receive(pdu: ByteArray) {
        if (!isCurrent || pdu.isEmpty()) return
        val waiting = pending ?: return
        if (waiting.completed.count == 0L) return
        if (pdu[0] == waiting.opcode && (waiting.opcode != 0x13.toByte() || pdu.size == 1)) {
            waiting.response = pdu.copyOf()
            waiting.completed.countDown()
        } else if (pdu.size == 5 && pdu[0] == 0x01.toByte() && pdu[1] == waiting.request[0] &&
            pdu[2] == waiting.request[1] && pdu[3] == waiting.request[2]) {
            waiting.completed.countDown()
        }
    }

    fun retire() {
        if (!retired.compareAndSet(false, true)) return
        synchronized(this) { pending?.completed?.countDown() }
        close()
    }
}
