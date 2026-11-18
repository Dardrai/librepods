/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Player-thread-only backpressure. Never changes samples, timestamps or codec control operations. */
internal class TidalDecoderInputLeadGate {
    companion object {
        const val BUDGET_US = 500_000L
        const val LATE_RENDER_BUDGET_US = 220_000L
        const val LATE_RENDER_RESERVE_FRAMES = 3_840L // 80 ms at 48 kHz.
        const val HEAD_POLL_MS = 100L
        const val PROGRESS_FRESH_MS = 250L
        const val RENDER_FRESH_MS = 50L
    }
    class Window internal constructor(val epoch: Long, val positionUs: Long, val atMs: Long)
    private var epoch = 0L
    private var owner: Any? = null
    private var track: Any? = null
    private var claim: Any? = null
    private var offsetUs = -1L
    private var lastPositionUs = -1L
    private var lastHighWaterUs = -1L
    private var head: Long? = null
    private var headCheckedAt = -1L
    private var progressedAt = -1L
    var blocked = 0L; private set
    var allowed = 0L; private set
    var lastLeadUs = -1L; private set
    var maxLeadUs = 0L; private set
    var renderAgeMs = -1L; private set
    var reason = "startup"; private set

    fun reset(why: String) {
        ++epoch; owner = null; track = null; claim = null
        offsetUs = -1; lastPositionUs = -1; lastHighWaterUs = -1
        head = null; headCheckedAt = -1; progressedAt = -1; reason = why
    }

    fun begin(owner: Any, track: Any, claim: Any, inputOffsetUs: Long, outputOffsetUs: Long,
        positionUs: Long, nowMs: Long, started: Boolean, readPlayingHead: () -> Long?): Window? {
        if (!started || nowMs < 0 || inputOffsetUs < 0 || inputOffsetUs == Long.MAX_VALUE ||
            inputOffsetUs != outputOffsetUs || positionUs < inputOffsetUs || positionUs == Long.MAX_VALUE) {
            reset("startup_or_offset"); return null
        }
        if (this.owner !== owner || this.track !== track || this.claim !== claim || offsetUs != inputOffsetUs ||
            positionUs < lastPositionUs || nowMs < headCheckedAt) reset("epoch_changed")
        this.owner = owner; this.track = track; this.claim = claim; offsetUs = inputOffsetUs
        lastPositionUs = positionUs
        if (headCheckedAt < 0 || nowMs - headCheckedAt >= HEAD_POLL_MS) {
            val next = readPlayingHead()
            if (next == null || next < 0 || head?.let { next < it } == true) {
                reset("head_stopped_or_reset"); return null
            }
            if (head?.let { next > it } == true) progressedAt = nowMs
            head = next; headCheckedAt = nowMs
        }
        return Window(epoch, positionUs, nowMs)
    }

    fun progressAgeMs(nowMs: Long) = if (progressedAt < 0 || nowMs < progressedAt) -1 else nowMs - progressedAt

    /** False is the normal renderer's 'no more input now' result, never a dropped codec buffer. */
    fun feed(window: Window?, highWaterUs: Long, nowMs: Long, current: Boolean,
        controlPending: Boolean, budgetUs: Long = BUDGET_US, hasReserve: () -> Boolean = { true },
        original: () -> Boolean): Boolean {
        renderAgeMs = window?.let { nowMs - it.atMs } ?: -1
        val bypass = when {
            !current -> "not_owned"
            controlPending -> "codec_control"
            window == null || window.epoch != epoch -> "no_render_scope"
            renderAgeMs !in 0..RENDER_FRESH_MS -> "stale_position"
            progressAgeMs(nowMs) !in 0..PROGRESS_FRESH_MS -> "no_head_progress"
            highWaterUs < offsetUs || highWaterUs == Long.MAX_VALUE -> "unknown_input_time"
            lastHighWaterUs >= 0 && highWaterUs < lastHighWaterUs -> "input_time_reset"
            else -> null
        }
        if (bypass != null) {
            reason = bypass
            if (!current || controlPending || bypass == "input_time_reset") reset(bypass)
            ++allowed; return original()
        }
        lastHighWaterUs = highWaterUs
        lastLeadUs = highWaterUs - checkNotNull(window).positionUs
        maxLeadUs = maxOf(maxLeadUs, lastLeadUs)
        if (lastLeadUs >= budgetUs) {
            if (hasReserve()) { reason = "lead_limited"; ++blocked; return false }
            reason = "low_output_reserve"
        } else reason = "below_budget"
        ++allowed; return original()
    }
}

/** Preserves the underrun fallback across flushes of the same AudioTrack. */
internal class TidalLateRenderReserve {
    data class Sample(val underruns: Int, val frames: Long?)

    private val tripped = WeakHashMap<Any, Boolean>()
    private var track: WeakReference<Any>? = null
    private var readAtMs = -1L
    var frames = 0L; private set
    var underruns = 0; private set

    fun failed(actual: Any) = tripped[actual] == true
    fun owns(actual: Any) = track?.get() === actual

    fun flush(actual: Any) {
        if (owns(actual)) readAtMs = -1
    }

    fun check(actual: Any, nowMs: Long, read: () -> Sample, onTrip: (Int) -> Unit): Boolean {
        if (failed(actual)) return false
        val sameTrack = owns(actual)
        val ageMs = nowMs - readAtMs
        val floor = TidalDecoderInputLeadGate.LATE_RENDER_RESERVE_FRAMES
        val estimate = frames - ageMs.coerceIn(0, 25) * 48
        if (!sameTrack || readAtMs < 0 || ageMs !in 0..24 || (frames >= floor && estimate < floor)) {
            val next = read()
            if (sameTrack && next.underruns != underruns) {
                tripped[actual] = true
                onTrip(next.underruns - underruns)
                return false
            }
            track = WeakReference(actual)
            underruns = next.underruns
            frames = next.frames ?: 0
            readAtMs = if (next.frames == null) -1 else nowMs
            return frames >= floor
        }
        return estimate >= floor
    }
}
