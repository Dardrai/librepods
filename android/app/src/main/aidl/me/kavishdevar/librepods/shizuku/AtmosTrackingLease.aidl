package me.kavishdevar.librepods.shizuku;
import android.os.Bundle;
interface AtmosTrackingLease {
    Bundle state() = 0;
    Bundle preparation() = 1;
    oneway void retire() = 2;
    boolean authorizeHandoff(in Bundle marker) = 3;
}
