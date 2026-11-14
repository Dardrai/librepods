/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.utils.HeadTracking
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal data class SpatialMotionCaptureRequest(val expectedMac: String, val phase: String, val durationSeconds: Int = 10) {
    init {
        MacAddress(expectedMac)
        require(phase in setOf("neutral", "yaw", "pitch", "roll")) { "Phase must be neutral, yaw, pitch, or roll." }
        require(durationSeconds in 1..10) { "Capture duration must be between 1 and 10 seconds." }
    }
}

internal data class SpatialMotionCaptureResult(val file: File?, val samples: Int, val reason: String)

/** Explicit debug capture of uncalibrated fields; it never converts or submits a pose. */
internal class SpatialMotionCapture(
    private val directory: File,
    private val motion: Flow<MotionReport?> = HeadTracking.motion,
    private val arrivalNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    suspend fun capture(
        request: SpatialMotionCaptureRequest,
        device: AppleDevice,
        isCurrent: () -> Boolean,
        onFile: (File) -> Unit = {},
    ): SpatialMotionCaptureResult = withContext(Dispatchers.IO) {
        if (!capturing.compareAndSet(false, true)) return@withContext SpatialMotionCaptureResult(null, 0, "Another motion capture is running.")
        val owner = Any()
        var file: File? = null
        var samples = 0
        try {
            check(device.macAddress.value.equals(request.expectedMac, ignoreCase = true)) { "The requested AirPods do not match." }
            check(isCurrent()) { "The expected AirPods are not connected." }
            check(device.startHeadTracking(owner)) { "Motion capture is unavailable." }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the private capture directory." }
            val output = File.createTempFile("${request.phase}-", ".jsonl", directory)
            file = output
            val metadata = device.metadata.value
            val reason = output.bufferedWriter().use { writer ->
                val startedArrival = arrivalNs()
                check(startedArrival > 0) { "Host arrival clock must be positive and increasing." }
                writer.appendLine(JSONObject().apply {
                    put("type", "capture")
                    put("expected_mac", request.expectedMac.uppercase(Locale.ROOT))
                    put("phase", request.phase)
                    put("duration_seconds", request.durationSeconds)
                    put("model_number", metadata.modelNumber)
                    put("firmware", metadata.version1)
                    put("started_arrival_ns", startedArrival)
                }.toString())
                onFile(output)
                fun requireCurrent() {
                    if (!isCurrent() || device.state.value.headTrackingState == BuddyState.INACTIVE)
                        throw CaptureStopped("Stopped: connection or motion request ended.")
                }
                var previousCounter: Long? = null
                var previousArrival = startedArrival
                val finished = withTimeoutOrNull(request.durationSeconds * 1_000L) {
                    coroutineScope {
                        val guard = launch {
                            while (true) { requireCurrent(); delay(100) }
                        }
                        try {
                            // StateFlow's first value may belong to an earlier stream.
                            motion.drop(1).filterNotNull().filter { report ->
                                val counter = report.counter
                                val previous = previousCounter
                                if (counter != null && previous != null) {
                                    val delta = (counter - previous) and 0xffffffffL
                                    if (delta == 0L) return@filter false
                                    if (delta > 0x7fffffffL) throw CaptureStopped("Stopped: sensor counter moved backward.")
                                }
                                previousCounter = counter
                                true
                            }.take(MAX_SAMPLES).collect { report ->
                                requireCurrent()
                                val arrived = arrivalNs()
                                check(arrived > previousArrival) { "Host arrival clock must be positive and increasing." }
                                previousArrival = arrived
                                writer.appendLine(JSONObject().apply {
                                    put("type", "sample")
                                    put("arrival_ns", arrived)
                                    put("report", JSONObject()
                                        .put("timestamp_ns", report.timestampNs ?: JSONObject.NULL)
                                        .put("attitude_q15", JSONArray(report.attitudeQ15))
                                        .put("gyro_raw", JSONArray(report.gyroRaw))
                                        .put("user_acceleration_raw", JSONArray(report.userAccelerationRaw))
                                        .put("gravity_raw", JSONArray(report.gravityRaw))
                                        .put("counter", report.counter ?: JSONObject.NULL))
                                }.toString())
                                samples++
                            }
                        } finally { guard.cancel() }
                    }
                    true
                } ?: false
                if (finished) "Sample limit reached." else "Capture duration complete."
            }
            SpatialMotionCaptureResult(output, samples, reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SpatialMotionCaptureResult(file, samples, error.message ?: "Motion capture failed.")
        } finally {
            try { device.stopHeadTracking(owner) } finally { capturing.set(false) }
        }
    }

    private class CaptureStopped(message: String) : Exception(message)

    companion object {
        const val MAX_SAMPLES = 500
        private val capturing = AtomicBoolean(false)
    }
}
