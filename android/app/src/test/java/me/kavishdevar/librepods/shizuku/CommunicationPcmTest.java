package me.kavishdevar.librepods.shizuku;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class CommunicationPcmTest {
    private byte[] frame(long epoch, long first, short... values) {
        ByteBuffer data = ByteBuffer.allocate(32 + values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(CommunicationPcm.MAGIC).putInt(1).putInt(values.length * 2).putInt(0).putLong(epoch).putLong(first);
        for (short sample : values) data.putShort(sample);
        return data.array();
    }
    private void feed(CommunicationPcm pcm, byte[] frame, int fragment) {
        for (int offset = 0; offset < frame.length;) {
            int count = Math.min(fragment, Math.min(frame.length - offset, pcm.readLength()));
            System.arraycopy(frame, offset, pcm.bytes, pcm.readOffset(), count);
            pcm.consume(count, 5); offset += count;
        }
    }
    @Test public void arbitrarilySplitFramesAndPartialWritesPreserveExactSamples() {
        CommunicationPcm pcm = new CommunicationPcm(false, 0);
        feed(pcm, frame(1, 0, (short) -32768, (short) 32767, (short) 123), 3);
        assertEquals(3, pcm.remaining()); assertEquals(-32768, pcm.samples[0]);
        pcm.accepted(1); pcm.accepted(0);
        assertEquals(2, pcm.remaining()); assertEquals(32767, pcm.samples[pcm.offset]);
        pcm.accepted(2); assertEquals(3, pcm.accepted); assertEquals(0, pcm.remaining());
    }
    @Test public void muteErasesPendingSpeechAndNeedsFreshEpochPlusWrittenHeadWatermark() {
        CommunicationPcm pcm = new CommunicationPcm(false, 0);
        feed(pcm, frame(1, 0, (short) 11, (short) 22), 32);
        pcm.accepted(1); assertTrue(pcm.mayUnmute(0));
        assertTrue(pcm.setMute(true, false, 20)); assertEquals(0, pcm.remaining()); assertEquals(0, pcm.samples[1]);
        assertTrue(pcm.setMute(false, false, 30));
        feed(pcm, frame(1, 2, (short) 99), 2); assertEquals(0, pcm.remaining());
        feed(pcm, frame(3, 3, (short) 33), 5);
        assertFalse(pcm.mayUnmute(0)); assertTrue(pcm.mayUnmute(1));
        assertEquals(1, pcm.staleFrames);
    }
    @Test public void globalMuteEdgesAdvanceEpochEvenWhenTheExplicitSwitchIsAlreadyMuted() {
        CommunicationPcm pcm = new CommunicationPcm(false, 0);
        pcm.setMute(true, false, 1); pcm.setMute(true, true, 2); pcm.setMute(true, false, 3);
        assertEquals(4, pcm.epoch); assertTrue(pcm.muted());
        assertFalse(pcm.setMute(true, false, 4)); assertEquals(4, pcm.epoch);
    }
    @Test public void muteDoesNotDiscardPartialFramingAndOldTailCannotBecomeNewSpeech() {
        CommunicationPcm pcm = new CommunicationPcm(false, 0);
        byte[] old = frame(1, 0, (short) 100, (short) 200);
        System.arraycopy(old, 0, pcm.bytes, 0, 32); pcm.consume(32, 0);
        pcm.setMute(true, false, 1); pcm.setMute(false, false, 2);
        System.arraycopy(old, 32, pcm.bytes, 32, 4); pcm.consume(4, 3);
        assertEquals(2, pcm.staleFrames); assertFalse(pcm.fresh); assertEquals(0, pcm.remaining());
        feed(pcm, frame(3, 2, (short) 300), 1); assertEquals(300, pcm.samples[0]);
    }
    @Test public void malformedAndFutureEpochFramesFailWithoutResynchronizing() {
        for (int payload : new int[]{0, 1, 962, Integer.MAX_VALUE}) {
            CommunicationPcm pcm = new CommunicationPcm(false, 0); byte[] bad = frame(1, 0, (short) 1);
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(8, payload);
            assertThrows(IllegalStateException.class, () -> feed(pcm, bad, 32));
        }
        assertThrows(IllegalStateException.class, () -> feed(new CommunicationPcm(false, 0), frame(2, 0, (short) 1), 32));
    }
    @Test public void overlappingPhysicalSamplesAreRejected() {
        CommunicationPcm pcm = new CommunicationPcm(false, 0);
        feed(pcm, frame(1, 100, (short) 1, (short) 2), 32); pcm.accepted(2);
        assertThrows(IllegalStateException.class, () -> feed(pcm, frame(1, 101, (short) 3), 32));
    }
}
