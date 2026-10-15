/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.overlays.PopupWindow

/** Keeps untrusted BLE invitations separate from bonded-device controls and battery state. */
@SuppressLint("MissingPermission")
class NearbyPairingController(
    private val context: Context,
    private val onPaired: (BluetoothDevice) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = NearbyAirPods.Session()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var popup: PopupWindow? = null
    private var receiver: BroadcastReceiver? = null
    private var discoveryOwned = false
    private var timeout: Runnable? = null
    private var closed = false

    fun onNearbyDevice(event: NearbyAirPods.Event) {
        handler.post {
            if (closed) return@post
            if (!event.lidOpen) {
                if (session.modelId == event.modelId) dismiss()
                return@post
            }
            if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean("show_bottom_sheet_popup", true) || !Settings.canDrawOverlays(context)) return@post
            dismiss()
            val token = session.open(event.modelId)
            val name = NearbyAirPods.modelNames[event.modelId] ?: return@post
            popup = PopupWindow(context) {
                if (session.isCurrent(token)) dismiss()
            }.also {
                it.open(name)
                it.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf(
                    context.getString(R.string.pairing_connect) to { prepare(token) }
                ))
            }
        }
    }

    private fun prepare(token: Long) {
        if (!session.isCurrent(token)) return
        val doubleTap = session.modelId in setOf(0x1920, 0x1b20, 0x2720, 0x3020, 0x3620)
        popup?.showPairingActions(context.getString(
            if (doubleTap) R.string.pairing_double_tap else R.string.pairing_hold_button
        ), listOf(context.getString(R.string.pairing_find) to { discover(token) }))
    }

    private fun discover(token: Long) {
        if (!session.isCurrent(token)) return
        stopDiscovery()
        val model = session.modelId ?: return
        val devices = linkedMapOf<String, BluetoothDevice>()
        val candidates = NearbyAirPods.Discovery(model)
        try {
            if (adapter?.isEnabled != true) {
                showRetry(token, R.string.pairing_bluetooth_off)
                return
            }
            // A discovery started elsewhere belongs to Android or another app.
            if (adapter.isDiscovering) {
                showRetry(token, R.string.pairing_discovery_busy)
                return
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!session.isCurrent(token)) return
                    try {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    ?: return
                                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
                                if (device.bondState == BluetoothDevice.BOND_NONE &&
                                    device.type != BluetoothDevice.DEVICE_TYPE_LE &&
                                    candidates.observe(device.address, name, SystemClock.elapsedRealtime())) {
                                    devices[device.address] = device
                                    popup?.showPairingActions(context.getString(R.string.pairing_searching_candidate,
                                        name ?: "AirPods", device.address), emptyList())
                                }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finishDiscovery(token, devices, candidates)
                        }
                    } catch (e: SecurityException) {
                        permissionDenied(token, e)
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }, Context.RECEIVER_EXPORTED)
            discoveryOwned = adapter.startDiscovery()
            if (!discoveryOwned) {
                stopDiscovery()
                showRetry(token, R.string.pairing_discovery_failed)
                return
            }
            popup?.showPairingActions(context.getString(R.string.pairing_searching), emptyList())
            timeout = Runnable { finishDiscovery(token, devices, candidates) }.also { handler.postDelayed(it, 15_000) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Nearby pairing requires Bluetooth permission", e)
            stopDiscovery()
            showRetry(token, R.string.pairing_permission_needed)
        }
    }

    private fun finishDiscovery(token: Long, devices: Map<String, BluetoothDevice>, candidates: NearbyAirPods.Discovery) {
        if (!session.isCurrent(token)) return
        stopDiscovery()
        candidates.finish()
        try {
            if (devices.isEmpty()) {
                showRetry(token, R.string.pairing_not_found)
                return
            }
            if (devices.size == 1) {
                val sole = devices.values.single()
                popup?.showPairingActions(context.getString(R.string.pairing_searching_candidate,
                    sole.name ?: "AirPods", sole.address), emptyList())
                timeout = Runnable {
                    if (session.isCurrent(token)) {
                        candidates.automatic(SystemClock.elapsedRealtime())?.let(devices::get)?.let { pair(token, it) }
                    }
                }
                    .also { handler.postDelayed(it, NearbyAirPods.Discovery.STABLE_MS) }
                return
            }
            popup?.showPairingActions(context.getString(R.string.pairing_choose_device),
                devices.values.map { device ->
                    // The user selects the Classic identity: a public model advertisement cannot prove it.
                    "${device.name ?: "AirPods"}\n${device.address}" to { pair(token, device) }
                })
        } catch (e: SecurityException) {
            permissionDenied(token, e)
        }
    }

    private fun pair(token: Long, device: BluetoothDevice) {
        if (!session.isCurrent(token)) return
        stopDiscovery()
        try {
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                showRetry(token, R.string.pairing_device_changed)
                return
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!session.isCurrent(token)) return
                    try {
                        val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (changed?.address != device.address) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDED -> {
                                stopDiscovery()
                                popup?.showPairingActions(context.getString(R.string.pairing_complete), emptyList())
                                onPaired(device)
                                timeout = Runnable { if (session.isCurrent(token)) dismiss() }
                                    .also { handler.postDelayed(it, 3_000) }
                            }
                            BluetoothDevice.BOND_NONE -> {
                                stopDiscovery()
                                showRetry(token, R.string.pairing_bond_failed)
                            }
                        }
                    } catch (e: SecurityException) {
                        permissionDenied(token, e)
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            if (!device.createBond()) {
                stopDiscovery()
                showRetry(token, R.string.pairing_bond_failed)
                return
            }
            popup?.showPairingActions(context.getString(R.string.pairing_confirm_android, device.name ?: "AirPods", device.address), emptyList())
            timeout = Runnable {
                if (session.isCurrent(token)) {
                    stopDiscovery()
                    showRetry(token, R.string.pairing_bond_timeout)
                }
            }.also { handler.postDelayed(it, 60_000) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Pairing permission denied", e)
            stopDiscovery()
            showRetry(token, R.string.pairing_permission_needed)
        }
    }

    private fun showRetry(token: Long, message: Int) {
        if (!session.isCurrent(token)) return
        popup?.showPairingActions(context.getString(message), listOf(
            context.getString(R.string.pairing_retry) to { prepare(token) }
        ))
    }

    private fun permissionDenied(token: Long, error: SecurityException) {
        Log.w(TAG, "Bluetooth permission revoked during pairing", error)
        stopDiscovery()
        showRetry(token, R.string.pairing_permission_needed)
    }

    private fun stopDiscovery() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        receiver?.let {
            context.unregisterReceiver(it)
            receiver = null
        }
        if (discoveryOwned) {
            discoveryOwned = false
            try {
                adapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked during discovery", e)
            }
        }
    }

    private fun dismiss() {
        session.close()
        stopDiscovery()
        val old = popup
        popup = null
        old?.close()
    }

    fun close() {
        closed = true
        dismiss()
    }

    fun cancelPending() { handler.post { dismiss() } }

    private companion object { const val TAG = "NearbyPairing" }
}
