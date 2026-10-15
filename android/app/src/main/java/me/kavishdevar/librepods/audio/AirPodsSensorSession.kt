/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SensorState(
    val supportsHeartRate: Boolean = false,
    val supportsMotion: Boolean = false,
    val heartRateEnabled: Boolean = false,
    val motionEnabled: Boolean = false,
    val heartRate: Int? = null,
    val heartRateConfidence: Int? = null,
    val heartRateTimestampMs: Long? = null,
    val motion: RtBuddySensors.Motion? = null,
    val heartRateError: String? = null,
    val motionError: String? = null,
)

/** Optional sensors never reconnect the shared Bluetooth/audio transport. */
class AirPodsSensorSession(private val aacp: AACPManager) : AutoCloseable {
    private class Session(val socket: BluetoothSocket, @Volatile var legacyMotionService: Int?, val parser: RtBuddySensors) {
        val running = AtomicBoolean(true)
        val heart = AtomicBoolean(false)
        val motion = AtomicBoolean(false)
        val suspended = AtomicBoolean(false)
        val mapping = AtomicReference(RtBuddySensors.Batch(false, 0, null, null, null, null))
        @Volatile var lastHeartMs = 0L
        @Volatile var lastMotionMs = 0L
        @Volatile var worker: Thread? = null
    }

    private val mutableState = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = mutableState.asStateFlow()
    @Volatile private var session: Session? = null
    private var closed = false
    private val callback: (ByteArray) -> Unit = ::receive

    init { aacp.rtBuddyPacketCallback = callback }

    @Synchronized
    fun onConnected(supportsHeartRate: Boolean, supportsMotion: Boolean, firmware: String) {
        if (closed) return
        val socket = BluetoothConnectionManager.aacpSocket ?: return
        if (!socket.isConnected) return
        val major = RtBuddySensors.publicFirmwareMajor(firmware)
        val legacy = major?.takeIf { it > 0 }?.let { if (it >= 8) 16 else 14 }
        val current = session
        if (current != null && current.running.get() && current.socket === socket) {
            current.parser.setLegacyMotionService(legacy)
            current.legacyMotionService = legacy
            if (!supportsHeartRate) current.heart.set(false)
            if (!supportsMotion) current.motion.set(false)
            mutableState.update { it.copy(supportsHeartRate = supportsHeartRate, supportsMotion = supportsMotion,
                heartRateEnabled = it.heartRateEnabled && supportsHeartRate, motionEnabled = it.motionEnabled && supportsMotion) }
            return
        }
        onDisconnected()
        val next = Session(socket, legacy, RtBuddySensors(legacy))
        session = next
        mutableState.value = SensorState(supportsHeartRate, supportsMotion)
        next.worker = Thread({ run(next) }, "AirPods sensors").apply { start() }
    }

    fun setHeartRateEnabled(enabled: Boolean): Boolean {
        val active = session?.takeIf { it.running.get() } ?: return false
        if (enabled && !state.value.supportsHeartRate) return false
        active.heart.set(enabled)
        mutableState.update { it.copy(heartRateEnabled = enabled, heartRateError = null,
            heartRate = null, heartRateConfidence = null, heartRateTimestampMs = null) }
        return true
    }

    fun setMotionEnabled(enabled: Boolean): Boolean {
        val active = session?.takeIf { it.running.get() } ?: return false
        if (enabled && !state.value.supportsMotion) return false
        active.motion.set(enabled)
        mutableState.update { it.copy(motionEnabled = enabled, motionError = null, motion = null) }
        return true
    }

    fun setSuspended(suspended: Boolean) { session?.suspended?.set(suspended) }

    @Synchronized
    fun onDisconnected() {
        session?.running?.set(false)
        session = null
        mutableState.value = SensorState()
    }

    private fun receive(packet: ByteArray) {
        val active = session ?: return
        val batch = active.parser.feed(packet)
        active.mapping.set(batch)
        if (active.suspended.get() || !active.running.get()) return
        val now = SystemClock.elapsedRealtime()
        if (active.heart.get() && batch.heartRate != null) {
            active.lastHeartMs = now
            mutableState.update { current ->
                if (session !== active || !active.heart.get() || active.suspended.get()) current else current.copy(heartRate = batch.heartRate.bpm,
                    heartRateConfidence = batch.heartRate.confidence, heartRateTimestampMs = System.currentTimeMillis(),
                    heartRateError = null)
            }
        }
        if (active.motion.get() && batch.motion != null) {
            active.lastMotionMs = now
            mutableState.update { current -> if (session !== active || !active.motion.get() || active.suspended.get()) current else current.copy(motion = batch.motion, motionError = null) }
        }
    }

    private fun run(active: Session) {
        var sequence = 0
        fun update(transform: (SensorState) -> SensorState) {
            mutableState.update { if (session === active) transform(it) else it }
        }
        fun send(packet: ByteArray) = aacp.sendSensorPacket(active.socket, packet)
        fun command(service: Int, period: Int, report: Int = 1, heart: Boolean = false): Boolean {
            sequence = (sequence + 1) and 0x3fff
            return send(RtBuddySensors.streamCommand(sequence, service, period, report, heart))
        }
        fun heartStop() {
            val a = command(84, 0, heart = true)
            val b = command(19, 0, heart = true)
            val c = send(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 2, 0, 0, 0))
            if (!(a && b && c)) mutableState.update { current ->
                if (session !== active) current else current.copy(heartRateError = "Could not stop the heart-rate sensor. Reconnect your AirPods.")
            }
        }
        fun current() = active.running.get() && session === active
        fun pause(ms: Long): Boolean {
            val end = SystemClock.elapsedRealtime() + ms
            while (current() && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
            return current() && active.heart.get() && !active.suspended.get()
        }
        var heartLive = false
        var heartAttempt = 0
        var heartDeadline = 0L
        var motionLive: Int? = null
        var motionNative = false
        val motionTimeout = MotionReportTimeout()
        var mappingGeneration = 0L
        var hadMotionDescriptor = false
        fun motionStop() {
            motionLive?.let { service ->
                val fifo = !motionNative || command(service, 10, report = 4)
                val stopped = command(service, 0)
                if (!(fifo && stopped)) mutableState.update { current ->
                    if (session !== active) current else current.copy(motionError = "Could not stop movement reports. Reconnect your AirPods.")
                }
            }
            motionLive = null
        }
        try {
            while (current()) {
                val now = SystemClock.elapsedRealtime()
                val mapping = active.mapping.get()
                if (mappingGeneration != mapping.generation || (motionLive != null &&
                        mapping.motionService != null && (motionLive != mapping.motionService || !motionNative))) {
                    // The old address can now belong to a different sensor; never write its Stop.
                    motionLive = null
                    active.lastMotionMs = 0
                    motionTimeout.restart(now)
                    mappingGeneration = mapping.generation
                }
                if (mapping.motionService != null) hadMotionDescriptor = true
                val desiredMotion = mapping.motionService ?: active.legacyMotionService.takeUnless { hadMotionDescriptor }
                if (motionLive != null && motionLive != desiredMotion) {
                    motionLive = null
                    active.lastMotionMs = 0
                    motionTimeout.restart(now)
                }
                if (active.lastHeartMs > 0 && now - active.lastHeartMs >= 10_000)
                    mutableState.update { current -> if (session !== active) current else current.copy(
                        heartRate = null, heartRateConfidence = null, heartRateTimestampMs = null) }
                val heartWanted = active.heart.get() && !active.suspended.get()
                if (!heartWanted && heartLive) {
                    heartStop(); heartLive = false
                }
                if (!heartWanted) heartAttempt = 0
                if (heartWanted && !heartLive) {
                    heartLive = true
                    heartAttempt++
                    active.lastHeartMs = 0
                    var ok = true
                    for ((packet, delay) in HEART_INIT) {
                        if (!current() || !active.heart.get() || active.suspended.get()) { ok = false; break }
                        if (!send(packet) || !pause(delay)) { ok = false; break }
                    }
                    if (ok) ok = send(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 1, 0, 0, 0)) && pause(120)
                    if (ok) ok = command(84, 1_000_000, heart = true) && pause(120) && command(19, 1_000_000, heart = true)
                    if (!ok && current() && active.heart.get() && !active.suspended.get()) {
                        active.heart.set(false)
                        update { it.copy(heartRateEnabled = false, heartRateError = "Could not start the heart-rate sensor.") }
                    }
                    heartDeadline = SystemClock.elapsedRealtime() + 12_000
                }
                if (heartWanted && heartLive && now >= heartDeadline &&
                    (active.lastHeartMs == 0L || now - active.lastHeartMs >= 12_000)) {
                    heartStop(); heartLive = false
                    if (heartAttempt >= 2) {
                        active.heart.set(false)
                        update { it.copy(heartRateEnabled = false,
                            heartRateError = "No reliable heart-rate reading. Wear both AirPods and try again.") }
                    }
                }
                val motionWanted = active.motion.get() && !active.suspended.get()
                if (!motionWanted && motionLive != null) motionStop()
                if (motionWanted && motionLive == null && desiredMotion != null) {
                    motionNative = mapping.motionService != null
                    val fifo = !motionNative || command(desiredMotion, 1, report = 4)
                    val interval = command(desiredMotion, if (motionNative) 20_000 else 40_000)
                    motionLive = desiredMotion
                    motionTimeout.restart(now)
                    active.lastMotionMs = 0
                    if (!(fifo && interval)) {
                        active.motion.set(false)
                        update { it.copy(motionEnabled = false, motionError = "Could not start movement reports.") }
                    }
                }
                if (motionTimeout.expired(motionWanted, now, active.lastMotionMs)) {
                    active.motion.set(false)
                    update { it.copy(motionEnabled = false, motion = null,
                        motionError = "No movement reports. Please try again.") }
                }
                Thread.sleep(50)
            }
        } catch (_: Exception) {
            mutableState.update { current -> if (session !== active) current else current.copy(
                heartRateEnabled = false, motionEnabled = false, heartRateError = "Sensor session stopped.", motionError = "Sensor session stopped.") }
        } finally {
            active.running.set(false)
            if (heartLive) heartStop()
            motionStop()
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            onDisconnected()
            if (aacp.rtBuddyPacketCallback === callback) aacp.rtBuddyPacketCallback = null
        }
    }

    companion object {
        private val HEART_INIT = listOf(
            byteArrayOf(0, 0, 0, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 0, 0, 1, 0, 0) to 220L,
            byteArrayOf(0, 0, 4, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 4, 0, 1, 0, 0) to 220L,
        )
    }
}
