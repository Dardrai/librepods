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

package me.kavishdevar.librepods.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.presentation.overlays.ListeningModeDialog

class AirPodsQSService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ServiceConnection? = null
    private var bound = false
    private var observation: Job? = null
    private var selected: AppleDevice? = null
    private var source: LibrePodsService? = null
    private var picker: ListeningModeDialog? = null
    private var pickerOwner: AppleDevice? = null
    private var destroyed = false
    private var pickerGeneration = 0L

    override fun onStartListening() {
        super.onStartListening()
        if (connection != null) return
        val owner = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                val service = (binder as? LibrePodsService.LocalBinder)?.getService() ?: return
                source = service
                observation?.cancel()
                observation = scope.launch {
                    service.devices.collectLatest { devices ->
                        coroutineScope {
                            val pods = devices.values.filterIsInstance<AppleDevice>()
                            fun refresh() {
                                selected = pods.firstOrNull(::canControl)
                                updateTile()
                            }
                            refresh()
                            pods.forEach { device ->
                                launch {
                                    combine(device.connectionState, device.state, device.settings, device.metadata) { _, _, _, _ -> Unit }
                                        .collect { refresh() }
                                }
                            }
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                observation?.cancel()
                observation = null
                selected = null
                source = null
                updateTile()
            }

            override fun onBindingDied(name: ComponentName) { if (connection === this) releaseBinding() }
            override fun onNullBinding(name: ComponentName) { if (connection === this) releaseBinding() }
        }
        connection = owner
        updateTile()
        bound = runCatching { bindService(Intent(this, LibrePodsService::class.java), owner, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w("AirPodsQSService", "Cannot bind device controls", it) }.getOrDefault(false)
        if (!bound) connection = null
    }

    override fun onStopListening() {
        releaseBinding()
        super.onStopListening()
    }

    override fun onDestroy() {
        destroyed = true
        dismissPicker()
        releaseBinding()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRemoved() {
        pickerGeneration++
        dismissPicker()
        super.onTileRemoved()
    }

    private fun dismissPicker() {
        val previous = picker
        picker = null
        pickerOwner = null
        previous?.dismiss()
    }

    private fun releaseBinding() {
        val old = connection
        connection = null
        observation?.cancel()
        observation = null
        selected = null
        source = null
        if (bound && old != null) runCatching { unbindService(old) }
        bound = false
        updateTile()
    }

    private fun canControl(device: AppleDevice): Boolean = device.connectionState.value == ConnectionState.CONNECTED &&
        BaseCapability.LISTENING_MODE in AirPodsSpecs.getSpec(device.metadata.value.model).baseCapabilities

    override fun onClick() {
        super.onClick()
        val generation = ++pickerGeneration
        val device = selected?.takeIf(::canControl) ?: return
        val devices = source?.devices ?: return
        val openPicker = Runnable {
            // Unlocking/collapsing may stop tile listening; the clicked pair must still be current.
            if (!destroyed && generation == pickerGeneration && !isLocked &&
                devices.value[device.macAddress] === device && canControl(device)) {
                val current = picker
                val dialog = if (pickerOwner === device && current?.isShowing == true) current else {
                    dismissPicker()
                    ListeningModeDialog(this, device.macAddress).also { next ->
                        picker = next
                        pickerOwner = device
                        next.setOnDismissListener {
                            if (picker === next) {
                                picker = null
                                pickerOwner = null
                            }
                        }
                    }
                }
                runCatching { showDialog(dialog) }.onFailure {
                    dismissPicker()
                    Log.w("AirPodsQSService", "Cannot show listening modes", it)
                }
            }
        }
        if (isLocked) unlockAndRun(openPicker) else openPicker.run()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val device = selected?.takeIf(::canControl)
        val mode = device?.state?.value?.controlStates?.get(ControlCommandIdentifier.LISTENING_MODE)?.firstOrNull()?.toInt()
        tile.state = if (device == null) Tile.STATE_UNAVAILABLE else Tile.STATE_ACTIVE
        tile.label = getString(when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> R.string.off
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.string.transparency
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.string.adaptive
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.string.noise_cancellation
            else -> R.string.noise_control
        })
        tile.subtitle = device?.metadata?.value?.name?.ifBlank { "AirPods" } ?: getString(R.string.takeover_disconnected)
        tile.icon = Icon.createWithResource(this, when (mode) {
            NoiseControlMode.OFF.ordinal + 1, NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.ic_noise_cancellation
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.ic_transparency
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.ic_adaptive
            else -> R.drawable.ic_airpods
        })
        runCatching { tile.updateTile() }.onFailure { Log.w("AirPodsQSService", "Cannot update tile", it) }
    }
}
