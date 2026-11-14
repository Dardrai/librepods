package me.kavishdevar.librepods.presentation.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient

@Composable
internal fun rememberPrivilegedAccessClient(): ShizukuAudioClient {
    val context = LocalContext.current
    val access = remember(context) { ShizukuAudioClient(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access.connect() }
    DisposableEffect(access) { onDispose { access.close() } }
    return access
}

@Composable
internal fun PrivilegedAccessSetting(
    access: ShizukuAudioClient = rememberPrivilegedAccessClient(),
    requestStarted: Boolean = false,
    enabled: Boolean = true,
) {
    val state by access.state.collectAsState()
    var requested by remember(access) { mutableStateOf(false) }
    StyledListItem(
        contentText = stringResource(R.string.bluetooth_access),
        supportingText = if (state.connected || requested || requestStarted) state.availability else stringResource(R.string.bluetooth_access_description),
        orientation = StyledListItemOrientation.Vertical,
        onClick = if (!enabled || state.connected) null else ({ requested = true; access.requestPermission() }),
    )
}
