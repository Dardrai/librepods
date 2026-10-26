package me.kavishdevar.librepods.shizuku;

import android.os.Bundle;

/** Root-owned, transient control of one pair's automatic HFP connection attempts. */
interface IHfpCallControl {
    Bundle acquire(String address, IBinder rootOwner, IBinder appOwner) = 0;
    Bundle state(IBinder rootOwner) = 1;
    Bundle release(IBinder rootOwner) = 2;
}
