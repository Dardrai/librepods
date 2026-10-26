package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AccommodationsChoices
import me.kavishdevar.librepods.data.accommodationsPairKey
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.AccommodationsLevel
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AccommodationsTuning
import me.kavishdevar.librepods.core.devices.ReceivedAccommodations
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.screens.settings.ObserveDongleControls
import me.kavishdevar.librepods.presentation.screens.settings.rememberLibrePodsService
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.math.roundToInt

@Composable
fun HeadphoneAccommodationsScreen(viewModel: AppleViewModel, navigateBack: (() -> Unit)?,
    refreshDeviceSettings: suspend (AppleDevice) -> DeviceSettingsRefreshResult, navigateToTransparency: () -> Unit) {
    val ui by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshingSettings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val ready = connection == ConnectionState.CONNECTED && !refreshing && !ui.state.microphoneState.isActive &&
        !ui.state.recordingState.isRecording
    val report = remember(ui.state.hearingModes, ui.state.controlSession) {
        ui.state.hearingModes.accommodationsReport(ui.state.controlSession)
    }
    val received = remember(ui.state.hearingModes, ui.state.controlSession, ui.state.accommodationsAudiogramSource) {
        ui.state.hearingModes.receivedAccommodations(ui.state.controlSession, ui.state.accommodationsAudiogramSource)
    }
    val editor = remember(viewModel) { viewModel.liveAccommodationsEditor() }
    val live by editor.state.collectAsState()
    AccommodationsPage(navigateBack) {
        AccommodationsControls(report, ui.state.accommodationsRequest, viewModel.accommodationsPreferenceKey,
            received = received, hasReceivedPme = ui.state.controlSession > 0 && ui.state.hearingModes.hasReceivedPme,
            presetsWritable = viewModel.transparencyTransportAvailable, enabled = ready,
            onEdit = { curves, change -> editor.edit(live.revision, curves, change) }, navigateToTransparency = navigateToTransparency,
            requested = live.requested?.selection, revision = live.revision, ownPending = live.pending, onFinished = { editor.flush() },
            onCanceled = { editor.cancel(live.revision) },
            audiogramAvailable = viewModel.transparencyTransportAvailable && ui.state.accommodationsAudiogramSource?.session == ui.state.controlSession)
    }
}

@Composable
internal fun DongleAccommodationsScreen(navigateBack: (() -> Unit)?, navigateToTransparency: () -> Unit) {
    val controller = rememberLibrePodsService()?.dongleControls
    if (controller == null) return
    val state by controller.state.collectAsState()
    ObserveDongleControls(controller)
    val editor = remember(controller) { controller.liveAccommodationsEditor() }
    val live by editor.state.collectAsState()
    AccommodationsPage(navigateBack) {
        AccommodationsControls(state.accommodationsReport, state.accommodationsRequest,
            received = state.hearingModes.receivedAccommodations(state.controlSession),
            hasReceivedPme = state.controlSession > 0 && state.hearingModes.hasReceivedPme,
            preferenceKey = accommodationsPairKey(state.status?.address),
            presetsWritable = true, enabled = state.connected && (!state.busy || live.pending) && state.status?.microphoneActive == false,
            onEdit = { curves, change -> editor.edit(live.revision, curves, change) }, navigateToTransparency = navigateToTransparency,
            requested = live.requested?.selection, revision = live.revision, ownPending = live.pending, onFinished = { editor.flush() },
            onCanceled = { editor.cancel(live.revision) }, error = state.error)
    }
}

@Composable
private fun AccommodationsPage(navigateBack: (() -> Unit)?, content: @Composable () -> Unit) {
    StyledScaffold(title = stringResource(R.string.headphone_accommodations), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(top))
            content()
            Spacer(Modifier.height(bottom))
        }
    }
}

@Composable
internal fun AccommodationsControls(report: AccommodationsReport?, request: AccommodationsRequestState,
    preferenceKey: String?, received: ReceivedAccommodations?, hasReceivedPme: Boolean,
    presetsWritable: Boolean, enabled: Boolean, onEdit: (Boolean, (me.kavishdevar.librepods.core.devices.AccommodationsSelection) -> me.kavishdevar.librepods.core.devices.AccommodationsSelection) -> Unit,
    navigateToTransparency: () -> Unit, error: String? = null, audiogramAvailable: Boolean = false,
    requested: me.kavishdevar.librepods.core.devices.AccommodationsSelection? = null,
    revision: Long = 0, ownPending: Boolean = false, onFinished: () -> Unit = {}, onCanceled: () -> Unit = {}) {
    val context = LocalContext.current
    val preferences = remember { AccommodationsChoices(context.getSharedPreferences("headphone-accommodations", Context.MODE_PRIVATE)) }
    val current = requested ?: request.requested ?: received?.let {
        me.kavishdevar.librepods.core.devices.AccommodationsSelection(it.phoneEnabled, it.mediaEnabled, it.preset, it.audiogram)
    }
    val active = current?.let { it.phone || it.media } == true
    var choices by remember(preferenceKey, received?.session) {
        mutableStateOf(preferences.read(preferenceKey, report.takeIf { requested == null && request.requested == null }))
    }
    fun rememberChoices(phone: Boolean, media: Boolean) {
        choices = phone to media
        preferences.save(preferenceKey, phone, media)
    }
    val editable = enabled && presetsWritable && request.copy(pending = false).editable(report) && (!request.pending || ownPending)
    val personal = current?.audiogram == true
    val tuning = current?.preset?.tuning.takeUnless { personal }
    val level = current?.preset?.level
    val phone = if (active || report == null) current?.phone == true else choices.first
    val media = if (active || report == null) current?.media == true else choices.second
    androidx.compose.runtime.key(received?.session, revision) {
        StyledToggle(label = stringResource(R.string.headphone_accommodations), checked = active,
            enabled = editable && (active || phone || media),
            description = stringResource(if (received != null && report == null) R.string.accommodations_read_only
                else if (!phone && !media && editable) R.string.accommodations_select_destination else R.string.accommodations_description),
            onCheckedChange = { value ->
                if (!value && current != null) rememberChoices(current.phone, current.media)
                val selected = choices
                onEdit(false) { it.copy(phone = value && selected.first, media = value && selected.second) }
            })
        StyledList(title = stringResource(R.string.accommodations_audio_tuning),
            key = listOf(received?.session, revision, personal, tuning, level, editable, presetsWritable, audiogramAvailable),
            description = when {
                !presetsWritable && report != null -> stringResource(R.string.accommodations_preset_unavailable)
                !audiogramAvailable -> stringResource(R.string.accommodations_audiogram_unavailable)
                else -> null
            }) {
            styledListItem(contentText = stringResource(R.string.accommodations_audiogram), selected = personal,
                enabled = editable && presetsWritable && audiogramAvailable, onClick = {
                    onEdit(true) { it.copy(preset = null, audiogram = true) }
                })
            for ((value, label) in listOf(AccommodationsTuning.BALANCED_TONE to R.string.accommodations_balanced_tone,
                AccommodationsTuning.VOCAL_RANGE to R.string.accommodations_vocal_range,
                AccommodationsTuning.BRIGHTNESS to R.string.accommodations_brightness)) {
                styledListItem(contentText = stringResource(label), selected = tuning == value,
                    enabled = editable && presetsWritable, onClick = {
                        onEdit(true) { it.copy(preset = AccommodationsPreset(value, it.preset?.level ?: AccommodationsLevel.SLIGHT), audiogram = false) }
                    })
            }
        }
        if (tuning != null) StyledSlider(label = stringResource(R.string.accommodations_optimization),
            value = (level?.ordinal ?: 0).toFloat(), valueRange = 0f..2f, snapPoints = listOf(0f, 1f, 2f), steps = 1, independent = true,
            startLabel = stringResource(R.string.accommodations_slight), endLabel = stringResource(R.string.accommodations_strong),
            description = stringResource(when (level) {
                AccommodationsLevel.MODERATE -> R.string.accommodations_moderate
                AccommodationsLevel.STRONG -> R.string.accommodations_strong
                else -> R.string.accommodations_slight
            }), enabled = editable && presetsWritable,
            onValueChange = { value -> onEdit(true) { it.copy(preset = it.preset?.copy(level = AccommodationsLevel.entries[value.roundToInt().coerceIn(0, 2)])) } },
            onValueChangeFinished = onFinished, onValueChangeCanceled = onCanceled)
        StyledList(title = stringResource(R.string.accommodations_customized_audio),
            key = listOf(received?.session, revision, active, phone, media, editable)) {
            styledToggle(label = stringResource(R.string.phone), checked = phone, enabled = editable, onCheckedChange = { value ->
                rememberChoices(value, media)
                if (active) onEdit(false) { it.copy(phone = value) }
            })
            styledToggle(label = stringResource(R.string.media), checked = media, enabled = editable, onCheckedChange = { value ->
                rememberChoices(phone, value)
                if (active) onEdit(false) { it.copy(media = value) }
            })
        }
    }
    when {
        request.result == AccommodationsResult.UNCONFIRMED -> Text(stringResource(R.string.accommodations_unconfirmed), color = MaterialTheme.colorScheme.error)
        error != null -> Text(error, color = MaterialTheme.colorScheme.error)
        received == null -> Text(stringResource(if (hasReceivedPme) R.string.accommodations_unavailable else R.string.accommodations_waiting),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StyledListItem(contentText = stringResource(R.string.transparency_mode), onClick = navigateToTransparency)
}
