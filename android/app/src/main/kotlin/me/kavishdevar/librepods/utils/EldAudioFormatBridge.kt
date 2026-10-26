/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** SDK37's framework mapper otherwise classifies the real Apple codec ID as unknown/SBC.
 * This maps its existing native audio format only; it does not register or select a codec. */
internal object EldAudioFormatBridge {
    const val NATIVE_AAC_ELD_FORMAT = 0x04000200

    fun map(codecId: Long, originalResult: Int, enabled: Boolean): Int =
        if (enabled && codecId == EldCodecIdentity.APPLE_CODEC_ID) NATIVE_AAC_ELD_FORMAT else originalResult

    /** No native library, service, permission or audio endpoint is touched by discovery. */
    fun discover(sdk: Int, processName: String, loader: ClassLoader): Method? {
        if (sdk != 37 || processName != "system_server") return null
        return runCatching {
            platformMethod(Class.forName("android.media.AudioSystem", false, loader))
        }.getOrNull()
    }

    internal fun platformMethod(owner: Class<*>): Method {
        val method = exactEldMethod(owner, "bluetoothA2dpCodecToAudioFormat", Int::class.javaPrimitiveType!!,
            listOf(Long::class.javaPrimitiveType!!), static = true)
        // The shipped SDK37 Java class omits this native constant. If a future build exposes it,
        // reject a changed value/type instead of interpreting a different format as AAC-ELD.
        owner.declaredFields.singleOrNull { it.name == "AUDIO_FORMAT_AAC_ELD" }?.let { field ->
            require(field.type == Int::class.javaPrimitiveType && Modifier.isStatic(field.modifiers)) {
                "Unknown native AAC-ELD audio format constant"
            }
            field.isAccessible = true
            require(field.getInt(null) == NATIVE_AAC_ELD_FORMAT) { "Changed native AAC-ELD audio format" }
        }
        return method
    }
}
