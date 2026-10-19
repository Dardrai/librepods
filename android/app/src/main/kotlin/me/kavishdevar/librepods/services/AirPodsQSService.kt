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
import kotlinx.coroutines.delay
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
    private var listening = false
    private var pendingClick: Job? = null
    private var publishedAvailable: Boolean? = null
    private var pickerGeneration = 0L

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        Log.d("AirPodsQSService", "Listening started")
        bindDeviceControls()
    }

    private fun bindDeviceControls() {
        if (connection != null) return
        val owner = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                val service = (binder as? LibrePodsService.LocalBinder)?.getService() ?: run {
                    Log.w("AirPodsQSService", "Device controls returned an invalid binder")
                    releaseBinding()
                    return
                }
                Log.d("AirPodsQSService", "Device controls bound")
                source = service
                observation?.cancel()
                observation = scope.launch {
                    service.devices.collectLatest { devices ->
                        coroutineScope {
                            val pods = devices.values.filterIsInstance<AppleDevice>()
                            fun refresh() {
                                selected = pods.firstOrNull(::canControl)
                                updateTile()
                                if (pendingClick?.isActive == true) {
                                    cancelPendingClick()
                                    Log.d("AirPodsQSService", "Pending click resolved")
                                    openPicker(pickerGeneration)
                                    if (!listening) releaseBinding(publishUnavailable = false)
                                }
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
                Log.w("AirPodsQSService", "Device controls disconnected")
                cancelPendingClick()
                observation?.cancel()
                observation = null
                selected = null
                source = null
                updateTile()
            }

            override fun onBindingDied(name: ComponentName) {
                if (connection === this) {
                    Log.w("AirPodsQSService", "Device controls binding died")
                    releaseBinding()
                }
            }
            override fun onNullBinding(name: ComponentName) {
                if (connection === this) {
                    Log.w("AirPodsQSService", "Device controls returned a null binding")
                    releaseBinding()
                }
            }
        }
        connection = owner
        bound = runCatching { bindService(Intent(this, LibrePodsService::class.java), owner, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w("AirPodsQSService", "Cannot bind device controls", it) }.getOrDefault(false)
        if (!bound) releaseBinding()
    }

    override fun onStopListening() {
        listening = false
        Log.d("AirPodsQSService", "Listening stopped; pendingClick=${pendingClick?.isActive == true}")
        if (pendingClick?.isActive != true) releaseBinding(publishUnavailable = false)
        super.onStopListening()
    }

    override fun onDestroy() {
        Log.d("AirPodsQSService", "Tile service destroyed")
        destroyed = true
        listening = false
        dismissPicker()
        releaseBinding(publishUnavailable = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRemoved() {
        pickerGeneration++
        releaseBinding(publishUnavailable = false)
        dismissPicker()
        super.onTileRemoved()
    }

    private fun dismissPicker() {
        val previous = picker
        picker = null
        pickerOwner = null
        previous?.dismiss()
    }

    private fun releaseBinding(publishUnavailable: Boolean = true) {
        cancelPendingClick()
        val old = connection
        connection = null
        observation?.cancel()
        observation = null
        selected = null
        source = null
        if (bound && old != null) runCatching { unbindService(old) }
        bound = false
        // SystemUI stops listening when the shade closes; that is not a device disconnect.
        if (publishUnavailable) updateTile()
    }

    private fun cancelPendingClick() {
        pendingClick?.cancel()
        pendingClick = null
    }

    private fun canControl(device: AppleDevice): Boolean = device.connectionState.value == ConnectionState.CONNECTED &&
        BaseCapability.LISTENING_MODE in AirPodsSpecs.getSpec(device.metadata.value.model).baseCapabilities

    override fun onClick() {
        super.onClick()
        val generation = ++pickerGeneration
        cancelPendingClick()
        if (destroyed) return
        if (source != null) {
            openPicker(generation)
            return
        }
        Log.d("AirPodsQSService", "Click waiting for current device-controls binding")
        pendingClick = scope.launch {
            delay(2_000)
            pendingClick = null
            Log.w("AirPodsQSService", "Click expired waiting for device controls")
            releaseBinding()
        }
        bindDeviceControls()
    }

    private fun openPicker(generation: Long) {
        val device = selected?.takeIf(::canControl) ?: run {
            Log.d("AirPodsQSService", "Click skipped: no connected supported device")
            return
        }
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
            } else {
                Log.d("AirPodsQSService", "Click skipped: owner, unlock or generation changed")
            }
        }
        if (isLocked) unlockAndRun(openPicker) else openPicker.run()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val device = selected?.takeIf(::canControl)
        val available = device != null
        if (publishedAvailable != available) {
            val pods = source?.devices?.value?.values?.filterIsInstance<AppleDevice>().orEmpty()
            val states = pods.joinToString { pod ->
                "${pod.connectionState.value}/${pod.metadata.value.model}/supported=" +
                    (BaseCapability.LISTENING_MODE in AirPodsSpecs.getSpec(pod.metadata.value.model).baseCapabilities)
            }
            Log.d("AirPodsQSService", "Tile available=$available bound=$bound service=${source != null} devices=${pods.size} states=[$states]")
            publishedAvailable = available
        }
        val mode = device?.state?.value?.controlStates?.get(ControlCommandIdentifier.LISTENING_MODE)?.firstOrNull()?.toInt()
        tile.state = if (available) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
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
