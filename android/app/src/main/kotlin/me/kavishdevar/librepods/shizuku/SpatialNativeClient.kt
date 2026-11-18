/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.Bundle
import android.os.Build
import android.os.Process
import androidx.annotation.Keep

@Keep
internal object SpatialNativeClient {
    private var loaded = false
    @Synchronized fun load(): Boolean {
        if (Process.myUid() != 0 || Build.VERSION.SDK_INT < 37) return false
        if (!loaded) loaded = runCatching { System.loadLibrary("pixel_spatial_control"); true }.getOrDefault(false)
        return loaded
    }
    @JvmStatic external fun nativeRequest(routeId: Int, routeEpoch: Long, mac: ByteArray, choiceEpoch: Long, choices: Int): Long
    @JvmStatic external fun nativeRelease(choiceEpoch: Long, routeEpoch: Long): Long
    @JvmStatic external fun nativeSnapshot(): LongArray
}

/** Cached native status is not proof that an audio buffer was committed. */
internal class SpatialBackendSnapshot private constructor(private val v: LongArray) {
    val supported get() = v[5] == 31L
    val known get() = v[2] == 1L && v[3] == 0L && v[4] and 6L == 6L && v[35] == 1L
    val routeId get() = v[9].toInt()
    val routeEpoch get() = v[10]
    val choiceEpoch get() = v[7]
    val choices get() = v[6].toInt()
    val completed get() = v[22]
    fun bundle() = Bundle().apply {
        putInt("version", 1); putBoolean("supported", supported); putBoolean("known", known)
        putBoolean("available", supported && v[2] == 1L)
        putBoolean("desired_spatial", choices and 1 != 0); putBoolean("desired_tracking", choices and 2 != 0)
        putBoolean("spatial_enabled", choices and 1 != 0); putBoolean("head_tracking_enabled", choices and 2 != 0)
        putLong("accepted_generation", choiceEpoch); putLong("applied_generation", v[13])
        putBoolean("actual_audio", known && v[4] and 120L == 120L && v[13] == choiceEpoch && v[14] == v[6])
        putBoolean("output_committed", known && v[4] and 16L != 0L)
        putBoolean("actual_spatial", v[14] and 1L != 0L)
        putBoolean("actual_tracking", v[14] and 2L != 0L && v[4] and 128L != 0L)
        putInt("route_id", routeId); putLong("route_epoch", routeEpoch)
        putLong("rendered_at_ns", v[17]); putLong("committed_at_ns", v[18])
        putInt("codec", v[15].toInt()); putInt("native_failure", v[21].toInt())
        putBoolean("applied", false); putString("reason", if (supported) { if (known) "ok" else "waiting_for_owner" } else "unsupported")
    }
    companion object {
        const val ABSENT = "backend_absent"

        fun read(value: LongArray?, nowNs: Long, pid: Int): SpatialBackendSnapshot? {
            if (value == null || value.size != 36 || value[0] != 1L || value[1] <= 0 || nowNs <= 0 || pid <= 0 ||
                nowNs - value[1] !in 0..2_000_000_000L || value[26] != pid.toLong() ||
                value[2] !in 0..1 || value[3] !in 0..3 || value[4] and 255L != value[4] || value[5] and 31L != value[5] ||
                value[6] !in setOf(0L, 1L, 3L) || value[14] !in setOf(0L, 1L, 3L) ||
                value[35] !in 0..1 || value[9] !in 0..Int.MAX_VALUE.toLong() || value[15] !in 0..2 ||
                listOf(7, 10, 11, 12, 13, 16, 17, 18, 22, 23).any { value[it] < 0 }) return null
            if (value[22] > value[23] || value[4] and 6L !in setOf(0L, 6L)) return null
            if (value[4] and 6L == 6L && (value[8] != pid.toLong() || value[7] <= 0 || value[9] <= 0 || value[10] <= 0)) return null
            if (value[4] and 16L != 0L && (value[4] and 40L != 40L || value[15] !in 1..2 ||
                    value[13] <= 0 || value[16] <= 0 || value[17] <= 0 || value[18] < value[17])) return null
            if (value[4] and 64L != 0L && (value[4] and 32L == 0L || value[13] != value[7] || value[14] != value[6])) return null
            return SpatialBackendSnapshot(value.copyOf())
        }
        fun unavailable(reason: String) = Bundle().apply {
            putInt("version", 1); putBoolean("supported", false); putBoolean("known", false)
            putBoolean("available", false); putBoolean("applied", false); putString("reason", reason)
        }
    }
}
