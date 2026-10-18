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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kavishdevar.librepods.bluetooth.hasNearbyDevicesPermissions
import me.kavishdevar.librepods.services.LibrePodsService
import kotlin.io.encoding.ExperimentalEncodingApi

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || !hasNearbyDevicesPermissions(context)) return
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startForegroundService(Intent(context, LibrePodsService::class.java))
        } catch (error: Exception) {
            android.util.Log.w("BootReceiver", "Unable to start Bluetooth service", error)
        }
    }
}
