/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.kavishdevar.librepods.audio.AtmosHeadTrackingCoordinator
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.services.headTrackingTimingJson

/** Explicit bounded native test switch. This receiver cannot mint or publish a decoder claim. */
class AtmosHeadTrackingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("operation") == "query") {
            val application = context.applicationContext as? LibrePodsApplication ?: return
            Log.i("LibrePodsAtmos", "query controllers=${application.nativeHeadTrackingControllers.size}")
            application.nativeHeadTrackingControllers.forEach { (address, controller) ->
                Log.i("LibrePodsAtmos", "query address=${address.value} state=${headTrackingTimingJson(controller.timingSnapshot())}")
            }
            return
        }
        val prefs = context.getSharedPreferences(AtmosHeadTrackingCoordinator.PREFERENCES, Context.MODE_PRIVATE)
        synchronized(AtmosHeadTrackingCoordinator.requestLock) { when (intent.getStringExtra("operation")) {
            "enable" -> {
                val mapping = intent.getIntExtra("directional_mapping", 0)
                if (mapping !in 0..4) return
                prefs.edit().putBoolean("enabled", true).putInt("directional_mapping", mapping)
                    .putLong("request_epoch", android.os.SystemClock.elapsedRealtimeNanos()).commit()
            }
            "disable" -> prefs.edit().putBoolean("enabled", false).commit()
        } }
        Log.i("LibrePodsAtmos", "enabled=${prefs.getBoolean("enabled", false)} mapping=${prefs.getInt("directional_mapping", 0)} bounded_buffer_permit_required=true")
    }
}
