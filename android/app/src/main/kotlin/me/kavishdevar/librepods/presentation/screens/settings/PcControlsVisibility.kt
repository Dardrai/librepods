package me.kavishdevar.librepods.presentation.screens.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle

/** Local presentation only; the companion link and its policies are independent. */
internal object PcControlsVisibility {
    private const val KEY = "show_pc_controls"
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("local-ui", Context.MODE_PRIVATE)
    fun get(context: Context): Boolean = preferences(context).getBoolean(KEY, false)
    fun set(context: Context, visible: Boolean) { preferences(context).edit().putBoolean(KEY, visible).apply() }

    @Composable
    fun current(): Boolean {
        val context = LocalContext.current
        val prefs = remember(context) { preferences(context) }
        var visible by remember(prefs) { mutableStateOf(prefs.getBoolean(KEY, false)) }
        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY || key == null) visible = prefs.getBoolean(KEY, false)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            visible = prefs.getBoolean(KEY, false)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return visible
    }
}

@Composable
internal fun PcControlsVisibilitySetting() {
    val context = LocalContext.current
    StyledToggle(label = stringResource(R.string.show_pc_controls),
        description = stringResource(R.string.show_pc_controls_description),
        checked = PcControlsVisibility.current(), onCheckedChange = { PcControlsVisibility.set(context, it) })
}
