package me.kavishdevar.librepods.core.bluetooth.att

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class ATTFailure(override val message: String) : Exception(message)

internal fun UUID.attBytes(): ByteArray = ByteArray(16) { index ->
    if (index < 8) (leastSignificantBits ushr (index * 8)).toByte()
    else (mostSignificantBits ushr ((index - 8) * 8)).toByte()
}

internal data class ATTCharacteristic(val handle: Int, val writeOpcode: Int?)

/** Settings service discovery is bounded and never falls back to a firmware-specific handle. */
internal fun discoverATTSettings(exchange: (ByteArray, Int) -> ByteArray): Map<ATTHandle, ATTCharacteristic> {
    val service = UUID.fromString("7798082b-b7b7-45a6-9933-563492efe04e").attBytes()
    val request = byteArrayOf(6, 1, 0, -1, -1, 0, 0x28) + service
    val response = exchange(request, 7)
    if (noATTAttribute(response, request)) return emptyMap()
    if (response.size == 5 && response[0] == 1.toByte()) throw ATTFailure("ATT service rejected: 0x${(response[4].toInt() and 255).toString(16)}")
    if (response.size != 5 || response[0] != 7.toByte()) throw ATTFailure("Invalid ATT service response")
    var start = response.u16(1)
    val end = response.u16(3)
    if (start == 0 || start >= end) throw ATTFailure("Invalid ATT service range")
    val found = mutableMapOf<ATTHandle, ATTCharacteristic>()
    var previousValue = 0
    repeat(16) {
        val pageRequest = byteArrayOf(8, start.toByte(), (start ushr 8).toByte(), end.toByte(), (end ushr 8).toByte(), 3, 0x28)
        val page = exchange(pageRequest, 9)
        if (noATTAttribute(page, pageRequest)) return found
        if (page.size == 5 && page[0] == 1.toByte()) throw ATTFailure("ATT discovery rejected: 0x${(page[4].toInt() and 255).toString(16)}")
        if (page.size < 2 || page[0] != 9.toByte() || page[1].toInt() !in listOf(7, 21))
            throw ATTFailure("Invalid ATT characteristic response")
        val each = page[1].toInt()
        if (page.size == 2 || (page.size - 2) % each != 0) throw ATTFailure("Truncated ATT characteristics")
        var previous = start - 1
        for (offset in 2 until page.size step each) {
            val declaration = page.u16(offset)
            val value = page.u16(offset + 3)
            if (declaration <= previous || declaration <= previousValue || declaration > end || value <= declaration || value > end)
                throw ATTFailure("Invalid ATT characteristic range")
            val setting = if (each == 21) ATTHandle.entries.firstOrNull {
                it.uuid?.attBytes()?.contentEquals(page.copyOfRange(offset + 5, offset + each)) == true
            } else null
            if (setting != null) {
                val properties = page[offset + 2].toInt() and 255
                if (setting in found || properties and 2 == 0) throw ATTFailure("ATT setting is not uniquely readable")
                found[setting] = ATTCharacteristic(value, when {
                    properties and 8 != 0 -> 0x12
                    properties and 4 != 0 -> 0x52
                    else -> null
                })
            }
            previous = declaration
            previousValue = value
        }
        if (previous == end) return found
        start = previous + 1
    }
    throw ATTFailure("ATT discovery exceeded service limit")
}

internal fun noATTAttribute(response: ByteArray, request: ByteArray): Boolean =
    request.size >= 5 && response.size == 5 && response[0] == 1.toByte() && response[1] == request[0] &&
        response[4] == 0x0a.toByte() && request[0] in byteArrayOf(6, 8) && request.u16(1) != 0 &&
        response.u16(2) in request.u16(1)..request.u16(3)

internal fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)

/** ATT read/write replies omit the handle. A timeout retires the entire bearer before another request. */
internal class ATTBearer(
    private val mtu: Int,
    private val send: (ByteArray) -> Unit,
    private val close: () -> Unit,
    private val current: () -> Boolean
) {
    private class Pending(val request: ByteArray, val opcode: Int) {
        val done = CountDownLatch(1)
        var response: ByteArray? = null
    }
    private val lock = ReentrantLock()
    private val retired = AtomicBoolean()
    private var pending: Pending? = null
    val isCurrent: Boolean get() = !retired.get() && current()

    fun command(pdu: ByteArray, canSend: () -> Boolean): Boolean = lock.withLock {
        if (!isCurrent || pdu.size < 3 || pdu[0] != 0x52.toByte() || pdu.u16(1) == 0 || pdu.size > mtu || !canSend()) return false
        try { send(pdu.copyOf()); isCurrent && canSend() }
        catch (_: Exception) { retire(); false }
    }

    fun request(pdu: ByteArray, opcode: Int, timeoutMillis: Long = 2000, canSend: () -> Boolean = { true }): ByteArray = lock.withLock {
        val validRequest = when (pdu.firstOrNull()?.toInt()) {
            6 -> pdu.size == 23 && opcode == 7
            8 -> pdu.size == 7 && opcode == 9
            0x0a -> pdu.size == 3 && pdu.u16(1) != 0 && opcode == 0x0b
            0x12 -> pdu.size >= 3 && pdu.u16(1) != 0 && opcode == 0x13
            else -> false
        }
        if (!isCurrent || !validRequest || pdu.size > mtu || timeoutMillis !in 1..2000 || !canSend()) throw ATTFailure("ATT session unavailable")
        val waiting = Pending(pdu.copyOf(), opcode)
        synchronized(this) { pending = waiting }
        try {
            if (!isCurrent || !canSend()) throw ATTFailure("ATT request retired")
            send(pdu.copyOf())
            if (!waiting.done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                retire()
                throw ATTFailure("ATT response timed out")
            }
            if (!isCurrent || !canSend()) throw ATTFailure("ATT request retired")
            waiting.response?.copyOf() ?: throw ATTFailure("ATT response unavailable")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            retire()
            throw ATTFailure("ATT request cancelled")
        } finally {
            synchronized(this) { if (pending === waiting) pending = null }
        }
    }

    @Synchronized fun receive(pdu: ByteArray) {
        if (!isCurrent) return
        if (pdu.isEmpty() || pdu.size > mtu) { retire(); return }
        // No subscriptions are installed. Unsolicited notifications cannot satisfy a request.
        if (pdu[0] == 0x1b.toByte() && pdu.size >= 3) return
        val waiting = pending ?: run { retire(); return }
        if (waiting.done.count == 0L) return
        val expected = (pdu[0].toInt() and 255) == waiting.opcode && (waiting.opcode != 0x13 || pdu.size == 1)
        val error = pdu.size == 5 && pdu[0] == 1.toByte() && pdu[1] == waiting.request[0] &&
            if (waiting.request[0] in byteArrayOf(6, 8)) pdu.u16(2) in waiting.request.u16(1)..waiting.request.u16(3)
            else waiting.request.size >= 3 && pdu.u16(2) == waiting.request.u16(1)
        if (!expected && !error) { retire(); return }
        waiting.response = pdu.copyOf()
        waiting.done.countDown()
    }

    fun retire() {
        if (!retired.compareAndSet(false, true)) return
        synchronized(this) { pending?.done?.countDown() }
        close()
    }
}
