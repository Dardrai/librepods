/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the optional audio path; Android's ordinary playback route remains the default. */
class ExperimentalAudioController(
    private val context: Context,
    aacp: AACPManager,
    onRecoveryRequired: (Long) -> Unit,
    private val onMicrophoneActive: (Boolean) -> Unit,
) : AutoCloseable {
    val bridge = ShizukuAudioClient(context)
    val microphone = AirPodsMicrophone(aacp, File(context.filesDir, "microphone-tests"), onRecoveryRequired)
    val sensors = AirPodsSensorSession(aacp)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableMessage = MutableStateFlow("")
    val message = mutableMessage.asStateFlow()
    private val lock = Any()
    private val generation = AudioSessionGeneration()
    private var closed = false
    private var opening: Long? = null
    private var sink: PipeSink? = null

    init {
        scope.launch {
            microphone.state.collect { state ->
                synchronized(lock) {
                    if (closed || microphone.state.value != state) return@synchronized
                    val active = state.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)
                    runCatching {
                        sensors.setSuspended(active)
                        onMicrophoneActive(active)
                    }.onFailure {
                        mutableMessage.value = "Microphone foreground access failed: ${it.message.orEmpty()}"
                        stopLocked()
                    }
                    // The initial IDLE emission must not cancel a route that is still opening.
                    if (!active && sink != null) stopBridgeLocked()
                }
            }
        }
        scope.launch {
            bridge.state.collect { state ->
                synchronized(lock) {
                    if (closed || bridge.state.value != state || (sink == null && opening == null)) return@synchronized
                    if (!state.connected || (sink != null && state.session in listOf("failed", "stopped"))) {
                        val reason = state.detail.ifBlank { "Shizuku audio route stopped." }
                        stopLocked()
                        mutableMessage.value = reason
                    }
                }
            }
        }
    }

    private fun allowedLocked(): Boolean {
        if (closed) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableMessage.value = "Allow microphone access first."
            return false
        }
        if (runCatching { context.getSystemService(AudioManager::class.java)?.isMicrophoneMute }.getOrNull() != false) {
            mutableMessage.value = "Turn on microphone access in Android's privacy controls first."
            return false
        }
        if (!bridge.state.value.connected) {
            mutableMessage.value = "Connect Shizuku so playback can be restored after microphone use."
            return false
        }
        if (bridge.state.value.probing) {
            mutableMessage.value = "Wait for the audio diagnostics to finish first."
            return false
        }
        if (bridge.isMicrophonePrivacyBlocked()) {
            mutableMessage.value = "Turn on microphone access in Android's privacy controls first."
            return false
        }
        if (opening != null || microphone.state.value.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)) {
            mutableMessage.value = "Stop the current microphone session first."
            return false
        }
        return true
    }

    fun startRecording() {
        synchronized(lock) {
            if (!allowedLocked()) return
            bridge.cancelPlaybackRecovery()
            val request = generation.next()
            mutableMessage.value = ""
            try {
                if (!startMicrophoneLocked(request, recordToFile = true)) mutableMessage.value = microphone.state.value.message
            } catch (error: Exception) {
                stopLocked()
                mutableMessage.value = error.message ?: "The AirPods microphone could not start."
            }
        }
    }

    fun startInjection(targetPackage: String, forCall: Boolean) {
        val prepared = synchronized(lock) {
            if (!allowedLocked()) return
            try {
                val request = generation.next()
                opening = request
                val route = bridge.prepareMicrophoneInjection(AirPodsMicrophoneClock.PHYSICAL_RATE, 1, targetPackage.trim(), forCall)
                mutableMessage.value = "Opening the microphone route…"
                request to route
            } catch (error: Exception) {
                opening = null
                mutableMessage.value = error.message ?: "The microphone route could not start."
                return
            }
        }
        val (request, route) = prepared
        scope.launch(Dispatchers.IO) {
            val descriptor = bridge.startPreparedInjection(route)
            synchronized(lock) {
                if (!generation.accepts(request)) {
                    runCatching { descriptor?.close() }
                    return@synchronized
                }
                opening = null
                if (descriptor == null) {
                    mutableMessage.value = bridge.state.value.detail
                    return@synchronized
                }
                if (!allowedLocked()) {
                    runCatching { descriptor.close() }
                    stopBridgeLocked()
                    return@synchronized
                }
                try {
                    val next = PipeSink(descriptor)
                    sink = next
                    if (!startMicrophoneLocked(request, recordToFile = false, output = next)) {
                        stopBridgeLocked()
                        mutableMessage.value = "The AirPods microphone could not start."
                    } else mutableMessage.value = "Verify the microphone in the receiving app. Audio is not saved."
                } catch (error: Exception) {
                    runCatching { descriptor.close() }
                    stopLocked()
                    mutableMessage.value = error.message ?: "The microphone route could not start."
                }
            }
        }
    }

    private fun startMicrophoneLocked(request: Long, recordToFile: Boolean, output: PcmSink? = null): Boolean {
        var started = false
        try {
            sensors.setSuspended(true)
            onMicrophoneActive(true)
            check(generation.accepts(request) && allowedLocked()) { mutableMessage.value.ifBlank { "Microphone start cancelled" } }
            started = microphone.start(recordToFile = recordToFile, sink = output)
            return started
        } finally {
            if (!started) {
                runCatching { sensors.setSuspended(false) }
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
        opening = null
        stopBridgeLocked()
        microphone.stop()
    }

    fun onDisconnected() {
        synchronized(lock) {
            if (closed) return
            generation.invalidate()
            opening = null
            stopBridgeLocked()
            microphone.onDisconnected()
            runCatching { sensors.onDisconnected() }
        }
    }

    fun onPermissionRevoked() {
        synchronized(lock) {
            if (closed) return
            stopLocked()
            microphone.onPermissionRevoked()
        }
    }

    private fun stopBridgeLocked() {
        runCatching { sink?.close() }
        sink = null
        bridge.stopSession()
    }

    private inner class PipeSink(private val descriptor: ParcelFileDescriptor) : PcmSink {
        private val closed = AtomicBoolean()
        init {
            val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }

        override fun onFormat(format: PcmFormat) {
            check(format.sampleRate == AirPodsMicrophoneClock.PHYSICAL_RATE && format.channels == 1) {
                "Unexpected microphone format; route stopped."
            }
        }

        override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + 500
            while (offset < bytes.size) {
                check(!closed.get() && SystemClock.elapsedRealtime() < deadline) { "Microphone output stalled." }
                try {
                    val written = Os.write(descriptor.fileDescriptor, bytes, offset, bytes.size - offset)
                    check(written > 0) { "Microphone output closed." }
                    offset += written
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                    val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLOUT.toShort() }
                    Os.poll(arrayOf(poll), 50)
                }
            }
        }

        fun close() { if (closed.compareAndSet(false, true)) descriptor.close() }
        override fun onStopped() {
            synchronized(lock) {
                runCatching { close() }
                if (sink === this) {
                    sink = null
                    bridge.stopSession()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation.close()
            opening = null
            stopBridgeLocked()
            microphone.close()
            runCatching { sensors.close() }
            bridge.close()
            runCatching { onMicrophoneActive(false) }
        }
        scope.cancel()
    }
}
