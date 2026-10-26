package me.kavishdevar.librepods.presentation.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient

@Composable
internal fun PrivilegedAccessSetting() {
    val context = LocalContext.current
    val access = remember(context) { ShizukuAudioClient(context) }
    val state by access.state.collectAsState()
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(access) { access.connect() }
    DisposableEffect(access) { onDispose { access.close() } }
    StyledListItem(
        contentText = stringResource(R.string.bluetooth_access),
        supportingText = if (state.connected || requested) state.availability else stringResource(R.string.bluetooth_access_description),
        orientation = StyledListItemOrientation.Vertical,
        onClick = if (state.connected) null else ({ requested = true; access.requestPermission() }),
    )
}
