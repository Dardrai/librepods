package me.kavishdevar.librepods.shizuku

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService

/** Bound to the app process; libsu tears it down when its last client leaves or dies. */
class RootAudioService : RootService() {
    private lateinit var bridge: ShizukuAudioService

    override fun onCreate() {
        check(Process.myUid() == 0) { "Root audio service was not launched as root" }
        bridge = ShizukuAudioService(this, exitOnDestroy = false)
    }

    override fun onBind(intent: Intent): IBinder = bridge

    override fun onDestroy() {
        bridge.close()
    }
}
