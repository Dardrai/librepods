/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Small, fixed-size call transport and mute ledger. No Android or audio-file dependency. */
final class CommunicationPcm {
    static final int HEADER = 32, MAX_SAMPLES = 480, MAGIC = 0x4d43504c;
    final byte[] bytes = new byte[HEADER + MAX_SAMPLES * 2];
    final short[] samples = new short[MAX_SAMPLES];
    int filled, wanted = HEADER, count, offset;
    long frameEpoch, frameStart, pendingAt, accepted, staleFrames;
    long epoch = 1, watermark, lastFrameEnd = -1, epochChangedAt;
    boolean explicitMute, systemMute, fresh;

    CommunicationPcm(boolean systemMute, long now) {
        this.systemMute = systemMute;
        epochChangedAt = now;
    }
    boolean muted() { return explicitMute || systemMute; }
    boolean setMute(boolean explicit, boolean system, long now) {
        if (explicit == explicitMute && system == systemMute) return false;
        require(epoch < Long.MAX_VALUE, "Call mute epoch exhausted");
        explicitMute = explicit; systemMute = system; epoch++; epochChangedAt = now;
        watermark = accepted; fresh = false;
        erasePending();
        return true;
    }
    int readOffset() { return filled; }
    int readLength() { return wanted - filled; }
    boolean consume(int length, long now) {
        require(count == offset && length > 0 && length <= readLength(), "Invalid call frame fragment");
        filled += length;
        if (filled < wanted) return false;
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (wanted == HEADER) {
            require(header.getInt() == MAGIC && header.getInt() == 1, "Unsupported call PCM frame");
            int payload = header.getInt();
            require(payload > 0 && payload <= MAX_SAMPLES * 2 && (payload & 1) == 0 && header.getInt() == 0,
                    "Invalid call PCM payload");
            frameEpoch = header.getLong(); frameStart = header.getLong();
            require(frameEpoch > 0 && frameStart >= 0 && frameStart <= Long.MAX_VALUE - MAX_SAMPLES,
                    "Invalid call PCM time");
            require(frameEpoch <= epoch, "Unknown future mute epoch");
            wanted += payload;
            return false;
        }
        int size = (wanted - HEADER) / 2;
        if (frameEpoch == epoch && !muted()) {
            require(frameStart >= lastFrameEnd, "Call PCM moved backwards");
            for (int i = 0; i < size; i++) samples[i] = (short) ((bytes[HEADER + i * 2] & 255) | (bytes[HEADER + i * 2 + 1] << 8));
            count = size; offset = 0; pendingAt = now; fresh = true; lastFrameEnd = frameStart + size;
        } else staleFrames += size;
        filled = 0; wanted = HEADER;
        Arrays.fill(bytes, (byte) 0);
        return count > offset;
    }
    int remaining() { return count - offset; }
    void accepted(int frames) {
        require(frames >= 0 && frames <= remaining(), "Invalid accepted call PCM prefix");
        offset += frames; accepted += frames;
        if (remaining() == 0) erasePending();
    }
    void acceptedSilence(int frames) { require(frames >= 0, "Invalid silence count"); accepted += frames; }
    boolean mayUnmute(long playbackHead) { return !muted() && fresh && playbackHead >= watermark; }
    void erasePending() { Arrays.fill(samples, (short) 0); count = 0; offset = 0; pendingAt = 0; }
    void erase() { erasePending(); Arrays.fill(bytes, (byte) 0); filled = 0; wanted = HEADER; }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
