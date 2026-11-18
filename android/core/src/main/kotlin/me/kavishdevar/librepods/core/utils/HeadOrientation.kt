/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.core.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.core.data.apple.MotionReport

data class Acceleration(val vertical: Float = 0f, val horizontal: Float = 0f)

/** Arrival is when the Bluetooth read completed, on Android's elapsedRealtimeNanos clock. */
data class MotionSample(val report: MotionReport, val arrivalNs: Long) {
    init { require(arrivalNs > 0) }
}

object HeadTracking {
    private val _acceleration = MutableStateFlow(Acceleration())
    val acceleration = _acceleration.asStateFlow()
    // Historical acceleration consumers receive gyro channels; preserve their thresholds.
    val angularVelocity = acceleration
    private val _motion = MutableStateFlow<MotionReport?>(null)
    val motion = _motion.asStateFlow()
    private val _motionSample = MutableStateFlow<MotionSample?>(null)
    val motionSample = _motionSample.asStateFlow()
    // Absorb a two-report burst; a slow renderer must lose old reports, never block the source.
    private val _motionSamples = MutableSharedFlow<MotionSample?>(replay = 1,
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val motionSamples = _motionSamples.asSharedFlow()

    fun addMotion(report: MotionReport?, vert: Float, horiz: Float, arrivalNs: Long = 0) {
        _motion.value = report
        // Legacy publishers have no source timestamp and cannot claim a fresh timed sample.
        val sample = report?.takeIf { arrivalNs > 0 }?.let { MotionSample(it, arrivalNs) }
        _motionSample.value = sample
        _motionSamples.tryEmit(sample)
        addAccel(vert, horiz)
    }

    fun addAccel(vert: Float, horiz: Float) {
        _acceleration.value = Acceleration(vert, horiz)
    }

    fun reset() {
        _motionSample.value = null
        _motionSamples.tryEmit(null)
        _acceleration.value = Acceleration()
        _motion.value = null
    }
}
