package me.kavishdevar.librepods.presentation.screens.settings

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.companion.CompanionState
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import kotlin.math.roundToInt
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.services.LibrePodsService

@Composable
internal fun CompanionSettingsSection() = CompanionSection()

@Composable
internal fun CompanionHomeSection(onOpenSettings: () -> Unit, service: LibrePodsService? = rememberLibrePodsService()) {
    if (PcControlsVisibility.current()) CompanionSection(onOpenSettings, service)
}

@Composable
internal fun rememberLibrePodsService(): LibrePodsService? {
    val context = LocalContext.current
    var service by remember { mutableStateOf<LibrePodsService?>(null) }
    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? LibrePodsService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        val bound = runCatching { context.bindService(Intent(context, LibrePodsService::class.java), connection, 0) }.getOrDefault(false)
        onDispose { if (bound) context.unbindService(connection) }
    }
    return service
}

@Composable
private fun CompanionSection(onOpenSettings: (() -> Unit)? = null, service: LibrePodsService? = rememberLibrePodsService()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localPhoneBusy by remember { mutableStateOf(false) }
    var localPhoneJob by remember { mutableStateOf<Job?>(null) }
    val controller = service?.companionHandoff
    val unavailable = stringResource(R.string.companion_service_unavailable)
    val fallback = remember(unavailable) { MutableStateFlow(CompanionState(detail = unavailable)) }
    val state by (controller?.state ?: fallback).collectAsState()
    val deviceFlow = remember(service) { service?.devices ?: MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap()) }
    val devices by deviceFlow.collectAsState()
    var setup by remember { mutableStateOf(false) }
    val displayedState = if (localPhoneBusy) state.copy(busy = true, detail = "Connecting phone audio…") else state
    CompanionSettingsContent(displayedState, controller != null, onLink = { if (onOpenSettings != null) onOpenSettings() else setup = true },
        onReconnect = { controller?.reconnect() }, onUnlink = { controller?.unlink() },
        onPhone = { controller?.transfer("android") }, onPc = { controller?.transfer("pc") },
        onCancel = { if (localPhoneBusy) localPhoneJob?.cancel() else controller?.cancelTransfer() }, onManage = onOpenSettings,
        onLocalPhone = if (service != null && controller?.linkedDevice() != null) ({
            if (!localPhoneBusy) {
                localPhoneBusy = true
                localPhoneJob = scope.launch {
                    try {
                        val connected = withContext(Dispatchers.IO) { service.connectCompanionPhone() }
                        if (!connected) android.widget.Toast.makeText(context, R.string.device_connect_failed, android.widget.Toast.LENGTH_LONG).show()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { android.widget.Toast.makeText(context, R.string.device_connect_failed, android.widget.Toast.LENGTH_LONG).show() }
                    finally { localPhoneBusy = false; localPhoneJob = null }
                }
            }
        }) else null)

    if (onOpenSettings == null && state.linked) {
        val pod = devices.values.filterIsInstance<AppleDevice>().firstOrNull { it.macAddress.value == controller?.linkedDevice() }
        val settings by (pod?.settings ?: remember { MutableStateFlow(AppleSettings()) }).collectAsState()
        val bridge by (service?.companionAudioAvailability ?: remember { MutableStateFlow(me.kavishdevar.librepods.shizuku.BridgeState()) }).collectAsState()
        var phonePermissionDenied by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            phonePermissionDenied = !granted
            if (granted) service?.setCompanionCallsEnabled(true)
        }
        CompanionPoliciesContent(settings, enabled = controller != null && pod != null && !displayedState.busy,
            syncIssue = state.settingsIssue,
            hostAvailable = service?.companionMediaAvailable() == true && !bridge.probing,
            permissionGranted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
            permissionDenied = phonePermissionDenied,
            onAutoPause = { pod?.updateSettings { current -> current.copy(hostAutoPause = it) } },
            onPopup = { pod?.updateSettings { current -> current.copy(showIslandPopup = it) } },
            onCache = { pod?.updateSettings { current -> current.copy(cacheDisconnectedComponentBattery = it) } },
            onCalls = { enabled ->
                if (!enabled) service?.setCompanionCallsEnabled(false)
                else if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
                    service?.setCompanionCallsEnabled(true)
                else permission.launch(Manifest.permission.READ_PHONE_STATE)
            }, onReturn = { service?.setCompanionCallsEnabled(settings.callTakeoverEnabled, returnAfterCall = it) },
            onRelative = { pod?.updateSettings { current -> current.copy(relativeConversationAwarenessVolumeEnabled = it) } },
            onFirst = { value -> pod?.updateSettings { current -> current.copy(conversationAwarenessVolume = value,
                conversationAwarenessReducedVolume = current.conversationAwarenessReducedVolume.coerceAtMost(value)) } },
            onSecond = { value -> pod?.updateSettings { current -> current.copy(conversationAwarenessReducedVolume = value.coerceAtMost(current.conversationAwarenessVolume)) } },
            onPauseConversation = { pod?.updateSettings { current -> current.copy(conversationAwarenessPauseMusicEnabled = it) } })
    }

    if (setup) {
        val pods = devices.values.filterIsInstance<AppleDevice>()
        val peers = pairedComputers(context, pods.map { it.macAddress.value }.toSet())
        CompanionLinkDialog(peers, pods, onDismiss = { setup = false }) { peer, device, token ->
            controller?.link(peer, device, token)
            setup = false
        }
    }
}

@Composable
internal fun CompanionPoliciesContent(settings: AppleSettings, enabled: Boolean, permissionGranted: Boolean, hostAvailable: Boolean = true,
    syncIssue: Boolean = false,
    permissionDenied: Boolean = false, onAutoPause: (Boolean) -> Unit, onPopup: (Boolean) -> Unit,
    onCache: (Boolean) -> Unit, onCalls: (Boolean) -> Unit, onReturn: (Boolean) -> Unit,
    onRelative: (Boolean) -> Unit = {}, onFirst: (Float) -> Unit = {}, onSecond: (Float) -> Unit = {},
    onPauseConversation: (Boolean) -> Unit = {}) {
    StyledList(title = stringResource(R.string.companion_shared_settings), description = stringResource(
        if (syncIssue) R.string.companion_settings_sync_failed else R.string.companion_shared_settings_description)) {
        styledToggle(label = stringResource(R.string.companion_host_auto_pause), description = stringResource(
            if (hostAvailable) R.string.companion_host_auto_pause_description else R.string.companion_host_auto_pause_unavailable),
            checked = settings.hostAutoPause, enabled = enabled && hostAvailable, onCheckedChange = onAutoPause)
        styledToggle(label = stringResource(R.string.companion_popup), checked = settings.showIslandPopup,
            enabled = enabled, onCheckedChange = onPopup)
        styledToggle(label = stringResource(R.string.companion_battery_cache), description = stringResource(R.string.companion_battery_cache_description),
            checked = settings.cacheDisconnectedComponentBattery, enabled = enabled, onCheckedChange = onCache)
    }
    val callsEnabled = settings.callTakeoverEnabled && settings.callTakeoverConsent && permissionGranted
    StyledList(title = stringResource(R.string.companion_calls), description = stringResource(R.string.companion_calls_description)) {
        styledToggle(label = stringResource(R.string.companion_take_calls), checked = callsEnabled,
            description = stringResource(if (permissionDenied) R.string.companion_calls_permission_denied else R.string.companion_take_calls_description),
            enabled = enabled, onCheckedChange = onCalls)
        styledToggle(label = stringResource(R.string.companion_return_after_call), description = stringResource(R.string.companion_return_after_call_description),
            checked = settings.callReturnEnabled, enabled = enabled && callsEnabled, onCheckedChange = onReturn)
    }
    val first = settings.conversationAwarenessVolume
    val second = settings.conversationAwarenessReducedVolume
    val validPolicy = first.isFinite() && second.isFinite() && first in 0f..100f && second in 0f..first
    StyledList(title = stringResource(R.string.companion_conversation_title), description = stringResource(
        if (validPolicy) R.string.companion_conversation_description else R.string.companion_conversation_unavailable)) {
        styledToggle(label = stringResource(R.string.relative_conversation_awareness_volume),
            description = stringResource(R.string.relative_conversation_awareness_volume_description),
            checked = settings.relativeConversationAwarenessVolumeEnabled, enabled = enabled && validPolicy, onCheckedChange = onRelative)
        item { index, count ->
            CompanionVolumeSlider(label = stringResource(R.string.companion_conversation_first, conversationPercent(settings.conversationAwarenessVolume)),
                value = first, onValueChange = { onFirst(it.roundToInt().toFloat()) },
                valueRange = 0f..100f, enabled = enabled && validPolicy, index = index, count = count)
        }
        item { index, count ->
            CompanionVolumeSlider(label = stringResource(R.string.companion_conversation_second, conversationPercent(settings.conversationAwarenessReducedVolume)),
                value = second, onValueChange = { onSecond(it.roundToInt().toFloat()) },
                valueRange = 0f..(if (first.isFinite()) first.coerceIn(1f, 100f) else 1f),
                enabled = enabled && validPolicy && first > 0, index = index, count = count)
        }
        styledToggle(label = stringResource(R.string.companion_pause_media_conversation),
            description = stringResource(R.string.conversation_awareness_pause_music_description),
            checked = settings.conversationAwarenessPauseMusicEnabled, enabled = enabled && validPolicy, onCheckedChange = onPauseConversation)
    }
}

private fun conversationPercent(value: Float): String = if (value.isFinite())
    java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else "—"

@Composable
private fun CompanionVolumeSlider(label: String, value: Float, onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean, index: Int, count: Int) {
    val valid = value.isFinite() && valueRange.endInclusive.isFinite() && valueRange.endInclusive > valueRange.start && value in valueRange
    val range = if (valid) valueRange else 0f..100f
    val current = if (valid) value.coerceIn(range) else 0f
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(current, range)
        if (enabled && valid) setProgress {
            if (!it.isFinite()) false else { onValueChange(it.coerceIn(range)); true }
        } else disabled()
    }) {
        // The incumbent Apple slider does not render its label; keep both designs named and accessible.
        Text(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        StyledSlider(value = current, onValueChange = onValueChange, valueRange = range,
            enabled = enabled && valid, index = index, count = count)
    }
}

private data class CompanionPeer(val address: String, val name: String?)

private fun pairedComputers(context: Context, airpods: Set<String>): List<CompanionPeer> {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return try {
        context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
            .filter { it.address !in airpods }.map { CompanionPeer(it.address, it.name) }.sortedBy { it.name.orEmpty() }
    } catch (_: SecurityException) { emptyList() }
}

@Composable
internal fun CompanionSettingsContent(state: CompanionState, enabled: Boolean,
    onLink: () -> Unit, onReconnect: () -> Unit, onUnlink: () -> Unit, onPhone: () -> Unit, onPc: () -> Unit,
    onCancel: () -> Unit, onManage: (() -> Unit)? = null, onLocalPhone: (() -> Unit)? = null) {
    StyledList(key = Triple(state.linked, state.connected, state.busy),
        title = stringResource(R.string.companion_title),
        description = if (state.linked) stringResource(R.string.companion_description) else null) {
        if (state.linked) {
            item { _, _ ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(when {
                            state.busy -> R.string.companion_switching
                            state.detail == "Connecting to NTPods…" -> R.string.companion_connecting
                            state.connected -> R.string.companion_connected
                            else -> R.string.companion_not_connected
                        }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (state.busy || !state.connected || state.detail != "PC linked. Choose where to listen.")
                        Text(companionDetail(state), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val phoneDescription = stringResource(R.string.companion_to_phone_description)
                        val pcDescription = stringResource(R.string.companion_to_pc_description)
                        val phoneEnabled = enabled && !state.busy && (state.connected || onLocalPhone != null)
                        val pcEnabled = enabled && state.connected && !state.busy
                        val appleStyle = LocalDesignSystem.current == DesignSystem.Apple
                        val foreground = MaterialTheme.colorScheme.onSurface
                        fun actionColor(actionEnabled: Boolean) = if (appleStyle)
                            foreground.copy(alpha = if (actionEnabled) 1f else 0.38f) else Color.Unspecified
                        StyledButton(Modifier.weight(1f).semantics { contentDescription = phoneDescription },
                            onClick = { if (state.connected) onPhone() else onLocalPhone?.invoke() }, enabled = phoneEnabled) {
                            Text(stringResource(R.string.companion_to_phone), color = actionColor(phoneEnabled))
                        }
                        StyledButton(Modifier.weight(1f).semantics { contentDescription = pcDescription },
                            onClick = onPc, enabled = pcEnabled) {
                            Text(stringResource(R.string.companion_to_pc), color = actionColor(pcEnabled))
                        }
                    }
                }
            }
            if (state.busy) styledListItem(contentText = stringResource(R.string.companion_cancel), enabled = enabled, onClick = onCancel)
            else if (!state.connected) styledListItem(contentText = stringResource(R.string.companion_reconnect), enabled = enabled, onClick = onReconnect)
            if (!state.busy) {
                if (onManage != null) styledListItem(contentText = stringResource(R.string.companion_manage), enabled = enabled, onClick = onManage)
                else styledListItem(contentText = stringResource(R.string.companion_unlink), enabled = enabled, onClick = onUnlink)
            }
        } else styledListItem(contentText = stringResource(R.string.companion_link),
            supportingText = companionDetail(state),
            orientation = StyledListItemOrientation.Vertical, enabled = enabled, onClick = onLink)
    }
}

@Composable
// shortcut: Map existing detail text until the controller exposes localized status codes.
private fun companionDetail(state: CompanionState): String = when (state.detail) {
    "Pair this phone with your PC in Bluetooth settings, then link NTPods." -> stringResource(R.string.companion_link_description)
    "PC linked. Choose where to listen." -> stringResource(R.string.companion_choose_destination)
    "Connecting to NTPods…" -> stringResource(R.string.companion_connecting_detail)
    "Requesting audio transfer…", "Preparing audio transfer…" -> stringResource(R.string.companion_preparing)
    "Releasing phone audio…" -> stringResource(R.string.companion_releasing)
    "Connecting phone audio…" -> stringResource(R.string.companion_acquiring)
    "Audio transfer complete." -> stringResource(R.string.companion_complete)
    "Audio transfer stopped. Choose where to listen to try again.",
    "Transfer stopped. Choose where to listen to try again.",
    "PC did not start the transfer. Try again." -> stringResource(R.string.companion_failed)
    "Transfer cancelled. Choose where to listen to try again." -> stringResource(R.string.companion_cancelled)
    "Transfer superseded by a phone action." -> stringResource(R.string.companion_superseded)
    "PC connection stopped. Open NTPods and tap Reconnect. Check where your audio is playing before trying again." -> stringResource(R.string.companion_offline_detail)
    else -> state.detail
}

@Composable
private fun CompanionLinkDialog(peers: List<CompanionPeer>, pods: List<AppleDevice>, onDismiss: () -> Unit,
    onLink: (String, String, String) -> Unit) {
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var selectedPods by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.companion_link)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.companion_setup), style = MaterialTheme.typography.bodyMedium)
                StyledList(title = stringResource(R.string.companion_pc)) {
                    peers.forEach { peer ->
                        styledListItem(contentText = peer.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.companion_unnamed_pc),
                            selected = selectedPeer == peer.address, onClick = { selectedPeer = peer.address })
                    }
                }
                StyledList(title = stringResource(R.string.companion_airpods)) {
                    pods.forEach { pod ->
                        val metadata by pod.metadata.collectAsState()
                        styledListItem(contentText = metadata.name.ifBlank { "AirPods" },
                            selected = selectedPods == pod.macAddress.value, onClick = { selectedPods = pod.macAddress.value })
                    }
                }
                if (peers.isEmpty() || pods.isEmpty()) Text(stringResource(R.string.companion_pair_first))
                CompanionLinkCodeField(token, error) { token = it.take(64); error = false }
            }
        },
        confirmButton = { TextButton(enabled = selectedPeer != null && selectedPods != null && token.matches(Regex("[0-9a-fA-F]{64}")), onClick = {
            try { onLink(selectedPeer!!, selectedPods!!, token); token = "" }
            catch (_: Exception) { token = ""; error = true }
        }) { Text(stringResource(R.string.companion_link)) } },
        dismissButton = { TextButton(onClick = { token = ""; onDismiss() }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun CompanionLinkCodeField(value: String, error: Boolean, onValueChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None },
        label = { Text(stringResource(R.string.companion_token)) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        isError = error, supportingText = { Text(stringResource(if (error) R.string.companion_setup_error else R.string.companion_token_hint)) })
}
