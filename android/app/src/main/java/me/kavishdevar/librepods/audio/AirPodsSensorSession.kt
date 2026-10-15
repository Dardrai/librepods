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
    private class Session(val socket: BluetoothSocket, @Volatile var legacyMotionService: Int?,
        val parser: RtBuddySensors, suspended: Boolean) {
        val running = AtomicBoolean(true)
        val heart = AtomicBoolean(false)
        val motion = AtomicBoolean(false)
        val suspended = AtomicBoolean(suspended)
        val mapping = AtomicReference(RtBuddySensors.Batch(false, 0, null, null, null, null))
        @Volatile var lastHeartMs = 0L
        @Volatile var lastMotionMs = 0L
        @Volatile var worker: Thread? = null
    }

    private val mutableState = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = mutableState.asStateFlow()
    @Volatile private var session: Session? = null
    private var closed = false
    private var suspended = false
    private val callback: (ByteArray) -> Unit = ::receive

    init { aacp.rtBuddyPacketCallback = callback }

    @Synchronized
    fun onConnected(supportsHeartRate: Boolean, supportsMotion: Boolean, firmware: String) {
        if (closed) return
        val socket = BluetoothConnectionManager.aacpSocket ?: return onDisconnected()
        if (!runCatching { socket.isConnected }.getOrDefault(false)) return onDisconnected()
        val major = RtBuddySensors.publicFirmwareMajor(firmware)
        val legacy = major?.takeIf { it > 0 }?.let { if (it >= 8) 16 else 14 }
        val current = session
        if (current != null && current.running.get() && current.socket === socket) {
            current.parser.setLegacyMotionService(legacy)
            current.legacyMotionService = legacy
            if (!supportsHeartRate) current.heart.set(false)
            if (!supportsMotion) current.motion.set(false)
            mutableState.update { it.copy(supportsHeartRate = supportsHeartRate, supportsMotion = supportsMotion,
                heartRateEnabled = it.heartRateEnabled && supportsHeartRate, motionEnabled = it.motionEnabled && supportsMotion,
                heartRate = it.heartRate.takeIf { supportsHeartRate },
                heartRateConfidence = it.heartRateConfidence.takeIf { supportsHeartRate },
                heartRateTimestampMs = it.heartRateTimestampMs.takeIf { supportsHeartRate },
                motion = it.motion.takeIf { supportsMotion },
                heartRateError = it.heartRateError.takeIf { supportsHeartRate },
                motionError = it.motionError.takeIf { supportsMotion }) }
            return
        }
        onDisconnected()
        val next = Session(socket, legacy, RtBuddySensors(legacy), suspended)
        session = next
        mutableState.value = SensorState(supportsHeartRate, supportsMotion)
        next.worker = Thread({ run(next) }, "AirPods sensors").apply { start() }
    }

    @Synchronized
    fun setHeartRateEnabled(enabled: Boolean): Boolean {
        val active = session?.takeIf { !closed && it.running.get() && it.socket === BluetoothConnectionManager.aacpSocket } ?: return false
        if (enabled && !state.value.supportsHeartRate) return false
        active.heart.set(enabled)
        mutableState.update { it.copy(heartRateEnabled = enabled, heartRateError = null,
            heartRate = null, heartRateConfidence = null, heartRateTimestampMs = null) }
        return true
    }

    @Synchronized
    fun setMotionEnabled(enabled: Boolean): Boolean {
        val active = session?.takeIf { !closed && it.running.get() && it.socket === BluetoothConnectionManager.aacpSocket } ?: return false
        if (enabled && !state.value.supportsMotion) return false
        active.motion.set(enabled)
        mutableState.update { it.copy(motionEnabled = enabled, motionError = null, motion = null) }
        return true
    }

    @Synchronized
    fun setSuspended(suspended: Boolean) {
        this.suspended = suspended
        session?.suspended?.set(suspended)
        if (suspended) mutableState.update { it.copy(heartRate = null, heartRateConfidence = null,
            heartRateTimestampMs = null, motion = null) }
    }

    @Synchronized
    fun onDisconnected() {
        session?.running?.set(false)
        session = null
        mutableState.value = SensorState()
    }

    @Synchronized
    private fun receive(packet: ByteArray) {
        val active = session?.takeIf { it.running.get() && it.socket === BluetoothConnectionManager.aacpSocket } ?: return
        val batch = active.parser.feed(packet)
        active.mapping.set(batch)
        if (batch.mappingChanged) {
            active.lastHeartMs = 0
            active.lastMotionMs = 0
            mutableState.update { it.copy(heartRate = null, heartRateConfidence = null,
                heartRateTimestampMs = null, motion = null) }
        }
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
        var heartMapping: RtBuddySensors.Batch? = null
        var motionMapping: RtBuddySensors.Batch? = null
        fun update(transform: (SensorState) -> SensorState) {
            mutableState.update { if (session === active) transform(it) else it }
        }
        fun current() = active.running.get() && session === active && active.socket === BluetoothConnectionManager.aacpSocket
        fun mapped(expected: RtBuddySensors.Batch?) = expected?.sameMapping(active.mapping.get()) == true
        fun heartWanted() = current() && active.heart.get() && !active.suspended.get()
        fun motionWanted() = current() && active.motion.get() && !active.suspended.get()
        fun send(packet: ByteArray, expected: RtBuddySensors.Batch? = null,
            allowed: () -> Boolean = { true }): Boolean = synchronized(this@AirPodsSensorSession) {
            if (session !== active || active.socket !== BluetoothConnectionManager.aacpSocket ||
                (expected != null && !mapped(expected)) || !allowed()) false
            else aacp.sendSensorPacket(active.socket, packet)
        }
        fun command(service: Int, period: Int, report: Int = 1, heart: Boolean = false,
            expected: RtBuddySensors.Batch? = null, allowed: () -> Boolean = { true }): Boolean {
            sequence = (sequence + 1) and 0x3fff
            return send(RtBuddySensors.streamCommand(sequence, service, period, report, heart), expected, allowed)
        }
        fun heartStop() {
            val owner = heartMapping ?: return
            if (!mapped(owner)) return
            val a = command(84, 0, heart = true, expected = owner)
            val b = command(19, 0, heart = true, expected = owner)
            val c = send(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 2, 0, 0, 0), owner)
            if (!(a && b && c) && mapped(owner)) mutableState.update { current ->
                if (session !== active) current else current.copy(heartRateError = "Could not stop the heart-rate sensor. Reconnect your AirPods.")
            }
        }
        fun pause(ms: Long, owner: RtBuddySensors.Batch): Boolean {
            val end = SystemClock.elapsedRealtime() + ms
            while (heartWanted() && mapped(owner) && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
            return heartWanted() && mapped(owner)
        }
        var heartLive = false
        var heartAttempt = 0
        var heartDeadline = 0L
        var motionLive: Int? = null
        var motionNative = false
        val motionTimeout = MotionReportTimeout()
        fun motionStop() {
            motionLive?.let { service ->
                val owner = motionMapping ?: return@let
                if (!mapped(owner) || (!motionNative && active.legacyMotionService != service)) return@let
                val fifo = !motionNative || command(service, 10, report = 4, expected = owner)
                val stopped = command(service, 0, expected = owner,
                    allowed = { motionNative || active.legacyMotionService == service })
                if (!(fifo && stopped) && mapped(owner)) mutableState.update { current ->
                    if (session !== active) current else current.copy(motionError = "Could not stop movement reports. Reconnect your AirPods.")
                }
            }
            motionLive = null
        }
        try {
            while (current()) {
                val now = SystemClock.elapsedRealtime()
                val mapping = active.mapping.get()
                if (heartMapping != null && !mapped(heartMapping)) {
                    heartLive = false
                    heartMapping = null
                    heartAttempt = 0
                    active.lastHeartMs = 0
                }
                if (motionMapping != null && !mapped(motionMapping)) {
                    // The old address can now belong to a different sensor; never write its Stop.
                    motionLive = null
                    motionMapping = null
                    active.lastMotionMs = 0
                    motionTimeout.restart(now)
                }
                val desiredMotion = mapping.motionService ?: active.legacyMotionService.takeIf {
                    mapping.legacyMotionAllowed && it != mapping.heartRateService }
                if (motionLive != null && motionLive != desiredMotion) {
                    motionLive = null
                    active.lastMotionMs = 0
                    motionTimeout.restart(now)
                }
                if (active.lastHeartMs > 0 && now - active.lastHeartMs >= 10_000)
                    mutableState.update { current -> if (session !== active) current else current.copy(
                        heartRate = null, heartRateConfidence = null, heartRateTimestampMs = null) }
                val wantHeart = heartWanted()
                if (!wantHeart && heartLive) {
                    heartStop(); heartLive = false
                }
                if (!wantHeart) heartAttempt = 0
                if (wantHeart && !heartLive) {
                    heartMapping = mapping
                    heartLive = true
                    heartAttempt++
                    active.lastHeartMs = 0
                    var ok = true
                    for ((packet, delay) in HEART_INIT) {
                        if (!send(packet, mapping, ::heartWanted) || !pause(delay, mapping)) { ok = false; break }
                    }
                    if (ok) ok = send(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 1, 0, 0, 0), mapping, ::heartWanted) && pause(120, mapping)
                    if (ok) ok = command(84, 1_000_000, heart = true, expected = mapping, allowed = ::heartWanted) &&
                        pause(120, mapping) && command(19, 1_000_000, heart = true, expected = mapping, allowed = ::heartWanted)
                    if (!ok && heartWanted() && mapped(mapping)) {
                        active.heart.set(false)
                        update { it.copy(heartRateEnabled = false, heartRateError = "Could not start the heart-rate sensor.") }
                    }
                    heartDeadline = SystemClock.elapsedRealtime() + 12_000
                }
                if (wantHeart && heartLive && now >= heartDeadline &&
                    (active.lastHeartMs == 0L || now - active.lastHeartMs >= 12_000)) {
                    heartStop(); heartLive = false
                    if (heartAttempt >= 2) {
                        active.heart.set(false)
                        update { it.copy(heartRateEnabled = false,
                            heartRateError = "No reliable heart-rate reading. Wear both AirPods and try again.") }
                    }
                }
                val wantMotion = motionWanted()
                if (!wantMotion && motionLive != null) motionStop()
                if (wantMotion && motionLive == null && desiredMotion != null) {
                    motionMapping = mapping
                    motionNative = mapping.motionService != null
                    val allowed = { motionWanted() && (motionNative || active.legacyMotionService == desiredMotion) }
                    val fifo = !motionNative || command(desiredMotion, 1, report = 4, expected = mapping, allowed = allowed)
                    val interval = command(desiredMotion, if (motionNative) 20_000 else 40_000, expected = mapping, allowed = allowed)
                    motionLive = desiredMotion
                    motionTimeout.restart(now)
                    active.lastMotionMs = 0
                    if (!(fifo && interval) && allowed() && mapped(mapping)) {
                        active.motion.set(false)
                        update { it.copy(motionEnabled = false, motionError = "Could not start movement reports.") }
                    }
                }
                if (motionTimeout.expired(wantMotion, now, active.lastMotionMs)) {
                    active.motion.set(false)
                    update { it.copy(motionEnabled = false, motion = null,
                        motionError = "No movement reports. Please try again.") }
                }
                Thread.sleep(50)
            }
        } catch (_: Exception) {
            active.heart.set(false)
            active.motion.set(false)
            mutableState.update { current -> if (session !== active) current else current.copy(
                heartRateEnabled = false, motionEnabled = false, heartRate = null, heartRateConfidence = null,
                heartRateTimestampMs = null, motion = null, heartRateError = "Sensor session stopped.", motionError = "Sensor session stopped.") }
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
