package me.kavishdevar.librepods.shizuku;

import android.os.Bundle;
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumeEvents;

interface IUsbAbsoluteVolumePolicy {
    Bundle inspect(int audioDeviceId, String boardSerial, String verifiedAddress);
    Bundle acquire(IBinder owner, int audioDeviceId, String boardSerial, String verifiedAddress, long connection,
        long expiresAt, int physicalLevel, IUsbAbsoluteVolumeEvents events);
    Bundle renew(IBinder owner, long connection, long expiresAt, String verifiedAddress);
    Bundle notifyPhysicalVolume(IBinder owner, long connection, int level, String verifiedAddress, long acknowledgedRequestSequence);
    Bundle release(IBinder owner);
}
