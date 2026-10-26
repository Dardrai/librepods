/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.core.bluetooth.att

import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.devices.AppleDevice
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ATTHandle(internal val uuid: UUID?) {
    TRANSPARENCY(UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390")),
    // Its UUID has not been verified. Do not substitute the old fixed handle.
    LOUD_SOUND_REDUCTION(null),
    HEARING_AID(UUID.fromString("a4120005-95c5-4d6f-9098-0f0b41457e0a"))
}

internal class ATTOwner(val transport: Any, val revision: Long) {
    fun same(other: ATTOwner?) = other != null && transport === other.transport && revision == other.revision
}

internal interface ATTTransport {
    fun connect()
    val connected: Boolean
    val receiveMtu: Int
    val transmitMtu: Int
    fun read(buffer: ByteArray): Int
    fun write(pdu: ByteArray)
    fun close()
}

private class BluetoothATTTransport(private val socket: BluetoothSocket) : ATTTransport {
    override fun connect() = socket.connect()
    override val connected get() = socket.isConnected
    override val receiveMtu get() = socket.maxReceivePacketSize
    override val transmitMtu get() = socket.maxTransmitPacketSize
    override fun read(buffer: ByteArray) = socket.inputStream.read(buffer)
    override fun write(pdu: ByteArray) { socket.outputStream.write(pdu); socket.outputStream.flush() }
    override fun close() = socket.close()
}

/** Secure Classic ATT on PSM31; authorization is established by real reads, never by an identity preference. */
class ATTManager internal constructor(
    private val captureOwner: () -> ATTOwner?,
    private val socketFactory: () -> ATTTransport
) {
    constructor(device: AppleDevice) : this(
        captureOwner = {
            device.aacp.captureAudioSocket()?.let { socket ->
                device.state.value.controlSession.takeIf { it > 0 }?.let { ATTOwner(socket, it) }
            }
        },
        socketFactory = {
            BluetoothATTTransport(device.createSocket(ParcelUuid(UUID.fromString("7798082b-b7b7-45a6-9933-563492efe04e")), 0x1f))
        }
    )

    private class Session(val owner: ATTOwner, val socket: ATTTransport) {
        val retired = AtomicBoolean()
        var bearer: ATTBearer? = null
        var reader: Thread? = null
        var mtu = 0
        var characteristics: Map<ATTHandle, ATTCharacteristic> = emptyMap()
        val reads = mutableMapOf<ATTHandle, ByteArray>()
    }
    private val operation = Mutex()
    @Volatile private var session: Session? = null
    @Volatile private var failedOwner: ATTOwner? = null

    val available: Boolean get() = currentSession()?.let { active -> synchronized(active.reads) {
        active.mtu >= 107 && active.characteristics[ATTHandle.TRANSPARENCY]?.writeOpcode != null &&
            active.reads[ATTHandle.TRANSPARENCY]?.let { it.size == 104 && parseTransparencySettingsResponse(it) != null } == true
    } } == true

    internal fun sessionToken(): Any? = currentSession()
    internal fun ownsSession(token: Any?): Boolean = token != null && currentSession() === token

    fun disconnect() { session?.let(::retire) }

    private fun currentSession(): Session? = session?.takeIf {
        !it.retired.get() && it.owner.same(captureOwner()) && it.socket.connected && it.bearer?.isCurrent == true
    }

    private fun retire(active: Session) {
        if (!active.retired.compareAndSet(false, true)) return
        synchronized(this) {
            if (session === active) session = null
            failedOwner = active.owner
        }
        synchronized(active.reads) { active.reads.clear() }
        active.bearer?.retire()
        runCatching { active.socket.close() }
        active.reader?.interrupt()
    }

    private suspend fun connect(): Session? {
        currentSession()?.let { return it }
        val owner = captureOwner() ?: return null
        session?.let(::retire)
        if (owner.same(failedOwner)) return null
        val active = try { Session(owner, socketFactory()) } catch (error: Exception) {
            failedOwner = owner
            Log.d("ATTManager", "ATT socket unavailable: ${error.javaClass.simpleName}")
            return null
        }
        synchronized(this) { session = active }
        return try {
            val opened = blocking(active, 4000) {
                if (!owner.same(captureOwner()) || active.retired.get()) throw ATTFailure("ATT connection retired")
                active.socket.connect()
                val rx = active.socket.receiveMtu
                val tx = active.socket.transmitMtu
                if (rx !in 48..65535 || tx !in 48..65535) throw ATTFailure("Unsupported Classic ATT MTU")
                active.mtu = minOf(rx, tx)
                if (!owner.same(captureOwner()) || !active.socket.connected || active.retired.get()) throw ATTFailure("ATT connection retired")
                active.bearer = ATTBearer(active.mtu, active.socket::write, { retire(active) }) {
                    session === active && !active.retired.get() && owner.same(captureOwner()) && active.socket.connected
                }
                // A full receive buffer preserves L2CAP SDU boundaries; writes are never split into PDUs.
                active.reader = Thread({
                    val buffer = ByteArray(rx)
                    try {
                        while (active.bearer?.isCurrent == true) {
                            val count = active.socket.read(buffer)
                            if (count <= 0) break
                            active.bearer?.receive(buffer.copyOf(count))
                        }
                    } catch (_: Exception) {
                        // Closing this ATT socket cancels a blocked read without disturbing AACP/audio.
                    } finally { retire(active) }
                }, "LibrePods-ATT-reader").also { it.isDaemon = true; it.start() }
                true
            } == true
            if (!opened) { retire(active); return null }
            val discovered = blocking(active, 8000) {
                discoverATTSettings { request, opcode -> active.bearer!!.request(request, opcode) }
            } ?: run { retire(active); return null }
            if (currentSession() !== active) { retire(active); null }
            else { active.characteristics = discovered; active }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) { retire(active); throw error }
            // ATT error codes are useful for authorization diagnostics; never log characteristic values.
            Log.d("ATTManager", if (error is ATTFailure) error.message else "ATT connection unavailable: ${error.javaClass.simpleName}")
            retire(active)
            null
        }
    }

    suspend fun readCharacteristic(handle: ATTHandle, canRead: () -> Boolean = { true },
                                   onAttempt: () -> Unit = {}): ByteArray? = operation.withLock {
        if (handle.uuid == null || !canRead()) return@withLock null
        onAttempt()
        val active = connect() ?: return@withLock null
        if (handle !in active.characteristics) return@withLock null
        try {
            blocking(active, 2500) { read(active, handle, canRead) }
        } catch (error: Exception) {
            retire(active)
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.d("ATTManager", "ATT read $handle: " +
                if (error is ATTFailure) error.message else "unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun read(active: Session, handle: ATTHandle, canRead: () -> Boolean): ByteArray? {
        val characteristic = active.characteristics[handle] ?: return null
        val response = active.bearer!!.request(byteArrayOf(0x0a, characteristic.handle.toByte(), (characteristic.handle ushr 8).toByte()), 0x0b, canSend = canRead)
        if (response[0] == 1.toByte()) throw ATTFailure("ATT read rejected: 0x${(response[4].toInt() and 255).toString(16)}")
        val value = response.copyOfRange(1, response.size)
        // A full-MTU reply can be a truncated long value. No read-blob assembly or guessed padding.
        if (response.size >= active.mtu || !validValue(handle, value) || currentSession() !== active || !canRead())
            throw ATTFailure("Incomplete or unsupported ATT setting")
        synchronized(active.reads) { active.reads[handle] = value.copyOf() }
        return value
    }

    internal suspend fun writeCharacteristic(handle: ATTHandle, data: ByteArray, expected: ByteArray,
        canSend: () -> Boolean): Boolean = operation.withLock {
        val active = currentSession() ?: return@withLock false
        val requested = data.copyOf()
        val baseline = expected.copyOf()
        val characteristic = active.characteristics[handle] ?: return@withLock false
        val opcode = characteristic.writeOpcode ?: return@withLock false
        val proof = synchronized(active.reads) { active.reads[handle]?.copyOf() }
        if (proof == null || !proof.contentEquals(baseline) || !validWrite(handle, requested) ||
            !validWrite(handle, baseline) || requested.size + 3 > active.mtu || !canSend()) return@withLock false
        try {
            blocking(active, 8000) {
                if (read(active, handle, canSend)?.contentEquals(baseline) != true) return@blocking false
                val pdu = byteArrayOf(opcode.toByte(), characteristic.handle.toByte(), (characteristic.handle ushr 8).toByte()) + requested
                val sent = if (opcode == 0x12) active.bearer!!.request(pdu, 0x13, canSend = canSend).contentEquals(byteArrayOf(0x13))
                    else active.bearer!!.command(pdu, canSend)
                if (!sent) { retire(active); return@blocking false }
                repeat(5) { attempt ->
                    val actual = read(active, handle, canSend) ?: return@blocking false
                    val matches = if (handle == ATTHandle.HEARING_AID)
                        hearingAidSettingsWritable(actual) && actual.copyOfRange(4, 104).contentEquals(requested.copyOfRange(4, 104))
                    else actual.contentEquals(requested)
                    if (matches) return@blocking currentSession() === active && canSend()
                    val unchanged = if (handle == ATTHandle.HEARING_AID)
                        hearingAidSettingsWritable(actual) && actual.copyOfRange(4, 104).contentEquals(baseline.copyOfRange(4, 104))
                    else actual.contentEquals(baseline)
                    if (!unchanged || attempt == 4) { retire(active); return@blocking false }
                    Thread.sleep(75)
                }
                false
            } == true
        } catch (error: Exception) {
            retire(active)
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.d("ATTManager", if (error is ATTFailure) error.message else "ATT write unconfirmed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun validValue(handle: ATTHandle, data: ByteArray): Boolean = when (handle) {
        ATTHandle.TRANSPARENCY -> data.size == 104 && parseTransparencySettingsResponse(data) != null
        ATTHandle.HEARING_AID -> data.size == 104 && parseHearingAidSettingsResponse(data) != null
        ATTHandle.LOUD_SOUND_REDUCTION -> false
    }
    private fun validWrite(handle: ATTHandle, data: ByteArray): Boolean = validValue(handle, data) &&
        (handle != ATTHandle.HEARING_AID || hearingAidSettingsWritable(data))

    private suspend fun <T> blocking(active: Session, timeoutMillis: Long, block: () -> T): T? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val worker = Thread({
                    try {
                        val result = block()
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }, "LibrePods-ATT-request").also { it.isDaemon = true }
                continuation.invokeOnCancellation { retire(active); worker.interrupt() }
                worker.start()
            }
        }.also { if (it == null) retire(active) }
}
