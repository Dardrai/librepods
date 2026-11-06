package me.kavishdevar.librepods.dongle

import android.content.Context
import android.os.Build
import me.kavishdevar.librepods.BuildConfig
import java.io.IOException

/** Independent of the private Bluetooth encoder; loaded only by the UID-0 control broker. */
internal object DongleHidNative {
    private var loaded = false

    @Synchronized fun load(context: Context) {
        if (loaded) return
        val app = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        val name = "libdongle_hid.so"
        val paths = buildList {
            app.nativeLibraryDir?.let { add("$it/$name") }
            for (apk in listOf(app.sourceDir) + app.splitSourceDirs.orEmpty())
                for (abi in Build.SUPPORTED_ABIS) add("$apk!/lib/$abi/$name")
        }
        var failure: Throwable? = null
        for (path in paths) {
            try { System.load(path); loaded = true; return }
            catch (error: UnsatisfiedLinkError) { failure = error }
        }
        throw IOException("Shared USB controls helper is unavailable", failure)
    }

    external fun identity(fd: Int, product: Int, serial: String): Boolean
    external fun descriptor(fd: Int): ByteArray
    external fun exchange(fd: Int, operation: Int, report: Int, payload: ByteArray): ByteArray
}
