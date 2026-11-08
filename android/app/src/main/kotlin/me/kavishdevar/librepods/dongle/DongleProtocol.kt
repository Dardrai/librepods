package me.kavishdevar.librepods.dongle

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class DongleBusyException : IOException("Dongle controls are busy")
internal class DongleRequestException(message: String) : IOException(message)

internal object DongleProtocol {
    const val UUID = "c6c4f8a0-6d47-4bd1-9b6a-415044470001"
    fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i ->
        value or ((bytes[at + i].toLong() and 255) shl (8 * i))
    }
    fun put32(bytes: ByteArray, at: Int, value: Long) {
        require(value in 0..0xffff_ffffL)
        repeat(4) { bytes[at + it] = (value shr (8 * it)).toByte() }
    }
    fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    fun header(report: Int) = byteArrayOf(report.toByte(), 65, 80, 68, 71, 1, 64) + ByteArray(57)
    fun checkReport(bytes: ByteArray, id: Int) {
        require(bytes.size == 64 && bytes.take(7) == header(id).take(7)) { "Unsupported dongle report" }
    }

    /** RFCOMM is a stream; no read is assumed to preserve a packet boundary. */
    fun exchange(input: InputStream, output: OutputStream, operation: Int, id: Int, payload: ByteArray): ByteArray {
        require(id in 2..3 && operation in 1..2 && payload.size == if (operation == 1) 0 else 64)
        output.write(byteArrayOf(65, 80, 68, 71, 1, operation.toByte(), id.toByte(), payload.size.toByte()))
        output.write(payload)
        output.flush()
        fun read(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var at = 0
            while (at < count) {
                val received = input.read(bytes, at, count - at)
                if (received <= 0) throw IOException("Dongle connection ended")
                at += received
            }
            return bytes
        }
        val frame = read(8)
        if (frame.take(5) != listOf<Byte>(65, 80, 68, 71, 1) || frame[6].toInt() != id)
            throw IOException("Unsupported dongle response")
        val length = frame[7].toInt() and 255
        if (frame[5] == 0xff.toByte() && length == 1) {
            val code = read(1)[0].toInt()
            if (code == 2) throw DongleBusyException()
            throw DongleRequestException(when (code) { 3 -> "Dongle controls are unavailable"; else -> "Dongle rejected the request" })
        }
        if ((frame[5].toInt() and 255) != (operation or 0x80) || length != 64)
            throw IOException("Unsupported dongle response")
        return read(length).also { checkReport(it, id) }
    }

    data class Status(val generation: Long, val address: List<Int>, val flags: Int, val anc: Int,
        val batteries: List<Int?>, val codec: Int, val capabilities: Int,
        val batteryAgeMs: List<Long?> = List(3) { null }, val batteryPresent: Int = 0,
        val batteryCaseSource: Int = 0, val charging: Int = 0,
        val lastSequence: Long = 0, val lastResult: Int = 0, val lastOpcode: Int = 0) {
        fun sameConnection(other: Status) = generation == other.generation && address == other.address
        val connected get() = flags and 1 != 0
        val controlsReady get() = flags and 2 != 0
        val microphoneActive get() = flags and 16 != 0
        fun batteryLive(index: Int, elapsedMs: Long = 0): Boolean {
            val age = batteryAgeMs.getOrNull(index) ?: return false
            return index in 0..2 && batteries.getOrNull(index) != null &&
                batteryPresent and (1 shl index) != 0 && age < 90_000 &&
                elapsedMs >= 0 && elapsedMs < 90_000 - age
        }
        fun batteryCharging(index: Int, elapsedMs: Long = 0) =
            batteryLive(index, elapsedMs) && charging and (1 shl index) != 0
    }
    fun status(bytes: ByteArray): Status {
        checkReport(bytes, 2)
        require(bytes[7].toInt() and 0x80 == 0 && bytes[18].toInt() in 0..4 &&
            bytes[22].toInt() and 0xf8 == 0 && bytes[23].toInt() in 0..3 &&
            bytes[40].toInt() in 0..6 && bytes[41].toInt() in 0..4 && bytes.drop(46).all { it == 0.toByte() })
        val levels = bytes.sliceArray(19..21).map { it.toInt() and 255 }
        require(levels.all { it <= 100 || it == 255 })
        val ages = listOf(24, 28, 32).map { u32(bytes, it).takeUnless { age -> age == 0xffff_ffffL } }
        val capabilities = u16(bytes, 42)
        val observations = capabilities and 64 != 0
        val present = if (observations) bytes[44].toInt() and 255 else
            levels.indices.fold(0) { mask, index -> if (levels[index] <= 100 && ages[index] != null) mask or (1 shl index) else mask }
        val caseSource = bytes[45].toInt() and 255
        require(if (observations) present and 0xf8 == 0 && caseSource and 0xf8 == 0 &&
            bytes[22].toInt() and present.inv() == 0 &&
            levels.indices.all { present and (1 shl it) == 0 || levels[it] <= 100 && ages[it] != null }
            else bytes[44] == 0.toByte() && bytes[45] == 0.toByte())
        return Status(u32(bytes, 8), bytes.sliceArray(12..17).map { it.toInt() and 255 },
            bytes[7].toInt() and 255, bytes[18].toInt(), levels.map { it.takeIf { it <= 100 } },
            bytes[23].toInt(), capabilities, ages, present, caseSource, bytes[22].toInt(),
            u32(bytes, 36), bytes[40].toInt(), bytes[41].toInt())
    }
    data class Setting(val value: Long, val ageMs: Long, val revision: Long)
    data class PhysicalVolume(val confirmed: Int?, val desired: Int?, val observationRevision: Long,
        val pending: Boolean, val inflight: Boolean, val authorityActive: Boolean, val ownToken: Long?,
        val remainingMs: Long, val usbGeneration: Long, val usbScopeReady: Boolean)
    fun physicalVolume(bytes: ByteArray): PhysicalVolume {
        require(bytes.size == 24 && bytes[0] == 1.toByte() && bytes[1].toInt() and 0xe0 == 0 && u32(bytes, 20) == 0L)
        val flags = bytes[1].toInt()
        val confirmed = (bytes[2].toInt() and 255).takeUnless { it == 255 }
        val desired = (bytes[3].toInt() and 255).takeUnless { it == 255 }
        val active = flags and 8 != 0
        val token = u32(bytes, 8).takeUnless { it == 0L }
        val ttl = u32(bytes, 12)
        val usbGeneration = u32(bytes, 16)
        require(confirmed == null || confirmed in 0..127)
        require(desired == null || desired in 0..127)
        require((confirmed != null) == (flags and 1 != 0) && (desired != null) == (flags and 6 != 0))
        require(if (active) ttl in 1..5_000 && flags and 16 != 0 && usbGeneration and 1L != 0L else token == null && ttl == 0L)
        return PhysicalVolume(confirmed, desired, u32(bytes, 4), flags and 2 != 0, flags and 4 != 0,
            active, token, ttl, usbGeneration, flags and 16 != 0)
    }

    fun volumeAuthorityRequest(action: Int, token: Long = 0, level: Int = 0): ByteArray {
        require(action in 0..3 && token in 0..0xffff_ffffL && level in 0..127 &&
            (action == 3 || level == 0) && (if (action == 0) token == 0L else token != 0L))
        return ByteArray(7).also { it[0] = 17; it[1] = action.toByte(); put32(it, 2, token); it[6] = level.toByte() }
    }
    fun settings(bytes: ByteArray): Map<Int, Setting> {
        require(bytes.size >= 2 && bytes[0] == 1.toByte()) { "Unsupported settings response" }
        val count = bytes[1].toInt() and 255
        require(count <= 32 && bytes.size == 2 + count * 13)
        val result = linkedMapOf<Int, Setting>()
        repeat(count) { index ->
            val at = 2 + index * 13
            val id = bytes[at].toInt() and 255
            require(id !in result && u32(bytes, at + 5) != 0L)
            result[id] = Setting(u32(bytes, at + 9), u32(bytes, at + 1), u32(bytes, at + 5))
        }
        return result
    }
    data class Cached(val ageMs: Long, val revision: Long, val packet: ByteArray)
    fun cached(bytes: ByteArray): Cached? {
        if (bytes.isEmpty()) return null
        require(bytes.size >= 9 && u32(bytes, 4) != 0L)
        return Cached(u32(bytes, 0), u32(bytes, 4), bytes.copyOfRange(8, bytes.size))
    }
    fun ears(bytes: ByteArray): Boolean? {
        if (bytes.size != 8 || bytes.take(6) != listOf<Byte>(4, 0, 4, 0, 6, 0) ||
            bytes[6].toInt() !in 0..3 || bytes[7].toInt() !in 0..3) return null
        val first = bytes[6].toInt()
        val second = bytes[7].toInt()
        return (first == 0 || second == 0) && (first == 0 || first == 2) && (second == 0 || second == 2)
    }
    data class AudioProfile(val requestedCodec: Int, val actualCodec: Int, val rateMode: Int,
        val requestedBitrate: Long, val actualBitrate: Long, val gameRequested: Boolean,
        val gameState: Int, val input: Int, val flags: Int, val delayTenthsMs: Int?)
    fun audio(bytes: ByteArray): AudioProfile {
        require(bytes.size == 20 && bytes[0] == 1.toByte() && bytes[1].toInt() in 0..3 &&
            bytes[2].toInt() in 0..3 && bytes[3].toInt() in 0..1 && bytes[12].toInt() in 0..1 &&
            bytes[13].toInt() in 0..6 && bytes[14].toInt() in 0..2 && bytes[15].toInt() and 0xf0 == 0 &&
            u16(bytes, 18) == 100) { "Unsupported audio profile response" }
        return AudioProfile(bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt(), u32(bytes, 4), u32(bytes, 8),
            bytes[12] == 1.toByte(), bytes[13].toInt(), bytes[14].toInt(), bytes[15].toInt(),
            u16(bytes, 16).takeIf { it != 65535 })
    }
    data class Performance(val input: Int, val codec: Int, val flags: Int, val fields: List<Long>)
    fun performance(bytes: ByteArray): Performance {
        require(bytes.size == 64 && bytes[0] == 1.toByte() && bytes[1].toInt() in 0..2 &&
            bytes[2].toInt() in 0..3 && bytes[3].toInt() and 0xf0 == 0)
        val fields = (4..60 step 4).map { u32(bytes, it) }
        fun within(value: Int, capacity: Int) = fields[value] == 0xffff_ffffL ||
            fields[capacity] == 0xffff_ffffL || fields[value] <= fields[capacity]
        require(within(1, 2) && within(3, 2) && within(12, 11) && within(13, 11))
        return Performance(bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt(), fields)
    }
    data class Nearby(val state: Int, val identityAvailable: Boolean, val rssi: Int?, val trend: Int, val ageMs: Long?,
        val reports: Long, val pauseFlags: Long, val session: Long, val smoothedRssi: Double?)
    fun nearby(bytes: ByteArray): Nearby {
        require(bytes.size == 24 && bytes[0] == 1.toByte() && (bytes[1].toInt() and 0x7f) in 0..4 && bytes[3].toInt() in 0..3)
        val rssi = bytes[2].toInt().takeIf { it != 127 }
        val smooth = u32(bytes, 20).toInt().takeIf { it != Int.MIN_VALUE }?.div(256.0)
        val identity = bytes[1].toInt() and 0x80 != 0
        val state = bytes[1].toInt() and 0x7f
        require((state == 0 || identity) && (identity || rssi == null && smooth == null) &&
            (rssi == null || rssi in -126..20) && (smooth == null || smooth in -126.0..20.0))
        return Nearby(bytes[1].toInt() and 0x7f, bytes[1].toInt() and 0x80 != 0, rssi, bytes[3].toInt(), u32(bytes, 4).takeIf { it != 0xffff_ffffL },
            u32(bytes, 8), u32(bytes, 12), u32(bytes, 16), smooth)
    }
}

/** One caller serializes this session. A failed write is never automatically retried. */
internal class DongleSession(private val io: suspend (Int, Int, ByteArray) -> ByteArray,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend () -> Unit = { delay(40) },
    val controlEpoch: Long = 0L,
    private val closeTransport: () -> Unit = {}, private val resumeSequences: Boolean = false,
    private val boundedIo: (suspend (Int, Int, ByteArray, Long) -> ByteArray)? = null) {
    private var sequence = 0L
    private var actionTarget: DongleProtocol.Status? = null
    private var leaseAttempted = false
    private var leaseFailed = false
    private var leaseToken: Long? = null
    private var transportBroken = false
    private var rpcCheckpoint: (suspend () -> Long?)? = null
    private var checkingVolume = false
    private var volumeDeadline: Long? = null
    private var rpcIoDeadline: Long? = null
    private var checkpointIoDeadline: Long? = null
    private var cleanupIoDeadline: Long? = null
    var status: DongleProtocol.Status? = null
        private set

    /** The caller still owns the whole workflow. Checkpoints run only between complete RPCs. */
    suspend fun <T> withRpcCheckpoints(checkpoint: suspend () -> Long?, block: suspend () -> T): T {
        check(rpcCheckpoint == null)
        rpcCheckpoint = checkpoint
        return try { block() } finally { rpcCheckpoint = null; volumeDeadline = null }
    }

    private suspend fun checkpointVolume() {
        val checkpoint = rpcCheckpoint ?: return
        if (checkingVolume) return
        checkingVolume = true
        checkpointIoDeadline = now() + 1_000
        try {
            // The coordinator returns remaining time, independently of this session's clock.
            volumeDeadline = checkpoint()?.let { remaining ->
                check(now() < checkNotNull(checkpointIoDeadline)) { "Volume checkpoint exceeded its budget" }
                check(remaining > 1_600) { "Not enough volume lease remains for settings" }
                now() + remaining - 1_500 // Reserve confirmed Cancel and a subsequent renewal.
            }
        } finally { checkingVolume = false; checkpointIoDeadline = null }
    }

    private suspend fun exchangeIo(operation: Int, report: Int, data: ByteArray): ByteArray {
        val end = cleanupIoDeadline ?: minOf(rpcIoDeadline ?: Long.MAX_VALUE,
            if (checkingVolume) checkpointIoDeadline ?: Long.MAX_VALUE else volumeDeadline ?: Long.MAX_VALUE)
        if (end == Long.MAX_VALUE) return io(operation, report, data)
        val remaining = end - now()
        if (remaining <= 0) throw IOException("Dongle I/O exceeded its volume lease budget")
        val result = if (boundedIo != null) boundedIo.invoke(operation, report, data, remaining)
            else withTimeout(remaining) { io(operation, report, data) }
        if (now() >= end) throw IOException("Dongle I/O exceeded its volume lease budget")
        return result
    }
    suspend fun refresh(): DongleProtocol.Status {
        check(!transportBroken) { "Reconnect the dongle before another request" }
        return DongleProtocol.status(exchangeIo(1, 2, byteArrayOf())).also { current ->
            check(actionTarget?.sameConnection(current) != false) { "AirPods connection changed; read the settings again" }
            status = current
        }
    }

    suspend fun command(opcode: Int): DongleProtocol.Status {
        require(opcode in listOf(1, 2, 4))
        val target = refresh()
        check(target.capabilities and (if (opcode == 4) 4 else 1) != 0) { "This dongle does not support pairing controls" }
        if (opcode != 1 && target.flags and (4 or 8 or 16 or 64) != 0)
            throw DongleRequestException("Pause playback, stop microphone use and disconnect the phone relay before pairing.")
        val token = ((target.lastSequence + 1) and 0xffff_ffffL).takeIf { it != 0L } ?: 1L
        val request = DongleProtocol.header(2).also {
            it[7] = opcode.toByte(); DongleProtocol.put32(it, 8, target.generation)
            target.address.forEachIndexed { index, value -> it[12 + index] = value.toByte() }
            DongleProtocol.put32(it, 18, token)
        }
        exchangeIo(2, 2, request)
        val deadline = now() + 2_000
        while (now() < deadline) {
            val current = refresh()
            if (current.lastSequence == token && current.lastOpcode == opcode) {
                if (current.lastResult == 2) {
                    if (opcode == 2) throw DongleRequestException("Pairing may have changed the saved AirPods. Check the dongle status before trying again.")
                    throw DongleBusyException()
                }
                check(current.lastResult == 1) { "The dongle did not accept the request" }
                return current
            }
            wait()
        }
        throw IOException("Dongle request timed out; it was not retried")
    }

    suspend fun <T> withTarget(target: DongleProtocol.Status, block: suspend () -> T): T {
        check(actionTarget == null)
        actionTarget = target
        return try { refresh(); block() } finally {
            try {
                leaseToken?.takeUnless { transportBroken }?.let { token -> cleanup {
                    val current = DongleProtocol.status(exchangeIo(1, 2, byteArrayOf()))
                    if (current.sameConnection(target))
                        check(transactRpc(7, leaseRequest(2, token), current).isEmpty())
                } }
            } finally {
                actionTarget = null
                leaseToken = null
                leaseAttempted = false
                leaseFailed = false
            }
        }
    }

    private fun leaseRequest(action: Int, token: Long) = ByteArray(6).also {
        it[0] = 10; it[1] = action.toByte(); DongleProtocol.put32(it, 2, token)
    }

    /** Cached mode baselines also need exclusion before constructing a multi-packet edit. */
    suspend fun acquireSettingsLease() {
        check(actionTarget != null) { "Settings require a target-bound action" }
        val target = refresh()
        check(target.capabilities and 0x20 != 0) { "Update the dongle firmware before editing hearing settings" }
        check(!leaseFailed) { "The settings lease was lost; read the settings again" }
        checkpointVolume()
        val acquiring = !leaseAttempted
        leaseAttempted = true
        try {
            val response = transactRpc(7, leaseRequest(if (acquiring) 0 else 1, leaseToken ?: 0), target)
            if (acquiring) {
                require(response.size == 5 && response[0] == 1.toByte() && DongleProtocol.u32(response, 1) != 0L)
                leaseToken = DongleProtocol.u32(response, 1)
            } else require(response.isEmpty())
        } catch (error: Exception) {
            leaseFailed = true
            // An acquire may have succeeded even when its receipt was lost.
            if (acquiring && error !is DongleBusyException) {
                transportBroken = true
                runCatching { closeTransport() }
            }
            throw error
        }
        checkpointVolume()
    }

    private suspend fun cleanup(block: suspend () -> Unit) = withContext(NonCancellable) {
        val previous = cleanupIoDeadline
        cleanupIoDeadline = now() + 1_000
        coroutineScope {
            // Coroutine cancellation alone cannot interrupt BluetoothSocket's blocking I/O.
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) { delay(1_000); runCatching { closeTransport() } }
            try { withTimeout(1_000) { block() } }
            catch (_: Exception) { transportBroken = true; runCatching { closeTransport() } }
            finally { watchdog.cancel(); cleanupIoDeadline = previous }
        }
    }

    suspend fun transact(operation: Int, data: ByteArray, beforeCommit: () -> Unit = {}): ByteArray {
        require(operation in listOf(1, 2, 3, 7) && data.size in 1..512)
        check(!leaseFailed) { "The settings lease was lost; read the settings again" }
        checkpointVolume()
        if (operation == 1 || operation == 2 || leaseToken != null) {
            acquireSettingsLease()
        }
        val target = refresh()
        check(operation == 7 || target.controlsReady) { "Connect the AirPods to the dongle first" }
        val response = transactRpc(operation, data, target, beforeCommit)
        checkpointVolume()
        return response
    }

    private suspend fun transactRpc(operation: Int, data: ByteArray, target: DongleProtocol.Status,
        beforeCommit: () -> Unit = {}): ByteArray {
        val previous = rpcIoDeadline
        val deadline = if (checkingVolume) checkNotNull(checkpointIoDeadline)
            else minOf(now() + 6_000, volumeDeadline ?: Long.MAX_VALUE)
        rpcIoDeadline = deadline
        return try { transactBeforeDeadline(operation, data, target, beforeCommit, deadline) }
        finally { rpcIoDeadline = previous }
    }

    private suspend fun transactBeforeDeadline(operation: Int, data: ByteArray, target: DongleProtocol.Status,
        beforeCommit: () -> Unit, deadline: Long): ByteArray {
        if (resumeSequences && sequence == 0L) {
            val previous = exchangeIo(1, 3, byteArrayOf())
            DongleProtocol.checkReport(previous, 3)
            if (previous[21].toInt() in 1..2) throw DongleBusyException()
            sequence = DongleProtocol.u32(previous, 12)
        }
        sequence = (sequence + 1).and(0xffff_ffffL).takeIf { it != 0L } ?: 1L
        val token = sequence
        fun request(op: Int, total: Int = 0, offset: Int = 0, chunk: ByteArray = byteArrayOf()) =
            DongleProtocol.header(3).also {
                it[7] = op.toByte(); DongleProtocol.put32(it, 8, target.generation)
                DongleProtocol.put32(it, 12, token)
                it[16] = total.toByte(); it[17] = (total shr 8).toByte()
                it[18] = offset.toByte(); it[19] = (offset shr 8).toByte(); it[20] = chunk.size.toByte()
                chunk.copyInto(it, 22)
            }
        suspend fun await(accept: (ByteArray) -> Boolean): ByteArray {
            while (now() < deadline) {
                val reply = exchangeIo(1, 3, byteArrayOf())
                DongleProtocol.checkReport(reply, 3)
                if (DongleProtocol.u32(reply, 8) != target.generation) throw IOException("AirPods connection changed")
                if (DongleProtocol.u32(reply, 12) == token) {
                    if (reply[21] == 4.toByte()) {
                        if (reply[22] == 2.toByte()) throw DongleBusyException()
                        throw IOException(when (reply[22].toInt()) {
                        3 -> "AirPods connection changed"
                        7 -> "This control is not supported by these AirPods"; else -> "Dongle request did not complete"
                        })
                    }
                    if (accept(reply)) return reply
                }
                wait()
            }
            throw IOException("Dongle request timed out; it was not retried")
        }
        var completed = false
        try {
            var sent = 0
            while (sent < data.size) {
                val next = minOf(sent + 42, data.size)
                exchangeIo(2, 3, request(operation, data.size, sent, data.copyOfRange(sent, next)))
                sent = next
                await { it[21] == 1.toByte() && DongleProtocol.u16(it, 18) == sent }
            }
            beforeCommit()
            exchangeIo(2, 3, request(6))
            var reply = await { it[21] == 3.toByte() }
            val size = DongleProtocol.u16(reply, 16)
            require(size <= 512)
            val result = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = reply[20].toInt() and 255
                require(DongleProtocol.u16(reply, 16) == size && DongleProtocol.u16(reply, 18) == read &&
                    count in 1..42 && count <= size - read)
                reply.copyInto(result, read, 22, 22 + count)
                read += count
                if (read < size) {
                    exchangeIo(2, 3, request(4, offset = read))
                    reply = await { it[21] == 3.toByte() && DongleProtocol.u16(it, 18) == read }
                }
            }
            val current = refresh()
            // A completed codec request may deliberately restart this pair's AACP transport.
            // Its receipt remains bound to the original generation; other results may not cross it.
            val codecRestart = operation == 7 && data[0] == 3.toByte() &&
                current.address == target.address && runCatching { DongleProtocol.audio(result) }.isSuccess
            check(current.generation == target.generation || codecRestart) { "AirPods connection changed" }
            completed = true
            return result
        } finally {
            if (!completed) cleanup {
                // A write acknowledgement only queues Cancel. Confirm it before another PDU.
                exchangeIo(2, 3, request(5))
                val cancelDeadline = now() + 1_000
                var cancelled = false
                while (now() < cancelDeadline) {
                    val reply = exchangeIo(1, 3, byteArrayOf())
                    DongleProtocol.checkReport(reply, 3)
                    if (DongleProtocol.u32(reply, 8) != target.generation ||
                        DongleProtocol.u32(reply, 12) == token && reply[21] == 4.toByte() && reply[22] == 8.toByte()) {
                        cancelled = true; break
                    }
                    wait()
                }
                check(cancelled) { "Dongle cancellation was not confirmed" }
            }
        }
    }
    suspend fun cached(slot: Int) = DongleProtocol.cached(transact(3, byteArrayOf(slot.toByte())))
    suspend fun settings() = DongleProtocol.settings(transact(7, byteArrayOf(2)))
    suspend fun readPhysicalVolume(): DongleProtocol.PhysicalVolume {
        check(refresh().capabilities and 128 != 0) { "The dongle does not support exclusive USB volume control" }
        return DongleProtocol.physicalVolume(transact(7, byteArrayOf(16)))
    }
    suspend fun volumeAuthority(action: Int, expectedUsbGeneration: Long, token: Long = 0, level: Int = 0,
        beforePhysicalCommit: (DongleProtocol.PhysicalVolume) -> Unit = {},
        beforeCommit: () -> Unit = {}): DongleProtocol.PhysicalVolume {
        check(resumeSequences) { "Volume authority requires the USB control transport" }
        check(actionTarget != null) { "Volume authority requires a target-bound action" }
        val request = DongleProtocol.volumeAuthorityRequest(action, token, level)
        val before = readPhysicalVolume()
        check(before.usbGeneration == expectedUsbGeneration && before.usbScopeReady) { "USB audio connection changed" }
        if (action != 0) check(before.ownToken == token && before.authorityActive) { "USB volume authority was lost" }
        return DongleProtocol.physicalVolume(transact(7, request) {
            beforePhysicalCommit(before)
            beforeCommit()
        }).also {
            check(it.usbGeneration == expectedUsbGeneration) { "USB audio connection changed" }
            check(when (action) {
                0 -> it.authorityActive && it.ownToken != null
                2 -> !it.authorityActive && it.ownToken == null
                else -> it.authorityActive && it.ownToken == token
            })
            // A successful SET is only admission; the caller still needs a newer physical observation.
        }
    }
    suspend fun setting(id: Int, value: Long, beforeCommit: () -> Unit = {}) = transact(7, ByteArray(6).also {
        it[0] = 1; it[1] = id.toByte(); DongleProtocol.put32(it, 2, value)
    }, beforeCommit)
    suspend fun requestAudio(codec: Int, rateMode: Int, bitrate: Long,
        expected: DongleProtocol.Status? = null): DongleProtocol.AudioProfile {
        val target = expected ?: refresh()
        val request = byteArrayOf(3, codec.toByte(), rateMode.toByte()) + ByteArray(4)
        DongleProtocol.put32(request, 3, bitrate)
        var attempted = false
        // A codec restart can erase its own receipt. Read the current preference, never replay it.
        try { withTarget(target) { transact(7, request) { attempted = true } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { if (!attempted) throw error }
        catch (error: IllegalStateException) { if (!attempted) throw error }
        val current = refresh()
        check(current.address == target.address) { "Selected AirPods changed" }
        return withTarget(current) { DongleProtocol.audio(transact(7, byteArrayOf(9))) }.also {
            check(it.requestedCodec == codec && it.rateMode == rateMode && it.requestedBitrate == bitrate) {
                "Codec request was not confirmed"
            }
        }
    }
}
