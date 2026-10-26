package me.kavishdevar.librepods.eldprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class ProbeContractCheck {
    private static int checks;
    static void check(boolean condition) { checks++; if(!condition) throw new AssertionError(checks); }
    static void rejects(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        ProbeContract.Asc apple=ProbeContract.parseAsc(ProbeContract.APPLE_ASC);
        check(apple.objectType==39 && apple.rate==48000 && apple.channelConfig==2);
        check(apple.coreFrames==480 && !apple.sbr && apple.resilienceFlags==0 && apple.exactAppleAsc);
        ProbeContract.Asc normal=ProbeContract.parseAsc(new byte[]{(byte)0xf8,(byte)0xe6,0x40,0});
        check(normal.coreFrames==512 && !normal.exactAppleAsc);
        ProbeContract.Asc mono=ProbeContract.parseAsc(new byte[]{(byte)0xf8,(byte)0xe6,0x30,0});
        check(mono.channelConfig==1 && mono.coreFrames==480 && !mono.exactAppleAsc);
        check(ProbeContract.parseAsc(new byte[]{(byte)0xf8,(byte)0xe6,0x51,0}).sbr);
        check(!ProbeContract.parseAsc(new byte[]{(byte)0xf8,(byte)0xe6,0x50,0,0}).exactAppleAsc);
        rejects(()->ProbeContract.parseAsc(new byte[]{(byte)0xf8,(byte)0xe6}));
        rejects(()->ProbeContract.parseAsc(new byte[]{0x11,(byte)0x90}));
        rejects(()->ProbeContract.parseAsc(new byte[65]));
        byte[] pcm=ProbeContract.syntheticPcm(48000);
        check(pcm.length==192000 && Arrays.equals(pcm,ProbeContract.syntheticPcm(48000)));
        ByteBuffer input=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        boolean different=false;
        while(input.hasRemaining()) {
            int left=input.getShort(), right=input.getShort();
            if(Math.abs(left)>4000 || Math.abs(right)>4000) throw new AssertionError("Amplitude");
            different |= left!=right;
        }
        check(different && pcm[0]==0 && pcm[1]==0 && pcm[2]==0 && pcm[3]==0);
        check(ProbeContract.timeUs(480)==10000 && ProbeContract.timeUs(512)==10666);
        check(ProbeContract.timeUs(48000)==1000000);
        rejects(()->ProbeContract.syntheticPcm(0));
        rejects(()->ProbeContract.syntheticPcm(48001));
        rejects(()->ProbeContract.timeUs(-1));
        System.out.println("PASS "+checks+" host contract checks; no Android codec executed");
    }
}
