/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.Bundle

internal data class AtmosRootDecoderBinding(val control: Long, val instance: Long, val epoch: Long, val revision: Long) {
    companion object {
        fun from(status: LongArray): AtmosRootDecoderBinding? = if (status.size == 20 && status[0] == 1L &&
            status[1] in 2L..4L && (4..7).all { status[it] > 0 })
            AtmosRootDecoderBinding(status[4], status[5], status[6], status[7]) else null
        fun preparing(status: LongArray): AtmosRootDecoderBinding? = if (status.size == 20 && status[0] == 1L &&
            status[1] == 1L && status[4] > 0 && (5..9).all { status[it] == 0L })
            AtmosRootDecoderBinding(status[4], 0, 0, 0) else null
    }
}

/** Cached native state: marker receipt alone is never permission to publish. */
internal data class AtmosDecoderHandoff(val phase: Int, val firstLossNs: Long, val markerDeadlineNs: Long,
    val startupDeadlineNs: Long, val old: AtmosRootDecoderBinding) {
    companion object {
        const val MARKER_WINDOW_NS = 500_000_000L
        fun from(value: LongArray): AtmosDecoderHandoff? {
            if (value.size != 9 || value[0] != 1L || value[1] !in 0L..9L) return null
            // Zero target is structural only. Root/core must already own the exact no-BEGIN paused proof.
            val preparingPause = value[5] > 0 && (6..8).all { value[it] == 0L }
            if (value[1] in listOf(1L, 2L, 3L, 7L) && (value[2] <= 0 || value[3] <= value[2] ||
                value[3] - value[2] > MARKER_WINDOW_NS)) return null
            if ((value[1] in 1L..3L || value[1] in 5L..7L) && !preparingPause && (5..8).any { value[it] <= 0 }) return null
            if (value[1] == 3L && value[4] <= value[2]) return null
            if (value[1] in 8L..9L && (value[2] <= 0 || value[3] <= value[2] ||
                value[3] - value[2] > MARKER_WINDOW_NS || value[4] != 0L || (5..8).any { value[it] <= 0 })) return null
            return AtmosDecoderHandoff(value[1].toInt(), value[2], value[3], value[4],
                AtmosRootDecoderBinding(value[5], value[6], value[7], value[8]))
        }
    }
}

internal data class AtmosHandoffCapture(val binding: AtmosRootDecoderBinding, val player: AtmosPlaybackWitness,
    val nonce: String, val samePlayerResume: Boolean = false, val pausedFlushResume: Boolean = false,
    val sourceRecovery: Boolean = false) {
    fun newPlayerReady(observed: AtmosPlaybackWitness?, ready: AtmosRootDecoderBinding?): Boolean =
        observed != null && (observed != player || samePlayerResume || pausedFlushResume) &&
            (!samePlayerResume || observed == player) && observed.uid == player.uid && observed.pid == player.pid &&
            observed.routeId == player.routeId && observed.address.equals(player.address, true) &&
            ready != null && ready.control != binding.control &&
            (!sourceRecovery || samePlayerResume && observed == player && ready.instance == binding.instance &&
                ready.epoch == binding.epoch && ready.revision > binding.revision)

    fun accepts(marker: Bundle, state: AtmosDecoderHandoff, bufferIssuedMs: Long, nowNs: Long): Boolean {
        val event = marker.getLong("event_boottime_ns")
        val timely = when (state.phase) {
            1 -> nowNs >= state.firstLossNs && nowNs < state.markerDeadlineNs &&
                event >= state.firstLossNs - AtmosDecoderHandoff.MARKER_WINDOW_NS
            5 -> event > 0 && nowNs >= event && nowNs - event <= AtmosDecoderHandoff.MARKER_WINDOW_NS
            else -> false
        }
        return timely && state.old == binding && event > 0 && event <= nowNs &&
            marker.getString("nonce") == nonce && marker.getLong("buffer_issued_ms") == bufferIssuedMs &&
            marker.getInt("process_pid") == player.pid && marker.getInt("audio_session_id") == player.session &&
            marker.getLong("ownership_epoch") > 0
    }
}

/** One JNI call captures both records under the same native core mutex. */
internal data class AtmosNativeState(val status: LongArray, val handoff: LongArray, val transition: AtmosDecoderHandoff) {
    companion object {
        fun from(value: LongArray, rootPid: Int): AtmosNativeState? {
            if (value.size != 29) return null
            val status = value.copyOfRange(0, 20)
            val handoff = value.copyOfRange(20, 29)
            val transition = AtmosDecoderHandoff.from(handoff) ?: return null
            return if (AtmosTrackingContract.validStatus(status, rootPid, handoff)) AtmosNativeState(status, handoff, transition) else null
        }
    }
}
