package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport

/** Constant-space timing before StateFlow conflation; no packet, identity, or pose history. */
internal class MotionSourceTiming {
    private class Metric {
        var count = 0L
        var min = Long.MAX_VALUE
        var max = 0L
        var total = 0.0
        var maxAt = 0L

        fun add(value: Long, at: Long) {
            if (value < 0 || at <= 0) return
            count++
            total += value
            min = minOf(min, value)
            if (count == 1L || value > max) { max = value; maxAt = at }
        }

        fun append(out: MutableMap<String, Long>, prefix: String, nanos: Boolean = true) {
            val unit = if (nanos) "_nanos" else ""
            out["${prefix}_count"] = count
            out["${prefix}_min$unit"] = if (count == 0L) 0 else min
            out["${prefix}_mean$unit"] = if (count == 0L) 0 else (total / count).toLong()
            out["${prefix}_max$unit"] = max
            out["${prefix}_max_at_nanos"] = maxAt
        }
    }

    private var reads = 0L
    private var readBytes = 0L
    private var motionReports = 0L
    private var deviceClockNonIncreasing = 0L
    private var counterNonUnitSteps = 0L
    private var motionStarts = 0L
    private var lastMotionStart = 0L
    private var lastMotionStartIntervalUs = 0L
    private var motionMappingChanges = 0L
    private var heartMappingChanges = 0L
    private var lastMotionMappingChange = 0L
    private var lastHeartMappingChange = 0L
    private var previousRead = 0L
    private var previousDevice: Long? = null
    private var previousCounter: Long? = null
    private val readInterval = Metric()
    private val readWait = Metric()
    private val readWork = Metric()
    private val dispatchDelay = Metric()
    private val feedWait = Metric()
    private val feedWork = Metric()
    private val publishDelay = Metric()
    private val deviceInterval = Metric()
    private val counterStep = Metric()

    @Synchronized fun readReturned(atNanos: Long, bytes: Int, startedAtNanos: Long = 0) {
        if (atNanos <= 0 || bytes <= 0) return
        reads++
        readBytes += bytes
        if (startedAtNanos > 0) readWait.add(atNanos - startedAtNanos, atNanos)
        if (previousRead > 0) readInterval.add(atNanos - previousRead, atNanos)
        previousRead = atNanos
    }

    @Synchronized fun readProcessed(readAtNanos: Long, finishedAtNanos: Long) {
        if (readAtNanos > 0) readWork.add(finishedAtNanos - readAtNanos, finishedAtNanos)
    }

    @Synchronized fun mappingChanged(motion: Boolean, heart: Boolean, atNanos: Long) {
        if (atNanos <= 0) return
        if (motion) { motionMappingChanges++; lastMotionMappingChange = atNanos }
        if (heart) { heartMappingChanges++; lastHeartMappingChange = atNanos }
    }

    /** Successful host start writes; this is not a firmware acknowledgement or measured rate. */
    @Synchronized fun motionStarted(intervalUs: Int, atNanos: Long) {
        if (atNanos <= 0 || intervalUs <= 0) return
        motionStarts++
        lastMotionStart = atNanos
        lastMotionStartIntervalUs = intervalUs.toLong()
    }

    @Synchronized fun dispatch(readAtNanos: Long, attemptedAtNanos: Long) {
        if (readAtNanos > 0) dispatchDelay.add(attemptedAtNanos - readAtNanos, attemptedAtNanos)
    }

    @Synchronized fun feedEntered(attemptedAtNanos: Long, enteredAtNanos: Long) {
        if (attemptedAtNanos > 0) feedWait.add(enteredAtNanos - attemptedAtNanos, enteredAtNanos)
    }

    @Synchronized fun feedFinished(enteredAtNanos: Long, finishedAtNanos: Long) {
        if (enteredAtNanos > 0) feedWork.add(finishedAtNanos - enteredAtNanos, finishedAtNanos)
    }

    /** Called for every parsed delivery, including reports a downstream StateFlow may conflate. */
    @Synchronized fun motionPublished(report: MotionReport?, readAtNanos: Long, publishedAtNanos: Long) {
        if (report == null || readAtNanos <= 0 || publishedAtNanos < readAtNanos) return
        motionReports++
        publishDelay.add(publishedAtNanos - readAtNanos, publishedAtNanos)
        val device = report.timestampNs
        previousDevice?.let { before -> device?.let {
            if (it > before) deviceInterval.add(it - before, publishedAtNanos)
            else deviceClockNonIncreasing++
        } }
        previousDevice = device
        val counter = report.counter
        previousCounter?.let { before -> counter?.let {
            val step = (it - before) and 0xffffffffL
            counterStep.add(step, publishedAtNanos)
            if (step != 1L) counterNonUnitSteps++
        } }
        previousCounter = counter
    }

    /** All *_max_at_nanos values use the same elapsed-realtime clock as the source arrival. */
    @Synchronized fun snapshot(): Map<String, Long> = buildMap {
        put("reads", reads)
        put("read_bytes", readBytes)
        put("motion_reports", motionReports)
        put("device_clock_non_increasing", deviceClockNonIncreasing)
        put("counter_non_unit_steps", counterNonUnitSteps)
        put("motion_start_count", motionStarts)
        put("last_motion_start_at_nanos", lastMotionStart)
        put("last_motion_start_interval_us", lastMotionStartIntervalUs)
        put("motion_mapping_changes", motionMappingChanges)
        put("heart_mapping_changes", heartMappingChanges)
        put("last_motion_mapping_change_at_nanos", lastMotionMappingChange)
        put("last_heart_mapping_change_at_nanos", lastHeartMappingChange)
        readInterval.append(this, "read_interval")
        readWait.append(this, "read_wait")
        readWork.append(this, "read_work")
        dispatchDelay.append(this, "dispatch_delay")
        feedWait.append(this, "feed_wait")
        feedWork.append(this, "feed_work")
        publishDelay.append(this, "publish_delay")
        deviceInterval.append(this, "device_interval")
        counterStep.append(this, "counter_step", nanos = false)
    }
}

/** Repeated ACTIVE is already StateFlow-equal; retain every reset and state transition. */
internal fun needsMotionStateUpdate(current: BuddyState, incoming: BuddyState): Boolean =
    incoming != BuddyState.ACTIVE || current != BuddyState.ACTIVE
