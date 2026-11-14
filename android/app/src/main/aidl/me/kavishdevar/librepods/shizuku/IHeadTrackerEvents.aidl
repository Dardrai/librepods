package me.kavishdevar.librepods.shizuku;

oneway interface IHeadTrackerEvents {
    void onReportingChanged(boolean enabled, long intervalNanos);
    void onStopped(String reason);
}
