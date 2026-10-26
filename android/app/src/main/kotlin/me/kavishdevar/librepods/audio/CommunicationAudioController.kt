/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient

/** Local consent and one selected call owner; no phone number or call audio is retained. */
class CommunicationAudioController internal constructor(
    private val context: Context,
    private val device: AppleDevice,
    private val audio: ExperimentalAudioController,
    private val deviceCurrent: () -> Boolean,
    private val armForeground: (Boolean) -> Boolean,
    private val suspendGame: suspend () -> Boolean,
    private val startInput: (CallPcmConsumer, () -> Boolean) -> AutoCloseable?,
    private val released: () -> Unit,
) : AutoCloseable {
    enum class Phase { OFF, WAITING, STARTING, ACTIVE, STOPPING, UNAVAILABLE, FAILED }
    data class State(
        val enabled: Boolean = false,
        val phase: Phase = Phase.OFF,
        val muted: Boolean = false,
    )
    internal data class Call(val kind: Int, val uid: Int, val session: Int, val packageName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("communication_audio", Context.MODE_PRIVATE)
    private val preferenceKey = "enabled:${device.macAddress.value}"
    private val initiallyEnabled = preferences.getBoolean(preferenceKey, false)
    private val mutableState = MutableStateFlow(State(initiallyEnabled, if (initiallyEnabled) Phase.WAITING else Phase.OFF))
    val state = mutableState.asStateFlow()
    val supported: Boolean get() = Build.VERSION.SDK_INT == 37 && audio.supportsMicrophone
    private var armed = false
    @Volatile private var closed = false
    private var sessionJob: Job? = null
    @Volatile private var owner: Any? = null
    @Volatile private var input: CallPcmConsumer? = null
    @Volatile internal var hasPreparedRoute = false
        private set
    private var lastCall: Call? = null
    private val pollDiagnostic = ChangedCallDiagnostic()

    init {
        scope.launch {
            yield() // Register the selected controller before callbacks can start a call.
            while (isActive && !closed) {
                try { poll() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    logPoll { "inspection-failed reason=${failure.javaClass.simpleName}" }
                    if (sessionJob == null && mutableState.value.enabled) publish(Phase.UNAVAILABLE)
                }
                delay(1_000)
            }
        }
    }

    /** Called by the selected device's visible switch after microphone permission. */
    fun setEnabled(enabled: Boolean) {
        if (closed) return
        preferences.edit().putBoolean(preferenceKey, enabled).apply()
        mutableState.value = mutableState.value.copy(enabled = enabled,
            phase = if (enabled) Phase.WAITING else if (sessionJob != null) Phase.STOPPING else Phase.OFF)
        lastCall = null
        if (!enabled) {
            sessionJob?.cancel()
            if (sessionJob == null) disarm()
        }
    }

    internal fun ownsInput(consumer: LivePcmConsumer): Boolean = input === consumer && owner != null && !closed
    internal val ownsSession: Boolean get() = owner != null
    internal val ownsMicrophoneCapture: Boolean get() = input?.let { audio.microphone.ownsLiveConsumer(it) } == true

    private fun publish(phase: Phase, muted: Boolean = false) {
        mutableState.value = mutableState.value.copy(phase = phase, muted = muted)
    }

    private fun disarm() {
        if (armed) { armed = false; armForeground(false) }
    }

    private suspend fun poll() {
        if (!mutableState.value.enabled) {
            logPoll { "off" }
            if (sessionJob == null) { disarm(); publish(Phase.OFF) }
            return
        }
        if (!supported || !deviceCurrent() || !audio.isConnected ||
            !hasPermissions()) {
            logPoll { "unavailable supported=$supported current=${deviceCurrent()} connected=${audio.isConnected} permissions=${hasPermissions()}" }
            sessionJob?.cancel()
            if (sessionJob == null) { disarm(); publish(Phase.UNAVAILABLE) }
            return
        }
        audio.bridge.connect()
        if (!audio.bridge.hasRootPrivileges) {
            logPoll { "root-unavailable" }
            if (sessionJob == null) publish(Phase.UNAVAILABLE)
            return
        }
        if (!armed) {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                logPoll { "waiting-for-foreground" }
                publish(Phase.WAITING)
                return
            }
            armed = armForeground(true)
            if (!armed) { logPoll { "foreground-admission-failed" }; publish(Phase.UNAVAILABLE); return }
        }
        if (sessionJob != null) { logPoll { "owned-session" }; return }
        if (!audio.canRefreshSettings()) {
            logPoll { "audio-busy microphone=${audio.microphone.state.value.phase} bridgeReady=${audio.bridge.canRefreshSettings()}" }
            publish(Phase.WAITING); return
        }
        val mode = context.getSystemService(AudioManager::class.java).mode
        if (mode !in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            logPoll { "waiting-for-call mode=$mode" }
            lastCall = null
            publish(Phase.WAITING)
            return
        }
        val candidates = if (mode == AudioManager.MODE_IN_CALL) listOf(1 to "")
            else VOIP_PACKAGES.map { 2 to it }
        val rejected = mutableListOf<String>()
        for ((kind, packageName) in candidates) {
            val report = audio.bridge.inspectCommunication(device.macAddress.value, kind, packageName)
            val call = inspectedCall(report, kind)
            if (call == null) {
                if (Log.isLoggable(TAG, Log.DEBUG)) rejected += "kind=$kind reason=${inspectionReason(report)}"
                continue
            }
            if (lastCall == call) { logPoll { "waiting-for-retry kind=$kind" }; return }
            lastCall = call
            logPoll { "starting kind=$kind" }
            val token = Any()
            owner = token
            sessionJob = scope.launch(start = CoroutineStart.LAZY) { runCall(token, call) }
            sessionJob?.start()
            return
        }
        logPoll { "ineligible mode=$mode " + rejected.joinToString("; ") }
        publish(Phase.WAITING)
    }

    private suspend fun runCall(token: Any, call: Call) {
        var readEnd: android.os.ParcelFileDescriptor? = null
        var consumer: CallPcmConsumer? = null
        var lease: ShizukuAudioClient.CommunicationLease? = null
        var live: AutoCloseable? = null
        var inputGenerationBeforeStart: Long? = null
        var failed = false
        fun current(): Boolean = !closed && owner === token && mutableState.value.enabled &&
            deviceCurrent() && audio.isConnected && audio.bridge.hasRootPrivileges && hasPermissions()
        try {
            publish(Phase.STARTING)
            check(current() && suspendGame() && current()) { "Game Mode did not release call audio" }
            val pipe = CallPcmConsumer.open()
            readEnd = pipe.first
            consumer = pipe.second
            input = consumer
            lease = audio.bridge.prepareCommunication(pipe.first, device.macAddress.value,
                call.kind, call.packageName, call.session, ::current)
            pipe.first.close(); readEnd = null
            hasPreparedRoute = true
            logReceipt("prepared", lease.readiness)
            var epoch = lease.readiness.getLong("epoch")
            check(epoch > 0 && current()) { "Call owner changed during preparation" }
            consumer.updateEpoch(epoch, lease.readiness.getBoolean("muted"), 0)
            inputGenerationBeforeStart = audio.microphone.state.value.generation
            live = startInput(consumer, ::current) ?: error("The AirPods microphone could not start")
            withTimeout(5_000) {
                while (audio.microphone.state.value.phase != MicrophonePhase.CAPTURING) {
                    check(current() && audio.microphone.state.value.phase !in
                        listOf(MicrophonePhase.ERROR, MicrophonePhase.RECOVERING)) {
                        audio.microphone.state.value.message.ifBlank { "Call microphone did not start" }
                    }
                    delay(25)
                }
            }
            while (currentCoroutineContext().isActive && current()) {
                val report = lease.state()
                if (!report.getBoolean("ready")) {
                    check(report.getBoolean("ended")) { report.getString("error") ?: "Call route ended" }
                    break
                }
                check(report.getInt("kind") == call.kind && report.getInt("uid") == call.uid &&
                    report.getInt("session") == call.session) { "Call identity changed" }
                val nextEpoch = report.getLong("epoch")
                check(nextEpoch >= epoch) { "Call mute epoch moved backwards" }
                if (nextEpoch != epoch) {
                    val floor = audio.microphone.receivedFrameFloor(consumer)
                        ?: error("Call microphone owner changed")
                    consumer.updateEpoch(nextEpoch, report.getBoolean("muted"), floor)
                    epoch = nextEpoch
                }
                check(audio.microphone.state.value.phase == MicrophonePhase.CAPTURING) {
                    audio.microphone.state.value.message.ifBlank { "Call microphone stopped" }
                }
                publish(Phase.ACTIVE, report.getBoolean("muted"))
                delay(50)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed = true
            Log.w(TAG, "Owned call route failed: ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            withContext(NonCancellable) {
                if (owner === token) publish(Phase.STOPPING)
                consumer?.stopSending()
                var resourcesStopped = false
                try {
                    lease?.quiesce()?.let { report ->
                        logReceipt("quiesced", report)
                        resourcesStopped = report.getBoolean("resourcesStopped")
                        if (!resourcesStopped) failed = true
                    }
                } catch (_: Exception) { failed = true }
                finally {
                    // Cancel any queued start before acknowledging that this input is quiet.
                    hasPreparedRoute = false
                    val stoppingConsumer = consumer
                    if (resourcesStopped && stoppingConsumer != null) audio.armCallStopRecovery(stoppingConsumer)
                    runCatching { live?.close() }
                    val until = SystemClock.elapsedRealtime() + 10_000
                    while (stoppingConsumer != null && audio.microphone.ownsLiveConsumer(stoppingConsumer) &&
                        SystemClock.elapsedRealtime() < until) delay(25)
                    val retired = stoppingConsumer == null || !audio.microphone.ownsLiveConsumer(stoppingConsumer)
                    val neverStarted = inputGenerationBeforeStart == null ||
                        inputGenerationBeforeStart == audio.microphone.state.value.generation
                    val inputQuiet = retired && (neverStarted ||
                        (stoppingConsumer != null && audio.microphone.takeQuietLiveStop(stoppingConsumer)))
                    if (!inputQuiet) failed = true
                    try {
                        lease?.finish(inputQuiet)?.let { report ->
                            logReceipt("finished", report)
                            if (!report.getBoolean("cleanupComplete")) failed = true
                        }
                    } catch (_: Exception) { failed = true }
                    finally { stoppingConsumer?.let(audio::clearCallStopRecovery) }
                    runCatching { consumer?.close() }
                    runCatching { readEnd?.close() }
                    if (owner === token) {
                        input = null; owner = null; sessionJob = null
                        if (!failed) lastCall = null
                        if (!mutableState.value.enabled || closed) disarm()
                        publish(if (failed) Phase.FAILED else if (mutableState.value.enabled) Phase.WAITING else Phase.OFF)
                        released()
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        if (sessionJob == null) disarm()
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private inline fun logPoll(message: () -> String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        pollDiagnostic.changed(message())?.let { Log.d(TAG, "inspection $it") }
    }

    private fun logReceipt(stage: String, report: Bundle) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val fields = listOf("kind", "uid", "session", "ready", "ended", "cleanupComplete", "resourcesStopped",
            "awaitingInputQuiet", "hfpOriginalPolicy", "hfpDisconnectAttempted", "hfpRestoreAttempted", "hfpResult",
            "hfpSuppressionAttempted", "hfpSuppressionActive", "hfpSuppressionReleased", "hfpSuppressionGeneration", "inputVerified",
            "outputVerified", "downlinkVerified", "sourceRate", "mixRate", "inputChannels", "outputChannels",
            "framesInput", "framesOutput", "staleFrames", "shortWrites", "zeroWrites", "pendingMaxAgeMs",
            "downlinkPendingMaxAgeMs", "uplinkBufferFrames", "downlinkBufferFrames", "outputBufferFrames",
            "outputCapacityFrames", "outputStartThresholdFrames", "outputPlayState", "outputAdmissionHead",
            "outputPlaybackHead", "outputWriteCalls", "outputZeroWrites", "outputShortWrites", "outputAcceptedFrames",
            "outputSilenceFrames", "maxOutputWriteUs", "maxLoopUs", "maxControlCheckUs", "maxActiveOutputWriteUs",
            "maxActiveLoopUs", "maxActiveControlCheckUs", "maxGuardAgeMs", "epoch", "muted", "error")
        @Suppress("DEPRECATION")
        Log.d(TAG, "$stage " + fields.joinToString(" ") { "$it=${report.get(it)}" })
    }

    companion object {
        private const val TAG = "LibrePodsCallAudio"
        internal val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.BLUETOOTH_CONNECT)
        private val VOIP_PACKAGES = listOf("cocobo1.pupu.app", "com.discord")
        internal fun inspectionReason(report: Bundle): String {
            val reason = report.getString("reason").orEmpty()
            // Framework exception messages may include package names; our validation messages do not.
            return if (reason.startsWith("IllegalStateException: ") || reason.startsWith("IllegalArgumentException: "))
                reason.substringAfter(": ").replace('\n', ' ').replace('\r', ' ').take(200)
            else reason.substringBefore(':').take(80).ifBlank { "invalid-inspection-receipt" }
        }
        internal fun inspectedCall(report: Bundle, expectedKind: Int): Call? {
            if (!report.getBoolean("eligible") || report.getInt("kind") != expectedKind) return null
            val uid = report.getInt("uid", -1)
            val session = report.getInt("session", -1)
            val packageName = report.getString("package").orEmpty()
            if (uid <= 0 || packageName.isBlank() ||
                (expectedKind == 1 && session != 0) || (expectedKind == 2 && session <= 0) || expectedKind !in 1..2) return null
            return Call(expectedKind, uid, session, packageName)
        }
    }
}

/** One receipt per poll-state transition, including a return to an earlier rejection. */
internal class ChangedCallDiagnostic {
    private var previous: String? = null
    fun changed(message: String): String? {
        if (message == previous) return null
        previous = message
        return message
    }
}
