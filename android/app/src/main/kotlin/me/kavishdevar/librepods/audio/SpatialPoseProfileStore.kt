/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.content.Context
import android.content.SharedPreferences
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class SpatialPoseProfileKey(val macAddress: String, val rawRole: List<Int>,
    val modelNumber: String, val firmware: String, val formatVersion: Int = 1)

/** Private basis only; loading never starts a stream or restores a clock epoch.
 * Matching source identity does not prove the fit is unchanged after reinsertion; guided recalibration remains available.
 */
internal class SpatialPoseProfileStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("spatial_pose_profiles", Context.MODE_PRIVATE))

    fun save(key: SpatialPoseProfileKey, profile: SpatialPoseProfile): Boolean = runCatching {
        val identity = identity(key) ?: return false
        val basis = SpatialPoseProfile.fromBasis(profile.headFromRaw) ?: return false
        val value = JSONObject().put("version", 1).put("key", identity)
            .put("head_from_raw", JSONArray(basis.headFromRaw.map { JSONArray(it) }))
        preferences.edit().putString(storageKey(identity), value.toString()).commit()
    }.getOrDefault(false)

    fun load(key: SpatialPoseProfileKey): SpatialPoseProfile? = runCatching {
        val identity = identity(key) ?: return null
        val text = preferences.getString(storageKey(identity), null) ?: return null
        val value = JSONObject(text)
        if (value.opt("version") != 1 || value.optJSONObject("key")?.toString() != identity.toString()) return null
        val rows = value.getJSONArray("head_from_raw")
        if (rows.length() != 3) return null
        val basis = (0..2).map { row ->
            val entries = rows.getJSONArray(row)
            if (entries.length() != 3) return null
            (0..2).map { column -> (entries.get(column) as? Number)?.toDouble() ?: return null }
        }
        SpatialPoseProfile.fromBasis(basis)
    }.getOrNull()

    private fun identity(key: SpatialPoseProfileKey): JSONObject? {
        if (key.formatVersion != 1 || key.rawRole.size !in 1..64 || key.rawRole.first() !in 1..2 ||
            key.rawRole.any { it !in 0..255 } || key.modelNumber.isBlank() || key.firmware.isBlank() ||
            key.modelNumber.length > 256 || key.firmware.length > 256) return null
        val address = runCatching { MacAddress(key.macAddress).value.uppercase(Locale.ROOT) }.getOrNull() ?: return null
        if (address in setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) return null
        return JSONObject().put("mac", address).put("role", JSONArray(key.rawRole))
            .put("model_number", key.modelNumber).put("firmware", key.firmware).put("format", key.formatVersion)
    }

    private fun storageKey(identity: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
