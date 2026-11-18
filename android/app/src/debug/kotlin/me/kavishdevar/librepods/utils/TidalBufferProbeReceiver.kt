/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Locale

/** Nonexported root-invoked diagnostic; route attestation is not production pose authorization. */
class TidalBufferProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            check(Build.VERSION.SDK_INT == 37)
            val prefs = checkNotNull(XposedServiceHolder.service) { "Open LibrePods; Xposed service unavailable" }
                .getRemotePreferences(TidalBufferProbeHooks.PREFERENCES)
            val prefix = TidalBufferProbePolicy.PREFIX
            val operation = intent.getStringExtra("operation") ?: "query"
            when (operation) {
                "observe" -> {
                    val now = SystemClock.elapsedRealtime()
                    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                    val uid = context.packageManager.getApplicationInfo(TidalBufferProbePolicy.PACKAGE, 0).uid
                    val observation = TidalBufferProbePolicy.Observation(now, intent.getLongExtra("deadline_ms", 0),
                        boot, prefs.getLong(prefix + "choice_epoch", 0))
                    check(TidalBufferProbeHooks.observationLive(prefs, observation, now, boot, uid)) {
                        "Live production buffer and decoder claim required"
                    }
                    val key = TidalBufferProbePolicy.OBSERVE_PREFIX
                    check(prefs.edit().remove(TidalBufferProbePolicy.LATE_RENDER_KEY).putLong(key + "issued_ms", observation.issuedMs)
                        .putLong(key + "deadline_ms", observation.deadlineMs).putInt(key + "boot", observation.bootCount)
                        .putLong(key + "choice_epoch", observation.choiceEpoch).commit()) {
                        "Remote observation write failed"
                    }
                    val message = "passive_window_live=true deadline_ms=${observation.deadlineMs} " +
                        "baseline_feeding_until_pace=true new_process_required=true"
                    Log.i(TidalBufferProbeHooks.TAG, message); runCatching { resultData = message }
                }
                "unobserve" -> {
                    val key = TidalBufferProbePolicy.OBSERVE_PREFIX
                    val edit = prefs.edit().remove(TidalBufferProbePolicy.LATE_RENDER_KEY)
                    listOf("issued_ms", "deadline_ms", "boot", "choice_epoch").forEach { edit.remove(key + it) }
                    check(edit.commit()) { "Remote observation write failed" }
                    Log.i(TidalBufferProbeHooks.TAG, "passive_window_live=false pacing_default_restored=true")
                }
                "pace", "unpace" -> {
                    val now = SystemClock.elapsedRealtime()
                    val deadline = intent.getLongExtra("deadline_ms", 0)
                    val key = TidalBufferProbePolicy.LATE_RENDER_KEY
                    val edit = prefs.edit()
                    if (operation == "pace") {
                        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                        val uid = context.packageManager.getApplicationInfo(TidalBufferProbePolicy.PACKAGE, 0).uid
                        val observation = checkNotNull(TidalBufferProbeHooks.observation(prefs))
                        val audio = context.getSystemService(AudioManager::class.java)
                        check(audio.mode == AudioManager.MODE_NORMAL && audio.activeRecordingConfigurations.isEmpty())
                        check(TidalBufferProbeHooks.observationLive(prefs, observation, now, boot, uid) &&
                            deadline in (now + 1)..observation.deadlineMs)
                        edit.putLong(key, deadline)
                    } else edit.remove(key)
                    check(edit.commit()) { "Remote pacing write failed" }
                    val message = "late_render_requested=${operation == "pace"} deadline_ms=$deadline"
                    Log.i(TidalBufferProbeHooks.TAG, message); runCatching { resultData = message }
                }
                "arm", "retain" -> {
                    check(intent.getBooleanExtra("route_verified", false) &&
                        intent.getBooleanExtra("no_calls_recording_verified", false) &&
                        intent.getBooleanExtra("new_player_verified", false)) { "Coordinator attestations required" }
                    val routeId = intent.getIntExtra("route_id", -1)
                    val peer = intent.getStringExtra("peer")?.uppercase(Locale.ROOT).orEmpty()
                    val info = context.packageManager.getPackageInfo(TidalBufferProbePolicy.PACKAGE, 0)
                    check(info.versionName == TidalBufferProbePolicy.VERSION && info.longVersionCode == TidalBufferProbePolicy.VERSION_CODE)
                    val audio = context.getSystemService(AudioManager::class.java)
                    check(audio.mode == AudioManager.MODE_NORMAL && audio.activeRecordingConfigurations.isEmpty()) { "Call or visible recording active" }
                    check(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                        it.id == routeId && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                            it.address.equals(peer, ignoreCase = true)
                    }) { "Attested AirPods output unavailable" }
                    if (operation == "retain") {
                        check(prefs.edit().putBoolean(TidalBufferProbePolicy.RETAINED_KEY, true).commit()) { "Remote preference write failed" }
                        Log.i(TidalBufferProbeHooks.TAG, "retained_enabled=true future_new_tracks_only=true new_process_required=true pose_authorized=false")
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        val permit = TidalBufferProbePolicy.Permit(now, intent.getLongExtra("deadline_ms", 0),
                            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1), routeId, peer)
                        check(permit.live(now, permit.bootCount)) { "Invalid deadline, boot or route" }
                        TidalComponentTransition.grantVisibility(context)
                        check(prefs.edit().putLong(prefix + "issued_ms", now).putLong(prefix + "deadline_ms", permit.deadlineMs)
                            .putInt(prefix + "boot", permit.bootCount).putInt(prefix + "route_id", permit.routeId)
                            .putString(prefix + "peer", permit.peer).commit()) { "Remote preference write failed" }
                        Log.i(TidalBufferProbeHooks.TAG, "armed deadline_ms=${permit.deadlineMs} route=${permit.routeId} fixed_frames=4800 pose_authorized=false new_process_required=true")
                    }
                }
                "unretain" -> {
                    check(prefs.edit().putBoolean(TidalBufferProbePolicy.RETAINED_KEY, false).commit()) { "Remote preference write failed" }
                    Log.i(TidalBufferProbeHooks.TAG, "retained_enabled=false existing_track_requires_teardown=true pose_authorized=false")
                }
                "disarm" -> {
                    check(prefs.edit().remove(prefix + "deadline_ms").commit()) { "Remote preference write failed" }
                    Log.i(TidalBufferProbeHooks.TAG, "disarmed existing_track_requires_teardown=true")
                }
                "query" -> {
                    val permit = TidalBufferProbeHooks.permit(prefs)
                    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                    val now = SystemClock.elapsedRealtime()
                    val uid = context.packageManager.getApplicationInfo(TidalBufferProbePolicy.PACKAGE, 0).uid
                    val observation = TidalBufferProbeHooks.observation(prefs)
                    val message = "permission_live=${permit?.live(now, boot) == true} deadline_ms=${permit?.deadlineMs ?: 0} " +
                        "route=${permit?.routeId ?: -1} retained_enabled=${TidalBufferProbeHooks.retained(prefs)} pose_authorized=false " +
                        "passive_window_armed=${observation?.windowLive(now, boot) == true} " +
                        "passive_window_live=${TidalBufferProbeHooks.observationLive(prefs, observation, now, boot, uid)}"
                    Log.i(TidalBufferProbeHooks.TAG, message); runCatching { resultData = message }
                }
            }
        } catch (failure: Exception) {
            Log.w(TidalBufferProbeHooks.TAG, "refused ${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(120)}")
        }
    }
}
