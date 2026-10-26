package me.kavishdevar.librepods.data

import android.content.SharedPreferences
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import java.util.Locale

internal fun accommodationsPairKey(address: String?): String? {
    if (address == null || !address.matches(Regex("(?:[0-9A-Fa-f]{12}|(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})"))) return null
    val hex = address.replace(":", "").uppercase(Locale.ROOT)
    return hex.takeUnless { it == "000000000000" }?.chunked(2)?.joinToString(":")
}

// Status bytes 12–17 carry the selected AirPods address in normal display order.
internal fun accommodationsPairKey(address: List<Int>?): String? {
    if (address == null || address.size != 6 || address.any { it !in 0..255 }) return null
    return accommodationsPairKey(address.joinToString(":") { "%02X".format(Locale.ROOT, it) })
}

internal class AccommodationsChoices(private val preferences: SharedPreferences) {
    fun read(pair: String?, report: AccommodationsReport?): Pair<Boolean, Boolean> {
        if (report != null && (report.phoneEnabled || report.mediaEnabled)) return report.phoneEnabled to report.mediaEnabled
        val key = accommodationsPairKey(pair) ?: return true to true
        return preferences.getBoolean("$key:phone", true) to
            preferences.getBoolean("$key:media", true)
    }

    fun save(pair: String?, phone: Boolean, media: Boolean) {
        val key = accommodationsPairKey(pair) ?: return
        preferences.edit().putBoolean("$key:phone", phone).putBoolean("$key:media", media).apply()
    }
}
