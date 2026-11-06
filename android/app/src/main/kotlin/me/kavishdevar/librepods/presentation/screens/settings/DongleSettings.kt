package me.kavishdevar.librepods.presentation.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.NumberFormat

@Composable
fun DongleSettingsScreen(navigateBack: (() -> Unit)?, navigateToAirPods: (() -> Unit)? = null) {
    StyledScaffold(title = stringResource(R.string.dongle_title), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = bottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DongleSettingsSection()
            navigateToAirPods?.let { navigate ->
                StyledList {
                    styledListItem(contentText = stringResource(R.string.more_settings_title), onClick = navigate)
                }
            }
        }
    }
}

@Composable
internal fun DongleSettingsSection(onManage: (() -> Unit)? = null,
    controller: DongleController? = rememberLibrePodsService()?.dongleControls) {
    val context = LocalContext.current
    val fallback = remember { MutableStateFlow(DongleState()) }
    val state by (controller?.state ?: fallback).collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionDenied = !it; choosing = true
    }
    val choose = { choosing = true }
    if (onManage != null && !state.connected) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StyledList(title = stringResource(R.string.dongle_title)) {
            styledListItem(contentText = state.name, orientation = StyledListItemOrientation.Vertical, supportingContent = {
                Text(stringResource(when {
                    state.busy -> R.string.dongle_working
                    !state.connected && state.usbPermissionRequired -> R.string.dongle_usb_permission
                    !state.connected -> R.string.dongle_offline
                    state.status?.flags?.and(32) == 32 -> R.string.dongle_phone_pairing
                    state.status?.connected != true -> R.string.dongle_waiting
                    state.status?.controlsReady == true -> R.string.dongle_ready
                    else -> R.string.dongle_waiting
                }), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }, onClick = onManage)
            if (state.connected && state.usb) styledListItem(contentText = stringResource(
                if (state.usbAudioAvailable) R.string.dongle_usb_audio_available else R.string.dongle_usb_controls_only))
            if (state.connected && state.status != null) {
                val status = state.status!!
                val cached = stringResource(R.string.island_battery_cached)
                val readings = status.batteries.mapIndexed { index, value -> when {
                    value == null -> "—"
                    status.batteryLive(index) -> "$value%"
                    else -> stringResource(R.string.island_battery_description, "$value%", cached)
                } }
                styledListItem(contentText = stringResource(R.string.dongle_batteries, *readings.toTypedArray()))
            }
            if (onManage != null) styledListItem(contentText = stringResource(R.string.dongle_manage), onClick = onManage)
            else {
                styledListItem(contentText = stringResource(R.string.dongle_choose), onClick = choose, enabled = controller != null && !state.busy)
                if (state.selected) styledListItem(contentText = stringResource(if (state.connected) R.string.dongle_disconnect else R.string.dongle_connect),
                    onClick = { if (state.connected) controller?.disconnect() else controller?.reconnect() }, enabled = !state.busy)
                if (state.connected && state.status?.capabilities?.and(1) == 1) {
                    styledListItem(contentText = stringResource(R.string.dongle_reconnect_airpods), onClick = { controller?.pairingCommand(1) }, enabled = !state.busy)
                    styledListItem(contentText = stringResource(R.string.dongle_pair_airpods), onClick = { pairing = true }, enabled = !state.busy)
                }
                if (state.connected && state.status?.capabilities?.and(4) == 4)
                    styledListItem(contentText = stringResource(R.string.dongle_pair_phone), onClick = { controller?.pairingCommand(4) }, enabled = !state.busy)
                if (state.selected) styledListItem(contentText = stringResource(R.string.dongle_forget), onClick = { controller?.forget() }, enabled = !state.busy)
                if ((state.usbVolumeRequested || state.usbVolumeEnabled) && (!state.connected || state.audio == null || state.status?.capabilities?.and(128) != 128))
                    styledToggle(label = stringResource(R.string.dongle_hardware_volume), checked = true,
                        enabled = controller != null, description = stringResource(R.string.dongle_hardware_volume_unavailable),
                        onCheckedChange = { controller?.usbHardwareVolume(it) })
            }
        }
        if (permissionDenied) Text(stringResource(R.string.dongle_permission), color = MaterialTheme.colorScheme.error)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (onManage == null && state.connected && controller != null) DongleControls(state, controller)
    }
    if (pairing) AlertDialog(onDismissRequest = { pairing = false }, title = { Text(stringResource(R.string.dongle_pair_airpods)) },
        text = { Text(stringResource(R.string.dongle_pair_airpods_description)) },
        confirmButton = { TextButton(onClick = { pairing = false; controller?.pairingCommand(2) }) { Text(stringResource(R.string.dongle_pair_airpods)) } },
        dismissButton = { TextButton(onClick = { pairing = false }) { Text(stringResource(R.string.cancel)) } })
    if (choosing) {
        val choices = controller?.choices().orEmpty()
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.dongle_choose)) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.dongle_attach_or_pair))
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    TextButton(onClick = { choosing = false; permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text(stringResource(R.string.dongle_permission)) }
                choices.forEach { choice -> TextButton(onClick = {
                    controller?.select(choice.address); choosing = false
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (choice.usb) R.string.dongle_choice_usb else R.string.dongle_choice_bluetooth, choice.name))
                } }
            } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.dongle_close)) } })
    }
}

@Composable
internal fun ObserveDongleControls(controller: DongleController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val owner = Any()
        controller.controlsVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), owner)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.controlsVisible(true, owner)
            if (event == Lifecycle.Event.ON_STOP) { controller.controlsVisible(false, owner); controller.stopFinder() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.controlsVisible(false, owner); controller.stopFinder() }
    }
}

@Composable
internal fun DongleControls(state: DongleState, controller: DongleController) {
    val locale = LocalLocale.current.platformLocale
    ObserveDongleControls(controller)
    val enabled = state.status?.controlsReady == true && state.status.microphoneActive == false
    val profileEnabled = enabled && state.status.flags and (4 or 64) == 0 &&
        state.audio?.let { it.flags and 7 == 0 && it.gameState !in listOf(1, 2, 6) } == true
    if (!state.usb) StyledList {
        styledToggle(label = stringResource(R.string.dongle_autopause), checked = state.automaticPause,
            description = stringResource(R.string.dongle_autopause_description), enabled = !state.busy, onCheckedChange = controller::automaticPause)
    }
    if (state.status?.capabilities?.and(0x10) == 0x10 && state.audio != null) {
        val liveAudio by controller.liveAudioEditor().state.collectAsState()
        val gameEditor = controller.liveGameModeEditor()
        val liveGame by gameEditor.state.collectAsState()
        val gameRevision = liveGame.revision
        val gameRequested = liveGame.displayed ?: state.audio.gameRequested
        val profile = liveAudio.displayed?.let { state.audio.copy(requestedCodec = it.codec, rateMode = it.rateMode, requestedBitrate = it.bitrate) } ?: state.audio
        val codecs = listOf(0L to stringResource(R.string.dongle_automatic), 3L to "AAC-ELD", 2L to "AAC", 1L to "SBC")
        val setAudioProfile = { codec: Int, rateMode: Int, bitrate: Long ->
            val request = dongleAudioRequest(codec, rateMode, bitrate)
            controller.audioProfile(request.codec, request.rateMode, request.bitrate)
        }
        key(controller, state.controlSession, state.status?.generation, state.status?.address) {
        StyledList(title = stringResource(R.string.dongle_audio), description = if (!profileEnabled) stringResource(R.string.dongle_profile_idle) else null) {
            item { _, _ -> OptionPicker(stringResource(R.string.dongle_codec), profile.requestedCodec.toLong(), profileEnabled, codecs) {
                setAudioProfile(it.toInt(), profile.rateMode, profile.requestedBitrate)
            } }
            item { _, _ -> OptionPicker(stringResource(R.string.dongle_rate_mode), profile.rateMode.toLong(), profileEnabled && profile.requestedCodec == 3,
                dongleAudioRateModes(profile.requestedCodec).map { it.toLong() to stringResource(if (it == 0) R.string.dongle_constant else R.string.dongle_variable) }) {
                setAudioProfile(profile.requestedCodec, it.toInt(), profile.requestedBitrate)
            } }
            item { _, _ -> OptionPicker(stringResource(R.string.dongle_bitrate), profile.requestedBitrate, profileEnabled && profile.requestedCodec in 2..3,
                dongleAudioBitrates(profile.requestedCodec).map { it to if (it == 0L) stringResource(R.string.dongle_automatic) else "${it / 1000} kbps" }) {
                setAudioProfile(profile.requestedCodec, profile.rateMode, it)
            } }
            styledListItem(contentText = stringResource(R.string.dongle_active_codec,
                codecs.firstOrNull { it.first == profile.actualCodec.toLong() && it.first != 0L }?.second ?: "—",
                formatDongleEncoderBitrateLimit(profile.actualBitrate, locale)))
            if (state.usb && state.status.capabilities and 128 != 0)
                styledToggle(label = stringResource(R.string.dongle_hardware_volume), checked = state.usbVolumeRequested || state.usbVolumeEnabled,
                    enabled = state.usbVolumeRequested || state.usbVolumeEnabled || state.usbVolumeAvailable && profileEnabled,
                    description = when {
                        state.usbVolumePending -> stringResource(R.string.dongle_working)
                        !state.usbVolumeAvailable -> stringResource(R.string.dongle_hardware_volume_unavailable)
                        !state.usbVolumeEnabled && state.error in listOf(stringResource(R.string.dongle_volume_failed), stringResource(R.string.dongle_volume_restore_failed)) -> state.error
                        !state.usbVolumeEnabled && !profileEnabled -> stringResource(R.string.dongle_profile_idle)
                        !state.usbVolumeEnabled && state.usbVolumeRequested -> stringResource(R.string.dongle_working)
                        else -> null
                    }, onCheckedChange = controller::usbHardwareVolume)
            styledToggle(label = stringResource(R.string.dongle_game_mode), checked = gameRequested,
                enabled = enabled && state.connected && (gameRequested || profile.flags and 8 != 0), description = when (profile.gameState) {
                    1 -> stringResource(R.string.dongle_game_pending)
                    4 -> stringResource(R.string.dongle_game_failed)
                    5 -> stringResource(R.string.dongle_game_unsupported)
                    6 -> stringResource(R.string.dongle_game_restoring)
                    else -> null
                }, onCheckedChange = { gameEditor.edit(gameRevision, it) })
        }
        }
    }
    StyledList(title = stringResource(R.string.dongle_checks), description = stringResource(R.string.dongle_checks_description)) {
        state.diagnostics?.let { checks ->
            for ((ear, result) in listOf(R.string.dongle_left to checks.left, R.string.dongle_right to checks.right))
                styledListItem(contentText = stringResource(R.string.dongle_check_result, stringResource(ear), result.generalStatus, result.measurementStatus))
        }
    }
    if (state.status?.capabilities?.and(0x10) == 0x10) {
        StyledList(title = stringResource(R.string.dongle_performance),
            description = if (state.performance != null) stringResource(R.string.dongle_priming) else null) {
            styledListItem(contentText = stringResource(R.string.dongle_performance_read), onClick = controller::readPerformance, enabled = !state.busy)
            state.performance?.fields?.let { values ->
                if (listOf(1, 2, 3).all { values[it] != 0xffffffffL })
                    styledListItem(contentText = stringResource(R.string.dongle_queue, values[1], values[2], values[3]))
                if (listOf(4, 5).all { values[it] != 0xffffffffL })
                    styledListItem(contentText = stringResource(R.string.dongle_drops, values[4], values[5]))
                if (listOf(7, 8).all { values[it] != 0xffffffffL })
                    styledListItem(contentText = stringResource(R.string.dongle_encode, values[7], values[8]))
                if (listOf(13, 11).all { values[it] != 0xffffffffL })
                    styledListItem(contentText = stringResource(R.string.dongle_heap, values[13], values[11]))
            }
        }
        StyledList(key = state.finderOpen to state.nearby?.identityAvailable,
            title = stringResource(R.string.dongle_nearby), description = stringResource(R.string.dongle_finder_description)) {
            if (state.finderOpen) {
                styledListItem(contentText = stringResource(R.string.dongle_finder_stop), onClick = controller::stopFinder)
                state.nearby?.let { value ->
                    styledListItem(contentText = stringResource(when (value.state) {
                        3 -> R.string.dongle_finder_old; 4 -> R.string.dongle_finder_paused; else -> R.string.dongle_finder_wait
                    }))
                    value.smoothedRssi?.let { rssi -> styledListItem(contentText = stringResource(R.string.dongle_finder_signal,
                        String.format(locale, "%.1f", rssi), stringResource(when (value.trend) {
                            1 -> R.string.dongle_stronger; 2 -> R.string.dongle_steady; 3 -> R.string.dongle_weaker; else -> R.string.dongle_trend_unknown
                        }))) }
                    value.ageMs?.let { age ->
                        val seconds = (age / 1000).toInt()
                        styledListItem(contentText = pluralStringResource(R.plurals.dongle_finder_last, seconds, seconds))
                    }
                }
            } else {
                if (state.nearby?.identityAvailable == false) styledListItem(contentText = stringResource(R.string.dongle_finder_identity))
                styledListItem(contentText = stringResource(R.string.dongle_finder_start), onClick = controller::startFinder, enabled = !state.busy)
            }
        }
    }
}

internal fun formatDongleEncoderBitrateLimit(bitrate: Long, locale: java.util.Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 3
        isGroupingUsed = false
    }.format(bitrate / 1000.0)

@Composable
private fun OptionPicker(label: String, value: Long?, enabled: Boolean, choices: List<Pair<Long, String>>,
    onChoose: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.first == value }?.second ?: stringResource(R.string.dongle_unknown)
    StyledListItem(onClick = { open = true }, contentText = label, enabled = enabled,
        orientation = StyledListItemOrientation.Vertical,
        supportingText = current)
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column { choices.forEach { (value, name) ->
            TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
                onClick = { onChoose(value); open = false }) { Text(name) }
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.dongle_close)) } })
}
