package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import me.kavishdevar.librepods.BuildConfig
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

private const val CONTROL_AUDIO = "android:control_audio"

internal class BackgroundAudioAccess(
    private val context: Context,
    private val shizukuGranted: () -> Boolean = {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    },
    private val allow: (Int, String) -> Unit = { uid, name -> allowOwnBackgroundAudio(uid, name) },
) {
    @Synchronized
    @Suppress("DEPRECATION")
    fun ensureAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return try {
            val uid = Process.myUid()
            val packageName = context.packageName
            check(packageName == BuildConfig.APPLICATION_ID && uid >= 10_000 &&
                context.packageManager.getApplicationInfo(packageName, 0).uid == uid) {
                "Background audio access must belong to LibrePods' own UID and package"
            }
            val appOps = checkNotNull(context.getSystemService(AppOpsManager::class.java))
            fun mode() = appOps.unsafeCheckOpRawNoThrow(CONTROL_AUDIO, uid, packageName)
            if (mode() == AppOpsManager.MODE_ALLOWED) return true
            check(shizukuGranted()) { "Shizuku is unavailable or LibrePods is not authorized" }
            allow(uid, packageName)
            check(mode() == AppOpsManager.MODE_ALLOWED) { "CONTROL_AUDIO allowance did not apply" }
            Log.i("BackgroundAudioAccess", "Background volume control allowed for LibrePods")
            true
        } catch (error: Exception) {
            Log.w("BackgroundAudioAccess", "Could not allow LibrePods background volume control", error)
            false
        }
    }
}

@SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal fun allowOwnBackgroundAudio(uid: Int, packageName: String,
    binder: () -> IBinder = {
        ShizukuBinderWrapper(checkNotNull(SystemServiceHelper.getSystemService(Context.APP_OPS_SERVICE)))
    }) {
    val operation = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
        .invoke(null, CONTROL_AUDIO) as Int
    val serviceType = Class.forName("com.android.internal.app.IAppOpsService")
    val service = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null, binder())
    serviceType.getMethod("setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        String::class.java, Int::class.javaPrimitiveType)
        .invoke(service, operation, uid, packageName, AppOpsManager.MODE_ALLOWED)
}
