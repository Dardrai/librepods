/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import me.kavishdevar.librepods.utils.UsbAbsoluteVolumePolicyHooks

/** Read-only preparation. No stream index, device behavior or native policy is changed here. */
@android.annotation.SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal object UsbAbsoluteVolumeInspection {
    fun inspect(context: Context, deviceId: Int, serial: String): Bundle {
        val root = Process.myUid() == 0
        val sdk = Build.VERSION.SDK_INT == 37
        val route = root && sdk && runCatching { verifiedAddress(context, deviceId, serial) != null }.getOrDefault(false)
        val registration = root && sdk && runCatching {
            val manager = Class.forName("android.media.AudioDeviceVolumeManager")
            val attributes = Class.forName("android.media.AudioDeviceAttributes")
            val volume = Class.forName("android.media.VolumeInfo")
            val listener = Class.forName("android.media.AudioDeviceVolumeManager\$OnAudioDeviceVolumeChangedListener")
            manager.getMethod("setDeviceAbsoluteVolumeBehavior", attributes, volume,
                java.util.concurrent.Executor::class.java, listener)
            manager.getMethod("resetDeviceAbsoluteVolumeBehavior", attributes)
            manager.getMethod("notifyAbsoluteVolumeChanged", volume, attributes)
            true
        }.getOrDefault(false)
        return Bundle().apply {
            putInt("version", 1)
            putBoolean("root", root)
            putBoolean("platform", sdk)
            putBoolean("route_eligible", route)
            putBoolean("registration_api", registration)
            putBoolean("native_policy_owned", UsbAbsoluteVolumePolicyHooks.OWNED_NATIVE_POLICY_AVAILABLE)
            putBoolean("available", false)
            putBoolean("active", false)
            putString("reason", when {
                !root -> "root_required"
                !sdk -> "platform_unavailable"
                !route -> "route_unavailable"
                !registration -> "registration_unavailable"
                else -> "native_policy_unavailable"
            })
        }
    }

    /** Filesystem identity belongs to UID0; system_server cannot read these sysfs labels. */
    internal fun verifiedAddress(context: Context, deviceId: Int, serial: String): String? {
        check(Process.myUid() == 0)
        val selected = selectedUsb(context, deviceId, serial) ?: return null
        return selected.address.takeIf { validAddress(it) && UsbAudioIdentity.matches(it, serial) }
    }

    internal fun resolveDeviceId(context: Context, serial: String): Int? {
        check(Process.myUid() == 0)
        val selected = selectedUsb(context, 0, serial) ?: return null
        return selected.id.takeIf { validAddress(selected.address) && UsbAudioIdentity.matches(selected.address, serial) }
    }

    internal fun validAddress(address: String) = Regex("card=[0-9]{1,4};device=[0-9]{1,4};?").matches(address)

    private fun selectedUsb(context: Context, deviceId: Int, serial: String,
        outputs: List<android.media.AudioDeviceInfo> = UsbAudioDevices.outputs()): android.media.AudioDeviceInfo? {
        if (deviceId < 0 || !serial.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode != AudioManager.MODE_NORMAL) return null
        return outputs.filter { it.type in UsbAbsoluteVolumeBroker.USB_TYPES }.singleOrNull()
            ?.takeIf { deviceId == 0 || it.id == deviceId }
    }

    /** Independently inspect framework topology and board identity, without filesystem access. */
    internal fun routeEligible(context: Context, deviceId: Int, serial: String, verifiedAddress: String,
        outputs: List<android.media.AudioDeviceInfo> = UsbAudioDevices.outputs()): Boolean {
        if (deviceId <= 0 || !serial.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return false
        val audio = context.getSystemService(AudioManager::class.java)
        val selected = selectedUsb(context, deviceId, serial, outputs) ?: return false
        if (!validAddress(verifiedAddress) || selected.address != verifiedAddress) return false
        val usb = context.getSystemService(UsbManager::class.java).deviceList.values
        val boards = usb.filter { it.vendorId == 0xcafe && it.productId in setOf(0x4005, 0x4015) }
        val board = boards.singleOrNull()?.takeIf { it.serialNumber == serial } ?: return false
        if ((0 until board.interfaceCount).none { board.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }) return false
        // A sole audio device and a sole board still need the framework's current media route.
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val routes = AudioManager::class.java.getMethod("getDevicesForAttributes", AudioAttributes::class.java)
            .invoke(audio, attributes) as List<*>
        val route = routes.singleOrNull() ?: return false
        val type = Class.forName("android.media.AudioDeviceAttributes")
        return type.getMethod("getRole").invoke(route) == 2 &&
            type.getMethod("getType").invoke(route) == selected.type &&
            type.getMethod("getAddress").invoke(route) == selected.address
    }
}
