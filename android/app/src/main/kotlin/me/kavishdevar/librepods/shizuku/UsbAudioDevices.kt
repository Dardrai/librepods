package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Process
import java.lang.reflect.Modifier

/** Fresh volume-policy metadata; AudioManager's process-wide port cache can miss re-enumeration. */
internal object UsbAudioDevices {
    internal data class Snapshot(val status: Int, val generation: Int, val devices: List<AudioDeviceInfo>,
        val portCount: Int = devices.size)

    // Root app_process and system_server can use these verified SDK37 Java APIs. No binary hooks,
    // app hidden-API exemption, global cache reset, routing or volume mutation is involved.
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private class Framework {
        private val port = Class.forName("android.media.AudioDevicePort")
        private val system = Class.forName("android.media.AudioSystem")
        private val integer = Int::class.javaPrimitiveType!!
        private val list = try { system.getMethod("listAudioPorts", ArrayList::class.java, IntArray::class.java, integer) }
            catch (_: NoSuchMethodException) { system.getMethod("listAudioPorts", ArrayList::class.java, IntArray::class.java) }
        private val wrap = AudioDeviceInfo::class.java.getDeclaredConstructor(port).apply { isAccessible = true }
        init { check(list.returnType == integer && Modifier.isStatic(list.modifiers)) }
        fun read(): Snapshot {
            val ports = ArrayList<Any>(); val generation = IntArray(1)
            // Pixel SDK37 AudioManager.listAudioDevicePorts selects type1; the older API lists all.
            val status = if (list.parameterCount == 3) list.invoke(null, ports, generation, 1) as Int
                else list.invoke(null, ports, generation) as Int
            check(status == 0 && generation[0] > 0 && ports.size <= 1024)
            return Snapshot(status, generation[0], ports.filter(port::isInstance)
                .map { wrap.newInstance(it) as AudioDeviceInfo }, ports.size)
        }
    }
    private val framework by lazy { Framework() }

    internal fun outputs(source: (() -> Snapshot)? = null, sdk: Int = Build.VERSION.SDK_INT,
        uid: Int = Process.myUid()): List<AudioDeviceInfo> {
        check(sdk == 37 && uid in setOf(0, 1000)) { "Fresh USB metadata requires the privileged SDK37 runtime" }
        try {
            val snapshot = source?.invoke() ?: framework.read()
            check(snapshot.status == 0 && snapshot.generation > 0 && snapshot.portCount in 0..1024)
            val usb = snapshot.devices.filter { it.isSink && it.type in UsbAbsoluteVolumeBroker.USB_TYPES }
            check(usb.all { it.id > 0 } && usb.map { it.id }.toSet().size == usb.size)
            return usb
        } catch (failure: Exception) {
            throw IllegalStateException("Fresh USB audio metadata is unavailable", failure)
        }
    }
}
