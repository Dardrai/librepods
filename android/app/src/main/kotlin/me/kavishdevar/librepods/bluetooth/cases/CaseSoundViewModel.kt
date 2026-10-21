@file:Suppress("MissingPermission")

package me.kavishdevar.librepods.bluetooth.cases

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.LibrePodsApplication
import java.util.concurrent.atomic.AtomicBoolean

internal data class CaseSoundState(
    val busy: Boolean = false,
    val ready: Boolean = false,
    val canPair: Boolean = false,
    val route: CaseSoundRoute = CaseSoundRoute.DULT,
    val entries: List<String> = emptyList(),
    val outcomeEnabled: Boolean = false,
)

internal class CaseSoundViewModel(context: Context): ViewModel() {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("case_sound_experiment", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(CaseSoundState(entries =
        preferences.getString("last_result", null)?.lines()?.takeLast(64) ?: emptyList()))
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var candidate: BluetoothDevice? = null

    private fun log(stage: String, text: String) {
        mutableState.update { it.copy(entries = (it.entries + "$stage: $text").takeLast(64)) }
    }
    private fun save() { preferences.edit().putString("last_result", mutableState.value.entries.joinToString("\n")).apply() }
    fun route(route: CaseSoundRoute) { if (!mutableState.value.busy) mutableState.update { it.copy(route = route) } }
    fun heard(outcome: String) {
        if (mutableState.value.outcomeEnabled && outcome in listOf("heard", "silent", "unsure")) {
            log("User", outcome); save(); mutableState.update { it.copy(outcomeEnabled = false) }
        }
    }
    fun stop() { work?.cancel() }

    private fun audioIdle(): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive && audio.activeRecordingConfigurations.isEmpty() &&
            (context as? LibrePodsApplication)?.experimentalAudioControllers?.values?.all { it.canRefreshSettings() } != false
    }.getOrDefault(false)

    private fun permitted(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private suspend fun scan(): BluetoothDevice {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: error("bluetooth")
        val candidates = linkedMapOf<String, BluetoothDevice>()
        val done = CompletableDeferred<Unit>()
        val callback = object: ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val age = SystemClock.elapsedRealtimeNanos() - result.timestampNanos
                if (!result.isConnectable || age !in 0..10_000_000_000L) return
                synchronized(candidates) {
                    if (candidates.size > 1) return
                    candidates[result.device.address] = result.device
                    if (candidates.size > 1) done.complete(Unit)
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) { done.completeExceptionally(IllegalStateException("scan")) }
        }
        val filters = listOf(CaseSoundRoute.DULT.service, CaseSoundRoute.HAWKEYE.service).flatMap { uuid ->
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build(),
                ScanFilter.Builder().setServiceData(ParcelUuid(uuid), byteArrayOf()).build())
        }
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            withTimeoutOrNull(8_000) {
                while (!done.isCompleted) { check(audioIdle()) { "audio-active" }; delay(100) }
                done.await()
            }
            return synchronized(candidates) {
                check(candidates.size == 1) { if (candidates.isEmpty()) "not-found" else "ambiguous" }
                candidates.values.single()
            }
        } finally { scanner.stopScan(callback) }
    }

    fun find() {
        if (mutableState.value.busy) return
        candidate = null
        mutableState.update { it.copy(busy = true, ready = false, canPair = false, entries = emptyList(), outcomeEnabled = false) }
        work = viewModelScope.launch {
            val connection = CaseGattConnection { }
            try {
                check(permitted()) { "permission" }
                check(audioIdle()) { "audio-active" }
                log("Discovery", "Scanning for one nearby case (8 seconds).")
                val device = scan()
                check(audioIdle()) { "audio-active" }
                log("Connection", "Opening a separate BLE connection.")
                connection.connect(context, device)
                connection.verifyIdentity()
                check(CaseSoundRoute.entries.any { connection.characteristic(it.service, it.characteristic) != null }) { "route" }
                candidate = device
                mutableState.update { it.copy(ready = true, canPair = device.bondState == BluetoothDevice.BOND_NONE) }
                log("Identity", "Apple Pro 3 case verified. This does not link it to your saved AirPods; confirm it is yours.")
            } catch (failure: Exception) { failure(failure) }
            finally {
                withContext(NonCancellable) { connection.close(); save(); mutableState.update { it.copy(busy = false) } }
            }
        }
    }

    fun pair() {
        val device = candidate ?: return
        if (mutableState.value.busy || !mutableState.value.canPair) return
        try {
            check(permitted()) { "permission" }
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                log("Pairing", if (device.createBond()) "Android pairing requested. Complete its dialog, then try Start again." else "Android could not start pairing.")
            } else log("Pairing", "Existing bond retained.")
        } catch (failure: Exception) { failure(failure) }
        save()
    }

    fun start() {
        val device = candidate ?: return
        if (mutableState.value.busy || !mutableState.value.ready) return
        val route = mutableState.value.route
        mutableState.update { it.copy(busy = true, outcomeEnabled = false, entries = listOf("Route: ${route.label}")) }
        work = viewModelScope.launch {
            val startIssued = AtomicBoolean(false)
            val stopIssued = AtomicBoolean(false)
            val connection = CaseGattConnection { bytes ->
                if (startIssued.get()) when (val response = route.response(bytes)) {
                    is CaseSoundResponse.Acknowledged -> if (!response.stop || stopIssued.get())
                        log("Protocol", "${if (response.stop) "Stop" else "Start"} response status ${response.status}. Audible sound remains unconfirmed.")
                    CaseSoundResponse.Completed -> log("Completion", "Case reported completion. This does not establish audible playback.")
                    null -> log("Protocol", "Unrecognized reply (${bytes.size} bytes); payload omitted.")
                }
            }
            try {
                check(permitted()) { "permission" }
                check(audioIdle()) { "audio-active" }
                log("Connection", "Connecting; existing bonding is retained.")
                connection.connect(context, device)
                connection.verifyIdentity()
                val characteristic = connection.characteristic(route.service, route.characteristic) ?: error("route")
                log("Identity", "Fresh Apple Pro 3 case and selected service verified.")
                connection.subscribe(characteristic)
                log("Subscription", "Enabled; previous descriptor value saved for restoration.")
                runCaseSoundWindow(::audioIdle, start = {
                    startIssued.set(true)
                    log("Start", "Request issued; automatic Stop in at most two seconds. Sound unconfirmed.")
                    val acknowledged = connection.write(characteristic, route.command(false))
                    log("ATT write", if (acknowledged) "Start accepted by ATT; awaiting protocol result." else "Start locally queued without ATT response.")
                }, stop = {
                    stopIssued.set(true)
                    log("Stop", "Stop requested.")
                    try {
                        val acknowledged = connection.stop(characteristic, route.command(true))
                        log("ATT write", if (acknowledged) "Stop accepted by ATT." else "Stop queued; ATT result unconfirmed.")
                        delay(400) // Allow a typed Stop response/completion before restoring the subscription.
                    } catch (failure: Exception) { log("Stop", "Could not confirm Stop; connection will close.") }
                })
                log("Result", "Window ended. Record whether you heard the case.")
            } catch (failure: Exception) { failure(failure) }
            finally {
                withContext(NonCancellable) {
                    log("Subscription", if (connection.close()) "Previous value restored and verified; connection closed."
                        else "Restoration could not be verified; connection closed.")
                    mutableState.update { it.copy(busy = false, outcomeEnabled = startIssued.get()) }
                    save()
                }
            }
        }
    }

    private fun failure(failure: Exception) {
        when {
            failure is CancellationException -> log("Cancelled", "Stopping and closing the connection.")
            failure is CaseGattFailure -> {
                val authentication = failure.isGattStatus && failure.status in listOf(5, 8, 15)
                log(if (authentication) "Authentication/encryption" else if (failure.isGattStatus) "Connection/ATT" else "Android write queue",
                    "Android status ${failure.status}. ${if (authentication) "Normal Android pairing may be required." else "No sound confirmed."}")
            }
            else -> log("Stopped", when (failure.message) {
                "permission" -> "Grant Nearby devices access in Android settings."
                "audio-active" -> "Pause media and end calls/recordings before testing."
                "ambiguous" -> "More than one nearby candidate. Move other cases away and Find again."
                "not-found" -> "No connectable case found. Open your case nearby and Find again."
                "route" -> "Selected service is unavailable; choose another route or Find again."
                else -> "Could not verify the case or complete this stage. Find again; no playback is confirmed."
            })
        }
    }
}
