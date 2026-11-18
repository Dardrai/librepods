package me.kavishdevar.librepods.audio;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface ISpatialMotionLease {
    Bundle identity();
    boolean isCurrent();
    @nullable ParcelFileDescriptor openPipe();
    boolean activate();
    oneway void close();
}
