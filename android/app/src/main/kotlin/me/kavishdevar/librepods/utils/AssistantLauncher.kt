package me.kavishdevar.librepods.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object AssistantLauncher {
    fun launch(context: Context): Boolean = runCatching {
        val configured = Settings.Secure.getString(context.contentResolver, "assistant")?.takeIf(String::isNotBlank)
            ?: Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        configured?.let(ComponentName::unflattenFromString)?.packageName?.let(intent::setPackage)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
