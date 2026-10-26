/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Coordinates the current audio route only. It never connects a device or selects a codec. */
class NativeGameModeController(
    private val device: AppleDevice,
    parentScope: CoroutineScope,
    private val hostBlocked: () -> Boolean,
) : AutoCloseable {
    enum class Issue { NONE, UNSUPPORTED, WAITING_FOR_AUDIO, WAITING_FOR_TIMING, BUSY, REQUEST_FAILED,
        REPORT_UNCONFIRMED, CLOCK_UNCONFIRMED, RESTORE_UNCONFIRMED, OWNER_LOST }
    data class State(val desiredOn: Boolean = false, val available: Boolean = false,
        val active: Boolean = false, val restoring: Boolean = false,
        val remoteRestorePending: Boolean = false, val issue: Issue = Issue.NONE)

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val desired = MutableStateFlow(false)
    private val closed = AtomicBoolean()
    private val microphoneBlocked = AtomicBoolean()
    private val cancellationEpoch = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()
    private val effectMutex = Mutex()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val ports = AndroidPorts()
    private val keepFlushBaseline = BuildConfig.DEBUG && BuildConfig.PRIVATE_GAME_KEEP_FLUSH_BASELINE
    private val engine = NativeGameModeEngine(ports, keepFlushBaseline)
    private val runner = scope.launch {
        while (isActive) {
            try {
                val next = engineMutex.withLock {
                    val hasWork = desired.value || engine.holdSerial > 0 || engine.remoteRestorePending
                    engine.tick(desired.value, eligible(), hasWork && blocked(), captureControl())
                }
                mutableState.value = next.copy(desiredOn = desired.value,
                    active = next.active && desired.value && !blocked() && !closed.get())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(active = false, issue = Issue.REQUEST_FAILED,
                    remoteRestorePending = engine.remoteRestorePending) }
            }
            val interval = if (desired.value || engine.holdSerial > 0 || engine.remoteRestorePending) 100L else 1000L
            withTimeoutOrNull(interval) { wakeup.receive() }
        }
    }
    private val parentCompletion = parentScope.coroutineContext[Job]?.invokeOnCompletion { close() }

    fun setDesiredOn(enabled: Boolean) {
        if (closed.get()) return
        desired.value = enabled
        if (!enabled) cancellationEpoch.incrementAndGet()
        mutableState.update { it.copy(desiredOn = enabled, active = it.active && enabled) }
        wakeup.trySend(Unit)
    }

    /** Only local clock normalization gates microphone admission; remote readback never delays calls. */
    suspend fun suspendForMicrophone(): Boolean {
        microphoneBlocked.set(true)
        cancellationEpoch.incrementAndGet()
        mutableState.update { it.copy(active = false) }
        val serial = engine.holdSerial
        if (serial == 0L || !engine.nativeObligation) return true
        val normal = try {
            withTimeoutOrNull(500) {
                effectMutex.withLock {
                    var snapshot = ports.cancelClockUnchecked(serial)
                    while (snapshot != null && (snapshot.clockPending || snapshot.clockInterval != 10)) {
                        delay(20)
                        snapshot = ports.pollUnchecked(serial)
                    }
                    snapshot != null && !snapshot.clockPending && snapshot.clockInterval == 10
                }
            } == true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        scope.launch { restoreOwned() }
        return normal
    }

    fun resumeAfterMicrophone() { microphoneBlocked.set(false); wakeup.trySend(Unit) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        desired.value = false
        microphoneBlocked.set(true)
        cancellationEpoch.incrementAndGet()
        mutableState.update { it.copy(desiredOn = false, active = false,
            restoring = engine.holdSerial > 0 || engine.remoteRestorePending) }
        runner.cancel()
        parentCompletion?.dispose()
        // This scope deliberately outlives a cancelled service scope long enough for owned cleanup.
        scope.launch {
            try {
                withContext(NonCancellable) {
                    withTimeoutOrNull(3000) { restoreOwned() }
                }
            } finally {
                scope.cancel()
                wakeup.close()
            }
        }
    }

    private suspend fun restoreOwned() {
        try { engineMutex.withLock { engine.shutdown(captureControl()) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(active = false, restoring = false,
                remoteRestorePending = engine.remoteRestorePending, issue = Issue.RESTORE_UNCONFIRMED) }
        }
    }

    private fun eligible() = Build.VERSION.SDK_INT == 37 &&
        device.metadata.value.model == AirPodsModel.AIRPODS_PRO_3 &&
        (device.metadata.value.firmwareMajor ?: 0) >= 8
    private fun blocked() = microphoneBlocked.get() || runCatching(hostBlocked).getOrDefault(true)
    private fun captureControl(): GameControlOwner? {
        if (device.connectionState.value != ConnectionState.CONNECTED) return null
        val session = device.state.value.controlSession
        val socket = device.aacp.captureAudioSocket() ?: return null
        if (session <= 0 || device.state.value.controlSession != session || !device.aacp.ownsAudioSocket(socket)) return null
        val peer = device.macAddress.value.split(':').map { it.toIntOrNull(16) ?: return null }
        if (peer.size != 6 || peer.any { it !in 0..255 } || peer.all { it == 0 } || peer.all { it == 255 }) return null
        return GameControlOwner(peer, session, socket)
    }

    private inner class AndroidPorts : NativeGameModePorts {
        private fun client() = NativeGameTransportClient.get(device.context)
        override fun nowMs() = SystemClock.elapsedRealtime()
        override fun owns(owner: GameControlOwner): Boolean {
            val current = captureControl() ?: return false
            return current.session == owner.session && current.peer == owner.peer && current.socket === owner.socket
        }
        private fun bytes(owner: GameControlOwner) = owner.peer.map(Int::toByte).toByteArray()
        private fun decode(reply: NativeGameTransportClient.Reply): GameNativeReceipt? =
            if (!reply.successful) null else NativeGameSnapshot.parse(reply.values)?.receipt()
        override suspend fun snapshot(heartbeatSerial: Long): GameNativeReceipt? {
            val first = decode(client().query()) ?: return null
            return if (heartbeatSerial > 0) decode(client().poll(heartbeatSerial)) ?: first else first
        }
        override suspend fun prepare(owner: GameControlOwner, route: GameNativeReceipt) = effectMutex.withLock {
            if (closed.get() || !desired.value || blocked() || !eligible() || !owns(owner)) null
            else decode(client().prepare(bytes(owner), route.host.mediaGeneration, route.encoderGeneration, route.acl))
        }
        override suspend fun target(owner: GameControlOwner, route: GameNativeReceipt,
            serial: Long, targetMs: Int): GameTargetWrite = effectMutex.withLock {
            fun rejected(reason: String): GameTargetWrite {
                if (BuildConfig.DEBUG) Log.d("LibrePodsGameMode", "target=$targetMs rejected=$reason attempted=false")
                return GameTargetWrite(false, nowMs(), mayHaveReachedRemote = false)
            }
            if (targetMs !in setOf(60, 150)) return@withLock rejected("invalid-target")
            if (!owns(owner)) return@withLock rejected("owner-changed")
            val epoch = cancellationEpoch.get()
            val preflightStart = nowMs()
            var proof: GameNativeReceipt? = null
            var attempts = 0
            while (proof == null && attempts++ < 4) {
                client().query() // Request a current native-main observation, not a cached route.
                delay(20)
                val sample = decode(client().poll(serial))
                if (sample != null && sample.observedMs >= preflightStart) proof = sample
            }
            val current = proof ?: return@withLock rejected("no-current-native-proof")
            val checkedAt = nowMs()
            if (!NativeGameTargetGuard.preflight(owner, route, serial, current, targetMs, checkedAt, keepFlushBaseline)) {
                if (BuildConfig.DEBUG) {
                    val sameRequest = serial > 0 && current.serial == serial && current.requestPeer == owner.peer &&
                        current.requestMedia == route.host.mediaGeneration && current.requestEncoder == route.encoderGeneration &&
                        current.requestAcl == route.acl
                    val sameHost = current.host.peer == owner.peer && current.host.mediaGeneration == route.host.mediaGeneration
                    val sameEncoderAcl = current.encoderGeneration == route.encoderGeneration && current.acl == route.acl
                    Log.d("LibrePodsGameMode", "target=$targetMs preflight observedAgeMs=${checkedAt - current.observedMs} " +
                        "sameReqTuple=$sameRequest hostSamePeerMedia=$sameHost sameEncoderAcl=$sameEncoderAcl " +
                        "holding=${current.holding} busy=${current.busy} " +
                        "currentKnown=${current.currentKnown} current=${current.current} " +
                        "baselineKnown=${current.baselineKnown} baseline=${current.baseline} " +
                        "streaming=${current.host.streaming} encoderReady=${current.host.encoderReady} " +
                        "scoOrMicrophone=${current.host.scoOrMicrophone} " +
                        "clockAvailable=${current.host.clockReady} leaseRemainingMs=${current.leaseDeadlineMs - checkedAt} " +
                        "desired=${desired.value} blocked=${blocked()}")
                }
                return@withLock rejected("native-preflight")
            }
            val provenAt = current.observedMs
            var boundaryNs = SystemClock.elapsedRealtimeNanos()
            var attempted = false
            val sent = withContext(Dispatchers.IO) {
                device.aacp.sendControlCommand(ControlCommandIdentifier.JITTER_BUFFER.value,
                    byteArrayOf(targetMs.toByte(), 0, 0, 0), expectedSocket = owner.socket as BluetoothSocket,
                    beforeWrite = {
                        boundaryNs = SystemClock.elapsedRealtimeNanos()
                        attempted = true
                    },
                    isValid = {
                        // A cross-process route proof cannot make this app-owned socket write atomic.
                        NativeGameTargetGuard.finalWrite(owns(owner), targetMs, nowMs(), provenAt,
                            epoch, cancellationEpoch.get(), !closed.get() && desired.value && !blocked() && eligible(),
                            current.leaseDeadlineMs)
                    })
            }
            if (!sent && BuildConfig.DEBUG) Log.d("LibrePodsGameMode",
                "target=$targetMs rejected=${if (attempted) "transport-or-publication" else "final-write-fence"} attempted=$attempted")
            GameTargetWrite(sent, boundaryNs / 1_000_000, boundaryNs, attempted)
        }
        override suspend fun beginClock(serial: Long, owner: GameControlOwner, route: GameNativeReceipt,
            clock: NativeGameModePolicy.Clock, restoring: Boolean) = effectMutex.withLock {
            if (closed.get() || blocked() || (!restoring && !desired.value) || !owns(owner)) null
            else decode(client().beginClock(serial, bytes(owner), route.host.mediaGeneration,
                route.encoderGeneration, route.acl, clock.intervalTenths, clock.driftMs))
        }
        suspend fun cancelClockUnchecked(serial: Long) = decode(client().cancelClock(serial))
        suspend fun pollUnchecked(serial: Long) = decode(client().poll(serial))
        override suspend fun cancelClock(serial: Long) = effectMutex.withLock { cancelClockUnchecked(serial) }
        override suspend fun restore(serial: Long) = effectMutex.withLock { decode(client().restore(serial)) }
    }
}

private fun NativeGameSnapshot.receipt(): GameNativeReceipt {
    val address = peer.map { it.toInt() and 255 }
    return GameNativeReceipt(host = NativeGameModePolicy.Host(address, mediaGeneration, startedMs,
        delaySerial, delayReceivedMs, delayTenths, hostFlags and 8 != 0, hostFlags and 4 != 0,
        supported, clockAvailable, streaming, hostFlags and 32 != 0, delayReceivedNs), supported = supported, busy = busy,
        phase = phase, serial = serial, submittedSerial = submittedSerial, holding = holding,
        baselineKnown = baselineKnown, baseline = baselineSlots, currentKnown = currentKnown,
        current = currentSlots, restorePending = restorePending, encoderGeneration = encoderGeneration,
        acl = aclHandle, requestPeer = requestPeer.map { it.toInt() and 255 }, requestMedia = requestMediaGeneration,
        requestEncoder = requestEncoderGeneration, requestAcl = requestAclHandle,
        clockRequest = clockRequestSerial, clockPending = clockPending, clockPhase = clockPhase,
        clockSerial = clockSerial, clockEncoder = clockEncoderGeneration,
        clockPeer = clockPeer.map { it.toInt() and 255 }, clockInterval = clockInterval,
        observedMs = observedMs, leaseDeadlineMs = leaseDeadlineMs, noControllerMutation = noControllerMutation)
}
