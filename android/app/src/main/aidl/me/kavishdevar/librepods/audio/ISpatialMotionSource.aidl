package me.kavishdevar.librepods.audio;

import me.kavishdevar.librepods.audio.ISpatialMotionLease;

interface ISpatialMotionSource {
    @nullable ISpatialMotionLease open(IBinder owner);
}
