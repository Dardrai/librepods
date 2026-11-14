/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.Closeable
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Temporary DEBUG lease; only the selected device's head-tracker preference may be written. */
internal class RootHeadTrackerProbe(
    private val platform: SpatialAudioAccess.Platform,
    private val openSink: (String, IPlaybackRecoveryOwner, ParcelFileDescriptor) -> HeadTrackerSinkSession,
    private val mutate: (Boolean, () -> Unit) -> Unit,
    private val permitted: () -> Boolean,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : Closeable {
    private val lock = Any()
    private var active: Lease? = null
    private var last: Lease? = null
    private var closed = false

    fun start(address: String, owner: IPlaybackRecoveryOwner, poses: ParcelFileDescriptor,
        events: IHeadTrackerEvents, seconds: Int): Bundle {
        val lease = try {
            require(seconds in 1..30) { "invalid_duration" }
            val selected = A2dpRecoveryGuard(address).address
            synchronized(lock) {
                active?.refreshCompletion()
                check(!closed && active == null) { "busy" }
                Lease(selected, owner, poses, events, seconds).also { active = it; last = it }
            }
        } catch (error: Exception) {
            runCatching { poses.close() }
            return failure(error.message?.takeIf { it in setOf("invalid_duration", "busy") } ?: "invalid_request")
        }
        lease.start()
        try {
            if (!lease.prepared.await(3_000, TimeUnit.MILLISECONDS)) lease.stop("startup_timeout")
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); lease.stop("cancelled") }
        return lease.state()
    }

    fun state(owner: IPlaybackRecoveryOwner): Bundle = owned(owner)?.state() ?: failure("no_owned_probe")

    fun finish(owner: IPlaybackRecoveryOwner): Bundle {
        val lease = owned(owner) ?: return failure("no_owned_probe")
        lease.stop("finished")
        lease.awaitCleanup()
        lease.retryCleanup()
        return lease.state()
    }

    private fun owned(owner: IPlaybackRecoveryOwner) = synchronized(lock) {
        (active ?: last)?.takeIf { it.owner.asBinder() == owner.asBinder() }
    }

    private inner class Lease(val address: String, val owner: IPlaybackRecoveryOwner,
        val poses: ParcelFileDescriptor, val events: IHeadTrackerEvents, seconds: Int) : IBinder.DeathRecipient {
        val prepared = CountDownLatch(1)
        private val done = CountDownLatch(1)
        private val stopping = AtomicBoolean()
        private val cleanupLock = Any()
        private val started = now()
        private val deadline = started + seconds * 1_000L
        private val startupDeadline = minOf(started + 3_000, deadline)
        private val timer = Timer("head-tracker-deadline", true)
        @Volatile private var sink: HeadTrackerSinkSession? = null
        @Volatile private var ready = false
        @Volatile private var reason = "preparing"
        @Volatile private var restored = false
        @Volatile private var restoreReason = "not_needed"
        @Volatile private var cleanupComplete = false
        @Volatile private var workerEnded = false
        @Volatile private var ownerPollerEnded = true
        @Volatile private var ownerCurrent = false
        @Volatile private var ownerCheckedAt = Long.MIN_VALUE
        @Volatile private var ownerPoller: Thread? = null
        @Volatile private var handshake = false
        @Volatile private var observed: SpatialAudioAccess.State? = null
        private var before: SpatialAudioAccess.DeviceState? = null
        private var attempted = false
        // App Binder callbacks never run on the UHID or preference-mutation worker.
        private val sinkOwner = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent() = ownerFresh()
        }

        private fun ownerFresh() = ownerCurrent && ownerCheckedAt != Long.MIN_VALUE &&
            now() - ownerCheckedAt in 0..500 && !stopping.get() && owner.asBinder().isBinderAlive

        fun stop(value: String) {
            if (stopping.compareAndSet(false, true)) reason = value
            ownerCurrent = false
            ownerPoller?.interrupt()
            sink?.close()
        }
        override fun binderDied() = stop("owner_died")

        fun start() {
            timer.schedule(object : TimerTask() { override fun run() = stop("deadline") },
                (deadline - now()).coerceAtLeast(0))
            thread(name = "head-tracker-probe", isDaemon = true) { run() }
        }

        private fun requireLive(starting: Boolean = false) {
            check(!stopping.get() && now() < if (starting) startupDeadline else deadline) { "deadline" }
            check(permitted()) { "permission_denied" }
            check(ownerFresh()) { "owner_changed" }
            check(!stopping.get()) { "cancelled" }
        }

        private fun readEligible(starting: Boolean): SpatialAudioAccess.State {
            requireLive(starting)
            val state = platform.read(address)
            observed = state
            requireLive(starting)
            check(state.nativePair && state.routeSelected) { "route_changed" }
            check(state.supported && state.compatible == true && state.mutationApi &&
                state.deviceEnabled == true && state.enabled && state.headTrackerEnabled != null) { "spatial_disabled" }
            return state
        }

        private fun run() {
            var linked = false
            try {
                owner.asBinder().linkToDeath(this, 0); linked = true
                ownerPollerEnded = false
                ownerPoller = thread(name = "head-tracker-owner", isDaemon = true) {
                    try {
                        while (!stopping.get()) {
                            val current = owner.asBinder().isBinderAlive && owner.isCurrent
                            if (!current) { stop("owner_changed"); break }
                            if (!stopping.get()) { ownerCurrent = true; ownerCheckedAt = now() }
                            Thread.sleep(100)
                        }
                    } catch (_: Exception) { stop("owner_changed") }
                    finally { ownerCurrent = false; ownerPollerEnded = true; refreshCompletion() }
                }
                val firstOwnerDeadline = minOf(startupDeadline, now() + 500)
                while (ownerCheckedAt == Long.MIN_VALUE && !stopping.get() && now() < firstOwnerDeadline) Thread.sleep(5)
                val initial = readEligible(true)
                before = checkNotNull(initial.device())
                mutate(false) {
                    requireLive(true)
                    check(readEligible(true).device() == before) { "state_changed" }
                    sink = openSink(address, sinkOwner, poses)
                }
                while (true) {
                    requireLive(true)
                    val current = checkNotNull(sink)
                    check(!current.isClosed) { "sink_stopped" }
                    handshake = current.halHandshake
                    if (handshake) break
                    Thread.sleep(25)
                }
                mutate(false) {
                    val state = readEligible(true)
                    check(state.device() == before && state.headTrackerPresent == true) { "head_tracker_not_discovered" }
                    if (before?.headTracking == false) {
                        attempted = true
                        restoreReason = "pending"
                        platform.setHeadTrackerEnabled(address, true)
                    }
                    val after = readEligible(true)
                    check(after.device() == before?.copy(headTracking = true)) { "state_changed" }
                }
                requireLive(true)
                ready = true; prepared.countDown()
                var reporting: RootHeadTrackerSink.Reporting? = null
                var nextStateRead = 0L
                while (true) {
                    check(!stopping.get() && now() < deadline) { "deadline" }
                    val current = checkNotNull(sink)
                    check(!current.isClosed) { "sink_stopped" }
                    if (current.reporting != reporting) {
                        reporting = current.reporting
                        events.onReportingChanged(reporting.enabled, reporting.intervalNanos)
                    }
                    if (now() >= nextStateRead) {
                        check(readEligible(false).device() == before?.copy(headTracking = true)) { "state_changed" }
                        nextStateRead = now() + 250
                    }
                    Thread.sleep(25)
                }
            } catch (error: Exception) {
                if (!stopping.get()) stop(error.message?.takeIf { it in setOf("deadline", "permission_denied",
                    "owner_changed", "cancelled", "route_changed", "spatial_disabled", "state_changed",
                    "sink_stopped", "head_tracker_not_discovered") } ?: "unavailable")
            } finally {
                stop(reason)
                runCatching { poses.close() }
                restorePreference()
                if (linked) runCatching { owner.asBinder().unlinkToDeath(this, 0) }
                timer.cancel()
                val sinkDeadline = System.nanoTime() + 500_000_000L
                runCatching { while ((sink?.isClosed == false || !ownerPollerEnded) && System.nanoTime() < sinkDeadline) Thread.sleep(10) }
                workerEnded = true
                refreshCompletion()
                prepared.countDown(); done.countDown()
                runCatching { events.onReportingChanged(false, sink?.reporting?.intervalNanos ?: 0) }
                runCatching { events.onStopped(if (restoreReason == "restore_failed") restoreReason else reason) }
            }
        }

        private fun restorePreference() = synchronized(cleanupLock) {
            if (!attempted) { restored = true; return@synchronized }
            if (restoreReason !in setOf("pending", "restore_failed")) return@synchronized
            val captured = before ?: return@synchronized
            restoreReason = "restore_failed"
            runCatching {
                mutate(true) {
                    val current = platform.read(address)
                    // Cleanup needs neither a current owner nor the original route/global choice.
                    if (current.device() == captured.copy(headTracking = true)) {
                        platform.setHeadTrackerEnabled(address, captured.headTracking)
                        restored = platform.read(address).device() == captured
                        restoreReason = if (restored) "restored" else "restore_failed"
                    } else if (current.device() == captured) {
                        restored = true; restoreReason = "already_restored"
                    } else restoreReason = "state_changed"
                }
            }
        }

        fun retryCleanup() {
            if (workerEnded) restorePreference()
            refreshCompletion()
        }

        fun refreshCompletion() {
            cleanupComplete = workerEnded && ownerPollerEnded && sink?.isClosed != false &&
                restoreReason !in setOf("pending", "restore_failed")
            synchronized(lock) { if (active === this && cleanupComplete) active = null }
        }

        fun awaitCleanup() { try { done.await(4_000, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() } }

        fun state(): Bundle {
            refreshCompletion()
            return Bundle().apply {
                putInt("version", 1); putBoolean("ready", ready && !stopping.get())
                putBoolean("active", !cleanupComplete && !stopping.get())
                putString("reason", if (reason == "preparing" && ready) "ready" else reason)
                putBoolean("cleanup_complete", cleanupComplete); putBoolean("restored", restored)
                putString("restore_reason", restoreReason); putBoolean("sink_closed", sink?.isClosed != false)
                putBoolean("owner_poller_retired", ownerPollerEnded)
                putBoolean("hal_handshake", handshake)
                putBoolean("reporting_enabled", sink?.reporting?.enabled == true && !stopping.get())
                putLong("report_interval_nanos", sink?.reporting?.intervalNanos ?: 0)
                putLong("remaining_ms", (deadline - now()).coerceAtLeast(0))
                observed?.headTrackerAvailable?.let { putBoolean("head_tracker_available", it) }
                observed?.actualHeadTrackingMode?.let { putInt("actual_head_tracking_mode", it) }
            }
        }
    }

    override fun close() {
        val lease = synchronized(lock) { closed = true; active }
        lease?.stop("root_closed"); lease?.awaitCleanup(); lease?.retryCleanup()
    }

    companion object {
        fun failure(reason: String) = Bundle().apply {
            putInt("version", 1); putBoolean("ready", false); putBoolean("active", false); putString("reason", reason)
        }
    }
}
