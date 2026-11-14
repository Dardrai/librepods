/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Intent
import android.media.AudioManager
import android.media.Spatializer
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemContent
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggleContent
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import java.util.concurrent.atomic.AtomicBoolean

internal data class FixedSpatialState(val checked: Boolean?, val writable: Boolean, val reason: String) {
    companion object {
        fun read(value: Bundle): FixedSpatialState {
            @Suppress("DEPRECATION") fun flag(name: String) = value.get(name) as? Boolean
            val known = value.getInt("version", 0) == 1 && flag("known") == true
            val global = flag("enabled")
            val device = flag("device_enabled")
            val tracking = flag("head_tracker_enabled")
            val checked = if (known && global != null && device != null && tracking != null)
                global && device && !tracking else null
            return FixedSpatialState(checked,
                known && checked != null && flag("fixed_supported") == true && flag("route_selected") == true,
                if (known && global == false) "global_disabled" else value.getString("reason") ?: "unavailable")
        }
    }
}

@Composable
internal fun supportsNativeSpatialAudio(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.getSystemService(AudioManager::class.java).spatializer.immersiveAudioLevel >
            Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE }.getOrDefault(false)
    }
}

@Composable
internal fun StyledListScope.NativeSpatialAudioSetting(viewModel: AppleViewModel) {
    // State belongs to the rendered row; partial recomposition must not append to the list builder.
    item { index, count -> NativeSpatialAudioRow(viewModel, index, count) }
}

@Composable
private fun NativeSpatialAudioRow(viewModel: AppleViewModel, index: Int, count: Int) {
    val context = LocalContext.current
    val connection by viewModel.connectionState.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val token = remember(viewModel, connection, ui.state.controlSession) { viewModel.captureSpatialAudioOwner() }
    val live = remember(viewModel, token) { AtomicBoolean(true) }
    DisposableEffect(live) { onDispose { live.set(false) } }
    val access = remember(context, viewModel) { ShizukuAudioClient(context, requireRoot = true) }
    DisposableEffect(access) { onDispose { access.close() } }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access.connect(); resumes++ }
    val bridge by access.state.collectAsState()
    val rootReady = bridge.connected && access.hasRootPrivileges
    val connected = connection == ConnectionState.CONNECTED && token != null
    var snapshot by remember(live) { mutableStateOf<FixedSpatialState?>(null) }
    var requested by remember(live) { mutableStateOf<Boolean?>(null) }
    var busy by remember(live) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = remember(live) { { live.get() && token != null && viewModel.ownsSpatialAudio(token) } }

    LaunchedEffect(live, rootReady, resumes) {
        if (rootReady && connected && current()) {
            val result = access.inspectSpatialAudio(viewModel.spatialAudioAddress, current)
            if (current()) snapshot = FixedSpatialState.read(result)
        }
    }
    SpatialAudioSettingContent(snapshot, connected, rootReady, busy, requested, index = index, count = count,
        requestRoot = access::requestPermission,
        openSystemSettings = { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) },
        change = { enabled ->
            if (!busy && snapshot?.writable == true && current()) {
                requested = enabled
                busy = true
                scope.launch {
                    try {
                        val result = access.setFixedSpatialEnabled(viewModel.spatialAudioAddress, enabled, current)
                        if (current()) {
                            if (result.getBoolean("applied", false)) snapshot = FixedSpatialState.read(result)
                            else {
                                snapshot = null
                                Toast.makeText(context, R.string.spatial_audio_change_failed, Toast.LENGTH_SHORT).show()
                                val fresh = access.inspectSpatialAudio(viewModel.spatialAudioAddress, current)
                                if (current()) snapshot = FixedSpatialState.read(fresh)
                            }
                        }
                    } finally { if (live.get()) { requested = null; busy = false } }
                }
            }
        })
}

@Composable
internal fun SpatialAudioSettingContent(
    snapshot: FixedSpatialState?, connected: Boolean, rootReady: Boolean, busy: Boolean = false,
    requested: Boolean? = null, requestRoot: () -> Unit = {}, openSystemSettings: () -> Unit = {},
    change: (Boolean) -> Unit,
    index: Int = 0, count: Int = 1,
) {
    val title = stringResource(R.string.spatial_audio_fixed)
    val needsRoot = connected && !rootReady
    val needsSystemSettings = connected && rootReady && snapshot?.reason == "global_disabled"
    if (needsRoot || needsSystemSettings) {
        StyledListItemContent(
            content = { Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
            supportingContent = { Text(stringResource(if (needsRoot) R.string.spatial_audio_root
                else R.string.spatial_audio_global_disabled), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) },
            orientation = StyledListItemOrientation.Vertical,
            index = index, count = count,
            onClick = if (needsRoot) requestRoot else openSystemSettings)
    } else {
        val description = when {
            !connected -> R.string.spatial_audio_connect
            snapshot == null -> R.string.spatial_audio_loading
            !snapshot.writable -> R.string.spatial_audio_unavailable
            else -> R.string.spatial_audio_description
        }
        StyledToggleContent(modifier = Modifier, label = title, description = stringResource(description),
            index = index, count = count,
            checked = requested ?: (snapshot?.checked == true), enabled = connected && rootReady &&
                !busy && snapshot?.writable == true, onCheckedChange = change)
    }
}
