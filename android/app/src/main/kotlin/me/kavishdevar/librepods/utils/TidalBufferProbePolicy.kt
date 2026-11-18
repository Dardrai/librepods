/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

internal object TidalBufferProbePolicy {
    const val PACKAGE = "com.aspiro.tidal"
    const val VERSION = "2.219.0"
    const val VERSION_CODE = 10023L
    const val APK_SHA256 = "c0dadd9a33fd3d36b20eeef25cd98860421305d6a164e3f166401ed56caae73f"
    const val PREFIX = "tidal_buffer_probe_"
    const val RETAINED_KEY = "tidal_small_buffer_enabled"
    const val OBSERVE_PREFIX = PREFIX + "observe_"
    const val LATE_RENDER_KEY = OBSERVE_PREFIX + "late_render_until_ms"
    const val MAX_WINDOW_MS = 15 * 60_000L
    const val MAX_READBACKS = (MAX_WINDOW_MS / 1000).toInt() + 2
    const val FRAMES = 4800
    const val BYTES = FRAMES * 8
    const val MAX_ESTIMATE_BYTES = Int.MAX_VALUE / 8
    const val MAX_ACTUAL_FRAMES = 72000 // Known stock 1.5-second ceiling, not a requested allocation.

    private val bluetoothAddress = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")

    fun validEstimate(bytes: Int): Boolean = bytes in 1..MAX_ESTIMATE_BYTES

    enum class BufferResult {
        INVALID, EXACT_100_MS, EFFECTIVE_100_MS, CLAMPED;
        val effective100Ms get() = this == EXACT_100_MS || this == EFFECTIVE_100_MS
    }
    fun bufferResult(size: Int, capacity: Int, threshold: Int, rate: Int = 48000,
        channels: Int = 2, encoding: Int = 4): BufferResult = when {
        rate != 48000 || channels != 2 || encoding != 4 || capacity !in 1..MAX_ACTUAL_FRAMES ||
            size !in 1..capacity || threshold !in 1..capacity -> BufferResult.INVALID
        size == FRAMES && capacity == FRAMES && threshold == FRAMES -> BufferResult.EXACT_100_MS
        size == FRAMES -> BufferResult.EFFECTIVE_100_MS
        else -> BufferResult.CLAMPED
    }

    // A coordinator-attested buffer experiment, never a Dolby pose lease.
    data class Permit(val issuedMs: Long, val deadlineMs: Long, val bootCount: Int,
        val routeId: Int, val peer: String) {
        fun live(nowMs: Long, boot: Int): Boolean = issuedMs > 0 && nowMs >= issuedMs &&
            deadlineMs > issuedMs && deadlineMs - issuedMs <= MAX_WINDOW_MS && nowMs < deadlineMs &&
            bootCount >= 0 && boot == bootCount && routeId > 0 &&
            peer.matches(bluetoothAddress)
    }

    /** A read-only window for one production choice; each sample still needs its own live grant. */
    data class Observation(val issuedMs: Long, val deadlineMs: Long, val bootCount: Int, val choiceEpoch: Long) {
        fun windowLive(nowMs: Long, boot: Int): Boolean = issuedMs > 0 && nowMs >= issuedMs &&
            deadlineMs > issuedMs && deadlineMs - issuedMs <= MAX_WINDOW_MS && nowMs < deadlineMs &&
            bootCount >= 0 && boot == bootCount && choiceEpoch > 0
        fun live(buffer: Permit?, claim: TidalComponentClaimPermit?, nowMs: Long, boot: Int, uid: Int,
            production: Boolean, currentChoiceEpoch: Long): Boolean = windowLive(nowMs, boot) &&
            buffer != null && claim != null && production && choiceEpoch == currentChoiceEpoch &&
            buffer.live(nowMs, boot) && claim.live(buffer, nowMs, boot, uid)
    }

    data class Format(val codec: String?, val mime: String?, val encoding: Int,
        val channels: Int, val rate: Int, val explicitBytes: Int, val hasTrack: Boolean,
        val floatEnabled: Boolean, val tunneling: Boolean)

    enum class Mode { NONE, DIAGNOSTIC, RETAINED }
    fun retireComponentOnDisarm(reason: String): Boolean =
        reason != "track_teardown" && reason != "decoder_or_stream_changed"

    fun mode(diagnosticLive: Boolean, retainedEnabled: Boolean): Mode = when {
        diagnosticLive -> Mode.DIAGNOSTIC
        retainedEnabled -> Mode.RETAINED
        else -> Mode.NONE
    }

    fun paceRetainedTrack(retained: Boolean, production: Boolean, safetyTripped: Boolean,
        observing: Boolean, armedTrial: Boolean): Boolean =
        retained && production && !safetyTripped && (!observing || armedTrial)

    fun supportedComponent(sdk: Int, version: String?, code: Long, format: Format): Boolean =
        sdk == 37 && version == VERSION && code == VERSION_CODE &&
            format.codec == "c2.pixel.eac3.float.decoder" && format.mime == "audio/raw" &&
            format.encoding == 4 && format.channels == 2 && format.rate == 48000 && format.explicitBytes == 0 &&
            format.floatEnabled && !format.tunneling

    // Buffer allocation changes remain limited to a fresh AudioTrack. A new codec
    // can still need its own claim while Media3 retains the verified float track.
    fun supported(sdk: Int, version: String?, code: Long, format: Format): Boolean =
        supportedComponent(sdk, version, code, format) && !format.hasTrack

    fun retainedBytes(enabled: Boolean, sdk: Int, version: String?, code: Long, format: Format,
        minimumBytes: Int, normalMode: Boolean, noVisibleRecording: Boolean): Int? =
        if (enabled && normalMode && noVisibleRecording && supported(sdk, version, code, format) &&
            validEstimate(minimumBytes)) BYTES else null

    fun bytes(permit: Permit?, nowMs: Long, boot: Int, sdk: Int, version: String?, code: Long,
        format: Format, minimumBytes: Int, alreadyApplied: Boolean): Int? {
        if (permit?.live(nowMs, boot) != true || alreadyApplied || !supported(sdk, version, code, format) ||
            !validEstimate(minimumBytes)) return null
        return BYTES
    }
}

/** Even simultaneous players cannot both consume this process's single experiment. */
internal class TidalBufferProbeClaim {
    private val claimed = AtomicBoolean()
    fun used(): Boolean = claimed.get()
    fun take(): Boolean = claimed.compareAndSet(false, true)
}

/** Preserve the caller's array and propagate its exception without retrying the original call. */
internal fun <T> tidalBufferProbeProceed(args: Array<Any?>, bytes: Int?, proceed: (Array<Any?>) -> T): T =
    proceed(if (bytes == null) args else args.copyOf().also { it[1] = bytes })

internal fun <T> tidalBufferProbeProceed(args: Array<Any?>, bytes: Int?, proceed: (Array<Any?>) -> T,
    configured: () -> Unit): T = tidalBufferProbeProceed(args, bytes, proceed).also { configured() }

/** The sink hook is valid only inside its exact renderer's synchronous configuration call. */
internal class TidalBufferProbeScope {
    private val current = ThreadLocal<Any?>()
    fun renderer(): Any? = current.get()
    fun <T> within(renderer: Any?, action: () -> T): T {
        val previous = current.get()
        current.set(renderer)
        try { return action() } finally { if (previous == null) current.remove() else current.set(previous) }
    }
}

/** Timestamp bounds in one renderer epoch, not a count of outstanding codec buffers. */
internal class TidalBufferProbeLead {
    private var queuedUs = -1L
    private var observedCount = -1
    private var valid = true
    private var owner: WeakReference<Any>? = null
    private var track: WeakReference<Any>? = null
    private var claim: Any? = null
    private var offsetUs = -1L
    private var positionUs = -1L
    var observationEpoch = 0L; private set

    fun resetObservation() {
        owner = null; track = null; claim = null; offsetUs = -1; positionUs = -1
        queuedUs = -1; observedCount = -1; valid = true; ++observationEpoch
    }

    fun current(owner: Any?, track: Any?, claim: Any?, inputOffsetUs: Long, outputOffsetUs: Long,
        positionUs: Long, controlPending: Boolean, queuedCount: Int, highWaterUs: Long, outputUs: Long): Sample? {
        if (owner == null || track == null || claim == null || controlPending || inputOffsetUs < 0 ||
            inputOffsetUs == Long.MAX_VALUE || inputOffsetUs != outputOffsetUs || positionUs < inputOffsetUs ||
            positionUs == Long.MAX_VALUE) { resetObservation(); return null }
        if (this.owner?.get() !== owner || this.track?.get() !== track || this.claim != claim ||
            offsetUs != inputOffsetUs || positionUs < this.positionUs) resetObservation()
        this.owner = WeakReference(owner); this.track = WeakReference(track); this.claim = claim
        offsetUs = inputOffsetUs; this.positionUs = positionUs
        if (!observed(queuedCount, highWaterUs)) { resetObservation(); return null }
        return sample(outputUs, positionUs)
    }

    fun queued(before: Int, after: Int, highestPtsUs: Long): Boolean {
        if (!valid || before < 0 || after < before || after.toLong() - before > 1) {
            valid = false
            return false
        }
        if (after == before) return true // Format/EOS/no-input paths can return without queuing media.
        if (highestPtsUs < 0 || highestPtsUs == Long.MAX_VALUE || highestPtsUs < queuedUs) {
            valid = false
            return false
        }
        queuedUs = highestPtsUs
        return true
    }

    /** Same-player-thread output snapshots can include several completed input queue calls. */
    fun observed(count: Int, highestPtsUs: Long): Boolean {
        if (!valid || count <= 0 || count < observedCount || highestPtsUs < 0 ||
            highestPtsUs == Long.MAX_VALUE || highestPtsUs < queuedUs ||
            count == observedCount && highestPtsUs != queuedUs) {
            valid = false
            return false
        }
        observedCount = count; queuedUs = highestPtsUs
        return true
    }

    fun sample(outputUs: Long, rendererPositionUs: Long): Sample? {
        if (!valid || queuedUs < 0 || outputUs < 0 || rendererPositionUs < 0 ||
            outputUs == Long.MAX_VALUE || rendererPositionUs == Long.MAX_VALUE) return null
        return Sample(queuedUs, outputUs, rendererPositionUs)
    }

    data class Sample(val inputHighWaterUs: Long, val outputUs: Long, val rendererPositionUs: Long) {
        val inputMinusOutputUs get() = inputHighWaterUs - outputUs
        val outputMinusPositionUs get() = outputUs - rendererPositionUs
        val inputMinusPositionUs get() = inputHighWaterUs - rendererPositionUs
    }
}

/** Passive observation must never install the renderer/input backpressure hooks. */
internal inline fun tidalLeadHooks(diagnostic: Boolean, passive: Boolean, observe: () -> Unit, gate: () -> Unit) {
    if (diagnostic) gate() else if (passive) observe()
}

/** Written only by LibrePods after authenticated root PREPARING, never by buffer-only arm. */
internal data class TidalComponentClaimPermit(val nonce: String, val issuedMs: Long,
    val deadlineMs: Long, val boot: Int, val targetUid: Int, val bufferIssuedMs: Long,
    val resumeFromNonce: String? = null, val resumePid: Int = -1, val resumeSession: Int = -1) {
    fun live(buffer: TidalBufferProbePolicy.Permit, nowMs: Long, currentBoot: Int, uid: Int): Boolean =
        buffer.live(nowMs, currentBoot) && bufferIssuedMs == buffer.issuedMs && boot == currentBoot &&
            targetUid > 0 && targetUid == uid && issuedMs >= bufferIssuedMs && nowMs >= issuedMs &&
            deadlineMs > issuedMs && deadlineMs <= buffer.deadlineMs && nowMs < deadlineMs &&
            validNonce(nonce) && (if (resumeFromNonce == null) resumePid == -1 && resumeSession == -1
            else validNonce(resumeFromNonce) && resumeFromNonce != nonce && resumePid > 0 && resumeSession > 0)

    fun matchesResume(previous: TidalComponentClaimPermit?, pid: Int, session: Int, sameOwner: Boolean) =
        previous != null && sameOwner && resumeFromNonce == previous.nonce && nonce != previous.nonce &&
            bufferIssuedMs == previous.bufferIssuedMs && boot == previous.boot && targetUid == previous.targetUid &&
            resumePid == pid && resumeSession == session && pid > 0 && session > 0

    fun write(edit: android.content.SharedPreferences.Editor): android.content.SharedPreferences.Editor {
        edit.putString(PREFIX + "nonce", nonce).putLong(PREFIX + "issued_ms", issuedMs)
            .putLong(PREFIX + "deadline_ms", deadlineMs).putInt(PREFIX + "boot", boot)
            .putInt(PREFIX + "target_uid", targetUid).putLong(PREFIX + "buffer_issued_ms", bufferIssuedMs)
        if (resumeFromNonce == null) {
            listOf("resume_from_nonce", "resume_process_pid", "resume_audio_session_id").forEach { edit.remove(PREFIX + it) }
        } else edit.putString(PREFIX + "resume_from_nonce", resumeFromNonce)
            .putInt(PREFIX + "resume_process_pid", resumePid).putInt(PREFIX + "resume_audio_session_id", resumeSession)
        return edit
    }

    companion object {
        const val PREFIX = "tidal_component_claim_"
        const val PARAMETER = "vendor.librepods.dolby.component-claim.value"
        const val CLEAR = "00000000000000000000000000000000"
        fun validNonce(nonce: String) = nonce.length == 32 && nonce != CLEAR && nonce.all { it in '0'..'9' || it in 'a'..'f' }
        fun fromValues(values: Map<String, *>): TidalComponentClaimPermit? = runCatching {
            TidalComponentClaimPermit(values[PREFIX + "nonce"] as String,
                values[PREFIX + "issued_ms"] as Long, values[PREFIX + "deadline_ms"] as Long,
                values[PREFIX + "boot"] as Int, values[PREFIX + "target_uid"] as Int,
                values[PREFIX + "buffer_issued_ms"] as Long,
                if (PREFIX + "resume_from_nonce" in values) values[PREFIX + "resume_from_nonce"] as String else null,
                if (PREFIX + "resume_process_pid" in values) values[PREFIX + "resume_process_pid"] as Int else -1,
                if (PREFIX + "resume_audio_session_id" in values) values[PREFIX + "resume_audio_session_id"] as Int else -1)
        }.getOrNull()
    }
}

/** One component attempt; revocation wins even if startup is already sending its tag. */
internal class TidalComponentClaimRelay<T : Any>(private val onClear: (Boolean) -> Unit = {},
    private val send: (T, String) -> Unit) {
    private val retired = AtomicBoolean()
    private val lock = Any()
    private var attempted = false
    private var sending = false
    private var target: T? = null

    fun attempt(value: T, nonce: String): Boolean {
        synchronized(lock) {
            if (retired.get() || attempted) return false
            attempted = true
            sending = true
            target = value // Retain even if the API throws after partial application.
        }
        try { send(value, nonce); return true }
        finally {
            synchronized(lock) { sending = false }
            if (retired.get()) clear()
        }
    }

    fun retire() { retired.set(true) }

    fun clear(): Boolean {
        retired.set(true)
        val value = synchronized(lock) {
            // The startup finally clears after its send; never block another thread behind that API.
            if (sending) return false
            (target ?: return false).also { target = null }
        }
        val accepted = runCatching { send(value, TidalComponentClaimPermit.CLEAR) }.isSuccess
        runCatching { onClear(accepted) }
        return true
    }
}
