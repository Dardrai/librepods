/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.services

import android.os.Bundle
import org.json.JSONObject

/** A cached read: dumping timing never starts a sensor or makes a root/Bluetooth call. */
@Suppress("DEPRECATION")
internal fun headTrackingTimingJson(value: Bundle): JSONObject = JSONObject().apply {
    for (key in value.keySet().sorted()) {
        val item = value.get(key)
        when (item) {
            is Bundle -> put(key, headTrackingTimingJson(item))
            is Number, is Boolean, is String -> put(key, item)
            null -> put(key, JSONObject.NULL)
        }
    }
}
