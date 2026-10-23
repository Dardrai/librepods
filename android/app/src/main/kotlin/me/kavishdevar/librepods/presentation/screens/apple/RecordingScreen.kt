package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.presentation.components.apple.rememberMicrophonePermission
import me.kavishdevar.librepods.presentation.components.primitives.*
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RecordingScreenRoute(viewModel: AppleViewModel, navigateBack: (() -> Unit)?) {
    val uiState by viewModel.uiState.collectAsState()
    RecordingScreen(uiState, navigateBack, viewModel.recordings(), viewModel::startRecording,
        viewModel::stopRecording, viewModel.audioController)
}

@Composable
fun RecordingScreen(
    uiState: AppleUiState,
    navigateBack: (() -> Unit)?,
    recordings: List<Recording>,
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
    audioController: ExperimentalAudioController? = null,
) {
    val state = uiState.state
    val recording = state.recordingState.isRecording
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMMd"), locale)
    }
    val use24Hour = DateFormat.is24HourFormat(context)
    val timeFormatter = remember(locale, use24Hour) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hms" else "hms"), locale)
    }
    var openError by remember(recordings) { mutableStateOf("") }
    val openFailure = stringResource(R.string.recorder_open_failed)
    val history = remember(audioController, recording, state.recordingState.recording?.uuid) { mutableStateListOf<Float>() }
    LaunchedEffect(audioController, recording, state.recordingState.recording?.uuid, state.microphoneState.level) {
        if (recording) {
            history += state.microphoneState.level
            if (history.size > 240) history.removeAt(0)
        }
    }

    StyledScaffold(title = stringResource(R.string.recorder), navigateBack = navigateBack) { top, bottom ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(top = top, bottom = bottom), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    contentAlignment = if (recording || recordings.isEmpty()) Alignment.Center else Alignment.TopStart) {
                    when {
                        recording -> RecordingWaveform(history)
                        recordings.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.ic_recorder_microphone), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                            Text(stringResource(R.string.recorder_empty), style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center)
                            Text(stringResource(R.string.recorder_empty_description), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StyledList(title = stringResource(R.string.recordings), key = recordings) {
                                recordings.forEach { saved ->
                                    val created = Instant.ofEpochMilli(saved.createdAt.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
                                    styledListItem(content = {
                                        Text(dateFormatter.format(created), style = MaterialTheme.typography.bodyMedium, softWrap = true)
                                    }, supportingContent = {
                                        Text(timeFormatter.format(created), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                        orientation = StyledListItemOrientation.Vertical,
                                        leadingContent = { Icon(LocalIcons.current.Play, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                                        onClick = {
                                            openError = ""
                                            try {
                                                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", saved.file)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "audio/wav")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, null))
                                            } catch (_: Exception) { openError = openFailure }
                                        })
                                }
                            }
                            if (openError.isNotBlank()) Text(openError, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
                RecorderControls(audioController, recording, startRecording, stopRecording)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RecordingWaveform(history: List<Float>) {
    val accent = MaterialTheme.colorScheme.error
    val baseline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val description = stringResource(R.string.recorder_waveform)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.recorder_recording), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Canvas(Modifier.fillMaxWidth().height(160.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .testTag("recording-waveform").semantics { contentDescription = description }) {
            val center = size.height / 2
            drawLine(baseline, Offset(16.dp.toPx(), center), Offset(size.width - 16.dp.toPx(), center))
            val width = 3.dp.toPx()
            val spacing = 3.dp.toPx()
            var x = size.width - 16.dp.toPx() - width
            for (level in history.asReversed()) {
                if (x < 16.dp.toPx()) break
                val amplitude = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
                val height = 2.dp.toPx() + amplitude * (size.height * 0.44f)
                drawRoundRect(accent, Offset(x, center - height), Size(width, height * 2), CornerRadius(width / 2))
                x -= width + spacing
            }
        }
    }
}

@Composable
private fun RecorderControls(controller: ExperimentalAudioController?, recording: Boolean,
    startRecording: () -> Unit, stopRecording: () -> Unit) {
    if (controller == null) {
        Text(stringResource(R.string.recorder_connect_airpods), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        RecorderAction(recording, recording) { if (recording) stopRecording() }
        return
    }
    val bridge by controller.bridge.state.collectAsState()
    val microphone by controller.microphone.state.collectAsState()
    val message by controller.message.collectAsState()
    var permissionError by remember(controller) { mutableStateOf("") }
    val permissionMessage = stringResource(R.string.experimental_audio_permission)
    val progress = when (microphone.phase) {
        MicrophonePhase.STARTING -> stringResource(R.string.recorder_starting)
        MicrophonePhase.STOPPING -> stringResource(R.string.recorder_stopping)
        MicrophonePhase.RECOVERING -> stringResource(R.string.recorder_recovering)
        else -> ""
    }
    val permission = rememberMicrophonePermission(controller) { permissionError = permissionMessage }
    val busy = microphone.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)
    if (!bridge.connected) {
        StyledList {
            styledListItem(contentText = stringResource(R.string.experimental_shizuku_connect),
                enabled = !busy, onClick = { permissionError = ""; controller.bridge.requestPermission() })
        }
        Spacer(Modifier.height(12.dp))
    }
    val status = when {
        !controller.isConnected -> stringResource(R.string.recorder_connect_airpods)
        !controller.supportsMicrophone -> stringResource(R.string.recorder_microphone_unsupported)
        !bridge.connected -> bridge.detail.ifBlank { bridge.availability }
        else -> permissionError.ifBlank { message.ifBlank { microphone.message.ifBlank {
            bridge.detail.ifBlank { progress }
        } } }
    }
    if (status.isNotBlank()) {
        Text(status, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().heightIn(max = 128.dp).verticalScroll(rememberScrollState())
                .semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(12.dp))
    }
    RecorderAction(recording, recording || controller.isConnected && controller.supportsMicrophone && bridge.connected && !busy) {
        if (recording) stopRecording()
        else { permissionError = ""; permission(startRecording) }
    }
}

@Composable
private fun RecorderAction(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    StyledButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(if (recording) RoundedCornerShape(3.dp) else CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)))
            Text(stringResource(if (recording) R.string.recorder_stop else R.string.recorder_start),
                style = MaterialTheme.typography.titleMedium)
        }
    }
}
