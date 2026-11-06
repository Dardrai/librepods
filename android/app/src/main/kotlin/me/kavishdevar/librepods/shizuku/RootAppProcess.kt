package me.kavishdevar.librepods.shizuku

import android.os.Process
import android.annotation.SuppressLint
import java.lang.reflect.Modifier

internal class MediaRuntimeUnavailableException(cause: Exception) :
    IllegalStateException("Media session runtime is unavailable", cause)

/** libsu uses systemMain(), but this APK-owned UID-0 client is not system_server. */
internal object RootAppProcess {
    /** app_process skips the normal app's per-process media initialization. */
    @JvmStatic @JvmOverloads @Synchronized
    fun prepareMedia(uid: Int = Process.myUid(), initializerClass: Class<*>? = null, managerClass: Class<*>? = null) {
        ShellAudioContext.packageForUid(uid)
        try {
            val initializer = initializerClass ?: Class.forName("android.media.MediaFrameworkPlatformInitializer")
            val manager = managerClass ?: Class.forName("android.media.MediaServiceManager")
            val getter = initializer.getMethod("getMediaServiceManager")
            check(Modifier.isStatic(getter.modifiers) && getter.returnType == manager)
            if (getter.invoke(null) != null) return
            val setter = initializer.getMethod("setMediaServiceManager", manager)
            check(Modifier.isStatic(setter.modifiers) && setter.returnType == Void.TYPE)
            val created = manager.getConstructor().newInstance()
            setter.invoke(null, created)
            check(getter.invoke(null) === created)
        } catch (failure: Exception) {
            throw MediaRuntimeUnavailableException(failure)
        }
    }

    // Verified in UID-0 app_process on SDK 37; this does not run in an ordinary app or system_server.
    // A changed/blocked layout fails closed through the exact type and isSystem readback checks.
    @SuppressLint("SoonBlockedPrivateApi")
    @Synchronized fun prepare(uid: Int = Process.myUid(),
        threadClass: Class<*> = Class.forName("android.app.ActivityThread")) {
        check(uid == 0) { "Root runtime preparation requires UID 0" }
        try {
            val system = threadClass.getMethod("isSystem")
            check(system.returnType == Boolean::class.javaPrimitiveType && Modifier.isStatic(system.modifiers))
            if (system.invoke(null) == false) return
            val current = checkNotNull(threadClass.getMethod("currentActivityThread").invoke(null))
            val field = threadClass.getDeclaredField("mSystemThread")
            check(field.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(field.modifiers))
            field.isAccessible = true
            field.setBoolean(current, false)
            check(system.invoke(null) == false)
        } catch (failure: Exception) {
            throw IllegalStateException("Root USB runtime compatibility is unavailable", failure)
        }
    }
}
