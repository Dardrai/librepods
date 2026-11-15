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
import kotlin.time.Duration.Companion.microseconds

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
    private val sharedMotionOwners: MotionOwners? = null,
    private val sourceTiming: MotionSourceTiming? = null,
    private val sourceClockNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = SensorFrames()
    private val reports = SensorReports()
    private var sequence = 0
    private var descriptorsRequested = false
    private val legacyMotionOwner = Any()
    private val motionOwners = sharedMotionOwners ?: MotionOwners()
    private var motionWanted = false
    private var heartWanted = false
    private var suspended = false
    private var closed = false
    private var motionLive: Int? = null
    private var motionAppliedIntervalUs: Int? = null
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
                    if (closed || !isCurrent()) return@synchronized
                    reconcileHeadTracking()
                    if (stopRetry && isCurrent()) { stopRetry = false; stopMotion(retry = true) }
                    if (motionWanted && !suspended) {
                        if (motionLive == null) startMotion()
                        if (now() - maxOf(motionSince, lastMotion) >= 5_000) {
                            clearMotionRequest()
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

    fun feed(chunk: ByteArray, readAtNanos: Long = 0) {
        val timing = sourceTiming?.takeIf { readAtNanos > 0 }
        val attemptedAt = if (timing != null) sourceClockNanos() else 0
        timing?.dispatch(readAtNanos, attemptedAt)
        synchronized(this) {
            val enteredAt = if (timing != null) sourceClockNanos() else 0
            timing?.feedEntered(attemptedAt, enteredAt)
            try { feedLocked(chunk, readAtNanos) }
            finally { timing?.feedFinished(enteredAt, sourceClockNanos()) }
        }
    }

    private fun feedLocked(chunk: ByteArray, readAtNanos: Long) {
        if (closed || !isCurrent()) return
        reconcileHeadTracking(updateInterval = false)
        for (frame in frames.feed(chunk)) {
            val packet = try { RTBuddyPacket.parse(frame) } catch (_: Exception) { continue }
            val remote = packet.rtBuddyPayload as? RemoteHidBuddyPayload
            if (remote != null) {
                if (remoteAllowed()) receiveRemote(remote.data, readAtNanos)
                continue
            }
            if (remoteAllowed()) continue
            val data = (packet.rtBuddyPayload as? SensorDataWxBuddyPayload)?.data ?: continue
            val batch = reports.receive(data, legacyMotion())
            if (data.hasStartAck() || data.hasCommandAck() || data.hasServiceSettings())
                sourceTiming?.motionControlReceived(data, reports.motionService, readAtNanos)
            if (batch.mappingChanged) sourceTiming?.mappingChanged(
                batch.motionMappingChanged, batch.heartRateMappingChanged, sourceClockNanos())
            if (batch.motionMappingChanged) {
                sourceTiming?.motionStopped()
                // The previous address can now name another sensor; never send it a delayed Stop.
                motionLive = null
                motionAppliedIntervalUs = null
                motionStopping = false
                stopRetry = false
                motionSince = now()
                lastMotion = 0
                if (motionWanted) onMotionState(BuddyState.WAITING)
            }
            if (batch.heartRateMappingChanged) {
                lastHeart = 0
                if (heartWanted) onHeartRateState(BuddyState.WAITING)
            }
            if (batch.motionMappingChanged && motionWanted && !suspended) startMotion()
            if (suspended) continue
            if (motionWanted && !motionStopping && batch.motion != null) {
                lastMotion = now()
                onMotionState(BuddyState.ACTIVE)
                onMotion(batch.motion)
                sourceTiming?.motionPublished(batch.motion.report, readAtNanos, sourceClockNanos())
            }
            if (heartWanted && batch.heartRate != null) {
                lastHeart = now()
                onHeartRateState(BuddyState.ACTIVE)
                onHeartRate(batch.heartRate)
            }
        }
    }

    private fun receiveRemote(body: ByteArray, readAtNanos: Long) {
        val packets = RemoteHid.packets(body) ?: return
        for (packet in packets) {
            if (packet.device == 0 || packet.response || packet.status) continue
            val connects = packet.kind == 0 && RemoteHid.motionDescriptor(packet.payload)
            if (connects || (packet.device == remoteService && packet.kind in setOf(0, 1))) {
                sourceTiming?.motionStopped()
                // A replacement address may name another sensor; never send the previous address a Stop.
                val same = connects && remoteService == packet.device && motionLive == packet.device
                if (same) stopMotion()
                remoteService = packet.device.takeIf { connects }
                if (!same) {
                    motionLive = null
                    motionAppliedIntervalUs = null
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
                sourceTiming?.motionPublished(report, readAtNanos, sourceClockNanos())
            }
        }
    }

    @Synchronized
    fun setHeadTrackingEnabled(enabled: Boolean, owner: Any = legacyMotionOwner, interval: Duration? = null): Boolean {
        if (closed || !isCurrent()) return false
        val us = interval?.inWholeMicroseconds
        // This is a bounded request, not a claim about the firmware's minimum interval.
        if (enabled && us != null && (us !in 5_000..1_000_000 || interval != us.microseconds)) return false
        val previous = motionOwners.request(owner)
        val request = motionOwners.set(owner, enabled, us?.toInt())
        val accepted = reconcileHeadTracking() && (!enabled || motionOwners.request(owner) === request)
        if (!accepted && enabled) {
            // A failed rate change must not replace another client's live stream or claim.
            motionOwners.restore(owner, requireNotNull(request), previous)
        }
        return accepted
    }

    @Synchronized
    fun reconcileHeadTracking(updateInterval: Boolean = true): Boolean {
        if (closed || !isCurrent()) return false
        val wanted = motionOwners.wanted()
        // Incoming samples must not turn a failed rate restoration into packet-rate retries.
        if (motionWanted == wanted) return !updateInterval || applyMotionInterval()
        motionWanted = wanted
        if (wanted) {
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
    fun stopAllHeadTracking() {
        if (closed || !isCurrent()) return
        motionOwners.clear()
        reconcileHeadTracking()
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
                    if (!remoteRequested) { clearMotionRequest(); onMotionState(BuddyState.INACTIVE) }
                }
                return
            }
            motionLive = service
            motionRemote = true
            val period = motionIntervalUs ?: 40_000
            if (!sendRaw(RemoteHid.feature(nextSequence(), service, 1, period))) {
                clearMotionRequest()
                stopMotion()
                onMotionState(BuddyState.INACTIVE)
                return
            }
            motionAppliedIntervalUs = period
            sourceTiming?.motionStarted(period, sourceClockNanos())
            motionSince = now()
            return
        }
        val service = reports.motionService ?: legacyMotion()?.takeUnless { it == reports.heartRateService } ?: return
        val native = reports.motionService != null
        motionLive = service
        motionNative = native
        motionRemote = false
        val period = interval(native)
        if ((native && !command(service, 1, report = 4)) || !command(service, period)) {
            clearMotionRequest()
            stopMotion()
            onMotionState(BuddyState.INACTIVE)
            return
        }
        motionAppliedIntervalUs = period
        sourceTiming?.motionStarted(period, sourceClockNanos())
        motionSince = now()
    }

    private fun stopMotion(retry: Boolean = false) {
        sourceTiming?.motionStopped()
        remoteRequested = false
        val service = motionLive ?: return
        val sent = if (motionRemote) {
            !remoteAllowed() || service != remoteService || sendRaw(RemoteHid.feature(nextSequence(), service, 1, 0))
        } else if (service == (reports.motionService ?: legacyMotion())) {
            val fifo = !motionNative || command(service, 10, report = 4)
            command(service, 0) && fifo
        } else true
        motionStopping = !sent
        if (sent) { motionLive = null; motionAppliedIntervalUs = null; stopRetry = false }
        else if (!retry) stopRetry = true
        lastMotion = 0
    }

    private fun clearMotionRequest() {
        if (isCurrent()) motionOwners.clear()
        motionWanted = false
    }

    private fun interval(native: Boolean): Int {
        val baseline = (motionIntervalUs ?: if (native) 20_000 else 40_000)
            .coerceAtLeast(if (native) 20_000 else 40_000)
        // Only an identified native WX stream gets temporary owner rates. Legacy and
        // RemoteHID retain their existing rate policy.
        return if (native) minOf(baseline, motionOwners.fastestIntervalUs() ?: baseline) else baseline
    }

    private fun applyMotionInterval(): Boolean {
        val service = motionLive ?: return true
        if (suspended) return true
        if (motionStopping) return false
        val period = if (motionRemote) motionIntervalUs ?: 40_000 else interval(motionNative)
        if (period == motionAppliedIntervalUs) return true
        val sent = if (motionRemote) sendRaw(RemoteHid.feature(nextSequence(), service, 1, period))
            else command(service, period)
        if (sent) {
            motionAppliedIntervalUs = period
            sourceTiming?.motionIntervalChanged(period, sourceClockNanos())
        }
        // Releases remain authoritative on failure; reconciliation retries restoration.
        return sent
    }

    @Synchronized
    fun setMotionInterval(interval: Duration): Boolean {
        val us = interval.inWholeMicroseconds
        if (closed || !isCurrent() || motionStopping || us !in 20_000..1_000_000 || us % 1000 != 0L) return false
        val previous = motionIntervalUs
        motionIntervalUs = us.toInt()
        if (!applyMotionInterval()) { motionIntervalUs = previous; return false }
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
        sourceTiming?.motionStopped()
        scope.cancel()
        if (sharedMotionOwners == null) motionOwners.clear()
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
