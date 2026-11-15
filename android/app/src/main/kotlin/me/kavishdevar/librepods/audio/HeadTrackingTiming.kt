/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.os.Bundle
import me.kavishdevar.librepods.core.utils.MotionSample

/** Scalar diagnostics only; no poses, identities, history or per-frame logging. */
internal class HeadTrackingTiming {
    private class Range {
        var count = 0L; var sum = 0L; var min = Long.MAX_VALUE; var max = 0L
        fun add(value: Long) {
            count++; sum += value; min = minOf(min, value); max = maxOf(max, value)
        }
        fun write(bundle: Bundle, name: String) {
            bundle.putLong("${name}_count", count)
            bundle.putLong("${name}_min_nanos", if (count == 0L) 0 else min)
            bundle.putDouble("${name}_mean_nanos", if (count == 0L) 0.0 else sum.toDouble() / count)
            bundle.putLong("${name}_max_nanos", max)
        }
    }
    private val deviceIntervals = Range()
    private val arrivalIntervals = Range()
    private val collectionAge = Range()
    private val collectorWork = Range()
    private var collectorWorkMaxAt = 0L
    private var epochRequestAt = 0L
    private var firstCollectionAt = 0L
    private var firstAttitudeAt = 0L
    private var firstSubmitAt = 0L
    private var previousDevice: Long? = null
    private var previousArrival = 0L
    private var epochs = 0L
    private var samples = 0L
    private var submitted = 0L
    private var stale = 0L
    private var requestedUs = 0L
    private var fallback = false
    private var firstFallbackAt = 0L
    private var firstFallbackReason: String? = null
    private var lastAttitudeAtFallback = 0L

    @Synchronized fun begin(requestAtNanos: Long = 0) {
        epochs++; previousDevice = null; previousArrival = 0
        epochRequestAt = requestAtNanos
        firstCollectionAt = 0; firstAttitudeAt = 0; firstSubmitAt = 0
    }
    @Synchronized fun requested(intervalUs: Long, fallback: Boolean = false) {
        requestedUs = intervalUs; this.fallback = fallback
    }
    @Synchronized fun fallingBack(atNanos: Long, reason: String, lastAttitudeAtNanos: Long = 0) {
        if (firstFallbackReason != null) return
        firstFallbackAt = atNanos
        firstFallbackReason = reason
        lastAttitudeAtFallback = lastAttitudeAtNanos
    }
    @Synchronized fun sample(value: MotionSample, collectedAt: Long) {
        require(collectedAt >= value.arrivalNs)
        samples++
        if (firstCollectionAt == 0L && epochRequestAt > 0 && collectedAt >= epochRequestAt)
            firstCollectionAt = collectedAt
        value.report.timestampNs?.let { time ->
            previousDevice?.let { before -> if (time > before) deviceIntervals.add(time - before) }
            previousDevice = time
        }
        if (previousArrival > 0 && value.arrivalNs > previousArrival)
            arrivalIntervals.add(value.arrivalNs - previousArrival)
        previousArrival = value.arrivalNs
        collectionAge.add(collectedAt - value.arrivalNs)
    }
    @Synchronized fun submitted() { submitted++ }
    @Synchronized fun validAttitude(atNanos: Long) {
        if (firstAttitudeAt == 0L && firstCollectionAt > 0 && atNanos >= firstCollectionAt) firstAttitudeAt = atNanos
    }
    // The caller's submitted counter remains separate; record only a successful callback's milestone here.
    @Synchronized fun poseSubmitted(atNanos: Long) {
        if (firstSubmitAt == 0L && firstAttitudeAt > 0 && atNanos >= firstAttitudeAt) firstSubmitAt = atNanos
    }
    @Synchronized fun collectorFinished(startedAt: Long, finishedAt: Long) {
        if (startedAt <= 0 || finishedAt < startedAt) return
        val elapsed = finishedAt - startedAt
        if (collectorWork.count == 0L || elapsed > collectorWork.max) collectorWorkMaxAt = finishedAt
        collectorWork.add(elapsed)
    }
    @Synchronized fun stale() { stale++ }
    @Synchronized fun snapshot(): Bundle = Bundle().apply {
        putInt("version", 1); putLong("source_epochs", epochs); putLong("source_samples", samples)
        putLong("submitted_poses", submitted); putLong("stale_samples_dropped", stale)
        putLong("requested_interval_us", requestedUs); putBoolean("fallback_rate", fallback)
        putLong("first_fallback_at_nanos", firstFallbackAt)
        putString("first_fallback_reason", firstFallbackReason)
        putLong("first_fallback_last_attitude_at_nanos", lastAttitudeAtFallback)
        putString("age_scope", "parsed_source_callback_to_app_collection")
        putBoolean("excludes_acoustic_latency", true)
        putLong("latest_epoch_request_at_nanos", epochRequestAt)
        putLong("latest_epoch_first_collection_at_nanos", firstCollectionAt)
        putLong("latest_epoch_first_attitude_at_nanos", firstAttitudeAt)
        putLong("latest_epoch_first_submit_at_nanos", firstSubmitAt)
        putString("collector_work_scope", "non_null_callback_entry_through_owner_check_conversion_submit")
        collectorWork.write(this, "collector_work")
        putLong("collector_work_max_at_nanos", collectorWorkMaxAt)
        deviceIntervals.write(this, "device_interval")
        arrivalIntervals.write(this, "source_arrival_interval")
        collectionAge.write(this, "app_collection_age")
    }
}
