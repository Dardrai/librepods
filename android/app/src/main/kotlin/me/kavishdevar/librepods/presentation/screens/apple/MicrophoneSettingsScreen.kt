package me.kavishdevar.librepods.presentation.screens.apple

import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.effectiveControl
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.audio.CommunicationAudioController
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun MicrophoneSettingsRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    val id = ControlCommandIdentifier.MIC_MODE
    val context = LocalContext.current
    val calls = viewModel.communicationController?.takeIf { it.supported }
    val callState = calls?.state?.collectAsState()?.value
    var pendingPermission by remember(calls) { mutableStateOf(false) }
    DisposableEffect(calls) { onDispose { pendingPermission = false } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pending = pendingPermission
        pendingPermission = false
        if (pending && viewModel.audioController?.isConnected == true) {
            if (CommunicationAudioController.REQUIRED_PERMISSIONS.all { name ->
                    context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED }) calls?.setEnabled(true)
            else Toast.makeText(context, R.string.communication_audio_permission, Toast.LENGTH_SHORT).show()
        }
    }

    MicrophoneSettingsScreen(
        navigateBack = navigateBack,
        selectedMode = state.effectiveControl(id, uiState.settings)?.getOrNull(0)?.toInt() ?: 0,
        onMicrophoneSettingsChanged = {
            viewModel.setControlCommand(id, it)
        },
        callState = callState,
        onHighQualityCallsChanged = { enabled ->
            if (!enabled) { pendingPermission = false; calls?.setEnabled(false) }
            else if (CommunicationAudioController.REQUIRED_PERMISSIONS.all { name ->
                    context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED }) calls?.setEnabled(true)
            else { pendingPermission = true; permission.launch(CommunicationAudioController.REQUIRED_PERMISSIONS) }
        },
    )
}

@Composable
fun MicrophoneSettingsScreen(
    navigateBack: (() -> Unit)?,
    selectedMode: Int,
    callState: CommunicationAudioController.State? = null,
    onHighQualityCallsChanged: (Boolean) -> Unit = {},
    onMicrophoneSettingsChanged: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.microphone_mode),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(scrollState)
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            StyledList {
                styledListItem(
                    contentText = stringResource(R.string.microphone_automatic),
                    selected = selectedMode == 0,
                    onClick = { onMicrophoneSettingsChanged(0) }
                )

                styledListItem(
                    contentText = stringResource(R.string.microphone_always_right),
                    selected = selectedMode == 1,
                    onClick = { onMicrophoneSettingsChanged(1) }
                )

                styledListItem(
                    contentText = stringResource(R.string.microphone_always_left),
                    selected = selectedMode == 2,
                    onClick = { onMicrophoneSettingsChanged(2) }
                )
            }

            if (callState != null) {
                Spacer(modifier = Modifier.height(24.dp))
                StyledList(description = stringResource(communicationAudioDescription(callState))) {
                    styledToggle(label = stringResource(R.string.communication_audio_title),
                        checked = callState.enabled, onCheckedChange = onHighQualityCallsChanged)
                }
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

internal fun communicationAudioDescription(state: CommunicationAudioController.State): Int = when (state.phase) {
    CommunicationAudioController.Phase.OFF -> R.string.communication_audio_description
    CommunicationAudioController.Phase.WAITING -> R.string.communication_audio_waiting
    CommunicationAudioController.Phase.STARTING -> R.string.communication_audio_starting
    CommunicationAudioController.Phase.ACTIVE -> if (state.muted) R.string.communication_audio_muted else R.string.communication_audio_active
    CommunicationAudioController.Phase.STOPPING -> R.string.communication_audio_stopping
    CommunicationAudioController.Phase.UNAVAILABLE -> R.string.communication_audio_unavailable
    CommunicationAudioController.Phase.FAILED -> R.string.communication_audio_failed
}
