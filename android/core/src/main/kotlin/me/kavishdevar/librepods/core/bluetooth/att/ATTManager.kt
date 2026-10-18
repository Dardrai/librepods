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

package me.kavishdevar.librepods.core.bluetooth.att

import me.kavishdevar.librepods.core.devices.Device

enum class ATTHandle(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A)
}

// shortcut: ATT stays unavailable until UUID discovery and the host-identity path are verified on Android.
@Suppress("UNUSED_PARAMETER")
class ATTManager(device: Device<*, *, *>) {
    suspend fun readCharacteristic(handle: ATTHandle): ByteArray? = null
    suspend fun writeCharacteristic(handle: ATTHandle, data: ByteArray): Boolean = false
}
