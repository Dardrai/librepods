package me.kavishdevar.librepods.eldprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Synthetic-only helpers; no Android dependency, also compiled by the host self-check. */
public final class ProbeContract {
    public static final int RATE = 48000, CHANNELS = 2, INPUT_FRAMES = 48000;
    public static final byte[] APPLE_ASC = {(byte) 0xf8, (byte) 0xe6, 0x50, 0};
    private static final int[] RATES = {96000,88200,64000,48000,44100,32000,24000,
        22050,16000,12000,11025,8000,7350};

    public static final class Asc {
        public final int objectType, rate, channelConfig, coreFrames, resilienceFlags;
        public final boolean sbr, exactAppleAsc;
        Asc(int aot, int rate, int channels, int frames, int resilience, boolean sbr,
            boolean exact) {
            this.objectType=aot; this.rate=rate; channelConfig=channels; coreFrames=frames;
            resilienceFlags=resilience; this.sbr=sbr; exactAppleAsc=exact;
        }
    }

    /** Reads the ELD core header only; SBR/extensions are reported, never fabricated. */
    public static Asc parseAsc(byte[] data) {
        if (data == null || data.length == 0 || data.length > 64)
            throw new IllegalArgumentException("ASC size outside 1..64");
        Bits b = new Bits(data);
        int aot=b.read(5);
        if (aot==31) aot=32+b.read(6);
        int frequency=b.read(4);
        int rate=frequency==15 ? b.read(24) : frequency<RATES.length ? RATES[frequency] : -1;
        int channels=b.read(4);
        if (rate<=0) throw new IllegalArgumentException("Reserved/invalid frequency");
        if (aot!=39) throw new IllegalArgumentException("Output is not AAC-ELD: "+aot);
        int frames=b.read(1)==1 ? 480 : 512;
        int resilience=b.read(3);
        boolean sbr=b.read(1)!=0;
        return new Asc(aot,rate,channels,frames,resilience,sbr,Arrays.equals(data,APPLE_ASC));
    }

    public static byte[] syntheticPcm(int frames) {
        if (frames<1 || frames>INPUT_FRAMES) throw new IllegalArgumentException("PCM bound");
        ByteBuffer b=ByteBuffer.allocate(frames*CHANNELS*2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i=0;i<frames;i++) {
            b.putShort((short) StrictMath.round(4000*StrictMath.sin(2*StrictMath.PI*997*i/RATE)));
            b.putShort((short) StrictMath.round(4000*StrictMath.sin(2*StrictMath.PI*1499*i/RATE)));
        }
        return b.array();
    }

    public static long timeUs(long frames) {
        if (frames<0 || frames>INPUT_FRAMES*4L) throw new IllegalArgumentException("PTS bound");
        return frames*1000000L/RATE;
    }

    public static String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder(bytes.length*2);
        for(byte value:bytes) b.append("0123456789abcdef".charAt((value>>>4)&15))
            .append("0123456789abcdef".charAt(value&15));
        return b.toString();
    }

    private static final class Bits {
        final byte[] bytes; int position;
        Bits(byte[] bytes) { this.bytes=bytes; }
        int read(int count) {
            if (position+count>bytes.length*8) throw new IllegalArgumentException("Truncated ASC");
            int result=0;
            while(count-->0) {
                result=(result<<1)|((bytes[position>>>3]>>>(7-(position&7)))&1);
                position++;
            }
            return result;
        }
    }
}
