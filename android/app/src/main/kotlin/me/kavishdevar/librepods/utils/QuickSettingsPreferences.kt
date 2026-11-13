/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Collections

private fun quickSettingsPreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences("local-ui", Context.MODE_PRIVATE)

/** Local presentation only; hiding a control never changes its AirPods setting. */
internal object QuickSettingsPreferences {
    const val PREFERENCE_KEY = "hidden_quick_settings"

    fun hidden(context: Context): Set<String> = Collections.unmodifiableSet(
        HashSet(quickSettingsPreferences(context).getStringSet(PREFERENCE_KEY, emptySet()).orEmpty()))

    @Synchronized
    fun setHidden(context: Context, settingName: String, hidden: Boolean) {
        val preferences = quickSettingsPreferences(context)
        val names = preferences.getStringSet(PREFERENCE_KEY, emptySet()).orEmpty().toMutableSet()
        val changed = if (hidden) names.add(settingName) else names.remove(settingName)
        if (changed) preferences.edit().putStringSet(PREFERENCE_KEY, names).apply()
    }
}

@Composable
internal fun rememberHiddenQuickSettings(): State<Set<String>> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { quickSettingsPreferences(context) }
    val hidden = remember(preferences) { mutableStateOf(QuickSettingsPreferences.hidden(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == QuickSettingsPreferences.PREFERENCE_KEY || key == null)
                hidden.value = QuickSettingsPreferences.hidden(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        hidden.value = QuickSettingsPreferences.hidden(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return hidden
}
