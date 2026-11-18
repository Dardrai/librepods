/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import me.kavishdevar.librepods.shizuku.AtmosDecoderHandoff

/** Stores a verified caller's observation; only Root can authorize a decoder handoff. */
class TidalTransitionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        putBoolean("accepted", runCatching {
            if (method != TidalComponentTransition.METHOD || arg != null || extras == null) return@runCatching false
            val app = context ?: return@runCatching false
            val uid = app.packageManager.getApplicationInfo(TidalBufferProbePolicy.PACKAGE, 0).uid
            if (uid <= 0 || Binder.getCallingUid() != uid) return@runCatching false
            val marker = TidalComponentTransition.fromBundle(extras) ?: return@runCatching false
            if (Binder.getCallingPid() != marker.pid) return@runCatching false
            val now = SystemClock.elapsedRealtimeNanos()
            if (marker.atNs > now || now - marker.atNs > AtmosDecoderHandoff.MARKER_WINDOW_NS) return@runCatching false
            val prefs = XposedServiceHolder.service?.getRemotePreferences(TidalBufferProbeHooks.PREFERENCES)
                ?: return@runCatching false
            val buffer = TidalBufferProbeHooks.permit(prefs) ?: return@runCatching false
            val claim = TidalBufferProbeHooks.claimPermit(prefs) ?: return@runCatching false
            val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (!buffer.live(now / 1_000_000, boot) || !claim.live(buffer, now / 1_000_000, boot, uid) ||
                marker.bufferIssuedMs != buffer.issuedMs || marker.nonce != claim.nonce ||
                marker.atNs / 1_000_000 < claim.issuedMs) return@runCatching false
            val previous = TidalComponentTransition.read(prefs)
            if (previous?.nonce == marker.nonce && previous.bufferIssuedMs == marker.bufferIssuedMs &&
                previous.atNs >= marker.atNs) return@runCatching previous == marker
            marker.write(prefs)
        }.getOrDefault(false))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
