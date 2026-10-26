package me.kavishdevar.librepods.shizuku;

import me.kavishdevar.librepods.shizuku.IPlaybackRecoveryOwner;
import me.kavishdevar.librepods.shizuku.IHfpCallControl;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

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
    boolean ensureOwnBackgroundAudio() = 12;
    boolean checkVolumeSync(String address, IPlaybackRecoveryOwner owner) = 13;
    void forgetVolumeSync(IPlaybackRecoveryOwner owner) = 14;
    oneway void retireOwner(IPlaybackRecoveryOwner owner) = 15;
    Bundle prepareMicrophoneProbe(in ParcelFileDescriptor pcm, IPlaybackRecoveryOwner owner) = 16;
    Bundle finishMicrophoneProbe(IPlaybackRecoveryOwner owner) = 17;
    Bundle inspectCommunication(String address, int kind, String targetPackage) = 18;
    Bundle prepareCommunication(in ParcelFileDescriptor pcm, IPlaybackRecoveryOwner owner,
        String address, int kind, String targetPackage, int recordingSession,
        boolean allowMedia, String mediaPackage, int routeProtocol,
        @nullable IHfpCallControl hfpControl) = 19;
    boolean setCommunicationMuted(IPlaybackRecoveryOwner owner, boolean muted) = 20;
    Bundle communicationState(IPlaybackRecoveryOwner owner) = 21;
    Bundle finishCommunication(IPlaybackRecoveryOwner owner, boolean inputQuiet) = 22;
    Bundle inspectCallMedia(String address) = 23;
    void destroy() = 16777114;
}
