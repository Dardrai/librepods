package me.kavishdevar.librepods.core.devices

import android.content.Context
import java.util.UUID
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** A durable local choice fence is independent of a failed companion projection-receipt write. */
object LocalPreferenceEdits {
    private val lock = Any()
    private fun key(device: String, field: String) = "${if (field == "theme") "global" else device}/$field"
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "companion-local-edits-v2"))
    private fun read(context: Context): Map<String, String> {
        val file = file(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyMap()
        val bytes = file.readFully()
        require(bytes.size <= 131072)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val json = JSONObject(text)
        require(json.toString() == text && json.keys().asSequence().toSet() == setOf("v", "edits") && json.get("v") == 1)
        val edits = json.getJSONObject("edits")
        require(edits.length() <= 1025)
        return edits.keys().asSequence().associateWith { name ->
            require(name.matches(Regex("(?:global/theme|(?:[0-9A-F]{2}:){5}[0-9A-F]{2}/(?:host_auto_pause|connection_popup_enabled|cache_disconnected_battery|call_takeover_enabled|call_return_enabled|conversation_policy|desired_microphone_side|desired_ear_detection|desired_noise_modes_cycle|desired_swipe_duration|desired_case_sounds|desired_case_volume|desired_allow_off))")))
            require(edits.get(name) is String)
            edits.getString(name).also { require(it.matches(Regex("[0-9a-f]{32}"))) }
        }
    }
    fun token(context: Context, device: String, field: String): String = synchronized(lock) {
        read(context)[key(device, field)] ?: "0".repeat(32)
    }
    fun remember(context: Context, device: String, fields: Set<String>): Boolean = synchronized(lock) {
        if (fields.isEmpty()) return true
        runCatching {
            val next = read(context).toMutableMap()
            fields.forEach { next[key(device, it)] = UUID.randomUUID().toString().replace("-", "") }
            val edits = JSONObject(); next.toSortedMap().forEach { (name, token) -> edits.put(name, token) }
            val json = JSONObject().put("v", 1).put("edits", edits)
            val file = file(context)
            val stream = file.startWrite()
            try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            read(context) == next
        }.getOrDefault(false)
    }
    fun changed(old: AppleSettings, next: AppleSettings): Set<String> = buildSet {
        if (old.hostAutoPause != next.hostAutoPause) add("host_auto_pause")
        if (old.showIslandPopup != next.showIslandPopup) add("connection_popup_enabled")
        if (old.cacheDisconnectedComponentBattery != next.cacheDisconnectedComponentBattery) add("cache_disconnected_battery")
        if (old.callTakeoverEnabled != next.callTakeoverEnabled) add("call_takeover_enabled")
        if (old.callReturnEnabled != next.callReturnEnabled) add("call_return_enabled")
        if (old.relativeConversationAwarenessVolumeEnabled != next.relativeConversationAwarenessVolumeEnabled ||
            old.conversationAwarenessVolume != next.conversationAwarenessVolume ||
            old.conversationAwarenessReducedVolume != next.conversationAwarenessReducedVolume ||
            old.conversationAwarenessPauseMusicEnabled != next.conversationAwarenessPauseMusicEnabled) add("conversation_policy")
        (old.savedUnconfirmedChoices.keys + next.savedUnconfirmedChoices.keys).forEach {
            if (old.savedUnconfirmedChoices[it] != next.savedUnconfirmedChoices[it]) add(it)
        }
    }
}
