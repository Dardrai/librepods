/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

internal data class SpatialHeadTrackingProbeRequest(val expectedMac: String, val durationSeconds: Int = 30) {
    init {
        MacAddress(expectedMac)
        require(durationSeconds in 5..30) { "Probe duration must be between 5 and 30 seconds." }
    }
}

internal enum class SpatialCalibrationStage { PREPARING, NEUTRAL, LOOK_UP, RETURN, READY }
internal data class SpatialCalibrationProgress(val stage: SpatialCalibrationStage, val secondsRemaining: Int)
internal data class SpatialHeadTrackingProbeResult(val reason: String, val poses: Int, val cleanup: Bundle?,
    val calibrated: Boolean = false, val calibrationFailure: SpatialCalibrationFailure? = null, val captureFile: File? = null)

/** Foreground calibration. The debug build can also verify and capture the sensor session. */
internal class SpatialHeadTrackingProbe(
    private val context: Context,
    private val motion: Flow<MotionReport?> = HeadTracking.motion,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val client: () -> ShizukuAudioClient = { ShizukuAudioClient(context, requireRoot = true) },
) {
    suspend fun run(request: SpatialHeadTrackingProbeRequest, device: AppleDevice, isCurrent: () -> Boolean,
        onStatus: (String) -> Unit = {}, onProgress: (SpatialCalibrationProgress) -> Unit = {}): SpatialHeadTrackingProbeResult = withContext(dispatcher) {
        val live = AtomicBoolean(true)
        val owner = Any()
        val submitted = AtomicInteger()
        var access: ShizukuAudioClient? = null
        var lease: ShizukuAudioClient.HeadTrackerLease? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var cleanup: Bundle? = null
        var reason = "stopped"
        var diagnosticReason = "stopped"
        var calibrated = false
        var calibrationFailure: SpatialCalibrationFailure? = null
        var captureFile: File? = null
        var captureHeader: JSONObject? = null
        val sampleLock = Any()
        val samples = Array(3) { mutableListOf<Pair<MotionReport, Long>>() }
        try {
            withTimeout(55_000) {
                check(nativeSpatialVersion) { "Head tracking calibration is unavailable on this Android version." }
                check(device.macAddress.value.equals(request.expectedMac, true) && isCurrent()) { "The expected AirPods are not connected." }
                val metadata = device.metadata.value
                check(metadata.model == AirPodsModel.AIRPODS_PRO_3) { "This measured motion format is for AirPods Pro 3." }
                val roleRaw = device.state.value.budRoleRaw.toList()
                val role = roleRaw.firstOrNull()
                val leftIsPrimary = device.state.value.leftIsPrimary
                check(role == 1 || role == 2) { "Waiting for the current bud role. No probe started." }
                fun current() = live.get() && isCurrent() && device.state.value.budRoleRaw == roleRaw &&
                    device.metadata.value.let { it.model == metadata.model &&
                        spatialPoseModelIdentity(it.modelNumber) == spatialPoseModelIdentity(metadata.modelNumber) &&
                        it.version1 == metadata.version1 }
                fun requireCurrent() {
                    check(current() && device.state.value.headTrackingState != BuddyState.INACTIVE) { "Connection, bud role or motion stream changed." }
                }
                check(!device.settings.value.headTrackingIntervalConfigured ||
                    device.settings.value.headTrackingInterval.inWholeMilliseconds <= 40) { "The saved motion interval is too slow for head tracking." }
                onProgress(SpatialCalibrationProgress(SpatialCalibrationStage.PREPARING, 0))
                val bridge = client().also { access = it; it.connect() }
                withTimeout(5_000) { bridge.state.first { it.connected && bridge.hasRootPrivileges } }
                val initial = bridge.inspectSpatialAudio(request.expectedMac, ::current)
                check(initial.getBoolean("device_enabled") && initial.getBoolean("enabled")) { "spatial_disabled" }
                check(device.startHeadTracking(owner)) { "Motion capture is unavailable." }
                var previousArrival = SystemClock.elapsedRealtimeNanos()
                if (BuildConfig.DEBUG) captureHeader = JSONObject().apply {
                    put("type", "capture"); put("expected_mac", request.expectedMac.uppercase(Locale.ROOT))
                    put("phase", "head_tracking_calibration"); put("duration_seconds", 13)
                    put("model_number", metadata.modelNumber); put("firmware", metadata.version1)
                    put("model", metadata.model.name); put("known_role", role)
                    put("bud_role_raw", JSONArray(roleRaw))
                    put("left_is_primary", leftIsPrimary)
                    put("started_arrival_ns", previousArrival)
                    put("phase_seconds", JSONObject().put("neutral", 3).put("look_up", 5).put("return", 5))
                    put("max_samples_per_phase", MAX_PHASE_SAMPLES)
                }
                coroutineScope {
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
                            check(arrived > 0 && arrived > previousArrival) { "Host arrival clock changed." }
                            previousArrival = arrived
                            synchronized(sampleLock) {
                                if (phase < 3) {
                                    check(samples[phase].size < MAX_PHASE_SAMPLES) { "Calibration sample limit reached." }
                                    samples[phase] += report to arrived
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
                        suspend fun countdown(stage: SpatialCalibrationStage, index: Int, seconds: Int, instruction: String) {
                            synchronized(sampleLock) { phase = index }
                            for (remaining in seconds downTo 1) {
                                requireCurrent()
                                onProgress(SpatialCalibrationProgress(stage, remaining))
                                onStatus("$instruction\n$remaining seconds remaining.")
                                delay(1_000)
                            }
                        }
                        countdown(SpatialCalibrationStage.NEUTRAL, 0, 3, "Hold your head still, level and facing forward.")
                        countdown(SpatialCalibrationStage.LOOK_UP, 1, 5, "Slowly look UP, then hold that position.")
                        countdown(SpatialCalibrationStage.RETURN, 2, 5, "Return to your starting position and hold still.")
                        val calibratedProfile = synchronized(sampleLock) {
                            val result = SpatialPoseProfile.calibrateResult(samples[0].map { it.first },
                                samples[1].map { it.first }, samples[2].map { it.first })
                            calibrationFailure = result.failure
                            val profile = result.profile
                            check(profile != null) { "calibration_rejected" }
                            converter = SpatialPoseConverter(profile, 0)
                            calibrated = true
                            phase = 3
                            profile
                        }
                        val saved = runCatching { SpatialPoseProfileStore(context).save(SpatialPoseProfileKey(request.expectedMac,
                            roleRaw, metadata.modelNumber, metadata.version1), calibratedProfile) }.getOrDefault(false)
                        check(saved) { "profile_save_failed" }
                        if (!BuildConfig.DEBUG) {
                            reason = "Calibration complete."
                            return@coroutineScope
                        }
                        onStatus("Calibration accepted. Preparing Android's head-tracking sensor…")
                        onProgress(SpatialCalibrationProgress(SpatialCalibrationStage.PREPARING, 0))
                        // Keep this one motion owner across calibration and proof; restarting may reset its reference.
                        withTimeout(3_000) { latest.filterNotNull().first() }
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
                        lease = bridge.startHeadTrackerProbe(request.expectedMac, checkNotNull(pipe)[0], request.durationSeconds,
                            ::current, { enabled, _ -> reporting.value = enabled }, { stopped.complete(it) })
                        onStatus("Head-tracking sensor ready for ${request.durationSeconds} seconds.\nKeep this screen open. Audio is started separately.")
                        onProgress(SpatialCalibrationProgress(SpatialCalibrationStage.READY, request.durationSeconds))
                        reason = withTimeoutOrNull(request.durationSeconds * 1_000L) { stopped.await() } ?: "duration_complete"
                        diagnosticReason = reason
                    } finally {
                        guard.cancel(); reader.cancel(); writer?.cancel()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            diagnosticReason = "deadline"
            reason = "Motion data took too long. Keep your AirPods connected and try again."
        } catch (cancelled: CancellationException) {
            diagnosticReason = "cancelled"
            reason = "Calibration stopped."
            throw cancelled
        } catch (error: Exception) {
            diagnosticReason = error.message ?: "probe_failed"
            reason = calibrationFailure?.let(::calibrationMessage) ?: when {
                diagnosticReason.startsWith("Motion reference changed:") -> "Motion changed unexpectedly. Keep your AirPods in place and try again."
                else -> error.message ?: "Head tracking could not start. Keep your AirPods connected and try again."
            }
        } finally {
            live.set(false)
            withContext(NonCancellable + Dispatchers.IO) {
                cleanup = runCatching { lease?.finish() }.getOrNull()
                pipe?.forEach { runCatching { it.close() } }
                runCatching { device.stopHeadTracking(owner) }
                runCatching { access?.close() }
                captureHeader?.let { header ->
                    captureFile = runCatching {
                        val directory = File(checkNotNull(context.filesDir), "spatial-head-tracker-calibrations")
                        check(directory.isDirectory || directory.mkdirs())
                        File.createTempFile("calibration-", ".jsonl", directory).also { output ->
                            output.bufferedWriter().use { writer ->
                                writer.appendLine(header.toString())
                                synchronized(sampleLock) {
                                    samples.forEachIndexed { index, phaseSamples ->
                                        phaseSamples.forEach { (report, arrival) ->
                                            writer.appendLine(motionCaptureSample(report, arrival)
                                                .put("calibration_phase", PHASE_NAMES[index]).toString())
                                        }
                                    }
                                }
                                writer.appendLine(JSONObject().put("type", "calibration_result")
                                    .put("calibrated", calibrated).put("calibration_failure", calibrationFailure?.name ?: JSONObject.NULL)
                                    .put("reason", diagnosticReason).put("poses", submitted.get())
                                    .put("ended_bud_role_raw", JSONArray(device.state.value.budRoleRaw)).toString())
                            }
                        }
                    }.getOrNull()
                }
            }
        }
        SpatialHeadTrackingProbeResult(reason, submitted.get(), cleanup, calibrated, calibrationFailure, captureFile)
    }

    private fun calibrationMessage(failure: SpatialCalibrationFailure): String = when (failure) {
        SpatialCalibrationFailure.NEUTRAL_UNSTEADY, SpatialCalibrationFailure.NEUTRAL_GRAVITY_INVALID ->
            "Hold your head still and level at the start, then try again."
        SpatialCalibrationFailure.RETURN_UNSTEADY, SpatialCalibrationFailure.RETURN_NOT_NEUTRAL,
        SpatialCalibrationFailure.RETURN_GRAVITY_INVALID -> "Return to your starting position and hold still until calibration finishes."
        SpatialCalibrationFailure.UP_TOO_SMALL -> "Look a little farther up, then return to your starting position."
        SpatialCalibrationFailure.UP_AXIS_NOT_HORIZONTAL, SpatialCalibrationFailure.UP_AXIS_INCONSISTENT,
        SpatialCalibrationFailure.INVALID_BASIS -> "Look straight up without turning sideways, then return to level."
        else -> "Not enough steady motion data. Keep your AirPods connected and try again."
    }

    companion object {
        private const val MAX_PHASE_SAMPLES = 1000
        private val PHASE_NAMES = listOf("neutral", "look_up", "return")
    }
}
