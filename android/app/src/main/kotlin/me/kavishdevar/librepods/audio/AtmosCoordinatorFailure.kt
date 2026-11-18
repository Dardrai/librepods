/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.os.Bundle
import android.os.SystemClock
import android.system.ErrnoException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Retain the initiating fault before coroutine cancellation or cleanup can obscure it. */
internal class AtmosCoordinatorFailure(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private data class Fault(val stage: String, val type: String, val reason: String, val atMs: Long)
    private val first = AtomicReference<Fault?>()
    private val producer = AtomicReference<Exception?>()

    fun record(stage: String, error: Exception) {
        if (error is CancellationException || first.get() != null) return
        val reason = when {
            error is ErrnoException -> "errno_${error.errno}"
            error.message in REASONS -> error.message!!
            else -> "unclassified"
        }
        first.compareAndSet(null, Fault(stage.take(40), error.javaClass.simpleName.take(80), reason, now()))
    }

    suspend fun <T> capture(stage: () -> String, action: suspend () -> T): T = try { action() }
        catch (error: Exception) { record(stage(), error); throw error }

    /** Keep the monitor alive for its next Root read after the source pipe fails. */
    suspend fun captureProducer(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { record("calibrated_source", error); producer.compareAndSet(null, error) }
    }

    fun readRootState(sender: Job?, read: () -> Bundle, retain: (Bundle) -> Unit): Bundle {
        val producerFinished = sender?.isCompleted == true
        return read().also {
            retain(it)
            if (producerFinished) throw producer.get() ?: IllegalStateException("motion_ended")
        }
    }

    fun snapshot(): Bundle? = first.get()?.let {
        Bundle().apply {
            putString("stage", it.stage); putString("class", it.type)
            putString("reason", it.reason); putLong("at_ms", it.atMs)
        }
    }

    private companion object {
        // Messages from framework/vendor failures may contain arbitrary data; never copy them.
        val REASONS = setOf("owner_changed", "request_retired", "motion_unavailable", "motion_ended",
            "host_clock_changed", "motion_changed", "session_changed", "sensor_time_unavailable",
            "unsupported_attitude", "sensor_time_changed", "sensor_counter_changed", "sensor_gap",
            "reference_discontinuity", "invalid_atmos_pose", "atmos_fd_not_pipe", "atmos_pipe_not_writable",
            "atmos_output_retired", "atmos_pipe_stalled", "root_lease_retired", "preparation_timeout",
            "claim_not_current", "preparation_retired", "native_status_unavailable", "native_not_ready",
            "invalid_decoder_binding", "decoder_replaced_or_retired", "experiment_permit_retired",
            "handoff_authorization_rejected", "handoff_not_authorized", "handoff_reused_control",
            "invalid_handoff_state", "handoff_failed", "handoff_binding_changed")
    }
}

internal fun retiredAtmosCoordinatorState(state: Bundle, reason: String) = Bundle(state).apply {
    putBoolean("live", false); putBoolean("can_stream", false); putBoolean("playback_verified", false)
    putBoolean("handoff_pending", false); putBoolean("handoff_authorizable", false)
    putBoolean("root_state_historical", true); putString("coordinator_reason", reason)
}
