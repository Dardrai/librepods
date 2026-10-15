/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.presentation.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.AirPodsSensorSession
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.audio.RtBuddySensors
import org.json.JSONObject
import kotlin.math.abs

@Composable
fun RootlessAudioControls(controller: ExperimentalAudioController, connected: Boolean, modelNumber: String?) {
    val context = LocalContext.current
    val bridge by controller.bridge.state.collectAsState()
    val microphone by controller.microphone.state.collectAsState()
    val controllerMessage by controller.message.collectAsState()
    val target = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    val microphoneEligible = modelNumber in MICROPHONE_MODELS
    val active = microphone.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)
    val bridgeBusy = bridge.session !in listOf("stopped", "failed")
    var pendingAction by rememberSaveable { mutableStateOf(0) }
    var pendingPackage by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val latestConnected by rememberUpdatedState(connected && microphoneEligible)
    val latestController by rememberUpdatedState(controller)
    val latestBridgeConnected by rememberUpdatedState(bridge.connected)
    val permissionMessage = stringResource(R.string.rootless_audio_permission)
    val disconnectedMessage = stringResource(R.string.rootless_audio_not_connected)
    val playbackError = stringResource(R.string.rootless_recording_play_failed)
    val shareError = stringResource(R.string.rootless_recording_share_failed)
    val shareTitle = stringResource(R.string.rootless_audio_share)
    val recoveryMessage = stringResource(R.string.rootless_audio_recovery_requires_shizuku)
    val retryPermissionMessage = stringResource(R.string.rootless_audio_permission_retry)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = 0
        Log.i("LibrePodsAudioUI", "Permission result: granted=$granted action=$action connected=$latestConnected bridge=$latestBridgeConnected")
        if (!granted) localError = permissionMessage
        else if (action == 0) localError = retryPermissionMessage
        else if (!latestConnected) localError = disconnectedMessage
        else if (!latestBridgeConnected) localError = recoveryMessage
        else when (action) {
            1 -> latestController.startRecording()
            2 -> latestController.startInjection(pendingPackage, false)
            3 -> latestController.startInjection(pendingPackage, true)
            else -> Unit
        }
    }
    fun start(action: Int) {
        Log.i("LibrePodsAudioUI", "Microphone action requested: action=$action connected=$connected bridge=${bridge.connected} phase=${microphone.phase}")
        player?.release()
        player = null
        localError = ""
        if (!bridge.connected) { localError = recoveryMessage; return }
        pendingPackage = target.text.toString().trim()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                1 -> controller.startRecording()
                2 -> controller.startInjection(pendingPackage, false)
                3 -> controller.startInjection(pendingPackage, true)
            }
        } else {
            pendingAction = action
            Log.i("LibrePodsAudioUI", "Requesting microphone permission: action=$action")
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            player?.release()
            player = null
            pendingAction = 0
        }
    }

    Spacer(Modifier.height(16.dp))
    StyledList(title = stringResource(R.string.rootless_audio_title), description = stringResource(R.string.rootless_audio_native)) {
        StyledListItem(name = stringResource(if (bridge.connected) R.string.rootless_shizuku_connected else R.string.rootless_audio_shizuku), description = bridge.availability,
            orientation = ListItemOrientation.Vertical, enabled = !bridge.connected && !active,
            onClick = { controller.bridge.requestPermission() })
        StyledListItem(name = stringResource(if (bridge.probing) R.string.rootless_audio_checking else R.string.rootless_audio_probe),
            enabled = bridge.connected && !bridge.probing && !active && !bridgeBusy,
            onClick = { controller.bridge.probe() })
    }

    if (microphoneEligible) {
        Spacer(Modifier.height(16.dp))
        StyledList(title = stringResource(R.string.rootless_microphone_test),
            description = stringResource(R.string.rootless_audio_calibration)) {
            StyledListItem(name = stringResource(R.string.rootless_audio_record),
                description = when {
                    pendingAction != 0 -> stringResource(R.string.rootless_audio_permission_pending)
                    !connected -> disconnectedMessage
                    !bridge.connected -> recoveryMessage
                    bridge.probing -> stringResource(R.string.rootless_audio_wait_for_checks)
                    bridgeBusy -> stringResource(R.string.rootless_audio_wait_for_route)
                    else -> stringResource(R.string.rootless_recording_limit)
                },
                orientation = ListItemOrientation.Vertical,
                enabled = connected && bridge.connected && !active && !bridgeBusy && !bridge.probing && pendingAction == 0,
                onClick = { start(1) })
            if (pendingAction != 0) StyledListItem(name = stringResource(R.string.rootless_audio_cancel_permission),
                onClick = { pendingAction = 0 })
            if (active || bridgeBusy) StyledListItem(name = stringResource(R.string.rootless_audio_stop),
                onClick = { controller.stop() })
            if (active || microphone.phase == MicrophonePhase.ERROR) {
                item { _, _ -> SupportingText(stringResource(when (microphone.phase) {
                    MicrophonePhase.STARTING -> R.string.rootless_mic_starting
                    MicrophonePhase.CAPTURING -> R.string.rootless_mic_capturing
                    MicrophonePhase.STOPPING -> R.string.rootless_mic_stopping
                    MicrophonePhase.RECOVERING -> R.string.rootless_mic_recovering
                    else -> R.string.rootless_mic_failed
                })) }
            }
            if (microphone.format != null) item { _, _ ->
                SupportingText(stringResource(R.string.rootless_recording_duration,
                    microphone.pcmDurationMs / 1000.0, microphone.wallDurationMs / 1000.0,
                    checkNotNull(microphone.format).sampleRate))
            }
            val recording = microphone.file?.takeIf { !active && it.isFile && it.length() > 44 }
            if (recording != null) {
                StyledListItem(name = stringResource(if (player == null) R.string.rootless_audio_play else R.string.rootless_recording_stop_playback),
                    onClick = {
                        if (player != null) { player?.release(); player = null }
                        else {
                            val next = MediaPlayer()
                            try {
                                next.setDataSource(recording.absolutePath)
                                next.setOnPreparedListener { if (player === it) it.start() }
                                next.setOnCompletionListener { if (player === it) player = null; it.release() }
                                next.setOnErrorListener { failed, _, _ ->
                                    if (player === failed) player = null
                                    failed.release(); localError = playbackError; true
                                }
                                player = next
                                next.prepareAsync()
                            } catch (_: Exception) {
                                next.release(); player = null; localError = playbackError
                            }
                        }
                    })
                StyledListItem(name = shareTitle, onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", recording)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/wav"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(shareTitle, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, shareTitle))
                    } catch (_: Exception) { localError = shareError }
                })
            }
        }
        if (bridge.connected) {
            Spacer(Modifier.height(16.dp))
            StyledInputField(inputState = target, focusRequester = focusRequester,
                placeholder = stringResource(R.string.rootless_audio_target))
            Spacer(Modifier.height(8.dp))
            StyledList(description = stringResource(R.string.rootless_audio_recipient_check)) {
                StyledListItem(name = stringResource(R.string.rootless_audio_inject),
                    enabled = connected && !active && !bridgeBusy && !bridge.probing && target.text.isNotBlank() && pendingAction == 0,
                    onClick = { start(2) })
                StyledListItem(name = stringResource(R.string.rootless_audio_call),
                    description = stringResource(R.string.rootless_audio_call_consent),
                    orientation = ListItemOrientation.Vertical,
                    enabled = connected && !active && !bridgeBusy && !bridge.probing && pendingAction == 0,
                    onClick = { start(3) })
            }
        }
    }
    val message = localError.ifBlank { controllerMessage.ifBlank { microphone.message.ifBlank { bridge.detail } } }
    if (message.isNotBlank()) SupportingText(message)
    if (bridge.report.isNotBlank()) {
        val report = remember(bridge.report) { runCatching { JSONObject(bridge.report) }.getOrNull() }
        Spacer(Modifier.height(16.dp))
        StyledList(title = stringResource(R.string.rootless_audio_diagnostics)) {
            for ((key, label) in DIAGNOSTIC_LABELS) {
                val status = report?.optJSONObject("capabilities")?.optJSONObject(key)?.optString("status")
                StyledListItem(name = stringResource(label), description = stringResource(when (status) {
                    "proven" -> R.string.rootless_check_passed
                    "blocked" -> R.string.rootless_check_blocked
                    else -> R.string.rootless_check_unverified
                }), orientation = ListItemOrientation.Vertical, onClick = null, enabled = true)
            }
            val clipboardLabel = stringResource(R.string.rootless_audio_diagnostics)
            val copied = stringResource(R.string.rootless_report_copied)
            StyledListItem(name = stringResource(R.string.rootless_audio_copy), onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(clipboardLabel, bridge.report))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            })
        }
    }
}

@Composable
fun RootlessSensorControls(sensors: AirPodsSensorSession) {
    val state by sensors.state.collectAsState()
    if (!state.supportsHeartRate && !state.supportsMotion) return
    DisposableEffect(sensors) {
        onDispose { sensors.setHeartRateEnabled(false); sensors.setMotionEnabled(false) }
    }
    Spacer(Modifier.height(16.dp))
    StyledList(title = stringResource(R.string.rootless_sensors_title)) {
        if (state.supportsHeartRate) {
            StyledToggle(label = stringResource(R.string.rootless_heart_rate),
                description = state.heartRate?.let { stringResource(R.string.rootless_heart_rate_reading, it) }
                    ?: stringResource(if (state.heartRateEnabled) R.string.rootless_heart_rate_waiting else R.string.rootless_heart_rate_hint),
                checked = state.heartRateEnabled, onCheckedChange = { sensors.setHeartRateEnabled(it) })
            state.heartRateError?.let { error -> item { _, _ -> SupportingText(error) } }
        }
        if (state.supportsMotion) {
            StyledToggle(label = stringResource(R.string.rootless_movement),
                description = stringResource(R.string.rootless_movement_description),
                checked = state.motionEnabled, onCheckedChange = { sensors.setMotionEnabled(it) })
            if (state.motionEnabled) item { _, _ -> SensorMotionPlot(state.motion) }
            state.motionError?.let { error -> item { _, _ -> SupportingText(error) } }
        }
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun SensorMotionPlot(motion: RtBuddySensors.Motion?) {
    val points = remember { mutableStateListOf<RtBuddySensors.Motion>() }
    LaunchedEffect(motion) {
        if (motion == null) points.clear()
        else { points += motion; if (points.size > 64) points.removeAt(0) }
    }
    val horizontal = MaterialTheme.colorScheme.primary
    val vertical = MaterialTheme.colorScheme.tertiary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val description = if (motion == null) stringResource(R.string.rootless_movement_waiting)
        else stringResource(R.string.rootless_movement_reading, motion.horizontal, motion.vertical)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }) {
            val bound = maxOf(1000, points.maxOfOrNull { maxOf(abs(it.horizontal), abs(it.vertical)) } ?: 0) * 1.2f
            val middle = size.height / 2
            drawLine(baseline, Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
            for (index in 1 until points.size) {
                val previous = points[index - 1]
                val next = points[index]
                val x0 = (index - 1) * size.width / 63
                val x1 = index * size.width / 63
                drawLine(horizontal, Offset(x0, middle - previous.horizontal / bound * middle),
                    Offset(x1, middle - next.horizontal / bound * middle), 2.dp.toPx())
                drawLine(vertical, Offset(x0, middle - previous.vertical / bound * middle),
                    Offset(x1, middle - next.vertical / bound * middle), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx())))
            }
        }
    }
}

private val MICROPHONE_MODELS = setOf("A3053", "A3050", "A3054", "A3056", "A3055", "A3057",
    "A3531", "A3532", "A3533", "A3439", "A3440", "A3441", "A2931", "A2699", "A2698",
    "A3047", "A3048", "A3049", "A3063", "A3064", "A3065", "A3454")
private val DIAGNOSTIC_LABELS = listOf("playbackCapture" to R.string.rootless_check_playback,
    "microphonePolicy" to R.string.rootless_check_microphone, "callAudio" to R.string.rootless_check_calls,
    "avdtpPsm19" to R.string.rootless_check_transport, "appleAacEldEncoder" to R.string.rootless_check_encoder)
