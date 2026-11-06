package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.RequestAllDescriptors
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceSetting
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.bluetooth.aacp.types.RemoteHidBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.PacketDestination
import kotlin.time.Duration

/** One typed sensor session; every write belongs to the socket captured by its callbacks. */
internal class RTBuddyManager(
    private val sendPacket: (AACPPacket) -> Boolean,
    private val sendRaw: (ByteArray) -> Boolean,
    private val isCurrent: () -> Boolean,
    private val firmwareMajor: () -> Int?,
    private val onMotion: (SensorReports.Motion) -> Unit,
    private val onHeartRate: (SensorReports.HeartRate) -> Unit,
    private val onMotionState: (BuddyState) -> Unit,
    private val onHeartRateState: (BuddyState) -> Unit,
    private val remoteAllowed: () -> Boolean = { false },
    private var motionIntervalUs: Int? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = SensorFrames()
    private val reports = SensorReports()
    private var sequence = 0
    private var descriptorsRequested = false
    private var motionWanted = false
    private var heartWanted = false
    private var suspended = false
    private var closed = false
    private var motionLive: Int? = null
    private var motionNative = false
    private var motionRemote = false
    private var motionStopping = false
    private var stopRetry = false
    private var remoteService: Int? = null
    private var remoteRequested = false
    private var motionSince = 0L
    private var lastMotion = 0L
    private var lastHeart = 0L
    private var heartJob: Job? = null
    private var heartEpoch = 0L

    init {
        scope.launch {
            while (isActive && isCurrent()) {
                delay(250)
                synchronized(this@RTBuddyManager) {
                    if (closed) return@synchronized
                    if (stopRetry && isCurrent()) { stopRetry = false; stopMotion(retry = true) }
                    if (motionWanted && !suspended) {
                        if (motionLive == null) startMotion()
                        if (now() - maxOf(motionSince, lastMotion) >= 5_000) {
                            motionWanted = false
                            stopMotion()
                            onMotionState(BuddyState.INACTIVE)
                        }
                    }
                }
            }
            close()
        }
    }

    @Synchronized
    fun requestDescriptors() {
        if (closed || descriptorsRequested || !isCurrent() || remoteAllowed()) return
        descriptorsRequested = sendSensorData(
            SensorDataWX.newBuilder().setRequestAllDescriptors(RequestAllDescriptors.getDefaultInstance()).build())
    }

    @Synchronized
    fun feed(chunk: ByteArray) {
        if (closed || !isCurrent()) return
        for (frame in frames.feed(chunk)) {
            val packet = try { RTBuddyPacket.parse(frame) } catch (_: Exception) { continue }
            val remote = packet.rtBuddyPayload as? RemoteHidBuddyPayload
            if (remote != null) {
                if (remoteAllowed()) receiveRemote(remote.data)
                continue
            }
            if (remoteAllowed()) continue
            val data = (packet.rtBuddyPayload as? SensorDataWxBuddyPayload)?.data ?: continue
            val batch = reports.receive(data, legacyMotion())
            if (batch.mappingChanged) {
                // The previous address can now name another sensor; never send it a delayed Stop.
                motionLive = null
                motionStopping = false
                stopRetry = false
                motionSince = now()
                lastMotion = 0
                lastHeart = 0
                if (motionWanted) onMotionState(BuddyState.WAITING)
                if (heartWanted) onHeartRateState(BuddyState.WAITING)
                if (motionWanted && !suspended) startMotion()
            }
            if (suspended) continue
            if (motionWanted && !motionStopping && batch.motion != null) {
                lastMotion = now()
                onMotionState(BuddyState.ACTIVE)
                onMotion(batch.motion)
            }
            if (heartWanted && batch.heartRate != null) {
                lastHeart = now()
                onHeartRateState(BuddyState.ACTIVE)
                onHeartRate(batch.heartRate)
            }
        }
    }

    private fun receiveRemote(body: ByteArray) {
        val packets = RemoteHid.packets(body) ?: return
        for (packet in packets) {
            if (packet.device == 0 || packet.response || packet.status) continue
            val connects = packet.kind == 0 && RemoteHid.motionDescriptor(packet.payload)
            if (connects || (packet.device == remoteService && packet.kind in setOf(0, 1))) {
                // A replacement address may name another sensor; never send the previous address a Stop.
                val same = connects && remoteService == packet.device && motionLive == packet.device
                if (same) stopMotion()
                remoteService = packet.device.takeIf { connects }
                if (!same) {
                    motionLive = null
                    motionStopping = false
                    stopRetry = false
                }
                motionSince = now()
                lastMotion = 0
                if (motionWanted) onMotionState(BuddyState.WAITING)
                if (motionWanted && !suspended) startMotion()
            }
            if (packet.kind == 2 && packet.device == remoteService && motionLive == packet.device &&
                packet.payload.size == 58 && motionWanted && !suspended && !motionStopping) {
                val report = MotionReport.parse(packet.payload, packet.timestampNs) ?: continue
                lastMotion = now()
                onMotionState(BuddyState.ACTIVE)
                onMotion(SensorReports.Motion(report.gyroRaw[1], report.gyroRaw[2]).also { it.report = report })
            }
        }
    }

    @Synchronized
    fun setHeadTrackingEnabled(enabled: Boolean): Boolean {
        if (closed || !isCurrent()) return false
        motionWanted = enabled
        if (enabled) {
            if (motionStopping) stopMotion()
            motionSince = now()
            lastMotion = 0
            onMotionState(BuddyState.WAITING)
            if (!suspended) startMotion()
        } else {
            stopMotion()
            onMotionState(BuddyState.INACTIVE)
        }
        return true
    }

    @Synchronized
    fun setHeartRateEnabled(enabled: Boolean): Boolean {
        if (closed || !isCurrent()) return false
        heartWanted = enabled
        heartJob?.cancel()
        heartJob = null
        if (enabled) {
            onHeartRateState(BuddyState.WAITING)
            if (!suspended) startHeartCampaign()
        } else {
            stopHeartWire()
            onHeartRateState(BuddyState.INACTIVE)
        }
        return true
    }

    @Synchronized
    fun setSuspended(value: Boolean) {
        if (closed || suspended == value) return
        suspended = value
        if (value) {
            stopMotion()
            heartJob?.cancel()
            heartJob = null
            if (heartWanted) stopHeartWire()
            if (motionWanted) onMotionState(BuddyState.WAITING)
            if (heartWanted) onHeartRateState(BuddyState.WAITING)
        } else {
            motionSince = now()
            lastMotion = 0
            if (motionWanted) startMotion()
            if (heartWanted) startHeartCampaign()
        }
    }

    private fun legacyMotion(): Int? = if (remoteAllowed() || reports.motionDescriptorSeen) null else firmwareMajor()?.let { if (it >= 8) 16 else 14 }

    private fun startMotion() {
        if (motionLive != null || suspended || !motionWanted || !isCurrent()) return
        if (remoteAllowed()) {
            val service = remoteService
            if (service == null) {
                if (!remoteRequested) {
                    remoteRequested = sendRaw(RemoteHid.connect(nextSequence()))
                    if (!remoteRequested) { motionWanted = false; onMotionState(BuddyState.INACTIVE) }
                }
                return
            }
            motionLive = service
            motionRemote = true
            if (!sendRaw(RemoteHid.feature(nextSequence(), service, 1, motionIntervalUs ?: 40_000))) {
                motionWanted = false
                stopMotion()
                onMotionState(BuddyState.INACTIVE)
                return
            }
            motionSince = now()
            return
        }
        val service = reports.motionService ?: legacyMotion()?.takeUnless { it == reports.heartRateService } ?: return
        val native = reports.motionService != null
        motionLive = service
        motionNative = native
        motionRemote = false
        if ((native && !command(service, 1, report = 4)) || !command(service, interval(native))) {
            motionWanted = false
            stopMotion()
            onMotionState(BuddyState.INACTIVE)
            return
        }
        motionSince = now()
    }

    private fun stopMotion(retry: Boolean = false) {
        remoteRequested = false
        val service = motionLive ?: return
        val sent = if (motionRemote) {
            !remoteAllowed() || service != remoteService || sendRaw(RemoteHid.feature(nextSequence(), service, 1, 0))
        } else if (service == (reports.motionService ?: legacyMotion())) {
            val fifo = !motionNative || command(service, 10, report = 4)
            command(service, 0) && fifo
        } else true
        motionStopping = !sent
        if (sent) { motionLive = null; stopRetry = false }
        else if (!retry) stopRetry = true
        lastMotion = 0
    }

    private fun interval(native: Boolean): Int = (motionIntervalUs ?: if (native) 20_000 else 40_000)
        .coerceAtLeast(if (native) 20_000 else 40_000)

    @Synchronized
    fun setMotionInterval(interval: Duration): Boolean {
        val us = interval.inWholeMicroseconds
        if (closed || !isCurrent() || motionStopping || us !in 20_000..1_000_000 || us % 1000 != 0L) return false
        val service = motionLive
        if (service != null) {
            val sent = if (motionRemote) sendRaw(RemoteHid.feature(nextSequence(), service, 1, us.toInt()))
                else command(service, us.toInt().coerceAtLeast(if (motionNative) 20_000 else 40_000))
            if (!sent) return false
        }
        motionIntervalUs = us.toInt()
        return true
    }

    private fun startHeartCampaign() {
        val epoch = ++heartEpoch
        heartJob?.cancel()
        heartJob = scope.launch {
            fun wanted() = synchronized(this@RTBuddyManager) {
                epoch == heartEpoch && !closed && heartWanted && !suspended && isCurrent()
            }
            try {
                repeat(2) {
                    if (!wanted()) return@launch
                    synchronized(this@RTBuddyManager) { lastHeart = 0 }
                    for ((packet, pause) in HEART_INIT) {
                        if (!wanted() || !sendRaw(packet)) return@launch
                        delay(pause)
                    }
                    if (!wanted() || !sendRaw(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 1, 0, 0, 0))) return@launch
                    delay(120)
                    if (!wanted() || !command(84, 1_000_000, heart = true)) return@launch
                    delay(120)
                    if (!wanted() || !command(19, 1_000_000, heart = true)) return@launch
                    val started = now()
                    while (wanted()) {
                        val last = synchronized(this@RTBuddyManager) { lastHeart }
                        if (now() - maxOf(started, last) >= 12_000) break
                        delay(250)
                    }
                    if (!wanted()) return@launch
                    synchronized(this@RTBuddyManager) { stopHeartWire() }
                }
            } finally {
                synchronized(this@RTBuddyManager) {
                    if (wanted()) {
                        stopHeartWire()
                        heartWanted = false
                        onHeartRateState(BuddyState.INACTIVE)
                    }
                }
            }
        }
    }

    private fun stopHeartWire() {
        command(84, 0, heart = true)
        command(19, 0, heart = true)
        if (!closed && isCurrent()) sendRaw(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 2, 0, 0, 0))
        lastHeart = 0
    }

    @Synchronized
    private fun command(service: Int, value: Int, report: Int = 1, heart: Boolean = false): Boolean {
        if (closed || !isCurrent() || service !in 1..127 || value < 0) return false
        val setting = SensorServiceSetting.newBuilder().setServiceValue(service).setSetting(2)
            .setConfiguration(ByteString.copyFrom(byteArrayOf(report.toByte()) + ByteArray(4) { (value ushr (it * 8)).toByte() }))
        val data = SensorDataWX.newBuilder().setServiceSettings(setting)
        if (heart) data.setLogType(2)
        return sendSensorData(data.build())
    }

    @Synchronized
    fun sendSensorData(data: SensorDataWX): Boolean {
        if (closed || !isCurrent()) return false
        return sendPacket(RTBuddyPacket.create(SensorDataWxBuddyPayload(data.toBuilder().setSeq(nextSequence()).build()), PacketDestination.DEVICE))
    }

    private fun nextSequence(): Int { sequence = (sequence + 1) and 127; return sequence }

    fun setSensorServiceReportInterval(sensorServiceType: SensorServiceType, interval: Duration): Boolean =
        interval.inWholeMicroseconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let {
            command(sensorServiceType.number, it.toInt())
        } ?: false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        motionWanted = false
        heartWanted = false
        onMotionState(BuddyState.INACTIVE)
        onHeartRateState(BuddyState.INACTIVE)
    }

    companion object {
        private fun now() = System.nanoTime() / 1_000_000
        private val HEART_INIT = listOf(
            byteArrayOf(0, 0, 0, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 0, 0, 1, 0, 0) to 220L,
            byteArrayOf(0, 0, 4, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 4, 0, 1, 0, 0) to 220L,
        )
    }
}
