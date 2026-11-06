package me.kavishdevar.librepods.dongle

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.shizuku.IPlaybackRecoveryOwner
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumeEvents
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumePolicy
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.UsbAbsoluteVolumeInspection
import me.kavishdevar.librepods.utils.UsbAbsoluteVolumeTransport
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private typealias SettingsVolumeRenew = suspend (DongleUsbVolume.Target, Long, Long) -> DongleUsbVolume.Observation
private typealias SettingsVolumeCheckpoint = suspend (SettingsVolumeRenew) -> Long

/** A saved board request is separate from each bounded native/firmware lease. */
internal class DongleUsbVolume(
    private val context: Context, private val scope: CoroutineScope, private val bridge: ShizukuAudioClient,
    private val target: () -> Target?, private val canStart: () -> Boolean, private val read: suspend (Target) -> Observation,
    private val action: suspend (Target, Int, Long, Long, Int, (DongleProtocol.PhysicalVolume) -> Unit) -> Observation,
    private val changed: (State) -> Unit,
    private val requested: (String) -> Boolean = { false },
    private val discover: suspend () -> IUsbAbsoluteVolumePolicy? = { UsbAbsoluteVolumeTransport.discover(context) },
) {
    data class Target(val owner: Any, val serial: String, val controlSession: Long, val generation: Long, val capabilities: Int)
    data class Observation(val value: DongleProtocol.PhysicalVolume, val observedAt: Long)
    internal data class Setup(val target: Target, val policy: IUsbAbsoluteVolumePolicy, val deviceId: Int, val address: String)
    private data class HostRequest(val level: Int, val nativeEpoch: Long, val sequence: Long)
    private data class HostSet(val request: HostRequest, val baselineRevision: Long)
    private class SupersededHostRequest : Exception()
    data class State(val available: Boolean = false, val enabled: Boolean = false, val pending: Boolean = false, val failed: Boolean = false, val cleanupFailed: Boolean = false)
    @Volatile private var wanted = false
    private var job: Job? = null
    private val starting = AtomicBoolean()
    private val requestVersion = AtomicLong()
    @Volatile private var failedTarget: Target? = null
    private val probing = AtomicBoolean()
    private var probedAt = -10_000L
    private var probedTarget: Target? = null
    private var prepared: Setup? = null
    private val topology = AtomicLong()
    private var state = State()
    @Volatile private var policyOwner: IPlaybackRecoveryOwner? = null
    @Volatile private var settingsCheckpoint: SettingsVolumeCheckpoint? = null
    private fun publish(value: State) { state = value; changed(value) }
    private fun now() = SystemClock.elapsedRealtime()

    fun topologyChanged() {
        topology.incrementAndGet()
        prepared = null; probedAt = -10_000; probedTarget = null
        if (!wanted) publish(state.copy(available = false))
        probe() // Read-only. The system-server policy owns teardown if an active route becomes invalid.
    }

    fun probe() {
        val selected = target() ?: run { prepared = null; if (!wanted) publish(State()); return }
        if (failedTarget == selected) return
        if (wanted || now() - probedAt < 10_000 && probedTarget == selected || !probing.compareAndSet(false, true)) return
        probedAt = now(); probedTarget = selected
        val epoch = topology.get()
        fun unavailable() {
            if (!wanted && topology.get() == epoch) { prepared = null; publish(State()) }
        }
        scope.launch {
            try {
                bridge.connect()
                val orphan = policyOwner
                if (orphan != null) {
                    if (bridge.releaseUsbAbsoluteVolume(orphan)?.getBoolean("cleanup_complete", false) != true) {
                        prepared = null; publish(State(failed = true, cleanupFailed = true)); return@launch
                    }
                    policyOwner = null
                }
                if (!bridge.hasRootPrivileges) { unavailable(); return@launch }
                val policy = discover() ?: run { unavailable(); return@launch }
                val info = bridge.inspectUsbAbsoluteVolumePolicy(policy, 0, selected.serial)
                val deviceId = info?.getInt("device_id", 0) ?: 0
                val address = info?.getString("device_address")
                if (!wanted && topology.get() == epoch && target() == selected && info?.getBoolean("available", false) == true &&
                    deviceId > 0 && address != null && UsbAbsoluteVolumeInspection.validAddress(address)) {
                    prepared = Setup(selected, policy, deviceId, address); publish(State(available = true))
                    reconcile()
                } else unavailable()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable() }
            finally {
                probing.set(false)
                if (topology.get() != epoch) probe()
            }
        }
    }

    fun enable(enabled: Boolean) {
        if (enabled && wanted) return
        requestVersion.incrementAndGet(); failedTarget = null
        if (!enabled) { wanted = false; job?.cancel(); publish(state.copy(enabled = false, pending = job?.isCompleted == false)); return }
        reconcile()
    }

    fun reconcile() {
        val selected = target() ?: return
        if (!requested(selected.serial) || failedTarget == selected || wanted || job?.isCompleted == false) return
        val setup = prepared?.takeIf { it.target == selected } ?: run { probe(); return }
        if (!starting.compareAndSet(false, true)) return
        try {
            if (!requested(selected.serial) || target() != selected || wanted || job?.isCompleted == false || !canStart()) return
            val version = requestVersion.get()
            wanted = true; publish(State(available = true, pending = true))
            job = scope.launch(start = CoroutineStart.LAZY) { run(setup.target, setup.policy, setup.deviceId, setup.address, version) }
            job!!.start()
        } finally { starting.set(false) }
    }

    fun retire() { wanted = false; prepared = null; job?.cancel(); publish(State()) }

    /** The caller already owns controller I/O; this must not acquire it again. */
    suspend fun keepAliveForSettings(renew: suspend (Target, Long, Long) -> Observation): Long? {
        val checkpoint = settingsCheckpoint
        if (checkpoint != null) return checkpoint(renew)
        check(policyOwner == null) { "USB volume activation or cleanup is still pending" }
        return null
    }

    private suspend fun run(selected: Target, policy: IUsbAbsoluteVolumePolicy, deviceId: Int, address: String, version: Long) {
        val requests = Channel<HostRequest>(Channel.CONFLATED)
        val nativeEpoch = AtomicLong()
        val lastHostSequence = AtomicLong()
        val pendingHostSet = AtomicReference<HostSet?>()
        var token = 0L; var usbGeneration = 0L; var rootAttempted = false; var failed = false
        val leaseDeadline = AtomicLong(Long.MAX_VALUE)
        val publication = Mutex()
        var activeCheckpoint: SettingsVolumeCheckpoint? = null
        fun current() = wanted && requested(selected.serial) && target() == selected && bridge.hasRootPrivileges && now() < leaseDeadline.get()
        fun ready() {
            if (!current() || !canStart()) {
                wanted = false
                throw CancellationException("USB volume setup deferred")
            }
        }
        fun requireCurrent() {
            if (!wanted || !requested(selected.serial) || target() != selected) {
                wanted = false
                throw CancellationException("USB volume owner retired")
            }
            check(current()) { "USB volume lease is unavailable" }
        }
        val owner = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent() = Binder.getCallingUid() == 0 && policyOwner === this && current()
        }
        val events = object : IUsbAbsoluteVolumeEvents.Stub() {
            override fun onVolumeChanged(connection: Long, level: Int, requestSequence: Long) {
                if (Binder.getCallingUid() == 1000 && connection == selected.controlSession && policyOwner === owner && current() && level in 0..127) {
                    if (requestSequence <= 0 || requestSequence <= lastHostSequence.getAndUpdate { maxOf(it, requestSequence) }) return
                    requests.trySend(HostRequest(level, nativeEpoch.get(), requestSequence))
                }
            }
            override fun onPolicyEnded(reason: String?, cleanupComplete: Boolean) {
                if (Binder.getCallingUid() != 1000 || !wanted || policyOwner !== owner) return
                failed = !cleanupComplete || target() == selected
                wanted = false; job?.cancel()
            }
        }
        fun proof(observation: Observation): Bundle = proof(selected, deviceId, address, observation)
        try {
            ready()
            val info = checkNotNull(bridge.inspectUsbAbsoluteVolumePolicy(policy, deviceId, selected.serial))
            check(info.getBoolean("available", false) && info.getString("device_address") == address)
            val baseline = read(selected)
            if (baseline.value.authorityActive || baseline.value.confirmed == null || baseline.value.pending ||
                baseline.value.inflight || !baseline.value.usbScopeReady) {
                wanted = false
                throw CancellationException("Waiting for fresh physical volume readiness")
            }
            usbGeneration = baseline.value.usbGeneration
            var observation = action(selected, 0, usbGeneration, 0, 0) { ready() }
            token = checkNotNull(observation.value.ownToken)
            leaseDeadline.set(deadline(observation))
            // Activation adopts the AirPods' level; only later host requests can change it.
            check(current() && observation.value.confirmed != null && !observation.value.pending && !observation.value.inflight)
            rootAttempted = true; policyOwner = owner
            val started = bridge.startUsbAbsoluteVolume(owner, policy, events, proof(observation))?.getBoolean("active", false) == true
            if (!current()) throw CancellationException("USB volume setup retired")
            check(started)
            var lastRevision = observation.value.observationRevision
            var lastSettledLevel = checkNotNull(observation.value.confirmed)
            var publishedAt = observation.observedAt
            suspend fun publishObservation(fresh: Observation): Long = publication.withLock {
                check(policyOwner === owner)
                requireCurrent()
                if (fresh.observedAt < publishedAt) return@withLock leaseDeadline.get()
                check(fresh.value.ownToken == token && fresh.value.usbGeneration == usbGeneration &&
                    fresh.value.authorityActive && fresh.value.usbScopeReady)
                val end = deadline(fresh)
                check(now() < end) { "USB volume renewal already expired" }
                leaseDeadline.set(end)
                check(bridge.renewUsbAbsoluteVolume(owner, proof(fresh))?.getBoolean("active", false) == true)
                if (fresh.value.confirmed != null && !fresh.value.pending && !fresh.value.inflight &&
                    fresh.value.observationRevision != lastRevision) {
                    val pending = pendingHostSet.get()
                    val completedSet = pending != null && fresh.value.observationRevision != pending.baselineRevision &&
                        pendingHostSet.compareAndSet(pending, null)
                    // The first settled report resolves our SET, even if a checkpoint publishes it before its caller.
                    val ownAcknowledgement = completedSet && fresh.value.confirmed == pending?.request?.level
                    if (completedSet || fresh.value.confirmed != lastSettledLevel) {
                        if (!ownAcknowledgement) nativeEpoch.incrementAndGet()
                        val acknowledgedSequence = if (ownAcknowledgement) checkNotNull(pending).request.sequence else 0L
                        check(bridge.notifyUsbAbsoluteVolume(owner, proof(fresh), acknowledgedSequence)?.getBoolean("active", false) == true)
                    }
                    lastRevision = fresh.value.observationRevision
                    lastSettledLevel = fresh.value.confirmed
                }
                publishedAt = fresh.observedAt
                end
            }
            activeCheckpoint = { renew ->
                try {
                    check(policyOwner === owner && settingsCheckpoint === activeCheckpoint)
                    requireCurrent()
                    // Wire renewal precedes the publication mutex: the heartbeat may be waiting for this caller's I/O.
                    val fresh = renew(selected, usbGeneration, token)
                    check(policyOwner === owner && settingsCheckpoint === activeCheckpoint)
                    requireCurrent()
                    publishObservation(fresh)
                } catch (failure: Exception) {
                    if (policyOwner === owner && settingsCheckpoint === activeCheckpoint) {
                        failed = wanted && target() == selected
                        wanted = false; job?.cancel()
                    }
                    throw failure
                }
            }
            settingsCheckpoint = activeCheckpoint
            publish(State(available = true, enabled = true))
            while (scope.isActive && current()) {
                val requested = withTimeoutOrNull(750) { requests.receive() }
                requireCurrent()
                observation = action(selected, 1, usbGeneration, token, 0) { requireCurrent() }
                publishObservation(observation)
                if (requested != null && requested.nativeEpoch == nativeEpoch.get() && requested.level != observation.value.confirmed) {
                    val baseline = observation.value
                    val revision = baseline.observationRevision
                    var committed: HostSet? = null
                    try {
                        observation = action(selected, 3, usbGeneration, token, requested.level) { fresh ->
                            requireCurrent()
                            if (requested.nativeEpoch != nativeEpoch.get() || fresh.observationRevision != revision ||
                                fresh.confirmed == null || fresh.confirmed != baseline.confirmed || fresh.pending || fresh.inflight)
                                throw SupersededHostRequest()
                            committed = HostSet(requested, revision).also { pendingHostSet.set(it) }
                        }
                        observation = awaitPhysical(selected, token, usbGeneration, revision, observation) { current() }
                        publishObservation(observation)
                    } catch (_: SupersededHostRequest) { continue }
                    finally { committed?.let { pendingHostSet.compareAndSet(it, null) } }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = wanted && target() == selected }
        finally {
            if (wanted && target() == selected && requested(selected.serial)) failed = true
            wanted = false
            if (settingsCheckpoint === activeCheckpoint) settingsCheckpoint = null
            withContext(NonCancellable) {
                val clean = !rootAttempted || withTimeoutOrNull(1500) {
                    bridge.releaseUsbAbsoluteVolume(owner)?.getBoolean("cleanup_complete", false) == true
                } == true
                // Unknown native cleanup leaves the firmware quiet until its lease expires.
                if (clean && token != 0L && target() == selected) withTimeoutOrNull(1500) {
                    runCatching { action(selected, 2, usbGeneration, token, 0) { check(target() == selected) } }
                }
                if (clean) policyOwner = null else prepared = null
                if ((failed || !clean) && requestVersion.get() == version) failedTarget = selected
                val latest = target()
                publish(State(available = clean && latest != null && prepared?.target == latest,
                    failed = (latest == selected && failed) || !clean, cleanupFailed = !clean))
                requests.close()
            }
        }
    }

    private suspend fun awaitPhysical(selected: Target, token: Long, usbGeneration: Long,
        revision: Long, initial: Observation, current: () -> Boolean): Observation {
        var observation = initial
        val end = now() + 1500
        while (current() && now() < end) {
            if (observation.value.ownToken == token && observation.value.usbGeneration == usbGeneration &&
                !observation.value.pending && !observation.value.inflight && observation.value.confirmed != null &&
                observation.value.observationRevision != revision) return observation
            delay(80); observation = read(selected)
        }
        error("Physical volume was not confirmed")
    }

    companion object {
        internal fun deadline(observation: Observation) = observation.observedAt + observation.value.remainingMs - 500
        internal fun proof(target: Target, deviceId: Int, address: String, observation: Observation) = Bundle().apply {
            val value = observation.value
            putBoolean("enabled", true); putInt("version", 1); putInt("device_id", deviceId)
            putString("board_serial", target.serial); putLong("control_session", target.controlSession)
            putString("device_address", address)
            putLong("connection_generation", target.generation); putInt("capabilities", target.capabilities)
            putInt("flags", (if (value.confirmed != null) 1 else 0) or (if (value.pending) 2 else 0) or
                (if (value.inflight) 4 else 0) or (if (value.authorityActive) 8 else 0) or (if (value.usbScopeReady) 16 else 0))
            putInt("level", value.confirmed ?: 255); putLong("observation_revision", value.observationRevision)
            putLong("firmware_token", value.ownToken ?: 0); putLong("ttl_ms", value.remainingMs)
            putLong("usb_generation", value.usbGeneration); putLong("observed_at", observation.observedAt)
        }
    }
}
