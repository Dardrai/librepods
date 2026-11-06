package me.kavishdevar.librepods.utils;

/** A host-only class that is intentionally unavailable to the module's loader. */
public final class IsolatedUsbAudioService {
    private static final class PrivateInfo {
        private final Runnable mCallback = null;
    }
    public PrivateInfo getAbsoluteVolumeDeviceInfo(Object device) { return null; }
}
