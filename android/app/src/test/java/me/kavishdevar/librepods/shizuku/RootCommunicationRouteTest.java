package me.kavishdevar.librepods.shizuku;

import static org.junit.Assert.*;
import android.media.AudioManager;
import android.telephony.TelephonyManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RootCommunicationRouteTest {
    @Test public void ringingAndIdleAreNotAnActiveCellularCall() {
        assertFalse(RootCommunicationRoute.callActive(1, AudioManager.MODE_IN_CALL, true, TelephonyManager.CALL_STATE_RINGING, 5));
        assertFalse(RootCommunicationRoute.callActive(1, AudioManager.MODE_IN_CALL, true, TelephonyManager.CALL_STATE_IDLE, 5));
        assertTrue(RootCommunicationRoute.callActive(1, AudioManager.MODE_IN_CALL, true, TelephonyManager.CALL_STATE_OFFHOOK, 5));
        assertTrue(RootCommunicationRoute.callActive(1, 5, true, TelephonyManager.CALL_STATE_OFFHOOK, 5));
        assertFalse(RootCommunicationRoute.callActive(1, AudioManager.MODE_NORMAL, true, TelephonyManager.CALL_STATE_OFFHOOK, 5));
    }
    @Test public void voiceCaptureRequiresEveryIdentityDimension() {
        assertTrue(RootCommunicationRoute.sameRecording(10234, 72, 10234, 72, 7));
        assertFalse(RootCommunicationRoute.sameRecording(10234, 72, 10235, 72, 7));
        assertFalse(RootCommunicationRoute.sameRecording(10234, 72, 10234, 73, 7));
        assertFalse(RootCommunicationRoute.sameRecording(10234, 72, 10234, 72, 1));
        assertFalse(RootCommunicationRoute.sameRecording(10234, 0, 10234, 0, 7));
        assertFalse(RootCommunicationRoute.sameRecording(0, 72, 0, 72, 7));
    }
    @Test public void voipNeedsCommunicationModeAndCannotMasqueradeAsCellular() {
        assertTrue(RootCommunicationRoute.callActive(2, AudioManager.MODE_IN_COMMUNICATION, false, 0, 6));
        assertTrue(RootCommunicationRoute.callActive(2, 6, false, 0, 6));
        assertFalse(RootCommunicationRoute.callActive(2, AudioManager.MODE_IN_CALL, true, 2, 6));
        assertFalse(RootCommunicationRoute.callActive(3, AudioManager.MODE_IN_COMMUNICATION, false, 0, 6));
    }
    @Test public void hfpRestorationNeedsStoppedResourcesAndExactQuietOrDeadOwner() {
        assertFalse(RootCommunicationRoute.mayRestoreHfp(false, false, true, true));
        assertFalse(RootCommunicationRoute.mayRestoreHfp(true, true, false, false));
        assertTrue(RootCommunicationRoute.mayRestoreHfp(true, true, true, false));
        assertTrue(RootCommunicationRoute.mayRestoreHfp(true, true, false, true));
        assertTrue(RootCommunicationRoute.mayRestoreHfp(true, false, false, false));
    }
    @Test public void postStartRouteConvergenceKeepsOriginalDeadlineAndPropagatesOwnerLoss() throws Exception {
        long[] now = {200};
        RootCommunicationRoute.RouteClock clock = new RootCommunicationRoute.RouteClock() {
            public long now() { return now[0]; }
            public void pause() { now[0] += 10; }
        };
        AtomicInteger polls = new AtomicInteger();
        assertTrue(RootCommunicationRoute.awaitRouteAdmission(250, () -> polls.incrementAndGet() == 3, clock));
        assertEquals(3, polls.get()); assertEquals(220, now[0]);
        assertFalse(RootCommunicationRoute.awaitRouteAdmission(250, () -> false, clock));
        assertEquals(250, now[0]);
        assertFalse(RootCommunicationRoute.awaitRouteAdmission(250, () -> { fail("Deadline cannot be reset after startRecording"); return true; }, clock));
        now[0] = 200;
        assertThrows(IllegalStateException.class, () -> RootCommunicationRoute.awaitRouteAdmission(250,
                () -> { throw new IllegalStateException("Call owner changed"); }, clock));
        assertEquals(200, now[0]);
    }
    @Test public void playbackNeedsFreshProgressAfterAdmissionIncludingUnsignedWrap() throws Exception {
        assertEquals(48000, RootCommunicationRoute.OUTPUT_KEEPALIVE_SAMPLES / 2 * 1_000_000 / 7500);
        long[] now = {1000}; long[] head = {480};
        RootCommunicationRoute.RouteClock clock = new RootCommunicationRoute.RouteClock() {
            public long now() { return now[0]; }
            public void pause() { now[0] += 10; if (now[0] == 1020) head[0] += 360; }
        };
        // The track already rendered before HFP release; its old nonzero head is not readiness.
        assertTrue(RootCommunicationRoute.awaitRouteAdmission(1100,
                () -> RootCommunicationRoute.outputHeadAdvanced(480, head[0]), clock));
        assertEquals(1020, now[0]);
        assertFalse(RootCommunicationRoute.outputHeadAdvanced(480, 480));
        assertFalse(RootCommunicationRoute.outputHeadAdvanced(480, 0));
        assertFalse(RootCommunicationRoute.outputHeadAdvanced(-1, 480));
        assertTrue(RootCommunicationRoute.outputHeadAdvanced(0xffff_fff0L, 32));
    }
    @Test public void slowControlReplyCannotRenewItsOwnExpiredValidation() {
        // A complete owner/privacy/route pass took 200 ms. It has only 50 ms left,
        // even though the audio worker and its clock continue independently.
        RootCommunicationRoute.GuardObservation observed = new RootCommunicationRoute.GuardObservation(1000, false);
        assertTrue(observed.fresh(1200));
        assertTrue(observed.fresh(1250));
        assertFalse(observed.fresh(1251));
        assertFalse(observed.fresh(999));
        // A delayed optional media reply must not retimestamp the immutable pass.
        assertFalse(observed.fresh(1400));
        assertEquals(1000, observed.startedMs);
        // A pass that itself took longer than the bound cannot admit any PCM.
        RootCommunicationRoute.GuardObservation slow = new RootCommunicationRoute.GuardObservation(2000, true);
        assertFalse(slow.fresh(2251));
        assertTrue(slow.muted);
    }
}
