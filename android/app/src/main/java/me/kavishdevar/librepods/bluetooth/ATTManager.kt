/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import me.kavishdevar.librepods.data.hearingAidSettingsWritable
import me.kavishdevar.librepods.data.parseTransparencySettingsResponse
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ATTManager"

enum class ATTHandles(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A)
}

enum class ATTCCCDHandles(val value: Int) {
    TRANSPARENCY(ATTHandles.TRANSPARENCY.value + 1),
    HEARING_AID(ATTHandles.HEARING_AID.value + 1)
}

class ATTManagerv2 {
    class Session internal constructor(val socket: BluetoothSocket) {
        internal lateinit var bearer: ATTBearer
        internal val characteristics = ConcurrentHashMap<ATTHandles, ByteArray>()
        internal var reader: Thread? = null
    }

    @Volatile private var session: Session? = null
    @Volatile private var onNotificationReceived: ((Session, Byte, ByteArray) -> Unit)? = null
    @Volatile private var onSessionChanged: (() -> Unit)? = null

    fun currentSession(): Session? = session?.takeIf { it.bearer.isCurrent }

    @Synchronized
    fun startReader(expectedSocket: BluetoothSocket? = BluetoothConnectionManager.attSocket) {
        val socket = expectedSocket?.takeIf { BluetoothConnectionManager.attSocket === it } ?: return
        if (session?.let { it.socket === socket && it.bearer.isCurrent } == true) return
        session?.bearer?.retire()
        if (!socket.isConnected) return
        val next = Session(socket)
        next.bearer = ATTBearer(
            send = { pdu -> socket.outputStream.write(pdu); socket.outputStream.flush() },
            close = { retire(next) },
            isTransportCurrent = { session === next && BluetoothConnectionManager.attSocket === socket && socket.isConnected }
        )
        session = next
        next.reader = Thread({
            try {
                val input = socket.inputStream
                val buffer = ByteArray(512)
                while (next.bearer.isCurrent) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) receive(next, buffer.copyOf(count))
                }
            } catch (e: Exception) {
                Log.d(TAG, "ATT reader stopped: ${e.message}")
            } finally {
                next.bearer.retire()
            }
        }, "ATT-Reader").also { it.isDaemon = true; it.start() }
        onSessionChanged?.invoke()
    }

    private fun retire(retired: Session) {
        val changed = synchronized(this) {
            if (session === retired) { session = null; true } else false
        }
        retired.characteristics.clear()
        runCatching { retired.socket.close() }
        retired.reader?.interrupt()
        if (changed) onSessionChanged?.invoke()
    }

    fun stopReader() = disconnected()

    fun disconnected() {
        session?.bearer?.retire()
    }

    fun setOnNotificationReceived(listener: ((Session, Byte, ByteArray) -> Unit)?) {
        onNotificationReceived = listener
    }

    fun setOnSessionChanged(listener: (() -> Unit)?) {
        onSessionChanged = listener
    }

    fun enableNotification(handle: ATTCCCDHandles, expectedSession: Session? = currentSession()): Boolean =
        writeCharacteristic(handle.value.toByte(), byteArrayOf(0x01, 0x00), expectedSession = expectedSession)

    fun getCharacteristic(handle: ATTHandles, expectedSession: Session? = currentSession()): ByteArray? {
        val active = expectedSession?.takeIf { currentSession() === it } ?: return null
        return active.characteristics[handle]?.copyOf() ?: readCharacteristic(handle, expectedSession = active)
    }

    fun readCharacteristic(handle: ATTHandles, timeoutMillis: Long = 2000, expectedSession: Session? = currentSession()): ByteArray? {
        val active = expectedSession?.takeIf { currentSession() === it } ?: return null
        val response = active.bearer.request(byteArrayOf(0x0A, handle.value.toByte(), 0x00), 0x0B, timeoutMillis)
            ?: return null
        val value = response.copyOfRange(1, response.size)
        if (currentSession() !== active) return null
        active.characteristics[handle] = value
        return value.copyOf()
    }

    fun writeCharacteristic(handle: ATTHandles, data: ByteArray, timeoutMillis: Long = 2000,
        expectedSession: Session? = currentSession(), canSend: () -> Boolean = { true }): Boolean {
        val active = expectedSession?.takeIf { currentSession() === it } ?: return false
        if (when (handle) {
            ATTHandles.HEARING_AID -> !hearingAidSettingsWritable(data)
            ATTHandles.TRANSPARENCY -> parseTransparencySettingsResponse(data) == null
            ATTHandles.LOUD_SOUND_REDUCTION -> data.size != 1 || data[0].toInt() !in 0..1
        }) return false
        if (!active.bearer.writeVerifiedSetting(handle, data, timeoutMillis, canSend)) return false
        if (currentSession() !== active) return false
        active.characteristics[handle] = data.copyOf()
        return true
    }

    fun writeCharacteristic(handle: Byte, data: ByteArray, timeoutMillis: Long = 2000,
        expectedSession: Session? = currentSession(), canSend: () -> Boolean = { true }): Boolean {
        val active = expectedSession?.takeIf { currentSession() === it } ?: return false
        return active.bearer.request(byteArrayOf(0x12, handle, 0x00) + data, 0x13, timeoutMillis, canSend) != null
    }

    private fun receive(active: Session, data: ByteArray) {
        if (currentSession() !== active || data.isEmpty()) return
        if (data[0] != 0x1B.toByte()) {
            active.bearer.receive(data)
            return
        }
        if (data.size < 3 || data[2] != 0.toByte()) return
        val handle = ATTHandles.entries.firstOrNull { it.value == (data[1].toInt() and 255) } ?: return
        val value = data.copyOfRange(3, data.size)
        active.characteristics[handle] = value
        if (currentSession() === active) runCatching { onNotificationReceived?.invoke(active, data[1], value.copyOf()) }
            .onFailure { Log.w(TAG, "ATT notification callback failed", it) }
    }
}
