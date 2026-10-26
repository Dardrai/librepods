package me.kavishdevar.librepods.presentation.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun DongleSettingsScreen(navigateBack: (() -> Unit)?, navigateToAccommodations: (() -> Unit)? = null) {
    StyledScaffold(title = stringResource(R.string.dongle_title), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = bottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DongleSettingsSection()
            navigateToAccommodations?.let { navigate ->
                StyledList(title = stringResource(R.string.accessibility)) {
                    styledListItem(contentText = stringResource(R.string.headphone_accommodations), onClick = navigate)
                }
            }
        }
    }
}

@Composable
internal fun DongleSettingsSection(onManage: (() -> Unit)? = null) {
    val context = LocalContext.current
    val service = rememberLibrePodsService()
    val controller = service?.dongleControls
    val fallback = remember { MutableStateFlow(DongleState()) }
    val state by (controller?.state ?: fallback).collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionDenied = !it; if (it) choosing = true
    }
    val choose = {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) choosing = true
        else permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (onManage != null && !state.selected) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StyledList(title = stringResource(R.string.dongle_title)) {
            styledListItem(contentText = state.name, supportingContent = {
                Text(stringResource(when {
                    state.busy -> R.string.dongle_working
                    !state.connected -> R.string.dongle_offline
                    state.status?.controlsReady == true -> R.string.dongle_ready
                    else -> R.string.dongle_waiting
                }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }, onClick = onManage)
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
                if (state.selected) styledListItem(contentText = stringResource(R.string.dongle_forget), onClick = { controller?.forget() }, enabled = !state.busy)
            }
        }
        if (permissionDenied) Text(stringResource(R.string.dongle_permission), color = MaterialTheme.colorScheme.error)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (onManage == null && state.connected && controller != null) DongleControls(state, controller)
    }
    if (choosing) {
        val choices = remember(controller, choosing) { controller?.choices().orEmpty() }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.dongle_choose)) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.dongle_pair_first))
                choices.forEach { choice -> TextButton(onClick = {
                    controller?.select(choice.address); choosing = false
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(choice.name) } }
            } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.dongle_close)) } })
    }
}

@Composable
internal fun ObserveDongleControls(controller: DongleController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        controller.controlsVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.controlsVisible(true)
            if (event == Lifecycle.Event.ON_STOP) { controller.controlsVisible(false); controller.stopFinder() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.controlsVisible(false); controller.stopFinder() }
    }
}

@Composable
internal fun DongleControls(state: DongleState, controller: DongleController) {
    val locale = LocalLocale.current.platformLocale
    ObserveDongleControls(controller)
    val enabled = state.status?.controlsReady == true && state.status.microphoneActive == false
    val onOff = listOf(1L to stringResource(R.string.dongle_on), 2L to stringResource(R.string.dongle_off))
    StyledList(title = stringResource(R.string.dongle_listening)) {
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_mode), 0x0d, state, enabled, listOf(
            1L to stringResource(R.string.dongle_off), 2L to stringResource(R.string.dongle_anc),
            3L to stringResource(R.string.dongle_transparency), 4L to stringResource(R.string.dongle_adaptive)), controller) }
        for ((id, label) in listOf(0x28 to R.string.dongle_conversation, 0x26 to R.string.dongle_personalized,
            0x1b to R.string.dongle_one_bud, 0x34 to R.string.dongle_allow_off)) {
            item { _, _ -> SettingChoice(stringResource(label), id, state, enabled, onOff, controller) }
        }
    }
    StyledList {
        styledToggle(label = stringResource(R.string.dongle_autopause), checked = state.automaticPause,
            description = stringResource(R.string.dongle_autopause_description), enabled = !state.busy, onCheckedChange = controller::automaticPause)
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_ear_setting), 0x0a, state, enabled,
            listOf(2L to stringResource(R.string.dongle_on), 1L to stringResource(R.string.dongle_off)), controller) }
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_sleep), 0x35, state, enabled, onOff, controller) }
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_microphone), 1, state, enabled, listOf(
            0L to stringResource(R.string.dongle_automatic), 1L to stringResource(R.string.dongle_right), 2L to stringResource(R.string.dongle_left)), controller) }
    }
    val intervals = listOf(0L to stringResource(R.string.dongle_default), 1L to stringResource(R.string.dongle_slower), 2L to stringResource(R.string.dongle_slowest))
    StyledList(title = stringResource(R.string.dongle_stem)) {
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_hold), 0x16, state, enabled, listOf(
            0x0505L to stringResource(R.string.dongle_hold_noise), 0x0501L to stringResource(R.string.dongle_hold_assistant_left),
            0x0105L to stringResource(R.string.dongle_hold_assistant_right), 0x0101L to stringResource(R.string.dongle_hold_assistant_both)), controller) }
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_swipe), 0x25, state, enabled, onOff, controller) }
        item { _, _ -> CycleModes(state, enabled, controller) }
        for ((id, label) in listOf(0x17 to R.string.dongle_press_speed, 0x18 to R.string.dongle_hold_duration, 0x23 to R.string.dongle_swipe_duration))
            item { _, _ -> SettingChoice(stringResource(label), id, state, enabled, intervals, controller) }
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_call_controls), 0x24, state, enabled, listOf(
            0x0200L to stringResource(R.string.dongle_call_once), 0x0300L to stringResource(R.string.dongle_call_twice)), controller) }
    }
    StyledList(title = stringResource(R.string.dongle_case)) {
        item { _, _ -> SettingChoice(stringResource(R.string.dongle_case_sounds), 0x31, state, enabled, onOff, controller) }
    }
    ControlRange(stringResource(R.string.dongle_case_volume), 0x40, state, enabled, controller)
    ControlRange(stringResource(R.string.dongle_chime_volume), 0x1f, state, enabled, controller)
    ControlRange(stringResource(R.string.dongle_adaptive_strength), 0x2e, state, enabled, controller)
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
        StyledList(title = stringResource(R.string.dongle_audio)) {
            item { _, _ -> OptionPicker(stringResource(R.string.dongle_codec), profile.requestedCodec.toLong(), enabled, codecs) {
                setAudioProfile(it.toInt(), profile.rateMode, profile.requestedBitrate)
            } }
            item { _, _ -> OptionPicker(stringResource(R.string.dongle_rate_mode), profile.rateMode.toLong(), enabled && profile.requestedCodec == 3,
                dongleAudioRateModes(profile.requestedCodec).map { it.toLong() to stringResource(if (it == 0) R.string.dongle_constant else R.string.dongle_variable) }) {
                setAudioProfile(profile.requestedCodec, it.toInt(), profile.requestedBitrate)
            } }
            item { _, _ -> OptionPicker(stringResource(R.string.dongle_bitrate), profile.requestedBitrate, enabled && profile.requestedCodec in 2..3,
                dongleAudioBitrates(profile.requestedCodec).map { it to if (it == 0L) stringResource(R.string.dongle_automatic) else "${it / 1000} kbps" }) {
                setAudioProfile(profile.requestedCodec, profile.rateMode, it)
            } }
            styledListItem(contentText = stringResource(R.string.dongle_active_codec,
                codecs.firstOrNull { it.first == profile.actualCodec.toLong() && it.first != 0L }?.second ?: "—", profile.actualBitrate / 1000))
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
    StyledList(title = stringResource(R.string.dongle_hearing), description = stringResource(R.string.dongle_hearing_description)) {
        state.hearingModes.hearingAidEnabled?.let { value -> styledToggle(label = stringResource(R.string.dongle_hearing_aid), checked = value,
            enabled = enabled && !state.busy && state.hearingModes.canChange(HearingMode.HEARING_AID, !value), onCheckedChange = { controller.hearingMode(HearingMode.HEARING_AID, it) }) }
        state.hearingModes.mediaAssistEnabled?.let { value -> styledToggle(label = stringResource(R.string.dongle_media_assist), checked = value,
            enabled = enabled && !state.busy && state.hearingModes.canChange(HearingMode.MEDIA_ASSIST, !value), onCheckedChange = { controller.hearingMode(HearingMode.MEDIA_ASSIST, it) }) }
    }
    DongleHearingControls(state, controller, enabled)
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

@Composable
private fun ControlRange(label: String, id: Int, state: DongleState, enabled: Boolean, controller: DongleController) {
    val editor = remember(controller, id) { controller.liveSettingEditor(id) }
    val live by editor.state.collectAsState()
    val reported = live.displayed
    val value = reported?.let { if (id == 0x1f) it and 255 else it }?.takeIf { it <= 100 }
    val selected = value?.toFloat() ?: 50f
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(live.revision) {
            StyledSlider(label = label, value = selected, valueRange = 0f..100f, independent = true, enabled = enabled,
                onValueChange = { editor.edit(live.revision, it.roundToInt().toLong() or if (id == 0x1f) 0x5000L else 0L) },
                onValueChangeFinished = editor::flush, onValueChangeCanceled = { editor.cancel(live.revision) },
                description = if (value == null) stringResource(R.string.dongle_choose_value) else null)
        }
    }
}

@Composable
private fun CycleModes(state: DongleState, enabled: Boolean, controller: DongleController) {
    var open by remember { mutableStateOf(false) }
    val editor = remember(controller) { controller.liveSettingEditor(0x1a) }
    val live by editor.state.collectAsState()
    val current = live.displayed?.toInt()
    var chosen by remember(open, live.revision) { mutableStateOf(current ?: 0) }
    val allowOff = state.requested[0x34] == 1L || state.settings[0x34]?.value == 1L || (current?.and(1) ?: 0) != 0
    val modes = listOf(1 to stringResource(R.string.dongle_off), 2 to stringResource(R.string.dongle_anc),
        4 to stringResource(R.string.dongle_transparency), 8 to stringResource(R.string.dongle_adaptive))
    StyledListItem(onClick = { open = true }, enabled = enabled, contentText = stringResource(R.string.dongle_cycle_modes),
        supportingText = current?.let { value -> modes.filter { value and it.first != 0 }.joinToString { it.second } }
            ?: stringResource(R.string.dongle_unknown), orientation = StyledListItemOrientation.Vertical)
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.dongle_cycle_modes)) }, text = {
        Column {
            Text(stringResource(R.string.dongle_cycle_minimum))
            modes.forEach { (bit, name) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = chosen and bit != 0,
                    enabled = enabled && (bit != 1 || allowOff), role = Role.Checkbox,
                    onValueChange = { checked ->
                        chosen = if (checked) chosen or bit else chosen and bit.inv()
                        editor.edit(live.revision, chosen.toLong().takeIf { Integer.bitCount(chosen) >= 2 })
                    })) {
                    Checkbox(checked = chosen and bit != 0, onCheckedChange = null, enabled = bit != 1 || allowOff)
                    Text(name, Modifier.padding(top = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.dongle_close)) } })
}

@Composable
private fun SettingChoice(label: String, id: Int, state: DongleState, enabled: Boolean,
    choices: List<Pair<Long, String>>, controller: DongleController) {
    val editor = remember(controller, id) { controller.liveSettingEditor(id) }
    val live by editor.state.collectAsState()
    key(live.revision) { OptionPicker(label, live.displayed, enabled, choices) { editor.edit(live.revision, it) } }
}

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

@Composable
private fun DongleHearingControls(state: DongleState, controller: DongleController, enabled: Boolean) {
    val editor = remember(controller) { controller.liveHearingEditor() }
    val live by editor.state.collectAsState()
    val profile = parseHearingAidSettingsResponse(live.displayed) ?: return
    val writable = enabled && hearingAidSettingsWritable(live.displayed) && state.hearingModes.enrolled == true &&
        state.hearingModes.clinicalReportsKnown
    fun edit(transform: (HearingAidSettings) -> HearingAidSettings) {
        editor.edit(live.revision) transform@{ baseline ->
            val original = parseHearingAidSettingsResponse(baseline) ?: return@transform null
            var requested: ByteArray? = null
            sendHearingAidSettings(baseline, transform(original)) { _, data, _ -> requested = data }
            requested
        }
    }
    key(live.revision) {
        StyledSlider(label = stringResource(R.string.dongle_gain), value = profile.netAmplification,
            valueRange = -1.5f..1.5f, independent = true, enabled = writable,
            onValueChange = { gain -> edit { it.withAmplification(gain, it.balance) } },
            onValueChangeFinished = editor::flush, onValueChangeCanceled = { editor.cancel(live.revision) })
        StyledSlider(label = stringResource(R.string.dongle_balance), value = profile.balance,
            valueRange = -3f..3f, independent = true, enabled = writable,
            onValueChange = { balance -> edit { it.withAmplification(it.netAmplification, balance) } },
            onValueChangeFinished = editor::flush, onValueChangeCanceled = { editor.cancel(live.revision) })
        StyledSlider(label = stringResource(R.string.dongle_tone), value = (profile.leftTone + profile.rightTone) / 2,
            valueRange = -1f..1f, independent = true, enabled = writable,
            onValueChange = { tone -> edit { it.copy(leftTone = tone, rightTone = tone) } },
            onValueChangeFinished = editor::flush, onValueChangeCanceled = { editor.cancel(live.revision) })
        StyledList { styledToggle(label = stringResource(R.string.dongle_boost),
            checked = profile.leftConversationBoost && profile.rightConversationBoost, enabled = writable,
            onCheckedChange = { boost -> edit { it.copy(leftConversationBoost = boost, rightConversationBoost = boost) } }) }
    }
}
