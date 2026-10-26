package me.kavishdevar.librepods.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RootMicrophoneProbeTest {
    private static byte[] pcm(short... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) { bytes[i * 2] = (byte) samples[i]; bytes[i * 2 + 1] = (byte) (samples[i] >> 8); }
        return bytes;
    }
    @Test public void shortAndZeroWritesDeliverEachSampleExactlyOnceInOrder() throws Exception {
        RootMicrophoneProbe.PendingPcm pending = new RootMicrophoneProbe.PendingPcm();
        RootMicrophoneProbe.Stats stats = new RootMicrophoneProbe.Stats();
        short[] original = {12, -23, Short.MAX_VALUE, Short.MIN_VALUE, 0};
        pending.load(pcm(original), original.length, 100);
        java.util.ArrayList<Short> delivered = new java.util.ArrayList<>();
        int[] accepts = {0, 2, 0, 1, 2};
        for (int i = 0; i < accepts.length; i++) {
            final int accepted = accepts[i];
            pending.drain((samples, offset, count) -> {
                assertTrue(accepted <= count);
                for (int j = 0; j < accepted; j++) delivered.add(samples[offset + j]);
                return accepted;
            }, stats, 100 + i, false);
        }
        assertEquals(java.util.Arrays.asList((short) 12, (short) -23, Short.MAX_VALUE, Short.MIN_VALUE, (short) 0), delivered);
        assertEquals(0, pending.remaining()); assertEquals(5, stats.frames());
        assertEquals(2, pending.shortWrites); assertEquals(2, pending.zeroWrites);
        assertEquals(4, pending.maxAgeMs); assertEquals(5, pending.offeredFrames);
        assertEquals(2, stats.clips);
        for (short sample : pending.samples) assertEquals(0, sample);
    }
    @Test public void aPendingSuffixCannotBeOverwrittenOrCountedBeforeAcceptance() throws Exception {
        RootMicrophoneProbe.PendingPcm pending = new RootMicrophoneProbe.PendingPcm();
        RootMicrophoneProbe.Stats stats = new RootMicrophoneProbe.Stats();
        pending.load(pcm((short) 3, (short) 7), 2, 100);
        pending.drain((samples, offset, count) -> 1, stats, 101, false);
        assertEquals(1, stats.frames()); assertEquals(1, pending.remaining());
        assertThrows(IllegalStateException.class, () -> pending.load(pcm((short) 9), 1, 102));
        pending.drain((samples, offset, count) -> { assertEquals(1, offset); assertEquals(7, samples[offset]); return count; }, stats, 103, false);
        assertEquals(2, stats.frames()); assertEquals(58, stats.squares);
    }
    @Test public void pendingAudioStopsAtTheAgeLimitOrCancellationWithoutAnotherWrite() throws Exception {
        RootMicrophoneProbe.PendingPcm pending = new RootMicrophoneProbe.PendingPcm();
        RootMicrophoneProbe.Stats stats = new RootMicrophoneProbe.Stats();
        AtomicInteger writes = new AtomicInteger();
        pending.load(pcm((short) 4), 1, 100);
        pending.drain((samples, offset, count) -> { writes.incrementAndGet(); return 0; }, stats, 349, false);
        assertThrows(IllegalStateException.class, () -> pending.drain((samples, offset, count) -> {
            writes.incrementAndGet(); return count;
        }, stats, 350, false));
        assertThrows(IllegalStateException.class, () -> pending.drain((samples, offset, count) -> {
            writes.incrementAndGet(); return count;
        }, stats, 101, true));
        assertEquals(1, writes.get()); assertEquals(0, stats.frames());
        pending.erase(); assertEquals(0, pending.remaining()); assertEquals(0, pending.samples[0]);
    }
    @Test public void invalidWriteResultsDoNotAdvancePendingOrAcceptedStatistics() {
        for (int invalid : new int[]{-1, 2}) {
            RootMicrophoneProbe.PendingPcm pending = new RootMicrophoneProbe.PendingPcm();
            RootMicrophoneProbe.Stats stats = new RootMicrophoneProbe.Stats();
            pending.load(pcm((short) 5), 1, 100);
            assertThrows(IllegalStateException.class, () -> pending.drain((samples, offset, count) -> invalid, stats, 101, false));
            assertEquals(1, pending.remaining()); assertEquals(0, stats.frames());
        }
    }
    @Test public void unreleasedRecorderRetainsBothItsVirtualSourceAndPolicyUntilOwnerDeath() {
        StringBuilder order = new StringBuilder();
        assertFalse(RootMicrophoneProbe.releaseVirtualInput(false,
                () -> { order.append("stop,"); return null; },
                () -> { order.append("release,"); return null; },
                () -> { order.append("policy"); return null; }));
        assertEquals("", order.toString());
        assertTrue(RootMicrophoneProbe.releaseVirtualInput(true,
                () -> { order.append("stop,"); return null; },
                () -> { order.append("release,"); return null; },
                () -> { order.append("policy"); return null; }));
        assertEquals("stop,release,policy", order.toString());
    }
    @Test public void releasedRecorderAllowsBestEffortRemainingCleanupAfterTrackStopFails() {
        StringBuilder order = new StringBuilder();
        assertFalse(RootMicrophoneProbe.releaseVirtualInput(true,
                () -> { order.append("stop,"); throw new IllegalStateException(); },
                () -> { order.append("release,"); return null; },
                () -> { order.append("policy"); return null; }));
        assertEquals("stop,release,policy", order.toString());
    }
    @Test public void exactVirtualRouteRejectsPhysicalForeignAndMissingSources() {
        assertTrue(RootMicrophoneProbe.exactRoute(true, 25, 25, "own", "own"));
        assertFalse(RootMicrophoneProbe.exactRoute(true, 15, 25, "own", "own"));
        assertFalse(RootMicrophoneProbe.exactRoute(false, 25, 25, "own", "own"));
        assertFalse(RootMicrophoneProbe.exactRoute(true, 25, 25, "other", "own"));
        assertFalse(RootMicrophoneProbe.exactRoute(true, 25, 25, "", ""));
        assertFalse(RootMicrophoneProbe.exactRoute(true, 25, 25, null, "own"));
    }
    @Test public void recorderStartRequiresExactOwnScopeAndStoppedLeaseCannotStart() {
        assertTrue(RootMicrophoneProbe.mayStart(0, 123, 123, 7, true, true, false));
        assertFalse(RootMicrophoneProbe.mayStart(2000, 123, 123, 7, true, true, false));
        assertFalse(RootMicrophoneProbe.mayStart(0, 124, 123, 7, true, true, false));
        assertFalse(RootMicrophoneProbe.mayStart(0, 0, 0, 7, true, true, false));
        assertFalse(RootMicrophoneProbe.mayStart(0, 123, 123, 1, true, true, false));
        for (int mask = 0; mask < 8; mask++)
            assertEquals(mask == 3, RootMicrophoneProbe.mayStart(0, 123, 123, 7,
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0));
    }
    @Test public void streamNeedsTimelyFirstSamplesAndNeverExtendsPastTwentySeconds() {
        assertTrue(RootMicrophoneProbe.withinStream(2999, 1000, 0, 0));
        assertFalse(RootMicrophoneProbe.withinStream(3000, 1000, 0, 0));
        assertTrue(RootMicrophoneProbe.withinStream(1249, 1000, 1100, 1000));
        assertFalse(RootMicrophoneProbe.withinStream(1250, 1000, 1100, 1000));
        assertTrue(RootMicrophoneProbe.withinStream(20999, 500, 1000, 20998));
        assertFalse(RootMicrophoneProbe.withinStream(21000, 500, 1000, 20999));
    }
    @Test public void levelStatisticsPreserveSignedFullScaleAndDoNotOverflow() {
        RootMicrophoneProbe.Stats stats = new RootMicrophoneProbe.Stats();
        short[] samples = {Short.MIN_VALUE, Short.MAX_VALUE, 0, 1, -1};
        for (int i = 0; i < 256000; i++) stats.add(samples, samples.length);
        assertEquals(1280000L, stats.frames());
        assertEquals(512000L, stats.clips);
        assertEquals(32768, stats.peak);
        assertEquals(256000L * (1073741824L + 1073676289L + 2), stats.squares);
    }
    @Test public void cleanupWaitsForResourcePublicationAndRejectsAllLaterWrites() throws Exception {
        RootMicrophoneProbe.Lease lease = new RootMicrophoneProbe.Lease();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch stopRequested = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        AtomicInteger handle = new AtomicInteger(), observed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread creating = new Thread(() -> {
            try { lease.mutate(() -> { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); handle.set(1); return null; }); }
            catch (Throwable error) { failure.set(error); }
        });
        Thread cleaning = new Thread(() -> {
            lease.stopped.set(true); stopRequested.countDown();
            if (lease.claimCleanup()) observed.set(handle.get());
            cleaned.countDown();
        });
        creating.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); cleaning.start();
        assertTrue(stopRequested.await(1, TimeUnit.SECONDS));
        assertFalse(cleaned.await(30, TimeUnit.MILLISECONDS)); release.countDown();
        creating.join(1000); cleaning.join(1000);
        assertFalse(creating.isAlive()); assertFalse(cleaning.isAlive()); assertNull(failure.get());
        assertEquals(1, observed.get()); assertFalse(lease.claimCleanup());
        assertThrows(IllegalStateException.class, () -> lease.mutate(() -> { handle.set(2); return null; }));
        assertEquals(1, handle.get());
    }
    @Test public void failedPublicationStillBelongsToCleanupAndCancellationBlocksCreation() throws Exception {
        RootMicrophoneProbe.Lease lease = new RootMicrophoneProbe.Lease();
        AtomicInteger handle = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> lease.mutate(() -> { handle.set(1); throw new IllegalStateException(); }));
        assertTrue(lease.claimCleanup()); assertEquals(1, handle.get());
        RootMicrophoneProbe.Lease cancelled = new RootMicrophoneProbe.Lease(); cancelled.stopped.set(true);
        assertThrows(IllegalStateException.class, () -> cancelled.mutate(() -> { handle.set(2); return null; }));
        assertEquals(1, handle.get()); assertTrue(cancelled.claimCleanup());
    }
}
