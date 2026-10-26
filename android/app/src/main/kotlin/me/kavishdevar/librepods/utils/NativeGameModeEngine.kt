/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

internal data class GameControlOwner(val peer: List<Int>, val session: Long, val socket: Any)
internal data class GameNativeReceipt(
    val host: NativeGameModePolicy.Host, val supported: Boolean = false, val busy: Boolean = false,
    val phase: Int = 0, val serial: Long = 0, val submittedSerial: Long = 0, val holding: Boolean = false,
    val baselineKnown: Boolean = false, val baseline: Int = 0, val currentKnown: Boolean = false,
    val current: Int = 0, val restorePending: Boolean = false, val encoderGeneration: Long = 0,
    val acl: Int = 0xffff, val requestPeer: List<Int> = emptyList(), val requestMedia: Long = 0,
    val requestEncoder: Long = 0, val requestAcl: Int = 0xffff,
    val clockRequest: Long = 0, val clockPending: Boolean = false, val clockPhase: Int = 0,
    val clockSerial: Long = 0, val clockEncoder: Long = 0, val clockPeer: List<Int> = emptyList(),
    val clockInterval: Int = 10, val observedMs: Long = 0, val leaseDeadlineMs: Long = 0,
    val noControllerMutation: Boolean = false,
) {
    val restored get() = !busy && !holding && !restorePending && !clockPending && clockInterval == 10 &&
        baselineKnown && currentKnown && baseline == current
    fun hasExpectedFlush(keepBaseline: Boolean) = currentKnown &&
        if (keepBaseline) baselineKnown && current == baseline else current == 96
}
internal data class GameTargetWrite(val sent: Boolean, val observedAfterMs: Long,
    val observedAfterNs: Long = observedAfterMs * 1_000_000, val mayHaveReachedRemote: Boolean = true)
internal object NativeGameTargetGuard {
    fun preflight(owner: GameControlOwner, route: GameNativeReceipt, serial: Long,
        value: GameNativeReceipt, target: Int, nowMs: Long, keepFlushBaseline: Boolean = false): Boolean {
        if (target !in setOf(60, 150) || serial <= 0 || value.serial != serial ||
            value.requestPeer != owner.peer || value.requestMedia != route.host.mediaGeneration ||
            value.requestEncoder != route.encoderGeneration || value.requestAcl != route.acl ||
            value.host.peer != owner.peer || value.host.mediaGeneration != route.host.mediaGeneration ||
            nowMs - value.observedMs !in 0..100) return false
        return target == 150 || (value.holding && !value.busy && value.hasExpectedFlush(keepFlushBaseline) &&
            value.host.streaming && value.host.encoderReady && !value.host.scoOrMicrophone &&
            value.encoderGeneration == route.encoderGeneration && value.acl == route.acl &&
            value.leaseDeadlineMs > nowMs)
    }
    fun finalWrite(owns: Boolean, target: Int, nowMs: Long, observedMs: Long, expectedEpoch: Long,
        currentEpoch: Long, applyAllowed: Boolean, leaseDeadlineMs: Long) = owns &&
        nowMs - observedMs in 0..100 && (target == 150 || (target == 60 && applyAllowed &&
            expectedEpoch == currentEpoch && nowMs < leaseDeadlineMs))
}
internal interface NativeGameModePorts {
    fun nowMs(): Long
    fun owns(owner: GameControlOwner): Boolean
    suspend fun snapshot(heartbeatSerial: Long): GameNativeReceipt?
    suspend fun prepare(owner: GameControlOwner, route: GameNativeReceipt): GameNativeReceipt?
    suspend fun target(owner: GameControlOwner, route: GameNativeReceipt, serial: Long, targetMs: Int): GameTargetWrite
    suspend fun beginClock(serial: Long, owner: GameControlOwner, route: GameNativeReceipt,
        clock: NativeGameModePolicy.Clock, restoring: Boolean): GameNativeReceipt?
    suspend fun cancelClock(serial: Long): GameNativeReceipt?
    suspend fun restore(serial: Long): GameNativeReceipt?
}

/** One transition at a time. The Android wrapper supplies only current-session effects. */
internal class NativeGameModeEngine(private val ports: NativeGameModePorts,
    private val keepFlushBaseline: Boolean = false) {
    private enum class Stage { IDLE, HOLD, REPORT_ON, CLOCK_ON, ACTIVE, REPORT_OFF, CLOCK_OFF, RESTORE, UNRESOLVED }
    private var stage = Stage.IDLE
    private var owner: GameControlOwner? = null
    private var route: GameNativeReceipt? = null
    @Volatile var holdSerial = 0L; private set
    @Volatile var remoteRestorePending = false; private set
    @Volatile var nativeObligation = false; private set
    private var lastControl: GameControlOwner? = null
    private var capabilityKnown = false
    private var available = false
    private var beforeReport = 0L
    private var sentAt = 0L
    private var sentAtNs = 0L
    private var phaseDeadline = 0L
    private var transitionDeadline = 0L
    private var clockRequest = 0L
    private var clockBefore = 0L
    private var issue = NativeGameModeController.Issue.NONE
    private var restoreReportSeen = false
    private var restoreWriteMayHaveReachedRemote = false
    private var restoreNeedsClock = false
    private var restoreRetryRequested = false
    private var lastRestoreStart = Long.MIN_VALUE
    private var wasBlocked = false
    private data class Attempt(val control: GameControlOwner, val media: Long, val encoder: Long, val start: Long)
    private var failedAttempt: Attempt? = null
    private var failedIssue: NativeGameModeController.Issue? = null
    private var previousDesired = false
    private var lastReceipt: GameNativeReceipt? = null

    private fun sameControl(a: GameControlOwner?, b: GameControlOwner?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.socket === b.socket && a.session == b.session && a.peer == b.peer
    }
    private fun policyOwner(): NativeGameModePolicy.Owner? = owner?.let { current ->
        route?.let { NativeGameModePolicy.Owner(current.peer, it.host.mediaGeneration, current.session) }
    }
    private fun sameRoute(value: GameNativeReceipt): Boolean {
        val original = route ?: return false
        return value.host.peer == original.host.peer && value.host.mediaGeneration == original.host.mediaGeneration &&
            value.encoderGeneration == original.encoderGeneration && value.acl == original.acl
    }
    private fun sameNativeOwner(value: GameNativeReceipt): Boolean {
        val original = route ?: return false
        return value.serial == holdSerial && value.requestPeer == original.host.peer &&
            value.requestMedia == original.host.mediaGeneration && value.requestEncoder == original.encoderGeneration &&
            value.requestAcl == original.acl
    }
    private fun verifiedHold(value: GameNativeReceipt) = sameNativeOwner(value) && value.holding &&
        !value.busy && value.baselineKnown && value.hasExpectedFlush(keepFlushBaseline)
    private fun sameAttempt(control: GameControlOwner?, value: GameNativeReceipt?): Boolean {
        val failed = failedAttempt ?: return false
        return value != null && sameControl(failed.control, control) && failed.media == value.host.mediaGeneration &&
            failed.encoder == value.encoderGeneration && failed.start == value.host.startedMs
    }
    private fun recordFailure(reason: NativeGameModeController.Issue) {
        val captured = owner
        val original = route
        if (captured != null && original != null && failedAttempt == null) {
            failedAttempt = Attempt(captured, original.host.mediaGeneration, original.encoderGeneration, original.host.startedMs)
            failedIssue = reason
        }
        issue = failedIssue ?: reason
    }
    private fun ownsRemote(control: GameControlOwner?, value: GameNativeReceipt?): Boolean {
        val current = owner ?: return false
        val expected = policyOwner() ?: return false
        return sameControl(current, control) && ports.owns(current) && value != null &&
            NativeGameModePolicy.canRestore(expected, current.session, value.host)
    }
    private fun state(desired: Boolean) = NativeGameModeController.State(desiredOn = desired,
        available = available, active = stage == Stage.ACTIVE, restoring = stage in RESTORING,
        remoteRestorePending = remoteRestorePending, issue = issue)

    suspend fun tick(desired: Boolean, eligible: Boolean, blocked: Boolean,
        control: GameControlOwner?): NativeGameModeController.State {
        val becameUnblocked = wasBlocked && !blocked
        wasBlocked = blocked
        val desiredChanged = desired != previousDesired
        if (desiredChanged) { failedAttempt = null; failedIssue = null }
        previousDesired = desired
        val changed = !sameControl(lastControl, control)
        if (changed) {
            lastControl = control; capabilityKnown = false; available = false
            restoreRetryRequested = false
        } else if (stage in RESTORING && (becameUnblocked || desiredChanged)) {
            restoreRetryRequested = true
        }
        if (!eligible) available = false
        if ((eligible && (!capabilityKnown || desired)) || holdSerial > 0 || remoteRestorePending) {
            val renew = if (stage in RENEWING) holdSerial else 0
            lastReceipt = ports.snapshot(renew)
            lastReceipt?.let { capabilityKnown = true; available = eligible && it.supported }
        }
        val value = lastReceipt
        nativeObligation = value != null && holdSerial > 0 && sameNativeOwner(value) &&
            (value.holding || value.busy || value.restorePending || value.clockPending || value.clockPhase in 2..3)
        if (stage in setOf(Stage.REPORT_ON, Stage.CLOCK_ON, Stage.ACTIVE) &&
            (value == null || !verifiedHold(value))) {
            recordFailure(NativeGameModeController.Issue.REQUEST_FAILED)
            beginRestore(control, value, forced = true)
        }
        if (stage != Stage.IDLE && stage != Stage.UNRESOLVED &&
            (!desired || blocked || !eligible || !sameControl(owner, control) || !ports.owns(owner!!) ||
                value == null || !sameRoute(value) || !value.host.streaming || value.host.scoOrMicrophone)) {
            if (stage !in RESTORING) beginRestore(control, value,
                forced = blocked || !eligible || value == null || !value.host.streaming ||
                    value.host.scoOrMicrophone || !sameControl(owner, control) || !sameRoute(value))
        }
        if (stage != Stage.IDLE && stage != Stage.ACTIVE && stage != Stage.UNRESOLVED &&
            ports.nowMs() >= transitionDeadline) {
            if (stage !in RESTORING) {
                recordFailure(NativeGameModeController.Issue.REQUEST_FAILED)
                beginRestore(control, value, forced = true)
            }
            else if (stage == Stage.REPORT_OFF || stage == Stage.CLOCK_OFF) {
                recordFailure(NativeGameModeController.Issue.CLOCK_UNCONFIRMED)
                releaseNative()
            } else unresolved(NativeGameModeController.Issue.RESTORE_UNCONFIRMED)
        }
        when (stage) {
            Stage.IDLE -> {
                if (!desired) issue = NativeGameModeController.Issue.NONE
                else if (!eligible || !available) issue = NativeGameModeController.Issue.UNSUPPORTED
                else if (blocked) issue = NativeGameModeController.Issue.BUSY
                else if (control == null || value == null || !ports.owns(control))
                    issue = NativeGameModeController.Issue.WAITING_FOR_AUDIO
                else if (sameAttempt(control, value)) issue = failedIssue ?: NativeGameModeController.Issue.REQUEST_FAILED
                else {
                    failedAttempt = null; failedIssue = null
                    val expected = NativeGameModePolicy.Owner(control.peer, value.host.mediaGeneration, control.session)
                    if (!NativeGameModePolicy.canApply(expected, control.session, value.host, ports.nowMs()))
                        issue = when {
                            !value.host.streaming -> NativeGameModeController.Issue.WAITING_FOR_AUDIO
                            value.host.delayTenths !in 1..65534 -> NativeGameModeController.Issue.WAITING_FOR_TIMING
                            value.host.delayTenths < 600 -> NativeGameModeController.Issue.UNSUPPORTED
                            else -> NativeGameModeController.Issue.WAITING_FOR_AUDIO
                        }
                    else {
                        owner = control; route = value; issue = NativeGameModeController.Issue.NONE
                        transitionDeadline = ports.nowMs() + 15_000
                        val result = ports.prepare(control, value)
                        holdSerial = result?.submittedSerial ?: 0
                        if (holdSerial > 0) { stage = Stage.HOLD; nativeObligation = true }
                        else {
                            recordFailure(NativeGameModeController.Issue.REQUEST_FAILED)
                            owner = null; route = null
                        }
                    }
                }
            }
            Stage.HOLD -> {
                if (value != null && verifiedHold(value)) {
                    beforeReport = value.host.delaySerial
                    // A failed write can still have reached the remote; cleanup owns it before attempting IO.
                    val wasRemoteRestorePending = remoteRestorePending
                    remoteRestorePending = true
                    val write = ports.target(owner!!, route!!, holdSerial, 60)
                    if (!write.sent && !write.mayHaveReachedRemote) remoteRestorePending = wasRemoteRestorePending
                    sentAt = write.observedAfterMs
                    sentAtNs = write.observedAfterNs
                    phaseDeadline = sentAt + 2000
                    if (write.sent) stage = Stage.REPORT_ON
                    else {
                        recordFailure(NativeGameModeController.Issue.REQUEST_FAILED)
                        beginRestore(control, value, forced = true)
                    }
                } else if (value != null && sameNativeOwner(value) && value.phase in setOf(9, 10)) {
                    recordFailure(NativeGameModeController.Issue.REQUEST_FAILED)
                    beginRestore(control, value, forced = true)
                }
            }
            Stage.REPORT_ON, Stage.REPORT_OFF -> {
                val restoring = stage == Stage.REPORT_OFF
                val expected = policyOwner()
                val fresh = expected != null && value != null && ownsRemote(control, value) &&
                    if (restoring) NativeGameModePolicy.freshRestoreReport(expected, owner!!.session,
                        value.host, beforeReport, sentAt, ports.nowMs(), sentAtNs)
                    else NativeGameModePolicy.freshReport(expected, owner!!.session, value.host,
                        beforeReport, sentAt, ports.nowMs(), sentAtNs)
                if (fresh) {
                    if (restoring) restoreReportSeen = true
                    val clock = NativeGameModePolicy.clock(value.host.delayTenths, if (restoring) 150 else 60)
                    if (restoring && !restoreNeedsClock) {
                        remoteRestorePending = false
                        releaseNative()
                    } else if (clock == null) {
                        if (restoring) releaseNative() else {
                            recordFailure(NativeGameModeController.Issue.CLOCK_UNCONFIRMED)
                            beginRestore(control, value, forced = true)
                        }
                    } else if (clock.intervalTenths == 10 && clock.driftMs == 0) {
                        if (restoring) { remoteRestorePending = false; releaseNative() }
                        else { stage = Stage.ACTIVE; issue = NativeGameModeController.Issue.NONE }
                    } else if (!blocked && value.host.streaming && value.host.encoderReady && !value.host.scoOrMicrophone) {
                        clockBefore = value.clockSerial
                        val result = ports.beginClock(holdSerial, owner!!, route!!, clock, restoring)
                        clockRequest = result?.clockRequest ?: 0
                        if (clockRequest > 0) stage = if (restoring) Stage.CLOCK_OFF else Stage.CLOCK_ON
                        else if (restoring) releaseNative() else {
                            recordFailure(NativeGameModeController.Issue.CLOCK_UNCONFIRMED)
                            beginRestore(control, value, forced = true)
                        }
                    } else if (restoring) { remoteRestorePending = false; releaseNative() }
                } else if (ports.nowMs() >= phaseDeadline) {
                    issue = NativeGameModeController.Issue.REPORT_UNCONFIRMED
                    if (restoring) releaseNative() else {
                        recordFailure(NativeGameModeController.Issue.REPORT_UNCONFIRMED)
                        beginRestore(control, value, forced = true)
                    }
                }
            }
            Stage.CLOCK_ON, Stage.CLOCK_OFF -> {
                val restoring = stage == Stage.CLOCK_OFF
                if (value != null && sameNativeOwner(value) && value.clockRequest == clockRequest &&
                    !value.clockPending) {
                    if (value.clockPhase == 0 && value.clockSerial == 0L) {
                        // Main processed this request but encoder admission refused it; no clock was started.
                        recordFailure(NativeGameModeController.Issue.CLOCK_UNCONFIRMED)
                        if (restoring) releaseNative() else {
                            beginRestore(control, value, forced = true)
                        }
                    } else if (value.clockSerial > clockBefore && value.clockEncoder == route!!.encoderGeneration &&
                        value.clockPeer == owner!!.peer) {
                        if (value.clockPhase == 4 && value.clockInterval == 10) {
                            if (restoring) { remoteRestorePending = false; releaseNative() }
                            else { stage = Stage.ACTIVE; issue = NativeGameModeController.Issue.NONE }
                        } else if (value.clockPhase in setOf(5, 6)) {
                            recordFailure(NativeGameModeController.Issue.CLOCK_UNCONFIRMED)
                            if (restoring) releaseNative() else beginRestore(control, value, forced = true)
                        }
                    }
                }
            }
            Stage.ACTIVE -> {
                if (value == null || !sameNativeOwner(value) || !value.holding || !value.hasExpectedFlush(keepFlushBaseline))
                    beginRestore(control, value, forced = true)
            }
            Stage.RESTORE -> {
                if (remoteRestorePending && restoreWriteMayHaveReachedRemote && !restoreReportSeen && ownsRemote(control, value) &&
                    NativeGameModePolicy.freshRestoreReport(policyOwner()!!, owner!!.session, value!!.host,
                        beforeReport, sentAt, ports.nowMs(), sentAtNs)) {
                    restoreReportSeen = true
                    remoteRestorePending = false
                }
                // Native cleanup can finish before the remote response arrives. Keep the
                // existing bounded wait so a later, still-fresh report can settle it.
                if (finishUnchangedPrepare(value)) return state(desired)
                if (value != null && sameNativeOwner(value) && value.restored && !remoteRestorePending)
                    finishRestore()
                else if (ports.nowMs() >= phaseDeadline) unresolved(NativeGameModeController.Issue.RESTORE_UNCONFIRMED)
            }
            Stage.UNRESOLVED -> {
                if (finishUnchangedPrepare(value)) return state(desired)
                if (value != null && sameNativeOwner(value) && value.restored) {
                    if (!remoteRestorePending) finishRestore()
                    else if (!blocked && ownsRemote(control, value) && value.host.streaming &&
                        (value.host.startedMs != lastRestoreStart || becameUnblocked || desiredChanged || restoreRetryRequested))
                        beginRestore(control, value, forced = false)
                }
            }
        }
        return state(desired)
    }

    private suspend fun beginRestore(control: GameControlOwner?, value: GameNativeReceipt?, forced: Boolean) {
        if (finishUnchangedPrepare(value)) return
        restoreRetryRequested = false
        transitionDeadline = ports.nowMs() + if (forced) 3000 else 15_000
        lastRestoreStart = value?.host?.startedMs ?: Long.MIN_VALUE
        restoreNeedsClock = !forced && value?.holding == true
        issue = if (ownsRemote(control, value)) issue else NativeGameModeController.Issue.OWNER_LOST
        if (holdSerial > 0) ports.cancelClock(holdSerial)
        restoreReportSeen = false
        restoreWriteMayHaveReachedRemote = false
        if (remoteRestorePending && ownsRemote(control, value)) {
            beforeReport = value!!.host.delaySerial
            restoreWriteMayHaveReachedRemote = true
            val write = ports.target(owner!!, route!!, holdSerial, 150)
            restoreWriteMayHaveReachedRemote = write.sent || write.mayHaveReachedRemote
            sentAt = write.observedAfterMs
            sentAtNs = write.observedAfterNs
            phaseDeadline = sentAt + 2000
            if (write.sent && !forced) { stage = Stage.REPORT_OFF; return }
        }
        releaseNative()
    }

    private suspend fun releaseNative() {
        if (holdSerial > 0) ports.restore(holdSerial)
        stage = Stage.RESTORE
        phaseDeadline = ports.nowMs() + 2500
    }

    private fun unresolved(reason: NativeGameModeController.Issue) {
        stage = Stage.UNRESOLVED
        issue = failedIssue ?: reason
    }

    private fun finishUnchangedPrepare(value: GameNativeReceipt?): Boolean {
        if (remoteRestorePending || holdSerial <= 0 || value == null || !sameNativeOwner(value) ||
            !value.noControllerMutation) return false
        // This exact Prepare never touched the controller; there is no readback to restore.
        recordFailure(NativeGameModeController.Issue.REQUEST_FAILED)
        finishRestore()
        return true
    }

    private fun finishRestore() {
        restoreRetryRequested = false
        holdSerial = 0; nativeObligation = false
        stage = Stage.IDLE; owner = null; route = null
        issue = failedIssue ?: NativeGameModeController.Issue.NONE
    }

    suspend fun shutdown(control: GameControlOwner?) {
        if (holdSerial == 0L && !remoteRestorePending) return
        beginRestore(control, lastReceipt, forced = true)
    }

    private companion object {
        val RESTORING = setOf(Stage.REPORT_OFF, Stage.CLOCK_OFF, Stage.RESTORE)
        val RENEWING = setOf(Stage.HOLD, Stage.REPORT_ON, Stage.CLOCK_ON, Stage.ACTIVE,
            Stage.REPORT_OFF, Stage.CLOCK_OFF)
    }
}
