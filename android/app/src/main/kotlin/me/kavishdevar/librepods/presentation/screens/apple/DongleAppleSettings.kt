package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.data.LiveValueState
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.dongle.appleUiState
import me.kavishdevar.librepods.dongle.canWriteControl
import me.kavishdevar.librepods.dongle.setAppleControl
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.screens.settings.ObserveDongleControls
import me.kavishdevar.librepods.presentation.screens.settings.rememberLibrePodsService
import kotlin.math.roundToInt

private fun dongleHoldActionByte(action: StemAction): Long = if (action == StemAction.DIGITAL_ASSISTANT) 1 else 5

/** Hold actions are one paired record: the low byte is right, the next byte is left. */
internal fun dongleHoldActionValue(current: Long?, side: String, action: StemAction): Long? {
    val pair = current?.and(0xffff) ?: return null
    if ((pair and 255) !in listOf(1L, 5L) || pair.shr(8) !in listOf(1L, 5L)) return null
    val shift = when (side) { "left" -> 8; "right" -> 0; else -> return null }
    val next = dongleHoldActionByte(action)
    return pair.and((255L shl shift).inv()).or(next shl shift)
}

@Composable
internal fun DongleAppleSettings(page: String, argument: String, navigateBack: (() -> Unit)?,
    navigate: (String, String) -> Unit, navigateToDongleSettings: () -> Unit,
    controller: DongleController? = rememberLibrePodsService()?.dongleControls) {
    val fallback = remember { MutableStateFlow(DongleState()) }
    val state by (controller?.state ?: fallback).collectAsState()
    if (controller != null && page !in setOf("accommodations", "transparency")) ObserveDongleControls(controller)
    val emptyListening = remember { MutableStateFlow(LiveValueState<Long>(null)) }
    val listeningMode by (controller?.liveSettingEditor(0x0d)?.state ?: emptyListening).collectAsState()
    val adaptiveStrength by (controller?.liveSettingEditor(0x2e)?.state ?: emptyListening).collectAsState()
    val ui = state.appleUiState(listeningMode = listeningMode, adaptiveStrength = adaptiveStrength)
    val connected = state.connected && state.status?.controlsReady == true
    val writeInt: (ControlCommandIdentifier, Int) -> Unit = { id, value ->
        if (state.canWriteControl(id)) controller?.setAppleControl(id, value)
    }
    val writeBoolean: (ControlCommandIdentifier, Boolean) -> Unit = { id, value ->
        if (state.canWriteControl(id)) controller?.setAppleControl(id, value)
    }
    when (page) {
        "long_press" -> {
            val editor = controller?.liveSettingEditor(0x1a)
            val live by (editor?.state ?: remember { MutableStateFlow(LiveValueState<Long>(null)) }).collectAsState()
            val cycle = LiveValueState(live.value?.toInt(), live.requested?.toInt(), live.revision, live.pending)
            val holdEditor = controller?.liveSettingEditor(0x16)
            val hold by (holdEditor?.state ?: remember { MutableStateFlow(LiveValueState<Long>(null)) }).collectAsState()
            val pair = hold.displayed
            val actionsKnown = pair != null && (pair and 255) in listOf(1L, 5L) && (pair shr 8 and 255) in listOf(1L, 5L)
            val holdUi = if (!actionsKnown) ui else checkNotNull(pair).let { current ->
                ui.copy(settings = ui.settings.copy(
                    leftLongPressAction = if ((current.shr(8) and 255) == 1L) StemAction.DIGITAL_ASSISTANT else StemAction.CYCLE_NOISE_CONTROL_MODES,
                    rightLongPressAction = if ((current and 255) == 1L) StemAction.DIGITAL_ASSISTANT else StemAction.CYCLE_NOISE_CONTROL_MODES))
            }
            LongPressSettings(holdUi, argument, state.canWriteControl(ControlCommandIdentifier.LISTENING_MODE_CONFIGS), live.pending,
                navigateBack, setAction = { side, action ->
                    if (action != StemAction.DIGITAL_ASSISTANT)
                        dongleHoldActionValue(pair, side, action)?.let { holdEditor?.edit(hold.revision, it) }
                }, applyCycle = { mask, _ -> editor?.edit(live.revision, mask.toLong()) }, liveCycle = cycle,
                cancelCycle = { editor?.cancel(live.revision) },
                actionsEnabled = state.canWriteControl(ControlCommandIdentifier.CLICK_HOLD_MODE) && actionsKnown,
                actionsKnown = actionsKnown, assistantAvailable = false, initialActions = {
                    InitialDongleHoldActions(
                        owner = listOf(controller, state.controlSession, state.status?.generation, state.status?.address),
                        revision = hold.revision, enabled = state.canWriteControl(ControlCommandIdentifier.CLICK_HOLD_MODE),
                        assistantAvailable = false,
                        onComplete = { value -> value == 0x0505L && holdEditor?.edit(hold.revision, value) == true })
                })
        }
        "microphone" -> MicrophoneSettingsScreen(navigateBack,
            selectedMode = ui.state.effectiveControl(ControlCommandIdentifier.MIC_MODE, ui.settings)?.firstOrNull()?.toInt(),
            enabled = state.canWriteControl(ControlCommandIdentifier.MIC_MODE),
            onMicrophoneSettingsChanged = { writeInt(ControlCommandIdentifier.MIC_MODE, it) })
        "call" -> CallControlOptions(argument, ui.state.legacyEndCallSinglePress(),
            state.canWriteControl(ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG), navigateBack,
            supportedLayout = ui.state.canSetLegacyEndCall()) { single ->
                writeInt(ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG, if (single) 0x0200 else 0x0300)
            }
        "accessibility" -> AccessibilitySettingsContent(ui, navigateBack, { navigate("accommodations", "") },
            writeInt, writeBoolean, canWriteControl = state::canWriteControl, dongle = true,
            toneVolume = { DongleAppleRange(state, controller, ControlCommandIdentifier.CHIME_VOLUME, R.string.tone_volume) },
            caseVolume = { DongleAppleRange(state, controller, ControlCommandIdentifier.IN_CASE_TONE_VOLUME, R.string.dongle_case_volume) })
        "accommodations" -> DongleAccommodationsScreen(navigateBack, controller) { navigate("transparency", "") }
        "transparency" -> DongleTransparencyScreen(navigateBack, controller)
        "hearing" -> HearingAidContent(ui, connected, ui.vendorIdHook, navigateBack,
            { navigate("hearing_adjustments", "") }, showHearingTest = false,
            writable = connected && state.status?.microphoneActive == false && !state.busy,
            setHearingMode = { mode, enabled -> controller?.hearingMode(mode, enabled) })
        "hearing_adjustments" -> {
            val editor = controller?.liveHearingEditor()
            val live by (editor?.state ?: remember { MutableStateFlow(me.kavishdevar.librepods.data.LiveRecordState()) }).collectAsState()
            StyledScaffold(title = stringResource(R.string.adjustments), navigateBack = navigateBack) { top, bottom ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(top))
                    HearingAidAdjustmentControls(live.displayed, state.controlSession, live.revision,
                        enabled = connected && state.status?.microphoneActive == false && !state.accommodationsRequest.pending &&
                            state.hearingModes.pending == null && state.hearingModes.uncertain == null &&
                            state.hearingModes.enrolled == true && state.hearingModes.clinicalReportsKnown,
                        gainSwipe = false, gainSwipeEnabled = false, onGainSwipe = {},
                        onEdit = { editor?.edit(live.revision, it) }, onFinished = { editor?.flush() },
                        onCanceled = { editor?.cancel(live.revision) }, readStatus = state.hearingAidReadStatus, connected = connected)
                    Spacer(Modifier.height(bottom))
                }
            }
        }
        "version" -> VersionContent(ui.metadata, navigateBack)
        else -> AppleSettingsScreen(ui, writeInt, writeBoolean, writeATTCharacteristic = { _, _ -> },
            updateSettings = {}, disconnect = {}, navigateBack = navigateBack, navigateToRename = {},
            navigateToHearingProtection = {}, navigateToHearingAid = { navigate("hearing", "") },
            navigateToLeftLongPress = { navigate("long_press", "left") }, navigateToRightLongPress = { navigate("long_press", "right") },
            navigateToEqualizer = {}, navigateToHeadTracking = {}, navigateToAccessibility = { navigate("accessibility", "") },
            navigateToVersion = { navigate("version", "") }, navigateToCallControlScreen = { navigate("call", it) },
            navigateToMicrophoneSettings = { navigate("microphone", "") }, navigateToRecordingScreen = {},
            navigateToHeartRateScreen = {}, navigateToDebugScreen = {}, dongle = true,
            canWriteControl = state::canWriteControl, navigateToDongleSettings = navigateToDongleSettings)
    }
}

@Composable
private fun DongleAppleRange(state: DongleState, controller: DongleController?, id: ControlCommandIdentifier, label: Int) {
    val editor = controller?.liveSettingEditor(id.value.toInt() and 255)
    val live by (editor?.state ?: remember { MutableStateFlow(LiveValueState<Long>(null)) }).collectAsState()
    val value = live.displayed?.and(255)?.takeIf { it <= 100 }?.toFloat()
    key(state.controlSession, live.revision) {
        StyledSlider(label = stringResource(label), value = value ?: 50f, valueRange = 0f..100f, independent = true,
            enabled = state.canWriteControl(id), description = if (value == null) stringResource(R.string.dongle_unknown) else null,
            onValueChange = { level -> editor?.edit(live.revision, level.roundToInt().toLong().or(
                if (id == ControlCommandIdentifier.CHIME_VOLUME) 0x5000L else 0L)) },
            onValueChangeFinished = { editor?.flush() }, onValueChangeCanceled = { editor?.cancel(live.revision) })
    }
}

@Composable
internal fun InitialDongleHoldActions(owner: Any?, revision: Long, enabled: Boolean,
    assistantAvailable: Boolean = true, onComplete: (Long) -> Boolean) {
    var left by remember(owner, revision, enabled) { mutableStateOf<StemAction?>(null) }
    var right by remember(owner, revision, enabled) { mutableStateOf<StemAction?>(null) }
    var submitted by remember(owner, revision, enabled) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dongle_hold_initialize_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dongle_hold_initialize_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((side, title) in listOf("left" to R.string.left, "right" to R.string.right)) {
            val selected = if (side == "left") left else right
            StyledList(title = stringResource(title), modifier = Modifier.testTag("initial-hold-$side"), key = selected) {
                for ((action, label) in listOf(StemAction.CYCLE_NOISE_CONTROL_MODES to R.string.noise_control,
                    StemAction.DIGITAL_ASSISTANT to R.string.digital_assistant)) {
                    styledListItem(contentText = stringResource(label), selected = selected == action,
                        enabled = enabled && !submitted && (assistantAvailable || action != StemAction.DIGITAL_ASSISTANT), onClick = {
                            if (side == "left") left = action else right = action
                            val l = left
                            val r = right
                            if (l != null && r != null && !submitted)
                                submitted = onComplete((dongleHoldActionByte(l) shl 8) or dongleHoldActionByte(r))
                        })
                }
            }
        }
    }
}
