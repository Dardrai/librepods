package me.kavishdevar.librepods.shizuku;

import static org.junit.Assert.*;
import org.junit.Test;

public class HfpCallLeaseTest {
    private static final class Fake implements HfpCallLease.Port, HfpCallLease.Clock {
        int policy = 100, profile = 2, audio = 12, disconnects, connects, closes, reads;
        boolean selected = true, competitor, serviceChanged, current = true, loseDisconnectReply, loseConnectReply;
        boolean disconnectCompletes = true, connectCompletes = true;
        long now;
        Runnable beforeRead = () -> { };
        Runnable beforeDisconnect = () -> { }, beforeConnect = () -> { };
        public HfpCallLease.Snapshot read() {
            reads++; beforeRead.run();
            if (serviceChanged) throw new IllegalStateException("Service replaced");
            return new HfpCallLease.Snapshot(policy, profile, audio, selected, competitor);
        }
        public void disconnect() {
            beforeDisconnect.run();
            disconnects++;
            if (disconnectCompletes) { profile = 0; audio = 10; }
            if (loseDisconnectReply) throw new IllegalStateException("Reply lost");
        }
        public void connect() {
            beforeConnect.run();
            connects++;
            if (connectCompletes) profile = 2;
            if (loseConnectReply) throw new IllegalStateException("Reply lost");
        }
        public void close() { closes++; }
        public long now() { return now; }
        public void pause() { now += 20; }
        HfpCallLease lease() { return new HfpCallLease(this, () -> current, this); }
    }
    @Test public void exactProfileReleaseRestoresWithoutChangingPreference() throws Exception {
        Fake port = new Fake(); HfpCallLease lease = port.lease();
        lease.acquire(); lease.checkIdle();
        assertEquals(1, port.disconnects); assertEquals(100, port.policy);
        assertTrue(lease.restore()); assertEquals(1, port.connects); assertEquals(100, port.policy);
        assertEquals("restored", lease.result()); assertTrue(lease.restore()); assertEquals(1, port.closes);
    }
    @Test public void uncertainDisconnectAndConnectRepliesNeedObservedState() throws Exception {
        Fake port = new Fake(); port.loseDisconnectReply = true; port.loseConnectReply = true;
        HfpCallLease lease = port.lease(); lease.acquire();
        assertTrue(lease.wasAttempted()); assertTrue(lease.restore()); assertEquals(1, port.connects);
        Fake stalled = new Fake(); stalled.disconnectCompletes = false; stalled.loseDisconnectReply = true;
        HfpCallLease failed = stalled.lease(); assertThrows(IllegalStateException.class, failed::acquire);
        assertTrue(failed.restore()); assertEquals(0, stalled.connects); assertEquals("already-restored", failed.result());
    }
    @Test public void savedForbiddenChoiceAndOriginallyIdleProfileNeverReconnect() throws Exception {
        Fake forbidden = new Fake(); forbidden.policy = 0;
        HfpCallLease lease = forbidden.lease(); lease.acquire(); assertTrue(lease.restore());
        assertEquals(0, forbidden.policy); assertEquals(0, forbidden.connects);
        Fake idle = new Fake(); idle.profile = 0; idle.audio = 10;
        HfpCallLease idleLease = idle.lease(); idleLease.acquire(); assertTrue(idleLease.restore());
        assertEquals(0, idle.disconnects); assertEquals(0, idle.connects);
    }
    @Test public void unknownPreferenceTransitionOrStaleOwnerCannotDisconnect() {
        for (int bad : new int[]{-1, 1, 99}) {
            Fake port = new Fake(); port.policy = bad;
            HfpCallLease lease = port.lease(); assertThrows(IllegalStateException.class, lease::acquire);
            assertTrue(lease.restore()); assertEquals(0, port.disconnects);
        }
        for (int changing : new int[]{1, 3}) {
            Fake port = new Fake(); port.profile = changing;
            HfpCallLease lease = port.lease(); assertThrows(IllegalStateException.class, lease::acquire);
            assertTrue(lease.restore()); assertEquals(0, port.disconnects);
        }
        Fake stale = new Fake(); stale.current = false;
        assertThrows(IllegalStateException.class, stale.lease()::acquire); assertEquals(0, stale.disconnects);
    }
    @Test public void newerPolicyDeviceDisconnectOrCompetingHeadsetIsPreserved() throws Exception {
        for (int choice = 0; choice < 3; choice++) {
            Fake port = new Fake(); HfpCallLease lease = port.lease(); lease.acquire();
            if (choice == 0) port.policy = 0;
            if (choice == 1) port.selected = false;
            if (choice == 2) port.competitor = true;
            assertTrue(lease.restore()); assertEquals(0, port.connects); assertEquals("newer-choice-preserved", lease.result());
        }
    }
    @Test public void competitorAppearingImmediatelyBeforeConnectCannotBeEvicted() throws Exception {
        Fake port = new Fake(); HfpCallLease lease = port.lease(); lease.acquire();
        int firstRestoreRead = port.reads + 1;
        port.beforeRead = () -> { if (port.reads > firstRestoreRead) port.competitor = true; };
        assertTrue(lease.restore()); assertEquals(0, port.connects);
    }
    @Test public void returningHfpFailsWithoutRepeatedDisconnectsAndServiceReplacementNeverConnects() throws Exception {
        Fake port = new Fake(); HfpCallLease lease = port.lease(); lease.acquire();
        port.profile = 2; port.audio = 12;
        assertThrows(IllegalStateException.class, lease::checkIdle); assertEquals(1, port.disconnects);
        port.serviceChanged = true;
        assertFalse(lease.restore()); assertEquals(0, port.connects); assertEquals(1, port.closes);
    }
    @Test public void interruptedSetupStillRestoresAfterQuietEvenIfOwnerHasDied() throws Exception {
        Fake port = new Fake(); port.loseDisconnectReply = true;
        port.beforeRead = () -> { if (port.disconnects > 0) port.current = false; };
        HfpCallLease lease = port.lease();
        // Cancellation can happen after the last current check and before the device reply.
        try { lease.acquire(); } catch (IllegalStateException expected) { }
        assertTrue(lease.restore()); assertEquals(1, port.disconnects); assertEquals(1, port.connects);
    }
    @Test public void unconfirmedQuietClosesWithoutRestoringAndRestoreTimeoutIsNotSuccess() throws Exception {
        Fake port = new Fake(); HfpCallLease lease = port.lease(); lease.acquire();
        lease.close(); assertFalse(lease.restore()); assertEquals(0, port.connects);
        Fake stalled = new Fake(); stalled.connectCompletes = false;
        HfpCallLease stalledLease = stalled.lease(); stalledLease.acquire();
        assertFalse(stalledLease.restore()); assertEquals(1, stalled.connects); assertTrue(stalled.now <= 2520);
    }
    private static final class Suppression implements HfpCallLease.Suppression {
        boolean active, loseAcquireReply, releaseSucceeds = true;
        int acquires, releases;
        public void acquire() {
            acquires++; active = true;
            if (loseAcquireReply) throw new IllegalStateException("Reply lost after admission");
        }
        public void check() { if (!active) throw new IllegalStateException("Lease retired"); }
        public boolean release() { releases++; if (releaseSucceeds) active = false; return releaseSucceeds; }
    }
    @Test public void transientIsolationCoversDisconnectAndEndsBeforeQueuedRestore() throws Exception {
        Fake port = new Fake(); Suppression suppression = new Suppression();
        port.beforeDisconnect = () -> assertTrue(suppression.active);
        port.beforeConnect = () -> assertFalse(suppression.active);
        HfpCallLease lease = new HfpCallLease(port, () -> port.current, port, suppression);
        lease.acquire(); lease.checkIdle();
        assertEquals(1, suppression.acquires); assertEquals(0, suppression.releases);
        assertTrue(lease.restore()); assertEquals(1, suppression.releases); assertEquals(1, port.connects);
        assertTrue(lease.restore()); assertEquals(1, suppression.releases);
    }
    @Test public void lostIsolationReplyStillReleasesButNeverDisconnects() {
        Fake port = new Fake(); Suppression suppression = new Suppression(); suppression.loseAcquireReply = true;
        HfpCallLease lease = new HfpCallLease(port, () -> port.current, port, suppression);
        assertThrows(IllegalStateException.class, lease::acquire);
        assertTrue(suppression.active); assertEquals(0, port.disconnects);
        assertTrue(lease.restore()); assertFalse(suppression.active); assertEquals(1, suppression.releases);
        assertEquals(0, port.connects);
    }
    @Test public void missingQuietOrUnconfirmedIsolationReleaseCannotReconnect() throws Exception {
        Fake port = new Fake(); Suppression suppression = new Suppression();
        HfpCallLease lease = new HfpCallLease(port, () -> port.current, port, suppression);
        lease.acquire(); lease.close();
        assertTrue(suppression.active); assertEquals(0, suppression.releases); assertFalse(lease.restore());
        assertEquals(0, port.connects);
        Fake uncertainPort = new Fake(); Suppression uncertain = new Suppression(); uncertain.releaseSucceeds = false;
        HfpCallLease uncertainLease = new HfpCallLease(uncertainPort, () -> uncertainPort.current, uncertainPort, uncertain);
        uncertainLease.acquire(); assertFalse(uncertainLease.restore()); assertEquals(0, uncertainPort.connects);
        assertEquals("suppression-release-unconfirmed", uncertainLease.result());
    }
    @Test public void retiredOrReplacedIsolationCannotAdmitAnOldCall() throws Exception {
        String peer = "01:23:45:67:89:AB";
        assertTrue(HfpCallLease.suppressionMatches(peer, 0, true, peer, 2));
        assertTrue(HfpCallLease.suppressionMatches(peer, 2, true, peer, 2));
        assertFalse(HfpCallLease.suppressionMatches(peer, 2, true, peer, 3));
        assertFalse(HfpCallLease.suppressionMatches(peer, 2, false, peer, 2));
        assertFalse(HfpCallLease.suppressionMatches(peer, 0, true, peer, 0));
        assertFalse(HfpCallLease.suppressionMatches(peer, 2, true, "01:23:45:67:89:AC", 2));
        Fake port = new Fake(); Suppression suppression = new Suppression();
        HfpCallLease lease = new HfpCallLease(port, () -> port.current, port, suppression);
        lease.acquire(); suppression.active = false;
        assertThrows(IllegalStateException.class, lease::checkIdle); assertEquals(1, port.disconnects);
    }
    @Test public void newerSuppressionOwnerPreventsOldCleanupFromConnecting() throws Exception {
        Fake port = new Fake(); Suppression suppression = new Suppression();
        HfpCallLease old = new HfpCallLease(port, () -> port.current, port, suppression);
        old.acquire();
        // A newer call took the suppression lease after the old app owner retired.
        // The server refuses old-token release even if the selected profile is idle.
        suppression.active = false; suppression.releaseSucceeds = false;
        assertFalse(old.restore()); assertEquals(0, port.connects);
        assertEquals("suppression-release-unconfirmed", old.result());
    }
}
