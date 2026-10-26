/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeGameModeEngineTest {
    private class Fixture : NativeGameModePorts {
        val peer = listOf(1, 2, 3, 4, 5, 6)
        var control = GameControlOwner(peer, 4, Any())
        var now = 1000L
        var value = GameNativeReceipt(NativeGameModePolicy.Host(peer, 7, 0, 1, 900, 1500,
            true, true, true, true, true, false), supported = true, encoderGeneration = 9, acl = 64)
        val effects = mutableListOf<String>()
        val clocks = mutableListOf<NativeGameModePolicy.Clock>()
        val dirtyBeforeWrites = mutableListOf<Boolean>()
        var failTarget = false
        var request = 20L
        var queries = 0
        var originalBaseline = 320
        var refusePrepare = false
        val engine = NativeGameModeEngine(this)
        override fun nowMs() = now
        override fun owns(owner: GameControlOwner) = owner.session == control.session &&
            owner.peer == control.peer && owner.socket === control.socket
        override suspend fun snapshot(heartbeatSerial: Long): GameNativeReceipt {
            queries++
            if (heartbeatSerial > 0) effects += "heartbeat:$heartbeatSerial"
            return value
        }
        override suspend fun prepare(owner: GameControlOwner, route: GameNativeReceipt): GameNativeReceipt {
            effects += "prepare"
            if (refusePrepare) return value.copy(submittedSerial = 0)
            value = value.copy(serial = 12, submittedSerial = 12, phase = 11, holding = true,
                baselineKnown = true, baseline = originalBaseline, currentKnown = true, current = 96,
                restorePending = originalBaseline != 96,
                requestPeer = peer, requestMedia = 7, requestEncoder = 9, requestAcl = 64)
            return value
        }
        override suspend fun target(owner: GameControlOwner, route: GameNativeReceipt,
            serial: Long, targetMs: Int): GameTargetWrite {
            effects += "target:$targetMs"
            dirtyBeforeWrites += engine.remoteRestorePending
            return GameTargetWrite(!failTarget, now)
        }
        override suspend fun beginClock(serial: Long, owner: GameControlOwner, route: GameNativeReceipt,
            clock: NativeGameModePolicy.Clock, restoring: Boolean): GameNativeReceipt {
            effects += "clock:$serial"
            clocks += clock
            value = value.copy(clockRequest = ++request, clockPending = true)
            return value
        }
        override suspend fun cancelClock(serial: Long): GameNativeReceipt {
            effects += "cancel:$serial"
            value = value.copy(clockPending = false, clockInterval = 10,
                clockPhase = if (value.clockPhase in 2..3) 5 else value.clockPhase)
            return value
        }
        override suspend fun restore(serial: Long): GameNativeReceipt {
            effects += "restore:$serial"
            value = value.copy(phase = 8, holding = false, busy = false,
                currentKnown = true, current = value.baseline, restorePending = false)
            return value
        }
        fun report(delayTenths: Int) {
            value = value.copy(host = value.host.copy(delaySerial = value.host.delaySerial + 1,
                delayReceivedMs = now, delayReceivedNs = now * 1_000_000, delayTenths = delayTenths))
        }
        fun completeClock() {
            value = value.copy(clockPending = false, clockPhase = 4, clockSerial = value.clockSerial + 1,
                clockEncoder = 9, clockPeer = peer, clockInterval = 10)
        }
        suspend fun step(desired: Boolean = true, blocked: Boolean = false,
            eligible: Boolean = true): NativeGameModeController.State {
            now += 100
            return engine.tick(desired, eligible, blocked, control)
        }
        suspend fun start(): NativeGameModeController.State {
            assertFalse(step().active)
            assertFalse(step().active)
            report(280)
            assertFalse(step().active)
            completeClock()
            return step()
        }
    }

    @Test fun activeRequiresVerifiedHoldFreshReportAndTheNewOwnedClockCompletion() = runBlocking {
        val f = Fixture()
        f.step()
        assertEquals(listOf("prepare"), f.effects)
        f.step()
        assertEquals(listOf(true), f.dirtyBeforeWrites)
        assertEquals(listOf("target:60"), f.effects.filter { it.startsWith("target:") })
        // A late report received before the write is not confirmation, even with a new serial.
        f.value = f.value.copy(host = f.value.host.copy(delaySerial = 2, delayReceivedMs = 1))
        assertFalse(f.step().active)
        assertTrue(f.clocks.isEmpty())
        f.report(280)
        f.value = f.value.copy(clockSerial = 40, clockPhase = 4, clockEncoder = 9, clockPeer = f.peer)
        assertFalse(f.step().active)
        assertEquals(listOf(NativeGameModePolicy.Clock(9, 22)), f.clocks)
        assertFalse(f.step().active) // Pending token cannot borrow old Completed state.
        f.value = f.value.copy(clockPending = false)
        assertFalse(f.step().active) // Old actual clock serial is still not this request.
        f.completeClock()
        assertTrue(f.step().active)
    }

    @Test fun normalOffAfterLongPlaybackGetsItsOwnDeadlineAndRestoresTheExactBaseline() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        f.now += 60_000
        val stopping = f.step(desired = false)
        assertTrue(stopping.restoring)
        assertFalse(stopping.active)
        assertEquals(listOf("target:60", "target:150"), f.effects.filter { it.startsWith("target:") })
        f.report(900)
        f.step(desired = false)
        assertEquals(NativeGameModePolicy.Clock(9, 60), f.clocks.last())
        f.completeClock()
        f.step(desired = false)
        val finished = f.step(desired = false)
        assertFalse(finished.active)
        assertFalse(finished.restoring)
        assertFalse(finished.remoteRestorePending)
        assertEquals(320, f.value.current)
        assertTrue(f.effects.contains("restore:12"))
    }

    @Test fun pauseOrMicrophoneAfterLongPlaybackRestoresLocallyWithoutWaitingForAReport() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        f.now += 60_000
        f.value = f.value.copy(host = f.value.host.copy(streaming = false, encoderReady = false,
            scoOrMicrophone = true))
        val stopping = f.step(blocked = true)
        assertFalse(stopping.active)
        assertTrue(f.effects.contains("cancel:12"))
        assertTrue(f.effects.contains("target:150"))
        assertTrue(f.effects.contains("restore:12"))
        val finished = f.step(blocked = true)
        assertTrue(finished.remoteRestorePending) // Missing remote report is not a successful receipt.
        assertEquals(NativeGameModeController.Issue.RESTORE_UNCONFIRMED, finished.issue)
        assertEquals(320, f.value.current)
    }

    @Test fun aPossiblySentTargetIsOwnedBeforeTheWriteAndFailureStillRestores() = runBlocking {
        val f = Fixture()
        f.step()
        f.failTarget = true
        assertFalse(f.step().active)
        assertEquals(listOf(true, true), f.dirtyBeforeWrites)
        assertEquals(listOf("target:60", "target:150"), f.effects.filter { it.startsWith("target:") })
        assertTrue(f.effects.contains("restore:12"))
        assertTrue(f.engine.remoteRestorePending)
    }

    @Test fun replacementSocketAndControlSessionNeverReceiveOldCleanup() = runBlocking {
        val f = Fixture()
        f.step(); f.step() // Remote60 may now be in force.
        f.control = GameControlOwner(f.peer, 5, Any())
        f.step()
        assertEquals(listOf("target:60"), f.effects.filter { it.startsWith("target:") })
        assertTrue(f.effects.contains("restore:12"))
        val stopped = f.step()
        assertFalse(stopped.active)
        assertTrue(stopped.remoteRestorePending)
    }

    @Test fun equalNumericDelayIsFreshBySerialAndUnavailableOffOnlyQueriesOnce() = runBlocking {
        val f = Fixture()
        val off = f.step(desired = false)
        assertTrue(off.available)
        f.step(desired = false)
        assertEquals(1, f.queries)
        assertTrue(f.effects.isEmpty())
        f.step(); f.step()
        f.report(1500)
        f.step()
        assertEquals(NativeGameModePolicy.Clock(11, 80), f.clocks.single())
    }

    @Test fun noAacpOwnerWhileOffDoesNotContinuouslyQueryOrAcquireAudio() = runBlocking {
        val f = Fixture()
        repeat(5) { f.engine.tick(false, true, false, null) }
        assertEquals(1, f.queries)
        assertTrue(f.effects.isEmpty())
    }

    @Test fun missingFreshReportNeverBecomesActiveAndExpiredRestorationStopsHeartbeat() = runBlocking {
        val f = Fixture()
        f.step(); f.step()
        f.now += 2001
        assertFalse(f.step().active)
        assertTrue(f.effects.contains("target:150"))
        assertTrue(f.effects.contains("restore:12"))
        f.step()
        val before = f.effects.count { it.startsWith("heartbeat:") }
        repeat(5) { assertFalse(f.step().active) }
        assertEquals(before, f.effects.count { it.startsWith("heartbeat:") })
    }

    @Test fun staleClockGenerationOrRequestTokenCannotCompleteTheTransition() = runBlocking {
        val f = Fixture()
        f.step(); f.step(); f.report(280); f.step(); f.completeClock()
        val complete = f.value
        f.value = complete.copy(clockEncoder = 99)
        assertFalse(f.step().active)
        f.value = complete.copy(clockRequest = complete.clockRequest - 1)
        assertFalse(f.step().active)
        f.value = complete.copy(clockPeer = f.peer.reversed())
        assertFalse(f.step().active)
        f.value = complete
        assertTrue(f.step().active)
    }

    @Test fun queuedTargetRequiresCurrentExactHoldingProofAndFinalCancellationFence() = runBlocking {
        val f = Fixture()
        f.step()
        val ready = f.value.copy(observedMs = f.now, leaseDeadlineMs = f.now + 3000)
        assertTrue(NativeGameTargetGuard.preflight(f.control, ready, 12, ready, 60, f.now))
        assertFalse(NativeGameTargetGuard.preflight(f.control, ready, 12, ready.copy(holding = false), 60, f.now))
        assertFalse(NativeGameTargetGuard.preflight(f.control, ready, 12,
            ready.copy(host = ready.host.copy(scoOrMicrophone = true)), 60, f.now))
        assertFalse(NativeGameTargetGuard.preflight(f.control, ready, 12,
            ready.copy(requestEncoder = 100), 60, f.now))
        assertFalse(NativeGameTargetGuard.preflight(f.control, ready, 12,
            ready.copy(leaseDeadlineMs = f.now), 60, f.now))
        assertFalse(NativeGameTargetGuard.preflight(f.control, ready, 12, ready, 60, f.now + 101))
        assertTrue(NativeGameTargetGuard.preflight(f.control, ready, 12,
            ready.copy(holding = false, host = ready.host.copy(streaming = false, encoderReady = false,
                scoOrMicrophone = true)), 150, f.now))
        assertTrue(NativeGameTargetGuard.finalWrite(true, 60, 1000, 1000, 5, 5, true, 2000))
        assertFalse(NativeGameTargetGuard.finalWrite(true, 60, 1000, 1000, 5, 6, true, 2000))
        assertFalse(NativeGameTargetGuard.finalWrite(true, 60, 1101, 1000, 5, 5, true, 2000))
        assertFalse(NativeGameTargetGuard.finalWrite(false, 150, 1000, 1000, 5, 5, false, 0))
        assertTrue(NativeGameTargetGuard.finalWrite(true, 150, 1000, 1000, 5, 6, false, 0))
    }

    @Test fun sameSessionResumeRetriesUnconfirmed150OnceBeforeApplyingAgain() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        f.value = f.value.copy(host = f.value.host.copy(streaming = false, startedMs = 0))
        f.step(); f.step()
        assertTrue(f.engine.remoteRestorePending)
        assertFalse(f.engine.nativeObligation)
        val before = f.effects.count { it == "target:150" }
        repeat(3) { f.step() }
        assertEquals(before, f.effects.count { it == "target:150" })
        f.value = f.value.copy(host = f.value.host.copy(streaming = true, startedMs = f.now))
        f.step()
        assertEquals(before + 1, f.effects.count { it == "target:150" })
        f.report(900)
        f.step(); f.step()
        assertFalse(f.engine.remoteRestorePending)
        assertEquals(0, f.engine.holdSerial.toInt())
    }

    @Test fun aSecondUnselectedControllerCannotCancelTheSelectedControllersHold() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        val second = NativeGameModeEngine(f)
        val another = GameControlOwner(f.peer.reversed(), 90, Any())
        val before = f.effects.toList()
        assertFalse(second.tick(false, true, false, another).active)
        assertFalse(second.tick(true, true, false, another).active)
        second.shutdown(another)
        assertEquals(before, f.effects)
        assertEquals(96, f.value.current)
        assertTrue(f.step().active)
    }

    @Test fun originalBaseline96StillHoldsAndCannotMasqueradeAsARestoredReceipt() = runBlocking {
        val f = Fixture().apply { originalBaseline = 96 }
        assertTrue(f.start().active)
        assertFalse(f.value.restorePending)
        assertFalse(f.value.restored)
        assertTrue(f.step(desired = false).restoring)
        f.report(1500) // A fresh150ms report needs no finite clock adjustment.
        f.step(desired = false); val off = f.step(desired = false)
        assertFalse(off.restoring)
        assertFalse(off.remoteRestorePending)
        assertEquals(96, f.value.current)
        assertTrue(f.value.restored)
    }

    @Test fun microphoneRecoveryRetriesSameOwnerCleanupEvenWithoutANewStart() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        f.value = f.value.copy(host = f.value.host.copy(scoOrMicrophone = true))
        f.step(blocked = true); f.step(blocked = true)
        val attempts = f.effects.count { it == "target:150" }
        f.value = f.value.copy(host = f.value.host.copy(scoOrMicrophone = false))
        f.step(blocked = false)
        assertEquals(attempts + 1, f.effects.count { it == "target:150" })
        f.report(900)
        f.step(); f.step()
        assertFalse(f.engine.remoteRestorePending)
    }

    @Test fun anExpiredHoldCannotBorrowAZeroAdjustmentReportToBecomeActive() = runBlocking {
        val f = Fixture()
        f.step(); f.step()
        f.value = f.value.copy(holding = false, current = 320, restorePending = false)
        f.report(600)
        val expired = f.step()
        assertFalse(expired.active)
        assertTrue(f.effects.contains("target:150"))
        assertTrue(f.effects.contains("restore:12"))
        assertTrue(f.clocks.isEmpty())
    }

    @Test fun equalFlushBaselineDoesNotRetireAnInFlightClockCancellation() = runBlocking {
        val f = Fixture().apply { originalBaseline = 96 }
        assertTrue(f.start().active)
        f.step(blocked = true)
        f.value = f.value.copy(clockPending = true)
        assertFalse(f.value.restored)
        f.step(blocked = true)
        assertEquals(12, f.engine.holdSerial.toInt())
        assertTrue(f.engine.nativeObligation)
        f.value = f.value.copy(clockPending = false, clockInterval = 11)
        assertFalse(f.value.restored)
        f.value = f.value.copy(clockInterval = 10)
        assertTrue(f.value.restored)
    }

    @Test fun prepareFailureDoesNotHammerTheControllerUntilDeliberateRetry() = runBlocking {
        val f = Fixture().apply { refusePrepare = true }
        repeat(8) { assertFalse(f.step().active) }
        assertEquals(1, f.effects.count { it == "prepare" })
        f.step(desired = false)
        f.step(desired = true)
        assertEquals(2, f.effects.count { it == "prepare" })
    }

    @Test fun successfulCleanupAfterMissingReportDoesNotAutomaticallyResend60() = runBlocking {
        val f = Fixture()
        f.step(); f.step()
        f.now += 2001
        f.step() // Missing On report; cancel,150,exact native restore.
        f.report(900)
        f.step()
        repeat(8) {
            val stopped = f.step()
            assertFalse(stopped.active)
            assertEquals(NativeGameModeController.Issue.REPORT_UNCONFIRMED, stopped.issue)
        }
        assertEquals(1, f.effects.count { it == "target:60" })
        assertEquals(1, f.effects.count { it == "prepare" })
        f.value = f.value.copy(host = f.value.host.copy(startedMs = f.now))
        f.now += 500
        f.step()
        assertEquals(2, f.effects.count { it == "prepare" })
    }

    @Test fun settledOnClockRefusalImmediatelyRollsBackInsteadOfWaitingForDeadline() = runBlocking {
        val f = Fixture()
        f.step(); f.step(); f.report(1540); f.step()
        f.value = f.value.copy(clockPending = false, clockPhase = 0, clockSerial = 0)
        val rejected = f.step()
        assertFalse(rejected.active)
        assertEquals(NativeGameModeController.Issue.CLOCK_UNCONFIRMED, rejected.issue)
        assertTrue(f.effects.contains("target:150"))
        assertTrue(f.effects.contains("restore:12"))
        assertTrue(rejected.remoteRestorePending)
    }

    @Test fun settledOffClockRefusalImmediatelyRestoresBaselineWithoutClaimingRemoteCompletion() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        f.step(desired = false); f.report(740); f.step(desired = false)
        f.value = f.value.copy(clockPending = false, clockPhase = 0, clockSerial = 0)
        val rejected = f.step(desired = false)
        assertFalse(rejected.active)
        assertEquals(NativeGameModeController.Issue.CLOCK_UNCONFIRMED, rejected.issue)
        assertTrue(f.effects.contains("restore:12"))
        assertEquals(320, f.value.current)
        val stopped = f.step(desired = false)
        assertTrue(stopped.remoteRestorePending)
        assertEquals(NativeGameModeController.Issue.CLOCK_UNCONFIRMED, stopped.issue)
    }

    @Test fun offClockDeadlineExplicitlyReleasesHoldingInsteadOfWaitingForLeaseExpiry() = runBlocking {
        val f = Fixture()
        assertTrue(f.start().active)
        f.step(desired = false); f.report(740); f.step(desired = false)
        f.now += 15_001
        val timedOut = f.step(desired = false)
        assertFalse(timedOut.active)
        assertTrue(f.effects.contains("restore:12"))
        assertTrue(timedOut.remoteRestorePending)
    }
}
