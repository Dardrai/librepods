package me.kavishdevar.librepods.shizuku;

import me.kavishdevar.librepods.shizuku.IPlaybackRecoveryOwner;

interface IAudioBridge {
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
