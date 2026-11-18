/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.shizuku.AtmosCanonicalPipe
import me.kavishdevar.librepods.shizuku.AtmosClientLease
import me.kavishdevar.librepods.shizuku.AtmosDecoderHandoff
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.TidalBufferProbeHooks
import me.kavishdevar.librepods.utils.TidalBufferProbePolicy
import me.kavishdevar.librepods.utils.TidalComponentClaimPermit
import me.kavishdevar.librepods.utils.TidalComponentTransition
import me.kavishdevar.librepods.utils.XposedServiceHolder

/** One explicit issuance owns one reference; only a verified, bounded decoder handoff may reuse it. */
internal class AtmosHeadTrackingCoordinator(private val device: AppleDevice, scope: CoroutineScope,
    private val access: ShizukuAudioClient, private val tryFast: (BluetoothSocket) -> Boolean,
    private val onFallback: (BluetoothSocket) -> Unit,
    private val sourceRejection: (BluetoothSocket) -> AtmosSourceRejection?) : AutoCloseable {
    companion object {
        const val PREFERENCES = "atmos_head_tracking"
        val requestLock = Any()
        private val claimLock = Any()
    }
    private data class Selection(val eligible: Boolean, val profile: SpatialPoseProfile?, val socket: BluetoothSocket?)
    private data class Controls(val spatial: Boolean, val tracking: Boolean, val epoch: Long, val accepted: Boolean)
    private val preferences = device.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var selection = Selection(false, null, null)
    @Volatile private var status = Bundle().apply { putString("reason", "disabled") }
    @Volatile private var currentProducer: AtmosHeadTrackingProducer? = null
    @Volatile private var currentLease: AtmosClientLease? = null
    @Volatile private var currentExperiment: AtomicBoolean? = null
    @Volatile private var firstSourceRejection = AtomicReference<AtmosSourceRejection?>()
    @Volatile private var firstFailure = AtmosCoordinatorFailure()
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean()
    private val gapRecoveries = HeadMotionGapRecovery.Scopes()
    private var spentIssued = preferences.getLong("spent_buffer_issued_ms", 0)
    @Volatile private var controls: Controls? = null
    @Volatile private var androidReady = false

    fun update(eligible: Boolean, profile: SpatialPoseProfile?, socket: BluetoothSocket?) { selection = Selection(eligible, profile, socket) }
    fun androidProviderReady(ready: Boolean) {
        androidReady = ready
        if (!ready) retireCurrent()
    }
    fun setControls(spatial: Boolean, tracking: Boolean, choiceEpoch: Long, accepted: Boolean = false) {
        synchronized(requestLock) {
            val old = controls
            if (old != null && (choiceEpoch < old.epoch || choiceEpoch == old.epoch &&
                    (old.spatial != spatial || old.tracking != tracking))) return
            if (old?.epoch != choiceEpoch || !accepted || !spatial || !tracking) retireCurrent()
            controls = Controls(spatial, tracking, choiceEpoch, accepted)
        }
    }
    fun snapshot() = Bundle(status).apply {
        putBoolean("android_provider_ready", androidReady)
        putBoolean("production_ready", productionReady())
        firstSourceRejection.get()?.let { putBundle("source_rejection", it.snapshot()) }
        firstFailure.snapshot()?.let { putBundle("coordinator_failure", it) }
    }
    fun retireCurrent() { currentExperiment?.set(false); currentProducer?.close(); currentLease?.close() }
    private fun productionReady(choice: Controls? = controls, selected: Selection = selection) =
        choice?.let { it.accepted && it.spatial && it.tracking } == true &&
            androidReady && selected.let { it.eligible && it.profile != null && it.socket != null }
    private val requestListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("enabled", "request_epoch", "directional_mapping")) retireCurrent()
    }.also(preferences::registerOnSharedPreferenceChangeListener)

    private val worker = scope.launch(Dispatchers.IO) {
        while (isActive && !closed.get()) {
            try {
                val selected = selection
                val choice = controls
                if (choice != null && productionReady(choice, selected)) {
                    val prefs = XposedServiceHolder.service?.getRemotePreferences(TidalBufferProbeHooks.PREFERENCES)
                    if (prefs != null) {
                        val recovery = recoveryFor(selected, choice.epoch)
                        if (!recovery.latched) {
                            try { runExperiment(null, prefs, selected, choice.epoch, recovery) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                if (error is CalibratedHeadMotionFault) recovery.failed(error.verifiedSensorGap)
                                delay(1000); throw error
                            }
                        }
                    }
                } else if (choice == null && preferences.getBoolean("enabled", false) && selected.eligible && selected.profile != null && selected.socket != null) {
                    val prefs = XposedServiceHolder.service?.getRemotePreferences(TidalBufferProbeHooks.PREFERENCES)
                    val permit = prefs?.let(TidalBufferProbeHooks::permit)
                    val boot = Settings.Global.getInt(device.context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                    if (permit != null && permit.live(SystemClock.elapsedRealtime(), boot) && permit.issuedMs != spentIssued &&
                        permit.peer.equals(device.macAddress.value, true)) {
                        val requestEpoch = synchronized(requestLock) {
                            check(preferences.getBoolean("enabled", false))
                            check(preferences.edit().putLong("spent_buffer_issued_ms", permit.issuedMs).commit())
                            preferences.getLong("request_epoch", 0)
                        }
                        spentIssued = permit.issuedMs
                        try { runExperiment(permit, prefs, selected, requestEpoch, recoveryFor(selected, requestEpoch)) }
                        finally { synchronized(requestLock) {
                            if (preferences.getLong("request_epoch", 0) == requestEpoch)
                                preferences.edit().putBoolean("enabled", false).commit()
                        } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                firstFailure.record("coordinator", error)
                status = retiredAtmosCoordinatorState(status, "experiment_failed")
            }
            delay(200)
        }
    }

    private fun recoveryFor(selected: Selection, choiceEpoch: Long) = gapRecoveries.forSource(
        checkNotNull(selected.socket), checkNotNull(spatialPoseProfileKey(device)),
        checkNotNull(selected.profile), choiceEpoch)

    private suspend fun runExperiment(initialBuffer: TidalBufferProbePolicy.Permit?, prefs: android.content.SharedPreferences,
        selected: Selection, requestEpoch: Long, recovery: HeadMotionGapRecovery) = coroutineScope {
        val production = initialBuffer == null
        lateinit var buffer: TidalBufferProbePolicy.Permit
        var hasBuffer = initialBuffer != null
        if (initialBuffer != null) buffer = initialBuffer
        status = Bundle().apply { putString("reason", "opening_root_lease") }
        val failure = AtmosCoordinatorFailure().also { firstFailure = it }
        var stage = "source_capture"
        val source = failure.capture({ stage }) {
            checkNotNull(AtmosMotionSource.capture(device, checkNotNull(selected.profile)))
        }
        val sourceEpoch = epoch.incrementAndGet()
        val discontinuity = (sourceEpoch and 255).toInt()
        val sourceLive = AtomicBoolean(true)
        val rejected = AtomicReference<AtmosSourceRejection?>().also { firstSourceRejection = it }
        currentExperiment = sourceLive
        fun current(): Boolean {
            fun reject(reason: String): Boolean {
                rejected.compareAndSet(null, AtmosSourceRejection(reason, SystemClock.elapsedRealtime()))
                return false
            }
            if (!sourceLive.get() || closed.get()) return reject("experiment_retired")
            if (production && !androidReady) return reject("android_provider_not_ready")
            if (!selection.eligible) {
                // Retain the actual source predicate when the controller's cached eligibility failed too.
                rejected.compareAndSet(null, sourceRejection(checkNotNull(selected.socket)) ?:
                    AtmosSourceRejection("selection_ineligible", SystemClock.elapsedRealtime()))
                return false
            }
            if (selection.socket !== selected.socket) return reject("selected_socket_changed")
            sourceRejection(checkNotNull(selected.socket))?.let { rejected.compareAndSet(null, it); return false }
            if (!source.current(selection.profile)) return reject("source_profile_changed")
            return true
        }
        fun requestCurrent() = if (production) controls?.let {
            it.epoch == requestEpoch && it.accepted && it.spatial && it.tracking
        } == true else preferences.getBoolean("enabled", false) &&
            preferences.getLong("request_epoch", 0) == requestEpoch
        fun bufferCurrent() = requestCurrent() && hasBuffer && TidalBufferProbeHooks.permit(prefs) == buffer &&
            buffer.live(SystemClock.elapsedRealtime(), buffer.bootCount)
        val pipe = failure.capture({ "opening_pipe" }) { ParcelFileDescriptor.createPipe() }
        var output: AtmosCanonicalPipe? = null
        var lease: AtmosClientLease? = null
        var producer: AtmosHeadTrackingProducer? = null
        var claim: TidalComponentClaimPermit? = null
        var sender: kotlinx.coroutines.Job? = null
        try {
            output = AtmosCanonicalPipe(pipe[1])
            val key = checkNotNull(spatialPoseProfileKey(device))
            val basis = ByteBuffer.allocate(9 * 8).apply { source.profile.headFromRaw.flatten().forEach(::putDouble) }.array()
            val identity = Bundle().apply {
                putByteArray("full_role", key.rawRole.map(Int::toByte).toByteArray())
                putString("model", key.modelNumber); putString("firmware", key.firmware); putInt("profile_schema", key.formatVersion)
                putByteArray("profile_digest", java.security.MessageDigest.getInstance("SHA-256").digest(basis))
                putLong("source_epoch", sourceEpoch); putLong("reference_epoch", sourceEpoch); putInt("discontinuity", discontinuity)
            }
            val permit = Bundle().apply {
                if (production) {
                    putBoolean("production", true); putLong("choice_epoch", requestEpoch)
                } else {
                    putLong("issued_ms", buffer.issuedMs); putLong("deadline_ms", buffer.deadlineMs); putInt("boot", buffer.bootCount)
                    putInt("route_id", buffer.routeId); putString("peer", buffer.peer)
                }
                putInt("directional_mapping", preferences.getInt("directional_mapping", 0))
            }
            stage = "opening_root_lease"
            lease = access.openAtmosHeadTracking(device.macAddress.value, Binder(), identity, permit, pipe[0]) {
                current() && requestCurrent() && (!hasBuffer && production || bufferCurrent())
            }
            currentLease = lease
            check(current() && requestCurrent()) { "request_retired" }
            stage = "root_preparation"
            var preparedControl = 0L
            val preparationDeadline = if (production) SystemClock.elapsedRealtime() + 5_000
                else minOf(buffer.deadlineMs, SystemClock.elapsedRealtime() + 5_000)
            while (current() && requestCurrent() && (!hasBuffer || TidalBufferProbeHooks.permit(prefs) == buffer) &&
                SystemClock.elapsedRealtime() < preparationDeadline) {
                val ready = lease.preparation()
                val nonce = ready.getByteArray("nonce")
                if (nonce != null && nonce.size == 16 && nonce.any { it != 0.toByte() }) {
                    if (production) {
                        val grant = checkNotNull(atmosProductionGrant(ready, device.macAddress.value, requestEpoch,
                            SystemClock.elapsedRealtime(), Settings.Global.getInt(device.context.contentResolver,
                                Settings.Global.BOOT_COUNT, -1))) { "invalid_root_grant" }
                        synchronized(claimLock) {
                            check(current() && requestCurrent()) { "request_retired" }
                            val prefix = TidalBufferProbePolicy.PREFIX
                            check(prefs.edit().putLong(prefix + "issued_ms", grant.issuedMs)
                                .putLong(prefix + "deadline_ms", grant.deadlineMs).putInt(prefix + "boot", grant.bootCount)
                                .putInt(prefix + "route_id", grant.routeId).putString(prefix + "peer", grant.peer)
                                .putBoolean(prefix + "production", true).putLong(prefix + "choice_epoch", requestEpoch).commit())
                        }
                        buffer = grant; hasBuffer = true
                    }
                    preparedControl = ready.getLong("control")
                    check(preparedControl > 0) { "invalid_decoder_binding" }
                    claim = TidalComponentClaimPermit(nonce.joinToString("") { "%02x".format(it.toInt() and 255) },
                        ready.getLong("issued_ms"), ready.getLong("deadline_ms"), ready.getInt("boot", -1),
                        ready.getInt("target_uid", -1), ready.getLong("buffer_issued_ms"))
                    break
                }
                status = lease.state()
                check(status.getBoolean("live")) { "root_lease_retired" }
                delay(50)
            }
            var granted = checkNotNull(claim) { "preparation_timeout" }
            check(current() && requestCurrent() && TidalBufferProbeHooks.permit(prefs) == buffer &&
                granted.live(buffer, SystemClock.elapsedRealtime(), buffer.bootCount, granted.targetUid)) { "claim_not_current" }
            stage = "claim_publication"
            synchronized(claimLock) {
                check(current() && requestCurrent()) { "request_retired" }
                check(granted.write(prefs.edit()).commit())
            }
            var binding: AtmosDecoderBinding? = null
            var authorizedControl = 0L
            fun readState() = failure.readRootState(sender, lease::state) {
                status = it.apply { putBoolean("claim_published", true) }
            }
            fun permitCurrent() = bufferCurrent() && TidalBufferProbeHooks.claimPermit(prefs) == granted
            suspend fun handleTransition(state: Bundle) {
                val transition = atmosHandoffFor(state.getLongArray("native_handoff_state"), binding, preparedControl, authorizedControl)
                if (transition == null) return
                stage = when (transition[1]) {
                    in 5L..7L -> "decoder_paused"
                    in 8L..9L -> "source_recovery"
                    else -> "decoder_handoff"
                }
                val oldControl = transition[5]
                if (transition[1] in listOf(1L, 5L) && authorizedControl != oldControl && state.getBoolean("handoff_authorizable")) {
                    val marker = TidalComponentTransition.read(prefs)
                    if (marker?.nonce == granted.nonce && marker.bufferIssuedMs == buffer.issuedMs) {
                        check(current() && permitCurrent()) { "request_retired" }
                        if (!lease.authorizeHandoff(marker.bundle())) {
                            val changed = lease.state().also { status = it.apply { putBoolean("claim_published", true) } }
                            check(current() && permitCurrent() && atmosPauseCleanupRacedAuthorization(changed, transition)) {
                                "handoff_authorization_rejected"
                            }
                            return
                        }
                        authorizedControl = oldControl
                    }
                }
                if (transition[1] != 3L) return
                val ready = lease.preparation()
                val fresh = atmosClaimFromPreparation(ready) ?: return
                if (fresh.nonce == granted.nonce) {
                    check(ready.getLong("control") == preparedControl) { "invalid_decoder_binding" }
                    return
                }
                check(fresh.live(buffer, SystemClock.elapsedRealtime(), buffer.bootCount, fresh.targetUid)) { "claim_not_current" }
                check(authorizedControl == oldControl || fresh.resumeFromNonce == granted.nonce) { "handoff_not_authorized" }
                val nextControl = ready.getLong("control")
                check(nextControl > 0 && nextControl != preparedControl) { "handoff_reused_control" }
                synchronized(claimLock) {
                    check(current() && permitCurrent()) { "request_retired" }
                    claim = fresh
                    check(fresh.write(prefs.edit()).commit())
                }
                authorizedControl = oldControl; preparedControl = nextControl; granted = fresh
            }
            suspend fun waitReady(): AtmosDecoderBinding {
                stage = "decoder_preparation"
                return awaitAtmosDecoder(buffer.deadlineMs, ::current, ::permitCurrent,
                    ::readState,
                    wait = { delay(25) }, streamAlreadyRunning = sender != null, handleTransition = ::handleTransition)
                    .also { check(it.control == preparedControl) { "invalid_decoder_binding" } }
            }
            // One wait path handles initial playback, pauses before BEGIN, and later explicit handoffs.
            binding = waitReady()
            check(current() && requestCurrent()) { "request_retired" }
            producer = AtmosHeadTrackingProducer(source, HeadTracking.motionSamples, discontinuity, output, ::current,
                { selection.profile }, { tryFast(checkNotNull(selected.socket)) }, { onFallback(checkNotNull(selected.socket)) },
                recovery = recovery, recoveryActive = {
                    val observed = status
                    observed.getBoolean("can_stream") && observed.getBoolean("playback_verified") &&
                        binding?.current(observed.getLongArray("native_status")) == true
                })
            currentProducer = producer
            sender = launch { failure.captureProducer { producer.run() } }
            stage = "decoder_monitor"
            while (current()) {
                if (production && !buffer.live(SystemClock.elapsedRealtime(), buffer.bootCount)) break
                check(permitCurrent()) { "experiment_permit_retired" }
                readState()
                check(status.getBoolean("live")) { "root_lease_retired" }
                val transition = atmosHandoffFor(status.getLongArray("native_handoff_state"), binding, preparedControl, authorizedControl)
                if (transition != null) {
                    binding = waitReady()
                    stage = "decoder_monitor"
                } else {
                    check(checkNotNull(binding).current(status.getLongArray("native_status"))) { "decoder_replaced_or_retired" }
                    delay(100)
                }
            }
        } catch (error: Exception) {
            failure.record(stage, error)
            throw error
        } finally {
            sourceLive.set(false)
            status = retiredAtmosCoordinatorState(status, if (production) {
                if (requestCurrent()) "waiting_for_root_rearm" else "disabled"
            } else "retired_no_reattach")
            if (currentExperiment === sourceLive) currentExperiment = null
            producer?.close(); lease?.close()
            if (currentProducer === producer) currentProducer = null
            if (currentLease === lease) currentLease = null
            runCatching { output?.close() ?: pipe[1].close() }; runCatching { pipe[0].close() }
            withContext(NonCancellable + Dispatchers.IO) {
                claim?.let { captured ->
                    runCatching { synchronized(claimLock) {
                        val prefix = TidalComponentClaimPermit.PREFIX
                        if (prefs.getString(prefix + "nonce", null) == captured.nonce) {
                            val edit = prefs.edit()
                            listOf("nonce", "issued_ms", "deadline_ms", "boot", "target_uid", "buffer_issued_ms",
                                "resume_from_nonce", "resume_process_pid", "resume_audio_session_id").forEach { edit.remove(prefix + it) }
                            edit.commit()
                        }
                    } }
                }
                if (production && hasBuffer) runCatching { synchronized(claimLock) {
                    val prefix = TidalBufferProbePolicy.PREFIX
                    if (TidalBufferProbeHooks.permit(prefs) == buffer && prefs.getBoolean(prefix + "production", false) &&
                        prefs.getLong(prefix + "choice_epoch", -1) == requestEpoch) {
                        val edit = prefs.edit()
                        listOf("issued_ms", "deadline_ms", "boot", "route_id", "peer", "production", "choice_epoch")
                            .forEach { edit.remove(prefix + it) }
                        edit.commit()
                    }
                } }
                sender?.cancelAndJoin()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        retireCurrent(); worker.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(requestListener)
    }
}

internal data class AtmosSourceRejection(val reason: String, val atMs: Long,
    val hostCheckedAtMs: Long? = null, val hostIdle: Boolean? = null) {
    fun snapshot() = Bundle().apply {
        putString("reason", reason); putLong("at_ms", atMs)
        hostCheckedAtMs?.let { putLong("host_checked_at_ms", it)
            putLong("host_age_ms", if (it == Long.MIN_VALUE) -1 else atMs - it) }
        hostIdle?.let { putBoolean("host_idle", it) }
    }
}

internal fun atmosProductionGrant(ready: Bundle, address: String, choiceEpoch: Long, nowMs: Long,
    boot: Int): TidalBufferProbePolicy.Permit? {
    val nonce = ready.getByteArray("nonce")
    if (choiceEpoch <= 0 || ready.getLong("choice_epoch", -1) != choiceEpoch || ready.getLong("control") <= 0 ||
        ready.getInt("target_uid", -1) <= 0 || nonce == null || nonce.size != 16 || nonce.all { it == 0.toByte() }) return null
    val grant = TidalBufferProbePolicy.Permit(ready.getLong("buffer_issued_ms"), ready.getLong("deadline_ms"),
        ready.getInt("boot", -1), ready.getInt("route_id", -1), ready.getString("peer") ?: "")
    return grant.takeIf { it.peer.equals(address, true) && it.live(nowMs, boot) }
}

internal data class AtmosDecoderBinding(val control: Long, val instance: Long, val epoch: Long, val revision: Long) {
    fun current(status: LongArray?): Boolean = status != null && status.size == 20 && status[0] == 1L &&
        status[1] in 2L..4L && status[4] == control && status[5] == instance && status[6] == epoch && status[7] == revision
}

internal suspend fun awaitAtmosDecoder(deadlineMs: Long, current: () -> Boolean, permitCurrent: () -> Boolean,
    read: () -> Bundle, now: () -> Long = SystemClock::elapsedRealtime, wait: suspend () -> Unit = { delay(200) },
    streamAlreadyRunning: Boolean = false, handleTransition: (suspend (Bundle) -> Unit)? = null): AtmosDecoderBinding {
    while (true) {
        check(current() && permitCurrent() && now() < deadlineMs) { "preparation_retired" }
        val state = read()
        check(state.getBoolean("live") && current() && permitCurrent() && now() < deadlineMs) { "root_lease_retired" }
        handleTransition?.invoke(state)
        check(current() && permitCurrent() && now() < deadlineMs) { "preparation_retired" }
        val status = state.getLongArray("native_status")
        if (status == null && (streamAlreadyRunning || handleTransition != null) && state.getBoolean("handoff_pending")) { wait(); continue }
        checkNotNull(status) { "native_status_unavailable" }
        if (status.size == 20 && status[0] == 1L && status[1] == 7L && handleTransition != null &&
            state.getLongArray("native_handoff_state")?.getOrNull(1) in listOf(1L, 2L, 5L, 6L, 7L, 8L, 9L)) { wait(); continue }
        check(status.size == 20 && status[0] == 1L && status[1] in 1L..(if (streamAlreadyRunning) 4L else 2L)) { "native_not_ready" }
        if (status[1] >= 2L && state.getBoolean("can_stream") && state.getBoolean("playback_verified")) {
            check((4..7).all { status[it] > 0 }) { "invalid_decoder_binding" }
            return AtmosDecoderBinding(status[4], status[5], status[6], status[7])
        }
        wait()
    }
}

internal fun atmosHandoffFor(state: LongArray?, binding: AtmosDecoderBinding?, preparedControl: Long = binding?.control ?: 0,
    authorizedControl: Long = 0): LongArray? {
    check(state != null && state.size == 9 && state[0] == 1L && state[1] in 0L..9L) { "invalid_handoff_state" }
    check(state[1] != 4L) { "handoff_failed" }
    if (state[1] == 0L) { check(state.drop(2).all { it == 0L }) { "invalid_handoff_state" }; return null }
    val old = AtmosDecoderBinding(state[5], state[6], state[7], state[8])
    if (state[1] in 8L..9L) check(AtmosDecoderHandoff.from(state) != null) { "invalid_handoff_state" }
    // Zero target is structural only: the captured root preparation owns the no-BEGIN pause proof.
    check(old.control > 0 && (state.slice(6..8).all { it > 0 } || state.slice(6..8).all { it == 0L })) { "invalid_handoff_state" }
    // Phase 3 can still describe the preceding binding after the fresh decoder has already consumed a pose.
    if (state[1] == 3L && binding?.control == preparedControl && preparedControl != old.control) {
        check(old.control == authorizedControl) { "handoff_binding_changed" }
        return null
    }
    if (state[1] == 3L && old.control == authorizedControl && binding?.control != old.control && preparedControl != old.control) return state
    check(old == binding || old.control == preparedControl && binding?.control != preparedControl) { "handoff_binding_changed" }
    return state
}

internal fun atmosPauseCleanupRacedAuthorization(state: Bundle, expected: LongArray): Boolean {
    val changed = state.getLongArray("native_handoff_state") ?: return false
    return state.getBoolean("live") && !state.getBoolean("can_stream") && !state.getBoolean("playback_verified") &&
        expected.size == 9 && changed.size == 9 && changed[0] == 1L &&
        (changed[1] == 7L && !state.getBoolean("handoff_authorizable") || expected[1] == 1L && changed[1] == 5L) &&
        (5..8).all { changed[it] == expected[it] }
}

private fun atmosClaimFromPreparation(ready: Bundle): TidalComponentClaimPermit? {
    val nonce = ready.getByteArray("nonce") ?: return null
    if (nonce.size != 16 || nonce.all { it == 0.toByte() }) return null
    return TidalComponentClaimPermit(nonce.joinToString("") { "%02x".format(it.toInt() and 255) },
        ready.getLong("issued_ms"), ready.getLong("deadline_ms"), ready.getInt("boot", -1),
        ready.getInt("target_uid", -1), ready.getLong("buffer_issued_ms"),
        ready.getString("resume_from_nonce"), ready.getInt("resume_process_pid", -1), ready.getInt("resume_audio_session_id", -1))
}
