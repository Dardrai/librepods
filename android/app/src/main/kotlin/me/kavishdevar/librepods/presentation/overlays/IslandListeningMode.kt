/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.os.SystemClock

/** Only confirmed reports, after this transport's first valid baseline, announce a mode change. */
internal class IslandListeningMode(private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {
    private var previous: Int? = null
    private var owner: Any? = null
    private var speaking = false
    private var conversationSequence = 0L
    private var consumedRequest: ByteArray? = null
    private var restoreMode: Int? = null
    private var restoreUntil = 0L

    fun observe(mode: Int): Boolean {
        if (mode !in 1..4) return false
        val changed = previous != null && previous != mode
        previous = mode
        return changed
    }

    fun observeReport(transport: Any?, previousReport: ByteArray?, report: ByteArray?,
                      currentReport: ByteArray? = report, conversationStage: Int = 0,
                      conversationSequence: Long = 0,
                      requestedMode: ByteArray? = null, quietRequest: Boolean = false): Int? {
        if (owner !== transport) { reset(); owner = transport }
        // Cached values and requested-only state changes do not constitute received reports.
        if (transport == null || report !== currentReport) return null
        if (conversationSequence > this.conversationSequence) {
            if (!speaking) restoreMode = restoreMode ?: previous
            speaking = true
            restoreUntil = 0L
        }
        this.conversationSequence = maxOf(this.conversationSequence, conversationSequence)
        when (conversationStage) {
            1, 2, 3 -> {
                if (!speaking) restoreMode = restoreMode ?: previous
                speaking = true
                restoreUntil = 0L
            }
            0, 4, 6, 7, 8, 9 -> {
                if (speaking && restoreMode != null) restoreUntil = nowMillis() + 2_000L
                speaking = false
            }
        }
        if (!speaking && restoreMode != null && nowMillis() >= restoreUntil) restoreMode = null
        val mode = report?.singleOrNull()?.toInt()?.and(255)?.takeIf { it in 1..4 } ?: return null
        val manualRequest = requestedMode != null && requestedMode !== consumedRequest &&
            requestedMode.singleOrNull()?.toInt()?.and(255) == mode
        if (manualRequest) consumedRequest = requestedMode
        if (manualRequest || (quietRequest && report !== previousReport)) restoreMode = null
        if (report === previousReport) return null
        if (!observe(mode)) return null
        if (quietRequest) return null
        if (!manualRequest) {
            if (mode == restoreMode) { restoreMode = null; return null }
            if ((speaking || restoreMode != null) && mode == 3) return null
        }
        restoreMode = null
        return mode
    }

    fun reset() {
        previous = null; owner = null; speaking = false; conversationSequence = 0
        consumedRequest = null; restoreMode = null; restoreUntil = 0L
    }
}
