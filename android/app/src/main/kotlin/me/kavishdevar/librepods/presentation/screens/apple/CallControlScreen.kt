package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun CallControlScreen(
    viewModel: AppleViewModel,
    action: String,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val writing by viewModel.writingCallControls.collectAsState()
    CallControlOptions(action, uiState.state.legacyEndCallSinglePress(),
        connection == ConnectionState.CONNECTED && uiState.metadata.supportsCallControls() && !writing,
        navigateBack, supportedLayout = uiState.state.canSetLegacyEndCall()) { viewModel.setCallControls(it, uiState.metadata) }
}

@Composable
internal fun CallControlOptions(action: String, endCallSinglePress: Boolean?, enabled: Boolean,
    navigateBack: (() -> Unit)?, supportedLayout: Boolean = false, onChange: (Boolean) -> Unit) {
    val muteLabel = stringResource(R.string.mute_unmute)
    val hangUpLabel = stringResource(R.string.hang_up)
    val kind = resolveCallControlAction(action, muteLabel, hangUpLabel)
    val title = when (kind) {
        CallControlAction.MUTE_UNMUTE -> muteLabel
        CallControlAction.HANG_UP -> hangUpLabel
        null -> stringResource(R.string.call_controls)
    }
    StyledScaffold(title = title, navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = top + 8.dp, bottom = bottom)) {
            StyledList(key = kind to endCallSinglePress,
                description = if (!supportedLayout && kind == CallControlAction.HANG_UP)
                    stringResource(R.string.call_control_unsupported_layout) else null) {
                if (kind == CallControlAction.MUTE_UNMUTE) {
                    styledListItem(contentText = muteLabel, supportingText = stringResource(R.string.mute_unmute_notice),
                        orientation = StyledListItemOrientation.Vertical, onClick = null)
                } else {
                    styledListItem(contentText = stringResource(R.string.press_once),
                        selected = kind == CallControlAction.HANG_UP && endCallSinglePress == true,
                        enabled = enabled && supportedLayout && kind == CallControlAction.HANG_UP, onClick = { onChange(true) })
                    styledListItem(contentText = stringResource(R.string.press_twice),
                        selected = kind == CallControlAction.HANG_UP && endCallSinglePress == false,
                        enabled = enabled && supportedLayout && kind == CallControlAction.HANG_UP, onClick = { onChange(false) })
                }
            }
        }
    }
}
