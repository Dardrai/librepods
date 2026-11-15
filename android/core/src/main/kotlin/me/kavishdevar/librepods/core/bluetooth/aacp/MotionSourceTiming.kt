package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.data.apple.motionCounterStep

/** Constant-space timing before StateFlow conflation; no packet, identity, or pose history. */
internal class MotionSourceTiming {
    private class ControlMetadata {
        var count = 0L
        var sequence = 0L
        var at = 0L
        fun received(seq: Int, atNanos: Long) { count++; sequence = seq.toLong(); at = atNanos }
        fun append(out: MutableMap<String, Long>, prefix: String) {
            out["${prefix}_count"] = count
            out["${prefix}_last_sequence"] = sequence
            out["${prefix}_last_at_nanos"] = at
        }
    }

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
    private var counterWrap16Count = 0L
    private var motionStarts = 0L
    private var lastMotionStart = 0L
    private var lastMotionStartIntervalUs = 0L
    private var motionIntervalUs = 0L
    private var intervalChanges = 0L
    private var lastIntervalChange = 0L
    private var active = false
    private var previousActiveRead = 0L
    private var previousActivePublish = 0L
    private var previousActiveIntervalChanges = 0L
    private var previousActiveDevice: Long? = null
    private var intervalAtMaxActivePublishGapUs = 0L
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
    private val activeReadInterval = Metric()
    private val activePublishInterval = Metric()
    private val activeDeviceInterval = Metric()
    private val motionStartAck = ControlMetadata()
    private val motionCommandAck = ControlMetadata()
    private val motionIntervalEcho = ControlMetadata()
    private var lastMotionIntervalEchoUs = 0L

    /** Socket-lifetime observations, including stop ACKs; neither ACKs nor echoes confirm an applied rate. */
    @Synchronized fun motionControlReceived(data: SensorDataWX, selectedService: Int?, readAtNanos: Long) {
        if (selectedService == null || selectedService !in 1..127 || readAtNanos <= 0) return
        if (data.hasStartAck() && data.startAck.serviceValue == selectedService)
            motionStartAck.received(data.seq, readAtNanos)
        if (data.hasCommandAck() && data.commandAck.serviceValue == selectedService)
            motionCommandAck.received(data.seq, readAtNanos)
        if (!data.hasServiceSettings()) return
        val setting = data.serviceSettings
        val bytes = setting.configuration
        if (setting.serviceValue != selectedService || setting.setting != 2 ||
            bytes.size() != 5 || bytes.byteAt(0) != 1.toByte()) return
        lastMotionIntervalEchoUs = (1..4).fold(0L) { value, index ->
            value or ((bytes.byteAt(index).toLong() and 255) shl ((index - 1) * 8))
        }
        motionIntervalEcho.received(data.seq, readAtNanos)
    }

    @Synchronized fun readReturned(atNanos: Long, bytes: Int, startedAtNanos: Long = 0) {
        if (atNanos <= 0 || bytes <= 0) return
        reads++
        readBytes += bytes
        if (startedAtNanos > 0) readWait.add(atNanos - startedAtNanos, atNanos)
        if (previousRead > 0) readInterval.add(atNanos - previousRead, atNanos)
        previousRead = atNanos
        if (active) {
            if (previousActiveRead > 0) activeReadInterval.add(atNanos - previousActiveRead, atNanos)
            previousActiveRead = atNanos
        }
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
        motionIntervalUs = intervalUs.toLong()
        active = true
        previousActiveRead = 0
        previousActivePublish = 0
        previousActiveDevice = null
    }

    @Synchronized fun motionStopped() { active = false }

    /** Successful live rate writes are distinct from starts and are not firmware acknowledgements. */
    @Synchronized fun motionIntervalChanged(intervalUs: Int, atNanos: Long) {
        if (atNanos <= 0 || intervalUs <= 0) return
        motionIntervalUs = intervalUs.toLong()
        intervalChanges++
        lastIntervalChange = atNanos
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
        if (active) {
            if (previousActivePublish > 0) {
                val gap = publishedAtNanos - previousActivePublish
                // A successful rate write inside this gap makes its interval regime mixed.
                if (activePublishInterval.count == 0L || gap > activePublishInterval.max)
                    intervalAtMaxActivePublishGapUs =
                        if (intervalChanges == previousActiveIntervalChanges) motionIntervalUs else 0L
                activePublishInterval.add(gap, publishedAtNanos)
            }
            previousActivePublish = publishedAtNanos
            previousActiveIntervalChanges = intervalChanges
            previousActiveDevice?.let { before -> device?.let {
                if (it > before) activeDeviceInterval.add(it - before, publishedAtNanos)
            } }
            previousActiveDevice = device
        }
        previousDevice?.let { before -> device?.let {
            if (it > before) deviceInterval.add(it - before, publishedAtNanos)
            else deviceClockNonIncreasing++
        } }
        val counter = report.counter
        previousCounter?.let { before -> counter?.let {
            val step = motionCounterStep(before, it, previousDevice, device)
            if (step != ((it - before) and 0xffffffffL)) counterWrap16Count++
            counterStep.add(step, publishedAtNanos)
            if (step != 1L) counterNonUnitSteps++
        } }
        previousCounter = counter
        previousDevice = device
    }

    /** All *_max_at_nanos values use the same elapsed-realtime clock as the source arrival. */
    @Synchronized fun snapshot(): Map<String, Long> = buildMap {
        put("reads", reads)
        put("read_bytes", readBytes)
        put("motion_reports", motionReports)
        put("device_clock_non_increasing", deviceClockNonIncreasing)
        put("counter_non_unit_steps", counterNonUnitSteps)
        put("counter_wrap16_count", counterWrap16Count)
        put("motion_start_count", motionStarts)
        put("last_motion_start_at_nanos", lastMotionStart)
        put("last_motion_start_interval_us", lastMotionStartIntervalUs)
        put("motion_interval_us", motionIntervalUs)
        put("motion_interval_change_count", intervalChanges)
        put("last_motion_interval_change_at_nanos", lastIntervalChange)
        put("active_publish_interval_max_requested_us", intervalAtMaxActivePublishGapUs)
        put("motion_mapping_changes", motionMappingChanges)
        put("heart_mapping_changes", heartMappingChanges)
        put("last_motion_mapping_change_at_nanos", lastMotionMappingChange)
        put("last_heart_mapping_change_at_nanos", lastHeartMappingChange)
        motionStartAck.append(this, "motion_start_ack")
        motionCommandAck.append(this, "motion_command_ack")
        motionIntervalEcho.append(this, "motion_interval_echo")
        put("motion_interval_echo_last_us", lastMotionIntervalEchoUs)
        readInterval.append(this, "read_interval")
        readWait.append(this, "read_wait")
        readWork.append(this, "read_work")
        dispatchDelay.append(this, "dispatch_delay")
        feedWait.append(this, "feed_wait")
        feedWork.append(this, "feed_work")
        publishDelay.append(this, "publish_delay")
        deviceInterval.append(this, "device_interval")
        counterStep.append(this, "counter_step", nanos = false)
        activeReadInterval.append(this, "active_read_interval")
        activePublishInterval.append(this, "active_publish_interval")
        activeDeviceInterval.append(this, "active_device_interval")
    }
}

/** Repeated ACTIVE is already StateFlow-equal; retain every reset and state transition. */
internal fun needsMotionStateUpdate(current: BuddyState, incoming: BuddyState): Boolean =
    incoming != BuddyState.ACTIVE || current != BuddyState.ACTIVE
