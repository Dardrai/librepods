/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.telecom.TelecomManager
import java.lang.reflect.Method

/** Captures identity at the incoming Binder method; stock authorization must still run. */
internal class EldCodecPreferenceBinderPlan(owner: Class<*>, stub: Class<*>, device: Class<*>,
    codec: Class<*>, attribution: Class<*>) {
    val preference: Method = exactEldMethod(owner, "setCodecConfigPreference", Void.TYPE,
        listOf(device, codec, attribution))
    init { require(stub.isAssignableFrom(owner)) { "Unknown A2DP Binder owner" } }

    companion object {
        fun discover(loader: ClassLoader, sdk: Int): EldCodecPreferenceBinderPlan {
            require(sdk == 37) { "Unknown A2DP Binder API" }
            return EldCodecPreferenceBinderPlan(
                Class.forName("com.android.bluetooth.a2dp.A2dpServiceBinder", false, loader),
                Class.forName("android.bluetooth.IBluetoothA2dp\$Stub", false, loader),
                Class.forName("android.bluetooth.BluetoothDevice", false, loader),
                Class.forName("android.bluetooth.BluetoothCodecConfig", false, loader),
                Class.forName("android.content.AttributionSource", false, loader))
        }
    }
}

internal class EldCodecOverrideGuard {
    data class Request(val peer: List<Int>, val codec: EldCodecIdentity.Fields)
    private val request = ThreadLocal<Request?>()
    val hasRequest: Boolean get() = request.get() != null

    fun <T> withRequest(value: Request?, proceed: () -> T): T {
        val previous = request.get()
        request.set(value)
        try { return proceed() }
        finally { if (previous == null) request.remove() else request.set(previous) }
    }

    /** Only the matching, already-authorized native call inside this Binder invocation is eligible. */
    fun shouldBlock(peer: List<Int>?, codecs: List<EldCodecIdentity.Fields?>,
        enabled: () -> Boolean, healthy: () -> Boolean, idle: () -> Boolean): Boolean {
        return runCatching {
            val current = request.get() ?: return false
            current.peer == peer && codecs.size == 1 && codecs.single() == current.codec &&
                forcedAac(current.codec) && enabled() && healthy() && idle()
        }.getOrDefault(false)
    }

    companion object {
        const val ACTOR = "com.google.android.apps.pixel.dcservice"
        fun failure(error: Throwable): String {
            val kind = error.javaClass.name.take(96)
            val message = error.message.orEmpty().take(160).map {
                if (Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                    it == '\u2028' || it == '\u2029') ' ' else it
            }.joinToString("").trim()
            return if (message.isEmpty()) kind else "$kind: $message"
        }
        fun forcedAac(codec: EldCodecIdentity.Fields) = codec.type == 1 &&
            codec.extendedCodecId == 2L && codec.priority == 1_000_000

        fun trustedCaller(callingUid: Int, packageUid: Int, system: Boolean, packages: List<String>?,
            attributionUid: Int?, attributionPackage: String?, delegated: Boolean): Boolean =
            callingUid >= 10_000 && callingUid == packageUid && system && packages == listOf(ACTOR) &&
                attributionUid == callingUid && attributionPackage == ACTOR && !delegated

        fun peer(address: String?): List<Int>? = address?.takeIf {
            it.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))
        }?.split(':')?.map { it.toInt(16) }?.takeIf { bytes ->
            bytes.any { it != 0 } && bytes.any { it != 255 }
        }

        @SuppressLint("NewApi", "MissingPermission")
        fun capture(context: Context?, callingUid: Int, device: BluetoothDevice?, codec: Any?,
            attribution: AttributionSource?, objects: EldCodecObjects): Request? {
            return runCatching {
                if (context == null || attribution == null || device == null || codec == null) return null
                val fields = objects.fields(codec)
                if (!forcedAac(fields) || attribution.uid != callingUid || attribution.packageName != ACTOR ||
                    attribution.next != null) return null
                val manager = context.packageManager
                val app = manager.getApplicationInfo(ACTOR, 0)
                if (!trustedCaller(callingUid, app.uid,
                        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        manager.getPackagesForUid(callingUid)?.toList(), attribution.uid, attribution.packageName,
                        attribution.next != null)) return null
                peer(device.address)?.let { Request(it, fields) }
            }.getOrNull()
        }

        @SuppressLint("MissingPermission")
        fun bonded(device: Any?): Boolean = (device as? BluetoothDevice)?.bondState == BluetoothDevice.BOND_BONDED

        @SuppressLint("MissingPermission")
        fun idle(context: Context?): Boolean {
            return runCatching {
                val audio = context?.getSystemService(AudioManager::class.java) ?: return false
                val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
                audio.mode == AudioManager.MODE_NORMAL && !audio.isBluetoothScoOn &&
                    audio.activeRecordingConfigurations.isEmpty() && !telecom.isInCall
            }.getOrDefault(false)
        }
    }
}
