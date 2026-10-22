package me.kavishdevar.librepods.companion

import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.presentation.design.NightTheme
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.AppleRepository
import org.json.JSONObject

/** Projects the shared whitelist; this adapter never invokes an accessory setter. */
internal class CompanionPreferenceAdapter(private val app: AppDataRepository, private val apple: AppleRepository,
    private val device: (String) -> AppleDevice?, private val policyChanged: () -> Unit,
    private val persist: suspend (AppleDevice, AppleSettings) -> Boolean) {
    private fun supported(pod: AppleDevice): Set<String> {
        val result = CompanionPreferences.boolKeys + setOf("theme", "conversation_policy")
        if (pod.metadata.value.model == AirPodsModel.UNKNOWN) return result
        val caps = AirPodsSpecs.getSpec(pod.metadata.value.model).baseCapabilities
        return result + setOf("desired_microphone_side", "desired_ear_detection") +
            (if (BaseCapability.LISTENING_MODE in caps) setOf("desired_noise_modes_cycle") else emptySet()) +
            (if (BaseCapability.SWIPE_FOR_VOLUME in caps) setOf("desired_swipe_duration") else emptySet()) +
            (if (pod.metadata.value.model in setOf(AirPodsModel.AIRPODS_PRO_2_LIGHTNING, AirPodsModel.AIRPODS_PRO_2_USBC,
                    AirPodsModel.AIRPODS_PRO_3, AirPodsModel.AIRPODS_4, AirPodsModel.AIRPODS_4_ANC))
                setOf("desired_case_sounds", "desired_case_volume") else emptySet()) +
            (if (pod.metadata.value.modelNumber in setOf("A3063", "A3064", "A3065")) setOf("desired_allow_off") else emptySet())
    }

    fun supports(address: String, key: String, payload: JSONObject): Boolean {
        val pod = device(address) ?: return false
        if (key !in supported(pod)) return false
        if (key == "conversation_policy" && listOf("first", "second").any {
            val value = payload.getString(it)
            CompanionPreferences.decimal(value.toFloat()) != value
        }) return false
        if (key == "desired_noise_modes_cycle" && payload.getString("value") != "unset" &&
            payload.getString("value").toInt(16) and 1 != 0 &&
            pod.state.value.effectiveAllowOff(pod.settings.value, pod.metadata.value.modelNumber) != true) return false
        if (key == "desired_noise_modes_cycle" && payload.getString("value") != "unset" &&
            payload.getString("value").toInt(16) and 8 != 0 &&
            BaseCapability.ADAPTIVE_AUDIO !in AirPodsSpecs.getSpec(pod.metadata.value.model).baseCapabilities) return false
        return true
    }

    suspend fun read(address: String): Map<String, JSONObject> {
        app.awaitInitialized()
        val pod = device(address) ?: return emptyMap()
        val settings = pod.settings.value
        val values = linkedMapOf<String, JSONObject>()
        fun value(key: String, value: Any) { values[key] = JSONObject().put("value", value) }
        value("host_auto_pause", settings.hostAutoPause)
        value("connection_popup_enabled", settings.showIslandPopup)
        value("cache_disconnected_battery", settings.cacheDisconnectedComponentBattery)
        value("call_takeover_enabled", settings.callTakeoverEnabled)
        value("call_return_enabled", settings.callReturnEnabled)
        value("theme", when (app.settings.value.nightMode) { NightTheme.System -> "system"; NightTheme.Light -> "light"; NightTheme.Dark -> "dark" })
        runCatching {
            val first = CompanionPreferences.decimal(settings.conversationAwarenessVolume)
            val second = CompanionPreferences.decimal(settings.conversationAwarenessReducedVolume)
            val policy = JSONObject().put("mode", if (settings.relativeConversationAwarenessVolumeEnabled) "relative" else "abs_cap")
                .put("first", first).put("second", second).put("pause_media", settings.conversationAwarenessPauseMusicEnabled)
            CompanionPreferences.validatePayload("conversation_policy", policy)
            values["conversation_policy"] = policy
        }
        supported(pod).filter { it.startsWith("desired_") }.forEach { key ->
            value(key, settings.savedUnconfirmedChoices[key] ?: "unset")
        }
        return values
    }

    suspend fun apply(address: String, key: String, payload: JSONObject, current: () -> Boolean): Boolean {
        val pod = device(address) ?: return false
        if (key !in supported(pod)) return false
        CompanionPreferences.validatePayload(key, payload)
        if (key == "theme") {
            val changed = app.applySharedTheme(when (payload.getString("value")) {
                "light" -> NightTheme.Light; "dark" -> NightTheme.Dark; else -> NightTheme.System
            }, current)
            if (!changed) throw CompanionProjectionSuperseded()
            val target = app.settings.value
            try { app.awaitSettingsSaved(target) }
            catch (error: Exception) {
                if (!current() || app.settings.value != target) throw CompanionProjectionSuperseded()
                throw error
            }
            return true
        }
        if (key == "desired_noise_modes_cycle" && payload.getString("value") != "unset") {
            val mask = payload.getString("value").toInt(16)
            if (mask and 1 != 0 && pod.state.value.effectiveAllowOff(pod.settings.value, pod.metadata.value.modelNumber) != true) return false
        }
        val old = pod.settings.value
        val next = when (key) {
            "host_auto_pause" -> old.copy(hostAutoPause = payload.getBoolean("value"))
            "connection_popup_enabled" -> old.copy(showIslandPopup = payload.getBoolean("value"))
            "cache_disconnected_battery" -> old.copy(cacheDisconnectedComponentBattery = payload.getBoolean("value"))
            "call_takeover_enabled" -> old.copy(callTakeoverEnabled = payload.getBoolean("value"))
            "call_return_enabled" -> old.copy(callReturnEnabled = payload.getBoolean("value"))
            "conversation_policy" -> {
                val first = payload.getString("first"); val second = payload.getString("second")
                // Keep exact wire values: a lossy float conversion must not replace the received preference.
                val a = first.toFloat(); val b = second.toFloat()
                if (CompanionPreferences.decimal(a) != first || CompanionPreferences.decimal(b) != second) return false
                old.copy(relativeConversationAwarenessVolumeEnabled = payload.getString("mode") == "relative",
                    conversationAwarenessVolume = a, conversationAwarenessReducedVolume = b,
                    conversationAwarenessPauseMusicEnabled = payload.getBoolean("pause_media"))
            }
            "desired_allow_off" -> old.copy(allowOffChoice = when (payload.getString("value")) { "on" -> true; "off" -> false; else -> null },
                savedUnconfirmedChoices = old.savedUnconfirmedChoices + (key to payload.getString("value")))
            else -> old.copy(savedUnconfirmedChoices = old.savedUnconfirmedChoices + (key to payload.getString("value")))
        }
        if (next != old && key in setOf("call_takeover_enabled", "call_return_enabled", "host_auto_pause")) policyChanged()
        var applied = false
        pod.updateSettingsFromCompanion { value -> if (current() && value == old) { applied = true; next } else value }
        if (!applied) throw CompanionProjectionSuperseded()
        val stored = try { persist(pod, next) }
        catch (error: Exception) {
            if (!current() || pod.settings.value != next) throw CompanionProjectionSuperseded()
            throw error
        }
        if (!current() || !stored && pod.settings.value != next) throw CompanionProjectionSuperseded()
        return stored
    }
}
