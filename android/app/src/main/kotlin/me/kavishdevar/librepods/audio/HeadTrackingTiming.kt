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
    private var previousDevice: Long? = null
    private var previousArrival = 0L
    private var epochs = 0L
    private var samples = 0L
    private var submitted = 0L
    private var stale = 0L
    private var requestedUs = 0L
    private var fallback = false

    @Synchronized fun begin() { epochs++; previousDevice = null; previousArrival = 0 }
    @Synchronized fun requested(intervalUs: Long, fallback: Boolean = false) {
        requestedUs = intervalUs; this.fallback = fallback
    }
    @Synchronized fun sample(value: MotionSample, collectedAt: Long) {
        require(collectedAt >= value.arrivalNs)
        samples++
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
    @Synchronized fun stale() { stale++ }
    @Synchronized fun snapshot(): Bundle = Bundle().apply {
        putInt("version", 1); putLong("source_epochs", epochs); putLong("source_samples", samples)
        putLong("submitted_poses", submitted); putLong("stale_samples_dropped", stale)
        putLong("requested_interval_us", requestedUs); putBoolean("fallback_rate", fallback)
        putString("age_scope", "parsed_source_callback_to_app_collection")
        putBoolean("excludes_acoustic_latency", true)
        deviceIntervals.write(this, "device_interval")
        arrivalIntervals.write(this, "source_arrival_interval")
        collectionAge.write(this, "app_collection_age")
    }
}
