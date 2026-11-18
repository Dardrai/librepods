/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import me.kavishdevar.librepods.BuildConfig

/** A hook observation, never permission: root separately checks the old player and its 500 ms window. */
internal data class TidalComponentTransition(val nonce: String, val bufferIssuedMs: Long, val pid: Int,
    val session: Int, val ownershipEpoch: Long, val atNs: Long) {
    fun bundle() = Bundle().apply {
        putString("nonce", nonce); putLong("buffer_issued_ms", bufferIssuedMs); putInt("process_pid", pid)
        putInt("audio_session_id", session); putLong("ownership_epoch", ownershipEpoch); putLong("event_boottime_ns", atNs)
    }
    // Hook-side framework preferences are read-only; only the module app stores observations.
    fun publish(context: Context): Boolean = context.contentResolver.call(Uri.parse("content://$AUTHORITY"),
        METHOD, null, bundle())?.getBoolean("accepted") == true

    fun write(prefs: SharedPreferences) = prefs.edit().putString(PREFIX + "nonce", nonce)
        .putLong(PREFIX + "buffer_issued_ms", bufferIssuedMs).putInt(PREFIX + "process_pid", pid)
        .putInt(PREFIX + "audio_session_id", session).putLong(PREFIX + "ownership_epoch", ownershipEpoch)
        .putLong(PREFIX + "event_boottime_ns", atNs).commit()

    companion object {
        fun grantVisibility(context: Context) = context.grantUriPermission(TidalBufferProbePolicy.PACKAGE,
            Uri.parse("content://$AUTHORITY"), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        const val PREFIX = "tidal_component_transition_"
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".tidal.transition"
        const val METHOD = "observe"
        private val fields = setOf("nonce", "buffer_issued_ms", "process_pid", "audio_session_id",
            "ownership_epoch", "event_boottime_ns")
        @Suppress("DEPRECATION")
        fun fromBundle(value: Bundle): TidalComponentTransition? = runCatching {
            if (value.keySet() != fields) return null
            TidalComponentTransition(value.get("nonce") as String, value.get("buffer_issued_ms") as Long,
                value.get("process_pid") as Int, value.get("audio_session_id") as Int,
                value.get("ownership_epoch") as Long, value.get("event_boottime_ns") as Long).takeIf(::valid)
        }.getOrNull()

        private fun valid(value: TidalComponentTransition): Boolean = TidalComponentClaimPermit.validNonce(value.nonce) &&
            value.bufferIssuedMs > 0 && value.pid > 0 && value.session > 0 && value.ownershipEpoch > 0 && value.atNs > 0
        fun read(prefs: SharedPreferences): TidalComponentTransition? = runCatching {
            val values = prefs.all
            TidalComponentTransition(values[PREFIX + "nonce"] as String, values[PREFIX + "buffer_issued_ms"] as Long,
                values[PREFIX + "process_pid"] as Int, values[PREFIX + "audio_session_id"] as Int,
                values[PREFIX + "ownership_epoch"] as Long, values[PREFIX + "event_boottime_ns"] as Long)
                .takeIf(::valid)
        }.getOrNull()
    }
}
