/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku;

import static org.junit.Assert.*;
import android.media.session.PlaybackState;
import org.junit.Test;

public class CallMediaLeaseTest {
    private static final Object SESSION = new Object();
    @Test public void initialPauseIsAllowedButAPauseAfterPlayingRetiresTheLease() {
        assertFalse(CallMediaLease.retiresPlayback(false, PlaybackState.STATE_PAUSED));
        assertFalse(CallMediaLease.retiresPlayback(false, PlaybackState.STATE_PLAYING));
        assertFalse(CallMediaLease.retiresPlayback(true, PlaybackState.STATE_PLAYING));
        assertTrue(CallMediaLease.retiresPlayback(true, PlaybackState.STATE_PAUSED));
        assertTrue(CallMediaLease.retiresPlayback(false, PlaybackState.STATE_STOPPED));
        assertTrue(CallMediaLease.retiresPlayback(false, PlaybackState.STATE_NONE));
    }
    private static CallMediaLease.Snapshot snapshot(boolean playing) {
        return new CallMediaLease.Snapshot(SESSION, 10234, 81, 72, playing);
    }
    private static final class Fake implements CallMediaLease.Backend {
        CallMediaLease.Snapshot current = snapshot(true);
        final Object token = new Object();
        int reads, watched, stopped, entered, exited, exitMode, enteredUid;
        boolean reject, throwEnter, throwExit, absentExit, failRead, changeOnWatch, changeOnEnter, pauseOnWatch, pauseOnEnter, failReadAfterEnter;
        Object enteredToken, exitedToken;
        @Override public CallMediaLease.Snapshot current() throws Exception {
            reads++;
            if (failRead) throw new Exception("read unavailable");
            return current;
        }
        @Override public void watch() {
            watched++;
            if (changeOnWatch) current = null;
            if (pauseOnWatch) current = snapshot(false);
        }
        @Override public void stopWatching() { stopped++; }
        @Override public Object token() { return token; }
        @Override public boolean enter(int uid, Object value) throws Exception {
            entered++; enteredUid = uid; enteredToken = value;
            if (changeOnEnter) current = null;
            if (pauseOnEnter) current = snapshot(false);
            if (failReadAfterEnter) failRead = true;
            if (throwEnter) throw new Exception("reply lost after delivery");
            return !reject;
        }
        @Override public boolean exit(Object value, int mode) throws Exception {
            exited++; exitedToken = value; exitMode = mode;
            if (throwExit) throw new Exception("exit reply unavailable");
            return !absentExit;
        }
    }
    @Test public void noEligiblePlayerLeavesFocusUntouched() throws Exception {
        Fake backend = new Fake(); backend.current = null;
        assertNull(CallMediaLease.acquire(backend));
        assertEquals(0, backend.entered); assertEquals(0, backend.watched);
    }
    @Test public void malformedIdentityCannotAcquireFocus() throws Exception {
        for (CallMediaLease.Snapshot invalid : new CallMediaLease.Snapshot[]{
                new CallMediaLease.Snapshot(null, 10234, 81, 72, true),
                new CallMediaLease.Snapshot(SESSION, 0, 81, 72, true),
                new CallMediaLease.Snapshot(SESSION, 10234, 0, 72, true),
                new CallMediaLease.Snapshot(SESSION, 10234, 81, 0, true)}) {
            Fake backend = new Fake(); backend.current = invalid;
            assertNull(CallMediaLease.acquire(backend)); assertEquals(0, backend.entered);
        }
    }
    @Test public void normalEndRetainsOnlyTheUnchangedPlayingSession() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        assertTrue(lease.isActive()); assertEquals(10234, backend.enteredUid);
        assertTrue(lease.finish(true)); assertEquals(CallMediaLease.RETAIN, backend.exitMode);
        assertSame(backend.token, backend.enteredToken); assertSame(backend.token, backend.exitedToken);
        assertTrue(lease.isCleanupComplete()); assertFalse(lease.isActive());
        lease.finish(true); lease.close(); assertEquals(1, backend.exited); assertEquals(1, backend.stopped);
    }
    @Test public void cancellationNeverRetainsFocus() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        assertTrue(lease.finish(false)); assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
    @Test public void initiallyPausedSelectionDoesNotRetainFocusOnExit() throws Exception {
        Fake backend = new Fake(); backend.current = snapshot(false);
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertTrue(lease.isActive()); assertTrue(lease.finish(true));
        assertEquals(CallMediaLease.LOSE, backend.exitMode);
        // No Play command exists, but Android's admission can deliver focus gain.
    }
    @Test public void userCanStartTheExplicitlySelectedInitiallyPausedSession() throws Exception {
        Fake backend = new Fake(); backend.current = snapshot(false);
        CallMediaLease lease = CallMediaLease.acquire(backend);
        backend.current = snapshot(true); lease.poll(1000);
        assertTrue(lease.isActive()); assertTrue(lease.finish(true));
        assertEquals(CallMediaLease.RETAIN, backend.exitMode);
    }
    @Test public void trackRecreationWithinTheSameSelectedSessionDoesNotRetargetTheLease() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        backend.current = new CallMediaLease.Snapshot(SESSION, 10234, 91, 99, true);
        lease.poll(1000); assertTrue(lease.isActive()); assertTrue(lease.finish(true));
        assertEquals(CallMediaLease.RETAIN, backend.exitMode);
    }
    @Test public void differentUidOrMediaSessionCannotInheritFocus() throws Exception {
        for (CallMediaLease.Snapshot changed : new CallMediaLease.Snapshot[]{
                new CallMediaLease.Snapshot(new Object(), 10234, 81, 72, true),
                new CallMediaLease.Snapshot(SESSION, 10345, 81, 72, true)}) {
            Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
            backend.current = changed; lease.poll(1000);
            assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
            assertEquals(CallMediaLease.LOSE, backend.exitMode);
        }
    }
    @Test public void manualPauseRouteChangeAndDeadOwnerAllRetireWithoutPlaybackCommands() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        backend.current = null; lease.poll(1000);
        assertFalse(lease.isActive()); assertEquals(CallMediaLease.LOSE, backend.exitMode);
        backend.current = snapshot(true); lease.poll(2000);
        assertEquals(1, backend.entered); // The route may explicitly acquire a new lease; this one never replays itself.
    }
    @Test public void pollingHasBoundedFrameworkQueryFrequency() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        lease.poll(1000); int reads = backend.reads;
        lease.poll(1001); lease.poll(1249); assertEquals(reads, backend.reads);
        lease.poll(1250); assertEquals(reads + 1, backend.reads);
        lease.close();
    }
    @Test public void ownershipChangeBeforeDispatchDoesNotEnterIsolation() throws Exception {
        Fake backend = new Fake(); backend.changeOnWatch = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
        assertEquals(0, backend.entered); assertEquals(0, backend.exited);
    }
    @Test public void pauseBetweenInitialSelectionAndCallbackRegistrationCannotGainFocus() throws Exception {
        Fake backend = new Fake(); backend.pauseOnWatch = true;
        // There is deliberately no pause callback: the second synchronous read must reject it.
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
        assertEquals(0, backend.entered); assertEquals(0, backend.exited); assertEquals(1, backend.stopped);
    }
    @Test public void pauseDuringAdmissionReleasesTheDeliveredTokenWithoutRetainingFocus() throws Exception {
        Fake backend = new Fake(); backend.pauseOnEnter = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
        assertEquals(1, backend.entered); assertEquals(1, backend.exited);
        assertSame(backend.enteredToken, backend.exitedToken); assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
    @Test public void unreadablePostAdmissionOwnerDoesNotLeaveTheLeaseActive() throws Exception {
        Fake backend = new Fake(); backend.failReadAfterEnter = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
        assertEquals(1, backend.entered); assertEquals(1, backend.exited);
        assertSame(backend.enteredToken, backend.exitedToken); assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
    @Test public void ownershipChangeDuringAdmissionImmediatelyReleasesTheExactToken() throws Exception {
        Fake backend = new Fake(); backend.changeOnEnter = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
        assertSame(backend.enteredToken, backend.exitedToken); assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
    @Test public void rejectionDoesNotReleaseAnotherRequestsIsolation() throws Exception {
        Fake backend = new Fake(); backend.reject = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete()); assertEquals(0, backend.exited);
    }
    @Test public void uncertainAdmissionStillRetainsItsCleanupToken() throws Exception {
        Fake backend = new Fake(); backend.throwEnter = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertTrue(lease.isCleanupComplete());
        assertSame(backend.enteredToken, backend.exitedToken); assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
    @Test public void uncertainCleanupRemainsPendingAndCanRetryWithoutASecondAdmission() throws Exception {
        Fake backend = new Fake(); backend.throwEnter = true; backend.throwExit = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertFalse(lease.isActive()); assertFalse(lease.isCleanupComplete()); assertEquals(0, backend.stopped);
        backend.throwExit = false; assertTrue(lease.finish(false));
        assertEquals(1, backend.entered); assertEquals(2, backend.exited); assertEquals(1, backend.stopped);
    }
    @Test public void absentExactTokenIsConfirmedCleanup() throws Exception {
        Fake backend = new Fake(); backend.absentExit = true;
        CallMediaLease lease = CallMediaLease.acquire(backend);
        assertTrue(lease.finish(false)); assertTrue(lease.isCleanupComplete());
    }
    @Test public void failedRetainReleaseDowngradesToLoseIfOwnershipChangesBeforeRetry() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        backend.throwExit = true; assertFalse(lease.finish(true));
        assertEquals(CallMediaLease.RETAIN, backend.exitMode);
        backend.current = null; backend.throwExit = false;
        assertTrue(lease.finish(true)); assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
    @Test public void unreadableOwnerStillReleasesRatherThanBlockingCleanupOnARead() throws Exception {
        Fake backend = new Fake(); CallMediaLease lease = CallMediaLease.acquire(backend);
        backend.failRead = true; assertTrue(lease.finish(true));
        assertEquals(CallMediaLease.LOSE, backend.exitMode);
    }
}
