package me.kavishdevar.librepods.shizuku;

oneway interface IUsbAbsoluteVolumeEvents {
    void onVolumeChanged(long connection, int level, long requestSequence);
    void onPolicyEnded(String reason, boolean cleanupComplete);
}
