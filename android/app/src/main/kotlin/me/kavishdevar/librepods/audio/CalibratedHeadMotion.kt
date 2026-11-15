/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocol
import kotlin.time.Duration.Companion.milliseconds

internal class CalibratedHeadMotionFault(reason: String) : IllegalStateException(reason)

internal fun spatialPoseProfileKey(device: AppleDevice): SpatialPoseProfileKey? {
    val metadata = device.metadata.value
    val role = device.state.value.budRoleRaw.toList()
    if (metadata.model != AirPodsModel.AIRPODS_PRO_3 || role.firstOrNull() !in listOf(1, 2) ||
        metadata.modelNumber.isBlank() || metadata.version1.isBlank()) return null
    return SpatialPoseProfileKey(device.macAddress.value, role,
        spatialPoseModelIdentity(metadata.modelNumber), metadata.version1)
}

/** A renderer owns its source request; the caller guards the exact socket/profile/session identity. */
internal suspend fun streamCalibratedHeadMotion(device: AppleDevice, motion: Flow<MotionSample?>,
    profile: SpatialPoseProfile, discontinuity: Int, current: () -> Boolean,
    timing: HeadTrackingTiming = HeadTrackingTiming(), now: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    tryFast: Boolean = true, onFallback: () -> Unit = {},
    submit: (SpatialPose, Long) -> Unit) {
    val owner = Any()
    try {
        check(current()) { "owner_changed" }
        val startedAt = now()
        val fast = tryFast && device.startHeadTracking(owner, 10.milliseconds)
        if (!fast) {
            timing.fallingBack(now(), if (tryFast) "fast_request_refused" else "socket_fallback")
            onFallback()
            check(current() && device.startHeadTracking(owner, 20.milliseconds)) { "motion_unavailable" }
        }
        timing.begin(startedAt); timing.requested(if (fast) 10_000 else 20_000, fallback = !fast)
        val converter = SpatialPoseConverter(profile, discontinuity)
        var previousCounter: Long? = null
        val firstAttitude = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lastAttitudeAt = AtomicLong()
        kotlinx.coroutines.coroutineScope {
            val fallback = if (fast) launch(Dispatchers.IO) {
                // Cold audio startup can take ~1.8 s; leave 2 s for fallback within the sink's 5 s startup budget.
                val received = withTimeoutOrNull(3_000) { firstAttitude.await(); true } == true
                var fallbackAt = now()
                var fallbackLastAttitudeAt = lastAttitudeAt.get()
                if (received) {
                    while (current()) {
                        delay(25)
                        fallbackAt = now()
                        fallbackLastAttitudeAt = lastAttitudeAt.get()
                        if (fallbackAt - fallbackLastAttitudeAt >= 150_000_000L) break
                    }
                }
                if (current()) {
                    // Keep this socket at its working rate even if root freshness closes during this write.
                    timing.fallingBack(fallbackAt, if (received) "attitude_stall" else "attitude_startup_timeout",
                        fallbackLastAttitudeAt)
                    onFallback()
                    check(device.startHeadTracking(owner, 20.milliseconds)) { "motion_unavailable" }
                    timing.requested(20_000, fallback = true)
                }
            } else null
            try {
                motion.filterNotNull().collect { sample ->
                    val callbackStartedAt = now()
                    try {
                        check(current()) { "owner_changed" }
                        val collectedAt = now()
                        if (sample.arrivalNs > collectedAt) throw CalibratedHeadMotionFault("host_clock_changed")
                        if (sample.arrivalNs < startedAt) return@collect
                        if (collectedAt - sample.arrivalNs > HeadTrackerUhidProtocol.FRESH_NANOS) {
                            timing.stale(); return@collect
                        }
                        val report = sample.report
                        if (report.counter != null && report.counter == previousCounter) return@collect
                        previousCounter = report.counter
                        timing.sample(sample, collectedAt)
                        val pose = converter.accept(report)
                        val savedReference = profile.referenceTimestampNs != null &&
                            report.timestampNs == profile.referenceTimestampNs && report.counter == profile.referenceCounter
                        if (!converter.invalidated && !savedReference && report.attitudeQ15.any { it != 0 })
                            timing.validAttitude(collectedAt)
                        pose?.let {
                            submit(it, sample.arrivalNs)
                            timing.poseSubmitted(now())
                        }
                        if (converter.invalidated) throw CalibratedHeadMotionFault(converter.invalidReason ?: "motion_changed")
                        if (pose != null || report.attitudeQ15.any { it != 0 }) {
                            lastAttitudeAt.set(sample.arrivalNs)
                            firstAttitude.complete(Unit)
                        }
                    } finally { timing.collectorFinished(callbackStartedAt, now()) }
                }
                error("motion_ended")
            } finally { fallback?.cancelAndJoin() }
        }
    } finally { device.stopHeadTracking(owner) }
}
