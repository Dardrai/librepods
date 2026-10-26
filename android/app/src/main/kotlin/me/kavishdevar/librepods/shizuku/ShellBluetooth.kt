package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Process

internal object ShellBluetooth {
    @SuppressLint("SoonBlockedPrivateApi")
    fun adapter(context: Context): BluetoothAdapter {
        ShellAudioContext.packageForUid(Process.myUid())
        // app_process skips ActivityThread.main's per-process Bluetooth module initialization.
        val initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
        synchronized(initializer) {
            if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
                val managerClass = Class.forName("android.os.BluetoothServiceManager")
                val manager = managerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                initializer.getMethod("setBluetoothServiceManager", managerClass).invoke(null, manager)
            }
        }
        val manager = BluetoothManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        return checkNotNull(manager.adapter) { "Bluetooth manager has no adapter" }
    }
}
