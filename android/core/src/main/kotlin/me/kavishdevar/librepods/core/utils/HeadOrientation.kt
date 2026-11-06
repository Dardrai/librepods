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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.core.data.apple.MotionReport

data class Acceleration(val vertical: Float = 0f, val horizontal: Float = 0f)

object HeadTracking {
    private val _acceleration = MutableStateFlow(Acceleration())
    val acceleration = _acceleration.asStateFlow()
    // Historical acceleration consumers receive gyro channels; preserve their thresholds.
    val angularVelocity = acceleration
    private val _motion = MutableStateFlow<MotionReport?>(null)
    val motion = _motion.asStateFlow()

    fun addMotion(report: MotionReport?, vert: Float, horiz: Float) {
        _motion.value = report
        addAccel(vert, horiz)
    }

    fun addAccel(vert: Float, horiz: Float) {
        _acceleration.value = Acceleration(vert, horiz)
    }

    fun reset() {
        _acceleration.value = Acceleration()
        _motion.value = null
    }
}
