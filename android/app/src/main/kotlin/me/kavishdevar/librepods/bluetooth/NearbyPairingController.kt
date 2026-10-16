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
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.presentation.overlays.PopupWindow

/** Keeps untrusted BLE invitations separate from bonded-device controls and battery state. */
@SuppressLint("MissingPermission")
class NearbyPairingController(
    private val context: Context,
    private val onInteractionChanged: (Int?) -> Unit = {},
    private val onPaired: (BluetoothDevice) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = NearbyAirPods.Session()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var popup: PopupWindow? = null
    private var receiver: BroadcastReceiver? = null
    private var discoveryOwned = false
    private var timeout: Runnable? = null
    private var continuation: Runnable? = null
    @Volatile private var closed = false
    @Volatile private var blocked = false

    fun onNearbyDevice(event: NearbyAirPods.Event, observedAtNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        handler.post {
            if (closed || blocked) return@post
            if (!event.lidOpen) {
                if (session.modelId == event.modelId) dismiss()
                return@post
            }
            if (!Settings.canDrawOverlays(context)) return@post
            val token = session.open(event.modelId) ?: return@post
            val name = NearbyAirPods.modelNames[event.modelId] ?: return@post
            popup = PopupWindow(context) {
                if (session.isCurrent(token)) dismiss()
            }.also {
                it.openPairing(name, event.modelId, observedAtNanos)
                Log.d(TAG, "Lid advertisement to pairing overlay: ${(SystemClock.elapsedRealtimeNanos() - observedAtNanos) / 1_000_000}ms")
                it.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf(
                    context.getString(R.string.pairing_connect) to { discover(token) }
                ))
            }
        }
    }

    private fun discover(token: Long) {
        if (closed || blocked) return
        val attempt = session.beginAttempt(token, SystemClock.elapsedRealtime()) ?: return
        stopDiscovery()
        onInteractionChanged(session.modelId)
        timeout = Runnable {
            if (session.isCurrentAttempt(token, attempt)) {
                stopDiscovery()
                showRetry(token, R.string.pairing_request_timeout)
            }
        }.also { handler.postDelayed(it, NearbyAirPods.Session.REQUEST_MS) }
        startScan(token, attempt)
    }

    private fun startScan(token: Long, attempt: Long) {
        if (!canContinue(token, attempt)) return
        stopScan()
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
                    if (!canContinue(token, attempt) || receiver !== this) return
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
                                    showInstructions(token, if (candidates.ambiguous) R.string.pairing_multiple_cases else null)
                                }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                // A late completion from an earlier scan must not finish this one.
                                if (!adapter.isDiscovering) {
                                    discoveryOwned = false
                                    finishDiscovery(token, attempt, devices, candidates)
                                }
                            }
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
                scheduleScan(token, attempt, R.string.pairing_discovery_failed)
                return
            }
            showInstructions(token)
            continuation = Runnable { finishDiscovery(token, attempt, devices, candidates) }
                .also { handler.postDelayed(it, minOf(15_000L, session.remainingMillis(token, attempt, SystemClock.elapsedRealtime()))) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Nearby pairing requires Bluetooth permission", e)
            stopDiscovery()
            showRetry(token, R.string.pairing_permission_needed)
        }
    }

    private fun finishDiscovery(token: Long, attempt: Long, devices: Map<String, BluetoothDevice>, candidates: NearbyAirPods.Discovery) {
        if (!canContinue(token, attempt)) return
        stopScan()
        candidates.finish()
        Log.d(TAG, "Classic discovery finished with ${devices.size} matching unpaired candidates")
        val sole = devices.values.singleOrNull()
        if (sole != null) {
            continuation = Runnable {
                if (canContinue(token, attempt)) {
                    candidates.automatic(SystemClock.elapsedRealtime())?.let(devices::get)?.let { pair(token, attempt, it) }
                }
            }
                .also { handler.postDelayed(it, NearbyAirPods.Discovery.STABLE_MS) }
        } else {
            scheduleScan(token, attempt, if (candidates.ambiguous) R.string.pairing_multiple_cases else null)
        }
    }

    private fun pair(token: Long, attempt: Long, device: BluetoothDevice) {
        if (!canContinue(token, attempt)) return
        stopScan()
        try {
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                showRetry(token, R.string.pairing_device_changed)
                return
            }
            var bondingObserved = false
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!canContinue(token, attempt) || receiver !== this) return
                    try {
                        val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (changed?.address != device.address) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDING -> bondingObserved = true
                            BluetoothDevice.BOND_BONDED -> {
                                if (!bondingObserved || device.bondState != BluetoothDevice.BOND_BONDED) return
                                stopDiscovery()
                                popup?.showPairingActions(context.getString(R.string.pairing_complete), emptyList(), busy = false)
                                onPaired(device)
                                timeout = Runnable { if (session.isCurrentAttempt(token, attempt)) dismiss() }
                                    .also { handler.postDelayed(it, 3_000) }
                            }
                            BluetoothDevice.BOND_NONE -> {
                                if (!bondingObserved || device.bondState != BluetoothDevice.BOND_NONE) return
                                scheduleScan(token, attempt, R.string.pairing_bond_failed)
                            }
                        }
                    } catch (e: SecurityException) {
                        permissionDenied(token, e)
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            if (!device.createBond()) {
                scheduleScan(token, attempt, R.string.pairing_bond_failed)
                return
            }
            showInstructions(token, R.string.pairing_confirm_system)
        } catch (e: SecurityException) {
            Log.w(TAG, "Pairing permission denied", e)
            stopDiscovery()
            showRetry(token, R.string.pairing_permission_needed)
        }
    }

    private fun showRetry(token: Long, message: Int) {
        if (!session.isCurrent(token)) return
        stopDiscovery()
        onInteractionChanged(null)
        popup?.showPairingActions(instructions(message), listOf(
            context.getString(R.string.pairing_retry) to { discover(token) }
        ))
    }

    private fun instructions(message: Int? = null): String {
        val gesture = if (NearbyAirPods.usesDoubleTap(session.modelId ?: 0)) R.string.pairing_double_tap else R.string.pairing_hold_button
        return context.getString(gesture) + (message?.let { "\n\n${context.getString(it)}" } ?: "")
    }

    private fun showInstructions(token: Long, message: Int? = null) {
        if (session.isCurrent(token)) popup?.showPairingActions(instructions(message), emptyList())
    }

    private fun scheduleScan(token: Long, attempt: Long, message: Int?) {
        if (!canContinue(token, attempt)) return
        stopScan()
        showInstructions(token, message)
        continuation = Runnable { startScan(token, attempt) }.also { handler.postDelayed(it, NearbyAirPods.Discovery.STABLE_MS) }
    }

    private fun canContinue(token: Long, attempt: Long): Boolean = !closed && !blocked &&
        session.remainingMillis(token, attempt, SystemClock.elapsedRealtime()) > 0

    private fun permissionDenied(token: Long, error: SecurityException) {
        Log.w(TAG, "Bluetooth permission revoked during pairing", error)
        stopDiscovery()
        showRetry(token, R.string.pairing_permission_needed)
    }

    private fun stopDiscovery() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        stopScan()
    }

    private fun stopScan() {
        continuation?.let(handler::removeCallbacks)
        continuation = null
        receiver?.let {
            receiver = null
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Pairing receiver was not registered", e)
            }
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
        onInteractionChanged(null)
        val old = popup
        popup = null
        if (closed) old?.forceClose() else old?.close()
    }

    fun close() {
        closed = true
        dismiss()
    }

    fun cancelPending() { handler.post { dismiss() } }

    fun setBlocked(value: Boolean) {
        blocked = value
        handler.post {
            session.setBlocked(value)
            if (value) dismiss()
        }
    }

    private companion object { const val TAG = "NearbyPairing" }
}
