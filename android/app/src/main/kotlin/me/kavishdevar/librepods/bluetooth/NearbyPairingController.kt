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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient

/** Keeps untrusted BLE invitations separate from bonded-device controls and battery state. */
@SuppressLint("MissingPermission")
class NearbyPairingController(
    private val context: Context,
    private val onInteractionChanged: (Int?) -> Unit = {},
    private val confirmConsent: (suspend (String, () -> Boolean) -> Boolean)? = null,
    private val onPaired: (BluetoothDevice) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = NearbyAirPods.Session()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var popup: PopupWindow? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consentJob: Job? = null
    @Volatile private var pendingConsent: BroadcastReceiver.PendingResult? = null
    private var consentFallback: Runnable? = null
    private var client: ShizukuAudioClient? = null
    private var discoveryOwned = false
    private var timeout: Runnable? = null
    private var continuation: Runnable? = null
    private var selection: Runnable? = null
    private var requestedAt = 0L
    private var firstCandidateAt: Long? = null
    private var pairedAddress: String? = null
    private var batteryObservedAfter = 0L
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
            val radioAge = (SystemClock.elapsedRealtimeNanos() - observedAtNanos).coerceAtLeast(0L)
            // Round up the age so decoding the same invitation cannot miss this floor by 1 ms.
            batteryObservedAfter = System.currentTimeMillis() - radioAge / 1_000_000 -
                if (radioAge % 1_000_000 == 0L) 0 else 1
            popup = PopupWindow(context) {
                if (session.isCurrent(token)) dismiss()
            }.also {
                it.openPairing(name, event.modelId, observedAtNanos)
                Log.d(TAG, "Lid advertisement to pairing overlay: ${(SystemClock.elapsedRealtimeNanos() - observedAtNanos) / 1_000_000}ms")
                it.showPairingActions(context.getString(R.string.pairing_nearby_hint), listOf(
                    context.getString(R.string.pairing_connect) to { discover(token) }
                ), stage = PopupWindow.Stage.NEARBY)
            }
        }
    }

    private fun discover(token: Long) {
        if (closed || blocked) return
        val now = SystemClock.elapsedRealtime()
        val attempt = session.beginAttempt(token, now) ?: return
        pairedAddress = null
        if (batteryObservedAfter == 0L) batteryObservedAfter = System.currentTimeMillis()
        stopDiscovery()
        requestedAt = now
        firstCandidateAt = null
        Log.d(TAG, "Connect requested")
        if (confirmConsent == null) runCatching {
            (client ?: ShizukuAudioClient(context).also { client = it }).connect()
        }
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
        fun observe(device: BluetoothDevice, name: String?) {
            val now = SystemClock.elapsedRealtime()
            if (candidates.observe(device.address, name, now) && firstCandidateAt == null) {
                firstCandidateAt = now
                Log.d(TAG, "Connect to first Classic candidate: ${now - requestedAt}ms")
            }
            showInstructions(token, if (candidates.ambiguous) R.string.pairing_multiple_cases else null)
            scheduleSelection(token, attempt, devices, candidates)
        }
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
                                val type = if (Build.VERSION.SDK_INT >= 37 || intent.hasExtra(BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE)) intent.getIntExtra(
                                    BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE, BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                                    else device.type
                                if (device.bondState != BluetoothDevice.BOND_NONE ||
                                    type !in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL)) return
                                // Keep unnamed Classic results so their later name event can finish this scan.
                                devices[device.address] = device
                                observe(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name)
                            }
                            BluetoothDevice.ACTION_NAME_CHANGED -> {
                                val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    ?: return
                                val device = devices[changed.address] ?: return
                                observe(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name)
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
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
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
        Log.d(TAG, "Classic discovery finished with ${devices.size} fresh Classic devices")
        if (candidates.readyInMillis(SystemClock.elapsedRealtime()) != null) {
            scheduleSelection(token, attempt, devices, candidates)
        } else {
            scheduleScan(token, attempt, if (candidates.ambiguous) R.string.pairing_multiple_cases else null)
        }
    }

    private fun scheduleSelection(token: Long, attempt: Long, devices: Map<String, BluetoothDevice>, candidates: NearbyAirPods.Discovery) {
        selection?.let(handler::removeCallbacks)
        selection = null
        val remaining = candidates.readyInMillis(SystemClock.elapsedRealtime()) ?: return
        selection = Runnable {
            selection = null
            if (canContinue(token, attempt)) {
                candidates.automatic(SystemClock.elapsedRealtime())?.let(devices::get)?.let { pair(token, attempt, it) }
            }
        }.also { handler.postDelayed(it, remaining) }
    }

    private fun pair(token: Long, attempt: Long, device: BluetoothDevice) {
        if (!canContinue(token, attempt)) return
        stopScan()
        try {
            if (device.bondState != BluetoothDevice.BOND_NONE ||
                device.name?.let { !NearbyAirPods.matchesName(session.modelId ?: 0, it) } == true) {
                showRetry(token, R.string.pairing_device_changed)
                return
            }
            var bondingObserved = false
            var consentRequested = false
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!canContinue(token, attempt) || receiver !== this) return
                    try {
                        val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (changed?.address != device.address) return
                        if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                            if (consentRequested || intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR) != 3 ||
                                device.bondState != BluetoothDevice.BOND_BONDING) return
                            consentRequested = true
                            val owner = this
                            // Keep Android's dialog as the fallback until the chosen consent succeeds.
                            val pending = if (isOrderedBroadcast) goAsync() else null
                            val consentDeadline = SystemClock.elapsedRealtime() + 1_500
                            if (pending != null) {
                                pendingConsent = pending
                                consentFallback = Runnable {
                                    if (pendingConsent === pending) {
                                        consentJob?.cancel()
                                        consentJob = null
                                        finishConsent(pending)
                                    }
                                }
                                    .also { handler.postDelayed(it, 1_500) }
                            }
                            consentJob = scope.launch {
                                var confirmed = false
                                val stillCurrent = {
                                    receiver === owner && canContinue(token, attempt) &&
                                        (pending == null || pendingConsent === pending && SystemClock.elapsedRealtime() < consentDeadline) &&
                                        runCatching { device.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false)
                                }
                                try {
                                    if (stillCurrent()) {
                                        confirmed = confirmConsent?.invoke(device.address, stillCurrent)
                                            ?: client?.confirmPairing(device.address, stillCurrent) ?: false
                                        Log.d(TAG, "Consent confirmation available: $confirmed")
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    Log.w(TAG, "Consent confirmation unavailable", error)
                                } finally {
                                    if (pending != null) handler.post {
                                        finishConsent(pending, confirmed && receiver === owner && canContinue(token, attempt) &&
                                            SystemClock.elapsedRealtime() < consentDeadline)
                                    }
                                }
                            }
                            return
                        }
                        if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDING -> {
                                bondingObserved = true
                                Log.d(TAG, "Connect to BONDING: ${SystemClock.elapsedRealtime() - requestedAt}ms")
                            }
                            BluetoothDevice.BOND_BONDED -> {
                                if (!bondingObserved || device.bondState != BluetoothDevice.BOND_BONDED) return
                                Log.d(TAG, "Connect to BONDED: ${SystemClock.elapsedRealtime() - requestedAt}ms")
                                finishConsent(accepted = true)
                                stopDiscovery()
                                pairedAddress = device.address
                                popup?.showPairingActions(context.getString(R.string.pairing_complete), listOf(
                                    context.getString(R.string.pairing_continue) to {
                                        if (session.isCurrentAttempt(token, attempt)) dismiss()
                                    }
                                ), busy = false, stage = PopupWindow.Stage.COMPLETE)
                                onPaired(device)
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
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
            }, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "Connect to createBond: ${SystemClock.elapsedRealtime() - requestedAt}ms")
            val started = if (Build.VERSION.SDK_INT >= 37) device.createBond(BluetoothDevice.TRANSPORT_BREDR)
                else device.createBond()
            if (!started) {
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
        pairedAddress = null
        batteryObservedAfter = 0L
        stopDiscovery()
        onInteractionChanged(null)
        popup?.showPairingActions(instructions(message), listOf(
            context.getString(R.string.pairing_retry) to { discover(token) }
        ), stage = PopupWindow.Stage.RETRY)
    }

    private fun instructions(message: Int? = null): String {
        val gesture = if (NearbyAirPods.usesDoubleTap(session.modelId ?: 0)) R.string.pairing_double_tap else R.string.pairing_hold_button
        return context.getString(gesture) + (message?.let { "\n\n${context.getString(it)}" } ?: "")
    }

    private fun showInstructions(token: Long, message: Int? = null) {
        if (!session.isCurrent(token)) return
        val connecting = message == R.string.pairing_confirm_system
        popup?.showPairingActions(
            if (connecting) context.getString(R.string.pairing_confirm_system) else instructions(message), emptyList(),
            stage = if (connecting) PopupWindow.Stage.CONNECTING else PopupWindow.Stage.SETUP)
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
        val oldReceiver = receiver
        receiver = null
        consentJob?.cancel()
        consentJob = null
        finishConsent()
        selection?.let(handler::removeCallbacks)
        selection = null
        continuation?.let(handler::removeCallbacks)
        continuation = null
        oldReceiver?.let {
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

    /** PendingResult is completed only on the main thread, once, including cancellation. */
    private fun finishConsent(expected: BroadcastReceiver.PendingResult? = pendingConsent, accepted: Boolean = false) {
        if (expected == null || pendingConsent !== expected) return
        pendingConsent = null
        consentFallback?.let(handler::removeCallbacks)
        consentFallback = null
        if (accepted) expected.abortBroadcast()
        expected.finish()
    }

    @androidx.annotation.MainThread
    fun updatePairingBattery(device: BluetoothDevice, batteries: Set<Battery>) {
        if (closed || blocked || pairedAddress != device.address) return
        if (runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)) {
            // Owned reports from this invitation can be verified after the bond completes.
            popup?.updatePairingBattery(batteries.filter { (it.observedAt ?: Long.MIN_VALUE) >= batteryObservedAfter }.toSet())
        } else dismiss()
    }

    private fun dismiss() {
        pairedAddress = null
        batteryObservedAfter = 0L
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
        scope.cancel()
        client?.close()
        client = null
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
