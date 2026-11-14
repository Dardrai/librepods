/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocol
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class SpatialHeadTrackingProbeRequest(val expectedMac: String, val durationSeconds: Int = 30) {
    init {
        MacAddress(expectedMac)
        require(durationSeconds in 5..30) { "Probe duration must be between 5 and 30 seconds." }
    }
}

internal data class SpatialHeadTrackingProbeResult(val reason: String, val poses: Int, val cleanup: Bundle?)

/** Explicit, foreground DEBUG proof. Calibration is never persisted or reused after a bud handover. */
internal class SpatialHeadTrackingProbe(
    private val context: Context,
    private val motion: Flow<MotionReport?> = HeadTracking.motion,
    private val client: () -> ShizukuAudioClient = { ShizukuAudioClient(context, requireRoot = true) },
) {
    suspend fun run(request: SpatialHeadTrackingProbeRequest, device: AppleDevice, isCurrent: () -> Boolean,
        onStatus: (String) -> Unit): SpatialHeadTrackingProbeResult = withContext(Dispatchers.Default) {
        val live = AtomicBoolean(true)
        val owner = Any()
        val submitted = AtomicInteger()
        var access: ShizukuAudioClient? = null
        var lease: ShizukuAudioClient.HeadTrackerProbeLease? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var cleanup: Bundle? = null
        var reason = "stopped"
        try {
            check(BuildConfig.DEBUG) { "Debug build required." }
            check(device.macAddress.value.equals(request.expectedMac, true) && isCurrent()) { "The expected AirPods are not connected." }
            check(device.metadata.value.model == AirPodsModel.AIRPODS_PRO_3) { "This measured motion format is for AirPods Pro 3." }
            val role = device.state.value.budRoleRaw.firstOrNull()
            check(role == 1 || role == 2) { "Waiting for the current bud role. No probe started." }
            fun current() = live.get() && isCurrent() && device.state.value.budRoleRaw.firstOrNull() == role
            fun requireCurrent() {
                check(current() && device.state.value.headTrackingState != BuddyState.INACTIVE) { "Connection, bud role or motion stream changed." }
            }
            check(!device.settings.value.headTrackingIntervalConfigured ||
                device.settings.value.headTrackingInterval.inWholeMilliseconds <= 40) { "The saved motion interval is too slow for head tracking." }
            val bridge = client().also { access = it; it.connect() }
            withTimeout(5_000) { bridge.state.first { it.connected && bridge.hasRootPrivileges } }
            val initial = bridge.inspectSpatialAudio(request.expectedMac, ::current)
            check(initial.getBoolean("device_enabled") && initial.getBoolean("enabled")) { "Enable Fixed Spatial Audio first." }
            check(device.startHeadTracking(owner)) { "Motion capture is unavailable." }
            withTimeout(45_000) {
                coroutineScope {
                    val samples = Array(3) { mutableListOf<MotionReport>() }
                    val sampleLock = Any()
                    var phase = 0
                    var converter: SpatialPoseConverter? = null
                    var previousCounter: Long? = null
                    val latest = MutableStateFlow<Pair<SpatialPose, Long>?>(null)
                    val reporting = MutableStateFlow(false)
                    val stopped = CompletableDeferred<String>()
                    val guard = launch {
                        while (true) { requireCurrent(); delay(100) }
                    }
                    val reader = launch {
                        motion.drop(1).filterNotNull().collect { report ->
                            requireCurrent()
                            if (report.counter != null && report.counter == previousCounter) return@collect
                            previousCounter = report.counter
                            val arrived = SystemClock.elapsedRealtimeNanos()
                            synchronized(sampleLock) {
                                if (phase < 3) {
                                    check(samples[phase].size < 500) { "Calibration sample limit reached." }
                                    samples[phase] += report
                                } else {
                                    val source = checkNotNull(converter)
                                    source.accept(report)?.let { latest.value = it to arrived }
                                    check(!source.invalidated) { "Motion reference changed: ${source.invalidReason}" }
                                }
                            }
                        }
                    }
                    var writer: kotlinx.coroutines.Job? = null
                    try {
                        onStatus("Hold your head level and face forward.")
                        delay(2_000)
                        synchronized(sampleLock) { phase = 1 }
                        onStatus("Slowly look UP, then return to level.")
                        delay(3_000)
                        synchronized(sampleLock) { phase = 2 }
                        onStatus("Hold level and face forward again.")
                        delay(2_000)
                        synchronized(sampleLock) {
                            val profile = SpatialPoseProfile.calibrate(samples[0], samples[1], samples[2])
                            check(profile != null) { "Calibration was not steady or the UP direction was unclear. No head tracking enabled." }
                            converter = SpatialPoseConverter(profile, 0)
                            phase = 3
                        }
                        // Keep this one motion owner across calibration and proof; restarting may reset its reference.
                        withTimeout(1_000) { latest.filterNotNull().first() }
                        pipe = ParcelFileDescriptor.createPipe()
                        val output = checkNotNull(pipe)[1].fileDescriptor
                        Os.fcntlInt(output, OsConstants.F_SETFL, Os.fcntlInt(output, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                        writer = launch(Dispatchers.IO) {
                            var lastSubmitted = 0L
                            combine(latest, reporting) { sample, enabled -> sample.takeIf { enabled } }
                                .filterNotNull().collect { (pose, arrival) ->
                                    requireCurrent()
                                    if (arrival <= lastSubmitted) return@collect
                                    val frame = HeadTrackerUhidProtocol.little(ByteArray(HeadTrackerUhidProtocol.FRAME_BYTES))
                                        .putInt(HeadTrackerUhidProtocol.FRAME_MAGIC).putInt(pose.discontinuity).putLong(arrival)
                                    (pose.rotationVector + pose.angularVelocity).forEach { frame.putFloat(it) }
                                    // One atomic nonblocking pipe write: a slow sink cannot stall motion or app cleanup.
                                    check(Os.write(output, frame.array(), 0, frame.capacity()) == frame.capacity()) { "Pose pipe stalled." }
                                    lastSubmitted = arrival
                                    submitted.incrementAndGet()
                                }
                        }
                        onStatus("Calibration accepted. Preparing Android's head-tracking sensor…")
                        lease = bridge.startHeadTrackerProbe(request.expectedMac, checkNotNull(pipe)[0], request.durationSeconds,
                            ::current, { enabled, _ -> reporting.value = enabled }, { stopped.complete(it) })
                        onStatus("Head-tracking sensor ready for ${request.durationSeconds} seconds.\nKeep this screen open. Audio is started separately.")
                        reason = withTimeoutOrNull(request.durationSeconds * 1_000L) { stopped.await() } ?: "duration_complete"
                    } finally {
                        guard.cancel(); reader.cancel(); writer?.cancel()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            reason = "Diagnostic deadline reached."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reason = error.message ?: "Head-tracking probe failed."
        } finally {
            live.set(false)
            withContext(NonCancellable + Dispatchers.IO) {
                cleanup = runCatching { lease?.finish() }.getOrNull()
                pipe?.forEach { runCatching { it.close() } }
                runCatching { device.stopHeadTracking(owner) }
                access?.close()
            }
        }
        SpatialHeadTrackingProbeResult(reason, submitted.get(), cleanup)
    }
}
