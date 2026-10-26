/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.audio.PipePcmConsumer
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.services.LibrePodsService
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Nonexported debug entry. Only aggregate statistics leave the own-session virtual input. */
class MicrophoneRouteProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("operation")) {
            "stop" -> running.get()?.cancel()
            "start" -> {
                val peer = intent.getStringExtra("peer")?.takeIf {
                    it.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))
                }?.let { runCatching { MacAddress(it.uppercase(Locale.ROOT)) }.getOrNull() } ?: return
                val job = scope.launch(start = CoroutineStart.LAZY) { runProbe(context.applicationContext, peer) }
                if (!running.compareAndSet(null, job)) { job.cancel(); return }
                job.invokeOnCompletion { running.compareAndSet(job, null) }
                job.start()
            }
        }
        // Return immediately; Android17 can defer Binder replies while a broadcast remains unfinished.
    }

    private suspend fun runProbe(context: Context, peer: MacAddress) {
        val ownJob = currentCoroutineContext()[Job]
        val connected = CompletableDeferred<LibrePodsService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = (binder as? LibrePodsService.LocalBinder)?.getService()
                if (service != null) connected.complete(service)
                else connected.completeExceptionally(IllegalStateException("Unexpected audio service"))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Audio service stopped"))
                ownJob?.cancel()
            }
        }
        var bound = false
        var input: ParcelFileDescriptor? = null
        var consumer: PipePcmConsumer? = null
        var root: ShizukuAudioClient.MicrophoneProbeLease? = null
        var live: AutoCloseable? = null
        try {
            check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                "Open LibrePods before starting the microphone check"
            }
            check(context.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL) {
                "Calls are excluded from this check"
            }
            bound = context.bindService(Intent(context, LibrePodsService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Audio service is unavailable" }
            val service = withTimeout(3000) { connected.await() }
            val app = context as LibrePodsApplication
            val device = service.devices.value[peer] as? AppleDevice ?: error("Selected AirPods are unavailable")
            val controller = app.experimentalAudioControllers[peer] ?: error("Selected audio controller is unavailable")
            val socket = device.aacp.captureAudioSocket() ?: error("Selected AirPods are not connected")
            fun gameOff(): Boolean = app.nativeGameModeControllers[peer]?.state?.value?.let {
                !it.desiredOn && !it.active && !it.restoring && !it.remoteRestorePending
            } != false
            fun selectedOwner() = service.devices.value[peer] === device &&
                app.experimentalAudioControllers[peer] === controller && device.aacp.ownsAudioSocket(socket)
            fun requireAdmission() {
                val state = controller.microphone.state.value
                requireMicrophoneProbeAdmission(selectedOwner(), gameOff(), state.phase,
                    controller.microphone.transportFor(state.generation) != null)
            }
            requireAdmission()
            awaitMicrophoneProbeRoot(controller.bridge, ::selectedOwner)
            requireAdmission()
            fun current() = selectedOwner() && controller.bridge.hasRootPrivileges && gameOff()
            val pipe = PipePcmConsumer.open()
            input = pipe.first
            consumer = pipe.second
            root = controller.bridge.prepareMicrophoneProbe(pipe.first, ::current)
            // The helper owns the received descriptor; retire this process's duplicate.
            pipe.first.close(); input = null
            check(current()) { "Selected audio owner changed" }
            live = service.startLiveMicrophone(device, pipe.second) ?: error("Microphone start was refused")
            withTimeout(4000) {
                while (controller.microphone.state.value.phase != MicrophonePhase.CAPTURING) {
                    val state = controller.microphone.state.value
                    check(current() && state.phase !in listOf(MicrophonePhase.ERROR, MicrophonePhase.RECOVERING)) {
                        "Microphone did not start: ownerCurrent=${current()} phase=${state.phase} message=${state.message}"
                    }
                    delay(25)
                }
            }
            Log.i(TAG, "Own virtual input ready; sourceRate=64000 mixRate=48000 inputType=25 files=0")
            val until = SystemClock.elapsedRealtime() + 19_500
            // Finish just before the helper's independent20s PCM deadline so normal teardown has no EOF race.
            while (SystemClock.elapsedRealtime() < until) {
                val state = controller.microphone.state.value
                check(current() && state.phase == MicrophonePhase.CAPTURING) {
                    "Microphone ended: ownerCurrent=${current()} phase=${state.phase} message=${state.message}"
                }
                delay(50)
            }
        } catch (cancelled: CancellationException) {
            Log.i(TAG, "Own microphone check cancelled")
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Own microphone check failed: ${failure.message}")
        } finally {
            withContext(NonCancellable) {
                consumer?.stopSending()
                consumer?.let {
                    Log.i(TAG, "Pipe bytes=${it.writtenBytes} partialWrites=${it.partialWrites} zeroWrites=${it.zeroWrites}" +
                        " wouldBlockWrites=${it.wouldBlockWrites} maxWriteDelayMs=${it.maxWriteDelayMs} failure=${it.lastFailure}")
                }
                try {
                    root?.finish()?.let { result ->
                        val fields = listOf("success", "cleanupComplete", "framesInput", "framesReceived", "sourceRate",
                            "receivedRate", "sourceRms", "receivedRms", "sourcePeak", "receivedPeak",
                            "sourceClipCount", "receivedClipCount", "wallMs", "silenceFrames", "framesReadFromProducer",
                            "shortWrites", "zeroWrites", "pendingMaxAgeMs", "trackBufferFrames", "trackCapacityFrames",
                            "recordBufferFrames", "error", "audioFilesWritten")
                        @Suppress("DEPRECATION")
                        Log.i(TAG, fields.joinToString(" ") { "$it=${result.get(it)}" })
                    }
                } catch (failure: Exception) {
                    Log.w(TAG, "Root microphone cleanup failed: ${failure.javaClass.simpleName}")
                } finally {
                    runCatching { live?.close() }
                    runCatching { consumer?.close() }
                    runCatching { input?.close() }
                    if (bound) runCatching { context.unbindService(connection) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LibrePodsMicRouteProbe"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val running = AtomicReference<Job?>()
    }
}

/** Check each independent condition before allocating a pipe or starting either microphone. */
internal fun requireMicrophoneProbeAdmission(selectedOwner: Boolean, gameOff: Boolean, phase: MicrophonePhase,
    transportInUse: Boolean = false) {
    check(selectedOwner) { "Selected AirPods connection changed" }
    check(gameOff) { "Stop Game Mode and wait for restoration before this check" }
    check(phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR) && !transportInUse) {
        "Microphone is not idle: $phase; transportInUse=$transportInUse"
    }
}

internal suspend fun awaitMicrophoneProbeRoot(bridge: ShizukuAudioClient, selectedOwner: () -> Boolean) {
    check(selectedOwner()) { "Selected AirPods connection changed" }
    if (!bridge.state.value.connected) {
        // Ordinary reconnect uses existing authorization. Never request a new root or Shizuku grant here.
        bridge.connect()
        check(withTimeoutOrNull(5000) { bridge.state.first { it.connected } } != null) {
            "Privileged audio service did not connect within five seconds"
        }
    }
    check(selectedOwner()) { "Selected AirPods connection changed while connecting privileged audio" }
    check(bridge.hasRootPrivileges) {
        "Microphone route check needs a UID-0 audio service; backend=${bridge.state.value.backend ?: "unavailable"}"
    }
}
