/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Only the verified adapter may give reserved native slot 5 this module identity. */
internal object EldCodecIdentity {
    const val NATIVE_SLOT = 5
    const val MODULE_CODEC_ID = 0x4c8001
    const val APPLE_CODEC_ID = 0x8001004cffL
    const val INITIAL_PRIORITY = 10_001 // Explicit prototype policy, not an Android default.

    data class Fields(val type: Int, val priority: Int, val sampleRate: Int,
        val bitsPerSample: Int, val channelMode: Int, val specific1: Long,
        val specific2: Long, val specific3: Long, val specific4: Long,
        val extendedCodecId: Long? = null)

    fun extendedInbound(value: Fields, ready: Boolean, nativeIndex: Int?): Fields? =
        if (value.extendedCodecId == APPLE_CODEC_ID && (!ready || value.type != nativeIndex)) null else value

    fun extendedOffload(value: Fields): Fields? =
        if (value.extendedCodecId == APPLE_CODEC_ID) null else value

    fun outbound(value: Fields, ready: Boolean): Fields =
        if (ready && value.type == NATIVE_SLOT) value.copy(type = MODULE_CODEC_ID) else value

    fun inbound(value: Fields, ready: Boolean): Fields? = when (value.type) {
        MODULE_CODEC_ID -> if (ready) value.copy(type = NATIVE_SLOT) else null
        NATIVE_SLOT -> if (ready) null else value // Public LC3 is never an ELD request.
        else -> value
    }

    fun priorities(values: List<Fields?>, ready: Boolean): List<Fields?> =
        if (!ready || values.any { it?.type == MODULE_CODEC_ID }) values
        else values + Fields(MODULE_CODEC_ID, INITIAL_PRIORITY, 0, 0, 0, 0, 0, 0, 0)

    fun offload(value: Fields, ready: Boolean): Fields? = when {
        value.type == MODULE_CODEC_ID -> null // This adapter has no hardware-offload provider.
        ready && value.type == NATIVE_SLOT -> null
        else -> value
    }
}
