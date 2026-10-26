/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import java.io.File

/** Owns microphone capture and restores the selected pair's native playback afterward. */
class ExperimentalAudioController(
    private val context: Context,
    private val device: AppleDevice,
    private val onMicrophoneActive: (Boolean) -> Unit,
) : AutoCloseable {
    val bridge = ShizukuAudioClient(context)
    private val aacp = device.aacp
    val microphone = AirPodsMicrophone(aacp, File(context.filesDir, "microphone-tests"), ::recoverPlayback)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableMessage = MutableStateFlow("")
    val message = mutableMessage.asStateFlow()
    private val lock = Any()
    private val generation = AudioSessionGeneration()
    private var closed = false
    private var settingsRefresh = false
    private var callStopRecovery: Pair<LivePcmConsumer, Long>? = null
    val isConnected: Boolean get() = aacp.captureAudioSocket() != null
    val supportsMicrophone: Boolean get() = device.metadata.value.modelNumber in MICROPHONE_MODELS

    fun canRefreshSettings(): Boolean = synchronized(lock) {
        !closed && !settingsRefresh &&
            microphone.state.value.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR) && bridge.canRefreshSettings()
    }

    fun tryBeginSettingsRefresh(): Boolean = synchronized(lock) {
        if (!canRefreshSettings() || !bridge.tryBeginSettingsRefresh()) false
        else { settingsRefresh = true; true }
    }

    fun endSettingsRefresh() = synchronized(lock) {
        settingsRefresh = false
        bridge.endSettingsRefresh()
    }

    init {
        scope.launch {
            microphone.state.collect { state ->
                synchronized(lock) {
                    if (closed || microphone.state.value != state) return@synchronized
                    val active = state.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)
                    runCatching {
                        onMicrophoneActive(active)
                    }.onFailure {
                        mutableMessage.value = "Microphone foreground access failed: ${it.message.orEmpty()}"
                        stopLocked()
                    }
                }
            }
        }
    }

    private fun allowedLocked(callCurrent: (() -> Boolean)? = null): Boolean {
        if (closed) return false
        val preparedCall = callCurrent?.let { runCatching(it).getOrDefault(false) } == true
        if (callCurrent != null && !preparedCall) {
            mutableMessage.value = "The selected call audio route is no longer ready."
            return false
        }
        if (settingsRefresh) {
            mutableMessage.value = "Wait for the settings refresh to finish."
            return false
        }
        if (device.metadata.value.modelNumber !in MICROPHONE_MODELS) {
            mutableMessage.value = "The high-quality microphone has not been verified for this AirPods model."
            return false
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableMessage.value = "Allow microphone access first."
            return false
        }
        val nativeMuted = runCatching { context.getSystemService(AudioManager::class.java)?.isMicrophoneMute }.getOrNull()
        if (nativeMuted == null || (nativeMuted && !preparedCall)) {
            mutableMessage.value = "Turn on microphone access in Android's privacy controls first."
            return false
        }
        if (!bridge.state.value.connected) {
            mutableMessage.value = "Allow Bluetooth access in Settings to restore playback after microphone use."
            return false
        }
        if (bridge.isMicrophonePrivacyBlocked()) {
            mutableMessage.value = "Turn on microphone access in Android's privacy controls first."
            return false
        }
        if (microphone.state.value.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)) {
            mutableMessage.value = "Stop the current microphone session first."
            return false
        }
        return true
    }

    fun startRecording(destination: File? = null): Boolean = startCapture { microphone.start(destination) }

    internal fun startLive(consumer: LivePcmConsumer): Boolean = startCapture { microphone.startLive(consumer) }

    /** Only an already-verified call route may translate Android's call mute into silent call PCM. */
    internal fun startLiveForCall(consumer: LivePcmConsumer, callCurrent: () -> Boolean): Boolean =
        startCapture(callCurrent) { microphone.startLive(consumer) }

    internal fun stopLive(consumer: LivePcmConsumer) = synchronized(lock) {
        if (microphone.ownsLiveConsumer(consumer)) stop()
    }

    /** Call only after the exact root lease confirms its streams and policy have stopped. */
    internal fun armCallStopRecovery(consumer: LivePcmConsumer): Boolean = synchronized(lock) {
        if (closed || !microphone.ownsLiveConsumer(consumer)) return false
        val state = microphone.state.value
        if (state.generation <= 0 || state.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)) return false
        callStopRecovery = consumer to state.generation
        true
    }

    internal fun clearCallStopRecovery(consumer: LivePcmConsumer) = synchronized(lock) {
        if (callStopRecovery?.first === consumer) callStopRecovery = null
    }

    internal fun receivedFrameFloor(consumer: LivePcmConsumer): Long? = synchronized(lock) {
        if (closed) null else microphone.receivedFrameFloor(consumer)
    }

    private fun startCapture(callCurrent: (() -> Boolean)? = null, start: () -> Boolean): Boolean = synchronized(lock) {
            if (!allowedLocked(callCurrent)) return false
            callStopRecovery = null
            bridge.cancelPlaybackRecovery()
            val request = generation.next()
            mutableMessage.value = ""
            try {
                if (!startMicrophoneLocked(request, callCurrent, start)) {
                    mutableMessage.value = microphone.state.value.message
                    false
                } else true
            } catch (error: Exception) {
                stopLocked()
                mutableMessage.value = error.message ?: "The AirPods microphone could not start."
                false
            }
    }

    private fun startMicrophoneLocked(request: Long, callCurrent: (() -> Boolean)?, start: () -> Boolean): Boolean {
        var started = false
        try {
            onMicrophoneActive(true)
            check(generation.accepts(request) && allowedLocked(callCurrent)) { mutableMessage.value.ifBlank { "Microphone start cancelled" } }
            started = start()
            return started
        } finally {
            if (!started) {
                runCatching { onMicrophoneActive(false) }
            }
        }
    }

    fun stop() = synchronized(lock) {
        if (!closed) {
            if (microphone.state.value.phase in listOf(MicrophonePhase.STOPPING, MicrophonePhase.RECOVERING)) microphone.stop()
            else stopLocked()
        }
    }

    private fun stopLocked() {
        generation.invalidate()
        bridge.cancelPlaybackRecovery()
        microphone.stop()
    }

    fun onDisconnected() {
        synchronized(lock) {
            if (closed) return
            callStopRecovery = null
            generation.invalidate()
            bridge.cancelPlaybackRecovery()
            microphone.onDisconnected()
        }
    }

    fun onPermissionRevoked() {
        synchronized(lock) {
            if (closed) return
            callStopRecovery = null
            stopLocked()
            microphone.onPermissionRevoked()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            callStopRecovery = null
            settingsRefresh = false
            bridge.endSettingsRefresh()
            generation.close()
            bridge.cancelPlaybackRecovery()
            microphone.close()
            bridge.close()
            runCatching { onMicrophoneActive(false) }
        }
        scope.cancel()
    }

    private fun recoverPlayback(session: Long) {
        val socket = microphone.transportFor(session) ?: return microphone.completeRecovery(session, false)
        scope.launch {
            fun current() = !closed && aacp.ownsAudioSocket(socket) &&
                microphone.state.value.generation == session && microphone.state.value.phase == MicrophonePhase.RECOVERING
            val callStopped = synchronized(lock) {
                val permit = callStopRecovery?.takeIf { it.second == session }
                if (permit == null) false else {
                    callStopRecovery = null
                    current() && microphone.completeCallStopRecovery(session, permit.first)
                }
            }
            if (callStopped) return@launch
            val restored = recoverMicrophonePlayback(::current,
                { microphone.canKeepPlayback(session) },
                { bridge.connectNativePlayback(device.macAddress.value, ::current) },
                { bridge.restoreNativePlayback(device.macAddress.value, ::current) })
            microphone.completeRecovery(session, restored && current())
        }
    }

    companion object {
        val MICROPHONE_MODELS = setOf("A3053", "A3050", "A3054", "A3056", "A3055", "A3057", "A3531", "A3532", "A3533",
            "A3439", "A3440", "A3441", "A2931", "A2699", "A2698", "A3047", "A3048", "A3049", "A3063", "A3064", "A3065", "A3454")
    }
}

/** A connected native profile needs no cycling after a successful quiet live-uplink stop. */
internal suspend fun recoverMicrophonePlayback(
    current: () -> Boolean,
    canKeepPlayback: () -> Boolean,
    connectOnly: suspend () -> Boolean,
    recover: suspend () -> Boolean,
): Boolean {
    if (!current()) return false
    if (canKeepPlayback()) {
        val connected = connectOnly()
        if (!current()) return false
        if (connected && canKeepPlayback()) return true
    }
    if (!current()) return false
    return recover() && current()
}
