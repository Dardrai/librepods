package me.kavishdevar.librepods.shizuku;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import me.kavishdevar.librepods.shizuku.IAudioBridgeCallback;
import me.kavishdevar.librepods.shizuku.IPlaybackRecoveryOwner;

interface IAudioBridge {
    void probe(IAudioBridgeCallback callback) = 0;
    ParcelFileDescriptor startMicrophoneInjection(int sampleRate, int channels,
        String targetPackage, boolean forCall, String selectedAddress, IBinder owner,
        IAudioBridgeCallback callback) = 1;
    void stopSession(IBinder owner) = 2;
    boolean restoreNativePlayback(String address, IPlaybackRecoveryOwner owner) = 3;
    boolean isMicrophonePrivacyBlocked() = 4;
    boolean confirmPairing(String address, IPlaybackRecoveryOwner owner) = 5;
    boolean connectNativePlayback(String address, IPlaybackRecoveryOwner owner) = 6;
    boolean releaseNativePlayback(String address, IPlaybackRecoveryOwner owner) = 7;
    boolean inspectNativePlayback(String address, IPlaybackRecoveryOwner owner) = 8;
    int nativeCall(String address, IPlaybackRecoveryOwner owner, boolean connect) = 9;
    boolean pauseOwnedMedia(String address, IPlaybackRecoveryOwner owner) = 10;
    boolean finishOwnedMedia(IPlaybackRecoveryOwner owner, boolean resume) = 11;
    void destroy() = 16777114;
}
