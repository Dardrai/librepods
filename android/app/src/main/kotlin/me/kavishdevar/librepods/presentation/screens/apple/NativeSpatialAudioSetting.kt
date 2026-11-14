/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Intent
import android.media.AudioManager
import android.media.Spatializer
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
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
    SpatialAudioSettingContent(snapshot, connected, rootReady, busy, requested,
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
internal fun StyledListScope.SpatialAudioSettingContent(
    snapshot: FixedSpatialState?, connected: Boolean, rootReady: Boolean, busy: Boolean = false,
    requested: Boolean? = null, requestRoot: () -> Unit = {}, openSystemSettings: () -> Unit = {},
    change: (Boolean) -> Unit,
) {
    val title = stringResource(R.string.spatial_audio_fixed)
    if (connected && !rootReady) {
        styledListItem(contentText = title, supportingText = stringResource(R.string.spatial_audio_root),
            onClick = requestRoot)
    } else {
        val description = when {
            !connected -> R.string.spatial_audio_connect
            snapshot == null -> R.string.spatial_audio_loading
            snapshot.reason == "global_disabled" -> R.string.spatial_audio_global_disabled
            !snapshot.writable -> R.string.spatial_audio_unavailable
            else -> R.string.spatial_audio_description
        }
        styledToggle(label = title, description = stringResource(description),
            checked = requested ?: (snapshot?.checked == true), enabled = connected && rootReady &&
                !busy && snapshot?.writable == true, onCheckedChange = change)
        if (connected && snapshot?.reason == "global_disabled") {
            styledListItem(contentText = stringResource(R.string.spatial_audio_system_settings), onClick = openSystemSettings)
        }
    }
}
