/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.utils.TidalBufferProbePolicy

/** One root-owned experiment. Java never waits for native Codec2 calls or joins its workers. */
internal class RootAtmosHeadTracking(private val context: Context, private val caller: () -> Unit,
    private val admissionRejection: () -> String?,
    private val masterAuthorizes: (String, Long) -> Boolean = { _, _ -> false }) : AutoCloseable {
    private var active: Lease? = null
    private var closed = false

    @Synchronized fun hasLiveLease(): Boolean = active?.isLive == true

    @Synchronized fun retireForVolumeRepair() { active?.stop("volume_repair") }

    @Synchronized fun open(address: String, owner: IPlaybackRecoveryOwner, socket: IBinder,
        source: Bundle, permit: Bundle, poses: ParcelFileDescriptor): AtmosTrackingLease {
        try {
            caller()
            check(Process.myUid() == 0 && !closed && (active == null || active?.finished == true)) { "atmos_busy" }
            check(AtmosTrackingContract.validSource(source)) { "invalid_source" }
            val fd = poses.fileDescriptor
            check(OsConstants.S_ISFIFO(Os.fstat(fd).st_mode)) { "atmos_fd_not_pipe" }
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            check(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY) { "atmos_pipe_not_readable" }
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            return Lease(A2dpRecoveryGuard(address).address, owner, socket, Bundle(source), Bundle(permit), poses,
                Binder.getCallingUid(), Binder.getCallingPid()).also { active = it; it.start() }
        } catch (error: Exception) { poses.close(); throw error }
    }

    @Synchronized fun retire(owner: IPlaybackRecoveryOwner) {
        active?.takeIf { it.owner.asBinder() == owner.asBinder() }?.stop("owner_retired")
    }
    @Synchronized override fun close() { closed = true; active?.stop("helper_closed") }

    private inner class Lease(val address: String, val owner: IPlaybackRecoveryOwner, val socket: IBinder,
        val source: Bundle, val permit: Bundle, val poses: ParcelFileDescriptor, val sourceUid: Int, val sourcePid: Int) :
        AtmosTrackingLease.Stub(), IBinder.DeathRecipient {
        private val live = AtomicBoolean(true)
        private val production = permit.getBoolean("production", false)
        private val choiceEpoch = permit.getLong("choice_epoch", 0)
        val isLive get() = live.get()
        @Volatile private var handle = 0L
        @Volatile private var snapshot: AtmosNativeSnapshot? = null
        @Volatile private var phoneMotion: AtmosPhoneMotion? = null
        @Volatile private var checkedAt = 0L
        @Volatile private var playbackVerified = false
        @Volatile private var playback: AtmosPlaybackWitness? = null
        @Volatile private var candidatePlayback: AtmosPlaybackWitness? = null
        @Volatile private var decoder: AtmosRootDecoderBinding? = null
        @Volatile private var claimNonce: String? = null
        @Volatile private var claimControl = 0L
        @Volatile private var handoff: AtmosHandoffCapture? = null
        private val freshSourceRecovery = AtmosFreshSourceRecovery()
        private val transitionLock = Any()
        @Volatile private var reason = "preparing"
        @Volatile private var guardStage = "initializing"
        @Volatile private var readerStage = "initializing"
        @Volatile private var failureClass = ""
        @Volatile private var failureMessage = ""
        @Volatile private var failureStage = ""
        @Volatile private var rejectedAdmission = ""
        @Volatile private var readerEnded = false
        @Volatile private var guardEnded = false
        val finished get() = readerEnded && guardEnded

        override fun binderDied() = stop("owner_died")
        fun stop(why: String, error: Throwable? = null, stage: String = "") {
            if (live.compareAndSet(true, false)) {
                if (error != null) {
                    failureClass = error.javaClass.name.take(120)
                    failureMessage = atmosFailureMessage(error)
                    failureStage = stage
                    runCatching {
                        android.util.Log.w("LibrePodsAtmos",
                            "first_failure stage=$failureStage class=$failureClass reason=$failureMessage")
                    }
                }
                reason = why
                playbackVerified = false
                phoneMotion?.close()
                val captured = handle
                if (captured != 0L) runCatching { AtmosNativeClient.nativeRetire(captured) }
            }
        }
        override fun retire() { caller(); stop("retired") }
        override fun state(): Bundle {
            caller()
            val captured = handle
            val pair = if (captured != 0L) runCatching { nativeState(captured) }.getOrNull() else null
            val status = pair?.status
            val transition = pair?.handoff
            val canStream = playbackVerified && fresh() && status != null && decoder != null &&
                AtmosTrackingContract.validStatus(status, Process.myPid(), transition) &&
                AtmosRootDecoderBinding.from(status) == decoder && transition?.let { AtmosDecoderHandoff.from(it)?.phase in listOf(0, 3) } == true
            val diagnostic = if (captured != 0L) AtmosTrackingContract.terminalDiagnostic(live.get(), status, Process.myPid()) {
                AtmosNativeClient.nativeDiagnostic(captured)
            } else null
            return Bundle().apply {
                putBoolean("live", live.get()); putString("reason", reason)
                putString("guard_stage", guardStage); putString("reader_stage", readerStage)
                putString("failure_class", failureClass); putString("failure_message", failureMessage)
                putString("failure_stage", failureStage)
                putString("admission_rejection", rejectedAdmission)
                phoneMotion?.let {
                    putBoolean("phone_registered", it.registered)
                    putLong("phone_samples", it.samples); putLong("phone_last_timestamp_ns", it.lastTimestampNs)
                }
                putBoolean("playback_verified", canStream)
                putBoolean("can_stream", canStream)
                if (transition != null && AtmosDecoderHandoff.from(transition) != null) {
                    putLongArray("native_handoff_state", transition)
                    putBoolean("handoff_pending", transition[1] in 1L..3L || transition[1] in 5L..9L)
                    putBoolean("handoff_authorizable", fresh() && transition[1] in listOf(1L, 5L) &&
                        handoff?.binding == AtmosDecoderHandoff.from(transition)?.old)
                }
                if (status != null && AtmosTrackingContract.validStatus(status, Process.myPid(), transition)) putLongArray("native_status", status)
                if (diagnostic != null) putLongArray("native_failure_diagnostic", diagnostic)
            }
        }
        override fun preparation(): Bundle {
            caller()
            val captured = handle; val prepared = snapshot ?: return Bundle()
            if (captured == 0L || !fresh()) return Bundle()
            val status = runCatching { nativeState(captured).status }.getOrNull() ?: return Bundle()
            if (!AtmosTrackingContract.validStatus(status, Process.myPid()) || status[1] != 1L || status[4] <= 0) return Bundle()
            val nonce = runCatching { AtmosNativeClient.nativePreparationNonce(captured) }.getOrNull() ?: return Bundle()
            if (nonce.size != 16 || nonce.all { it == 0.toByte() } || !fresh() || handle != captured) return Bundle()
            val confirmed = runCatching { nativeState(captured).status }.getOrNull() ?: return Bundle()
            if (confirmed[1] != 1L || confirmed[4] != status[4]) return Bundle()
            claimNonce = nonce.joinToString("") { "%02x".format(it.toInt() and 255) }
            claimControl = status[4]
            return Bundle().apply {
                putLong("control", status[4])
                putByteArray("nonce", nonce); putLong("issued_ms", prepared.preparedBoottimeNs / 1_000_000)
                putLong("deadline_ms", prepared.deadlineBoottimeNs / 1_000_000); putInt("boot", prepared.bootCount)
                putInt("target_uid", prepared.targetUid); putLong("buffer_issued_ms", prepared.bufferIssuedBoottimeMs)
                putBoolean("production", production); putLong("choice_epoch", if (production) choiceEpoch else 0)
                putString("peer", address); putInt("route_id", permit.getInt("route_id"))
                handoff?.takeIf { it.samePlayerResume }?.let {
                    putString("resume_from_nonce", it.nonce)
                    putInt("resume_process_pid", it.player.pid); putInt("resume_audio_session_id", it.player.session)
                }
            }
        }
        override fun authorizeHandoff(marker: Bundle): Boolean {
            caller()
            return synchronized(transitionLock) {
                val captured = handle
                val expected = handoff ?: return@synchronized false
                if (captured == 0L || !fresh()) return@synchronized false
                val transition = nativeState(captured).transition
                if (!expected.accepts(marker, transition, permit.getLong("issued_ms"), SystemClock.elapsedRealtimeNanos())) return@synchronized false
                val old = expected.binding
                AtmosNativeClient.nativeAuthorizeDecoderHandoff(captured, old.control, old.instance,
                    old.epoch, old.revision, marker.getLong("event_boottime_ns"))
            }
        }
        private fun fresh() = live.get() && checkedAt > 0 && SystemClock.elapsedRealtime() - checkedAt in 0..500 &&
            owner.asBinder().isBinderAlive && socket.isBinderAlive
        private fun nativeState(captured: Long) = checkNotNull(AtmosNativeState.from(
            AtmosNativeClient.nativeSnapshot(captured), Process.myPid())) { "invalid_native_snapshot" }
        private fun failure(error: Exception, stage: String): String = error.message?.takeIf { it in setOf(
            "owner_changed", "permit_expired", "route_or_recording_changed", "route_changed", "player_replaced_or_rerouted",
            "native_library_unavailable", "native_open_failed", "owner_stale", "pipe_failed", "short_frame",
            "invalid_frame", "source_or_player_changed", "native_offer_rejected", "target_version_changed", "permission_revoked",
            "fresh_source_authorization_rejected", "fresh_source_changed", "fresh_source_timeout",
            "boot_count_timeout", "boot_count_exit", "boot_count_invalid", "invalid_directional_mapping") } ?: stage

        fun start() {
            try { owner.asBinder().linkToDeath(this, 0); socket.linkToDeath(this, 0) }
            catch (_: Exception) { stop("owner_died") }
            thread(name = "atmos-owner-guard", isDaemon = true) {
                try {
                    val route = RootHeadTrackerSink.NativeA2dpRoute()
                    val audio = context.getSystemService(AudioManager::class.java)
                    val players = AtmosPlaybackObserver(audio)
                    val targetUid = context.packageManager.getApplicationInfo(TidalBufferProbePolicy.PACKAGE, 0).uid
                    guardStage = "boot_count"
                    val boot = AtmosRootBootCount.read(live::get)
                    if (production) {
                        guardStage = "production_grant"
                        check(choiceEpoch > 0 && masterAuthorizes(address, choiceEpoch)) { "owner_changed" }
                        check(route.selected(address) && audio.mode == AudioManager.MODE_NORMAL &&
                            audio.activeRecordingConfigurations.isEmpty()) { "route_or_recording_changed" }
                        val selected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                        check(selected.size == 1 && selected.single().address.equals(address, true)) { "route_changed" }
                        val now = SystemClock.elapsedRealtime()
                        check(now > 0 && boot >= 0 && targetUid > 0 && selected.single().id > 0) { "invalid_root_grant" }
                        issueAtmosProductionGrant(permit, now, boot, selected.single().id, address)
                    }
                    while (live.get()) {
                        val attestedAtNs = SystemClock.elapsedRealtimeNanos()
                        checkAtmosOwner({
                            val rejected = admissionRejection()
                            if (rejected != null) rejectedAdmission = rejected
                            rejected == null
                        }, { owner.asBinder().isBinderAlive }, { socket.isBinderAlive },
                            { owner.isCurrent }) { guardStage = it }
                        guardStage = "permission"
                        check(context.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
                            BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED) { "permission_revoked" }
                        guardStage = "permit"
                        val now = SystemClock.elapsedRealtime()
                        check(!production || masterAuthorizes(address, choiceEpoch)) { "owner_changed" }
                        check(AtmosTrackingContract.validPermit(permit, now, boot, address)) { "permit_expired" }
                        guardStage = "route"
                        check(route.selected(address) && audio.mode == AudioManager.MODE_NORMAL &&
                            audio.activeRecordingConfigurations.isEmpty()) { "route_or_recording_changed" }
                        check(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                            it.id == permit.getInt("route_id") && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                                it.address.equals(address, true)
                        }) { "route_changed" }
                        guardStage = "actual_player"
                        if (handle != 0L) {
                            observePlayback(players.observe(targetUid, address, permit.getInt("route_id"), candidatePlayback ?: playback), attestedAtNs)
                        } else playbackVerified = false
                        if (live.get()) checkedAt = SystemClock.elapsedRealtime()
                        Thread.sleep(100)
                    }
                } catch (error: Exception) { stop(failure(error, "guard_$guardStage"), error, "guard_$guardStage") }
                catch (error: LinkageError) { stop("native_link_failed", error, "guard_$guardStage") }
                finally { guardEnded = true; checkedAt = 0; playbackVerified = false }
            }
            thread(name = "atmos-pose-reader", isDaemon = true) {
                try {
                    val start = SystemClock.elapsedRealtime()
                    readerStage = "waiting_owner"
                    while (live.get() && checkedAt == 0L &&
                        SystemClock.elapsedRealtime() - start < AtmosRootBootCount.READER_STARTUP_WAIT_MS) Thread.sleep(5)
                    check(fresh()) { "owner_stale" }
                    readerStage = "load_library"
                    check(AtmosNativeClient.load()) { "native_library_unavailable" }
                    readerStage = "target_package"
                    val target = context.packageManager.getPackageInfo(TidalBufferProbePolicy.PACKAGE, 0)
                    check(target.versionName == TidalBufferProbePolicy.VERSION && target.longVersionCode == TidalBufferProbePolicy.VERSION_CODE) { "target_version_changed" }
                    val targetUid = context.packageManager.getApplicationInfo(TidalBufferProbePolicy.PACKAGE, 0).uid
                    val now = SystemClock.elapsedRealtimeNanos()
                    val prepared = prepareAtmosNativeSnapshot(Process.myUid(), Process.myPid(), sourceUid, sourcePid,
                        targetUid, address, source, permit, owner.asBinder(), socket, now)
                    check(fresh())
                    readerStage = "native_open"
                    val opened = AtmosNativeClient.nativeOpen(prepared)
                    check(opened != 0L) { "native_open_failed" }
                    handle = opened; snapshot = prepared
                    if (!fresh()) { AtmosNativeClient.nativeRetire(opened); error("owner_changed") }
                    readerStage = "phone_sensor"
                    val phone = AtmosPhoneMotion(context, opened, { live.get() && handle == opened },
                        AtmosNativeClient::nativePhonePose) { error -> stop("phone_sensor_failed", error, "phone_sensor") }
                    phoneMotion = phone
                    if (live.get()) phone.start() else phone.close()
                    readerStage = "pose_pipe"
                    readFrames(opened)
                } catch (error: Exception) { stop(failure(error, "reader_$readerStage"), error, "reader_$readerStage") }
                catch (error: LinkageError) { stop("native_link_failed", error, "reader_$readerStage") }
                finally {
                    stop("eof")
                    runCatching { poses.close() }
                    runCatching { owner.asBinder().unlinkToDeath(this, 0) }; runCatching { socket.unlinkToDeath(this, 0) }
                    readerEnded = true
                }
            }
        }
        private fun observePlayback(observation: AtmosPlaybackObservation, attestedAtNs: Long): Unit = synchronized(transitionLock) {
            check(observation !is AtmosPlaybackObservation.Invalid) {
                (observation as AtmosPlaybackObservation.Invalid).reason
            }
            val observed = (observation as? AtmosPlaybackObservation.Present)?.witness
            val paused = observation is AtmosPlaybackObservation.Paused
            val previous = playback
            if (previous != null && observed != null) check(observed.pid == previous.pid) { "player_process_replaced" }
            var native = nativeState(handle)
            var transition = native.transition
            check(transition.phase != 4) { "handoff_failed" }
            if (observed != null) candidatePlayback = observed
            if (transition.phase in 8..9) {
                playbackVerified = false
                val old = checkNotNull(decoder) { "missing_old_decoder" }
                check(old.control == claimControl) { "fresh_source_changed" }
                val captured = AtmosHandoffCapture(old, checkNotNull(previous) { "missing_old_player" },
                    checkNotNull(claimNonce) { "missing_old_claim" })
                freshSourceRecovery.observe(transition, captured, observed, attestedAtNs,
                    SystemClock.elapsedRealtimeNanos(), permit.getLong("deadline_ms") * 1_000_000, { handoff = it }) { binding ->
                    val current = handle
                    live.get() && current != 0L && AtmosNativeClient.nativeAuthorizeFreshSource(current,
                        binding.control, binding.instance, binding.epoch, binding.revision)
                }
                return@synchronized
            }
            val pauseBinding = if (paused) (AtmosRootDecoderBinding.from(native.status) ?:
                AtmosRootDecoderBinding.preparing(native.status) ?: transition.old.takeIf {
                    transition.phase in 1..2 && it == decoder &&
                        (observation as AtmosPlaybackObservation.Paused).witness == previous
                })?.takeIf { it.control == claimControl } else null
            if (pauseBinding != null && transition.phase in 0..3) {
                playbackVerified = false
                val known = (observation as AtmosPlaybackObservation.Paused).witness
                // Claim discovery can race this no-BEGIN snapshot. A rejected request publishes nothing;
                // the next guard pass must observe and validate the new tuple instead of guessing it.
                if (!AtmosNativeClient.nativePauseDecoder(handle, pauseBinding.control, pauseBinding.instance,
                        pauseBinding.epoch, pauseBinding.revision)) {
                    val changed = nativeState(handle)
                    check(changed.transition.phase != transition.phase ||
                        AtmosRootDecoderBinding.from(changed.status)?.let { it != pauseBinding } == true) { "pause_failed" }
                    return@synchronized
                }
                handoff = AtmosHandoffCapture(pauseBinding, known, checkNotNull(claimNonce) { "missing_old_claim" })
                playback = known; decoder = pauseBinding
                native = nativeState(handle); transition = native.transition
                check(transition.phase in 5..7) { "pause_failed" }
            } else if (previous != null && (transition.phase == 0 && !paused && observed != previous ||
                    transition.phase == 5 && !paused && observed != previous)) {
                playbackVerified = false
                val old = checkNotNull(decoder) { "missing_old_decoder" }
                AtmosNativeClient.nativeQuiesceDecoder(handle, old.control, old.instance, old.epoch, old.revision)
                native = nativeState(handle); transition = native.transition
                // Native TargetMissing may have entered this exact transition first; never restart its window.
                check((transition.phase in 1..3 || transition.phase == 6) && transition.old == old) { "handoff_quiesce_failed" }
            }
            if (transition.phase in 1..3 || transition.phase in 5..7) {
                playbackVerified = false
                val old = handoff ?: AtmosHandoffCapture(checkNotNull(decoder) { "missing_old_decoder" },
                    checkNotNull(previous) { "missing_old_player" }, checkNotNull(claimNonce) { "missing_old_claim" }).also { handoff = it }
                check(old.binding == transition.old) { "handoff_binding_changed" }
                val now = SystemClock.elapsedRealtimeNanos()
                val overall = permit.getLong("deadline_ms") * 1_000_000
                val deadline = when (transition.phase) { 3 -> transition.startupDeadlineNs; 5, 6 -> overall; else -> transition.markerDeadlineNs }
                check(now < minOf(deadline, overall) && (transition.phase != 3 || deadline <= overall)) { "handoff_timeout" }
                if (transition.phase in 5..6 && observed != null) {
                    check(transition.phase == 6 || observed == old.player) { "paused_player_replaced" }
                    handoff = old.copy(samePlayerResume = transition.phase == 5, pausedFlushResume = transition.phase == 6)
                    check(AtmosNativeClient.nativeResumeDecoder(handle, old.binding.control, old.binding.instance,
                        old.binding.epoch, old.binding.revision)) { "resume_failed" }
                    return@synchronized
                }
                if (transition.phase == 3) {
                    val ready = AtmosRootDecoderBinding.from(native.status)
                    if (old.newPlayerReady(observed, ready)) {
                        playback = observed; decoder = ready; playbackVerified = true
                    }
                }
                return@synchronized
            }
            handoff = null
            val ready = AtmosRootDecoderBinding.from(native.status)
            if (ready != null && observed != null) {
                playback = observed; decoder = ready; playbackVerified = true
            } else playbackVerified = false
        }
        private fun readFrames(captured: Long) {
            val frame = ByteArray(AtmosCanonicalFrame.BYTES)
            var used = 0
            while (live.get()) {
                check(fresh()) { "owner_stale" }
                val poll = StructPollfd().apply { fd = poses.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                Os.poll(arrayOf(poll), 25)
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) error("pipe_failed")
                if (poll.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLHUP) == 0) continue
                val count = try { Os.read(poses.fileDescriptor, frame, used, frame.size - used) }
                    catch (error: ErrnoException) { if (error.errno == OsConstants.EAGAIN) continue else throw error }
                if (count == 0) { check(used == 0) { "short_frame" }; return }
                used += count
                if (used == frame.size) {
                    val value = checkNotNull(AtmosCanonicalFrame.decode(frame)) { "invalid_frame" }
                    check(value.discontinuity == source.getInt("discontinuity") && fresh()) { "source_or_player_changed" }
                    // During handoff native keeps reference history, but cannot publish old canonical/ARM authority.
                    check(AtmosNativeClient.nativeOffer(captured, frame, playbackVerified)) { "native_offer_rejected" }
                    used = 0
                }
            }
        }
    }
}

/** Called only after root has verified the master choice and the exact selected route. */
internal fun issueAtmosProductionGrant(permit: Bundle, nowMs: Long, boot: Int, routeId: Int, address: String) {
    // These are native directional-test mappings, not a universal anatomical calibration.
    check(permit.getInt("directional_mapping", -1) in 1..4) { "invalid_directional_mapping" }
    permit.putLong("issued_ms", nowMs)
    permit.putLong("deadline_ms", nowMs + AtmosTrackingContract.MAX_WINDOW_MS)
    permit.putInt("boot", boot); permit.putInt("route_id", routeId); permit.putString("peer", address)
}

internal fun prepareAtmosNativeSnapshot(rootUid: Int, rootPid: Int, sourceUid: Int, sourcePid: Int,
    targetUid: Int, address: String, source: Bundle, permit: Bundle, owner: IBinder, socket: IBinder,
    nowNs: Long) = AtmosNativeSnapshot(rootUid, rootPid, sourceUid, sourcePid, targetUid,
    permit.getInt("boot"), address, address.split(':').map { it.toInt(16).toByte() }.toByteArray(),
    checkNotNull(source.getByteArray("full_role")).copyOf(), checkNotNull(source.getString("model")),
    checkNotNull(source.getString("firmware")), source.getInt("profile_schema"),
    checkNotNull(source.getByteArray("profile_digest")).copyOf(), owner, socket,
    source.getLong("source_epoch"), source.getLong("reference_epoch"), nowNs, source.getInt("discontinuity"), 0,
    permit.getLong("issued_ms"), nowNs, permit.getLong("deadline_ms") * 1_000_000,
    permit.getInt("directional_mapping"))

internal fun checkAtmosOwner(admitted: () -> Boolean, ownerAlive: () -> Boolean, socketAlive: () -> Boolean,
    current: () -> Boolean, stage: (String) -> Unit) {
    stage("owner_admission"); check(admitted()) { "owner_changed" }
    stage("owner_binder"); check(ownerAlive()) { "owner_changed" }
    stage("source_binder"); check(socketAlive()) { "owner_changed" }
    stage("owner_callback"); check(current()) { "owner_changed" }
}

/** Root's repeated guard cannot renew the native window or reuse an earlier player's proof. */
internal class AtmosFreshSourceRecovery {
    private var authorized: AtmosDecoderHandoff? = null

    fun observe(transition: AtmosDecoderHandoff, current: AtmosHandoffCapture, observed: AtmosPlaybackWitness?,
        attestedAtNs: Long, nowNs: Long, overallDeadlineNs: Long,
        publish: (AtmosHandoffCapture) -> Unit,
        authorize: (AtmosRootDecoderBinding) -> Boolean): AtmosHandoffCapture {
        check(transition.phase in 8..9 && current.binding == transition.old && observed == current.player) { "fresh_source_changed" }
        check(nowNs >= transition.firstLossNs && nowNs < transition.markerDeadlineNs &&
            transition.markerDeadlineNs <= overallDeadlineNs && attestedAtNs > 0 &&
            nowNs - attestedAtNs in 0..AtmosDecoderHandoff.MARKER_WINDOW_NS) { "fresh_source_timeout" }
        val resume = current.copy(samePlayerResume = true, sourceRecovery = true)
        // The pose reader can reprepare before authorize returns; preparation must already see this provenance.
        publish(resume)
        if (transition.phase == 9 && authorized != transition && attestedAtNs >= transition.firstLossNs) {
            check(authorize(current.binding)) { "fresh_source_authorization_rejected" }
            authorized = transition
        }
        return resume
    }
}
