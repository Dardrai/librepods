/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.shizuku.AtmosPoseOutput

/** Captured source identity only; this is not proof of root route or Dolby decoder ownership. */
internal class AtmosMotionSource private constructor(
    val device: AppleDevice, val profile: SpatialPoseProfile,
    private val socket: BluetoothSocket, private val key: SpatialPoseProfileKey,
) {
    private val basis = profile.headFromRaw.map { it.toList() }
    private val referenceTimestamp = profile.referenceTimestampNs
    private val referenceCounter = profile.referenceCounter

    fun current(profileNow: SpatialPoseProfile?): Boolean =
        device.connectionState.value == ConnectionState.CONNECTED && device.aacp.ownsAudioSocket(socket) &&
            spatialPoseProfileKey(device) == key && profileNow != null && profileNow.headFromRaw == basis &&
            profileNow.referenceTimestampNs == referenceTimestamp && profileNow.referenceCounter == referenceCounter

    companion object {
        fun capture(device: AppleDevice, profile: SpatialPoseProfile): AtmosMotionSource? {
            val key = spatialPoseProfileKey(device) ?: return null
            val socket = device.aacp.captureAudioSocket() ?: return null
            return AtmosMotionSource(device, profile, socket, key).takeIf { it.current(profile) }
        }
    }
}

/** Independent calibrated consumer. No UHID preference/reporting changes or saved calibration writes. */
internal class AtmosHeadTrackingProducer(
    private val source: AtmosMotionSource,
    private val motion: Flow<MotionSample?>,
    private val discontinuity: Int,
    private val output: AtmosPoseOutput,
    // These guards must read bounded local snapshots, not perform Binder or preference IO per pose.
    private val allowed: () -> Boolean,
    private val profileNow: () -> SpatialPoseProfile?,
    private val tryFast: () -> Boolean,
    private val onFallback: () -> Unit,
    private val timing: HeadTrackingTiming = HeadTrackingTiming(),
    private val now: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val recovery: HeadMotionGapRecovery? = null,
    private val recoveryActive: () -> Boolean = { true },
) : AutoCloseable {
    private val live = AtomicBoolean(true)
    private val started = AtomicBoolean()
    private val worker = AtomicReference<Job?>()

    private fun current() = live.get() && allowed() && source.current(profileNow())

    suspend fun run() {
        check(started.compareAndSet(false, true)) { "atmos_producer_already_started" }
        coroutineScope {
            worker.set(coroutineContext[Job])
            try {
                streamCalibratedHeadMotion(source.device, motion, source.profile, discontinuity, ::current, timing, now,
                    tryFast = tryFast(), onFallback = onFallback, recovery = recovery,
                    recoveryActive = { current() && recoveryActive() }) { pose, arrival ->
                    check(current()) { "owner_changed" }
                    output.submit(pose, arrival)
                }
            } finally { worker.set(null); close() }
        }
    }

    override fun close() {
        if (live.compareAndSet(true, false)) {
            recovery?.withheld()
            runCatching { output.close() }
            worker.get()?.cancel()
        }
    }
}
