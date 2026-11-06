package me.kavishdevar.librepods.presentation.screens.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.hasNearbyDevicesPermissions
import me.kavishdevar.librepods.bluetooth.nearbyDevicesPermissions
import me.kavishdevar.librepods.companion.CompanionState
import me.kavishdevar.librepods.companion.CompanionPairingClient
import me.kavishdevar.librepods.companion.CompanionPairingProtocol
import me.kavishdevar.librepods.companion.CompanionPairingState
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
        val hostAvailable = remember(bridge, service) { service?.companionMediaAvailable() == true }
        var phonePermissionDenied by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            phonePermissionDenied = !granted
            if (granted) service?.setCompanionCallsEnabled(true)
        }
        CompanionPoliciesContent(settings, enabled = controller != null && pod != null && !displayedState.busy,
            syncIssue = state.settingsIssue,
            hostAvailable = hostAvailable,
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
        val choices = pods.map { pod ->
            val metadata by pod.metadata.collectAsState()
            CompanionDeviceOption(pod.macAddress.value, metadata.name.ifBlank { "AirPods" })
        }
        CompanionLinkSetup(choices, controller != null, controller?.pairing, onDismiss = { setup = false }) { peer, device, token ->
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

internal data class CompanionDeviceOption(val address: String, val name: String?)
internal enum class CompanionPairingAccess { READY, PERMISSION_REQUIRED, BLUETOOTH_OFF, UNAVAILABLE }
internal data class CompanionPairingDevices(val access: CompanionPairingAccess,
    val devices: List<CompanionDeviceOption> = emptyList(), val bondedAddresses: Set<String> = emptySet())

internal fun companionPairingDevices(context: Context): CompanionPairingDevices {
    if (!hasNearbyDevicesPermissions(context))
        return CompanionPairingDevices(CompanionPairingAccess.PERMISSION_REQUIRED)
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return CompanionPairingDevices(CompanionPairingAccess.UNAVAILABLE)
        if (!adapter.isEnabled) return CompanionPairingDevices(CompanionPairingAccess.BLUETOOTH_OFF)
        val bonded = adapter.bondedDevices
        val computers = bonded.filter { device ->
            // Some PCs omit their class; exclude only known non-computer classes.
            when (device.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.NETWORKING,
                BluetoothClass.Device.Major.AUDIO_VIDEO, BluetoothClass.Device.Major.PERIPHERAL,
                BluetoothClass.Device.Major.IMAGING, BluetoothClass.Device.Major.WEARABLE,
                BluetoothClass.Device.Major.TOY, BluetoothClass.Device.Major.HEALTH -> false
                else -> true
            }
        }
        CompanionPairingDevices(CompanionPairingAccess.READY,
            computers.map { CompanionDeviceOption(it.address, it.name) }.sortedBy { it.name.orEmpty() },
            bonded.map { it.address }.toSet())
    } catch (_: SecurityException) { CompanionPairingDevices(CompanionPairingAccess.PERMISSION_REQUIRED) }
    catch (_: Exception) { CompanionPairingDevices(CompanionPairingAccess.UNAVAILABLE) }
}

@Composable
internal fun ObserveCompanionPairing(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        // Bluetooth broadcasts can originate from a privileged system app; they only trigger a fresh local read.
        val registered = runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED); true }.getOrDefault(false)
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer); if (registered) context.unregisterReceiver(receiver) }
    }
}

@Composable
private fun CompanionLinkSetup(pods: List<CompanionDeviceOption>, ready: Boolean, pairing: CompanionPairingClient?, onDismiss: () -> Unit,
    onLink: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val owner = remember(pairing) { Any() }
    val pairingState by (pairing?.state ?: remember { MutableStateFlow(CompanionPairingState()) }).collectAsState()
    val ownedState = pairingState.takeIf { it.owner === owner }
    DisposableEffect(pairing, owner) { onDispose { pairing?.cancel(owner) } }
    LaunchedEffect(ownedState?.complete) { if (ownedState?.complete == true) onDismiss() }
    var paired by remember(context) { mutableStateOf(companionPairingDevices(context)) }
    var settingsError by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    ObserveCompanionPairing { paired = companionPairingDevices(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        paired = companionPairingDevices(context)
        permissionDenied = paired.access == CompanionPairingAccess.PERMISSION_REQUIRED
    }
    val bonded = paired.bondedAddresses
    val podAddresses = pods.map { it.address }.toSet()
    CompanionLinkDialog(paired.copy(devices = paired.devices.filter { it.address !in podAddresses }),
        pods.filter { it.address in bonded }, ready, settingsError,
        onOpenBluetooth = {
            settingsError = runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.isFailure
        }, onPermission = {
            if (permissionDenied) settingsError = runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }.isFailure
            else permission.launch(nearbyDevicesPermissions.toTypedArray())
        },
        onDismiss = { pairing?.cancel(owner); onDismiss() },
        pairingState = pairingState.copy(busy = ownedState?.busy == true, failed = ownedState?.failed == true),
        retryReady = pairing != null && pairingState.pendingPeer in paired.devices.map { it.address } && pairingState.pendingDevice in bonded,
        onRetryPending = { pairing?.retry(owner) },
        onLink = { peer, device, token ->
            val full = CompanionPairingProtocol.fullCode(token)
            if (full != null) onLink(peer, device, full)
            else pairing?.start(owner, peer, device, token)
        })
}

@Composable
internal fun CompanionSettingsContent(state: CompanionState, enabled: Boolean,
    onLink: () -> Unit, onReconnect: () -> Unit, onUnlink: () -> Unit, onPhone: () -> Unit, onPc: () -> Unit,
    onCancel: () -> Unit, onManage: (() -> Unit)? = null, onLocalPhone: (() -> Unit)? = null) {
    StyledList(key = Triple(state.linked, state.connected, state.busy),
        title = stringResource(if (onManage == null) R.string.companion_connection_title else R.string.companion_title),
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
            orientation = StyledListItemOrientation.Vertical, onClick = onLink)
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun CompanionLinkDialog(paired: CompanionPairingDevices, pods: List<CompanionDeviceOption>, ready: Boolean = true,
    settingsError: Boolean = false, onOpenBluetooth: () -> Unit, onPermission: () -> Unit, onDismiss: () -> Unit,
    pairingState: CompanionPairingState = CompanionPairingState(), retryReady: Boolean = false, onRetryPending: () -> Unit = {},
    onLink: (String, String, String) -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            CompanionLinkForm(paired, pods, ready, settingsError, onOpenBluetooth, onPermission, onDismiss,
                pairingState, retryReady, onRetryPending, onLink)
        }
    }
}

@Composable
internal fun CompanionLinkForm(paired: CompanionPairingDevices, pods: List<CompanionDeviceOption>, ready: Boolean = true,
    settingsError: Boolean = false, onOpenBluetooth: () -> Unit, onPermission: () -> Unit, onDismiss: () -> Unit,
    pairingState: CompanionPairingState = CompanionPairingState(), retryReady: Boolean = false, onRetryPending: () -> Unit = {},
    onLink: (String, String, String) -> Unit) {
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var selectedPods by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val peers = paired.devices
    val validSelection = selectedPeer != selectedPods && peers.any { it.address == selectedPeer } && pods.any { it.address == selectedPods }
    val code = CompanionPairingProtocol.fullCode(token) ?: CompanionPairingProtocol.shortCode(token)
    val canLink = ready && !pairingState.busy && paired.access == CompanionPairingAccess.READY && validSelection && code != null
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.companion_link), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f, fill = false).heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StyledList(key = Pair(paired.access, pairingState.busy), title = stringResource(R.string.companion_pairing_title),
                    description = stringResource(R.string.companion_pairing_description)) {
                    if (paired.access == CompanionPairingAccess.PERMISSION_REQUIRED)
                        styledListItem(contentText = stringResource(R.string.companion_allow_nearby), enabled = !pairingState.busy, onClick = onPermission)
                    styledListItem(contentText = stringResource(R.string.companion_open_bluetooth), enabled = !pairingState.busy, onClick = onOpenBluetooth)
                }
                if (paired.access != CompanionPairingAccess.READY) Text(stringResource(when (paired.access) {
                    CompanionPairingAccess.PERMISSION_REQUIRED -> R.string.companion_nearby_required
                    CompanionPairingAccess.BLUETOOTH_OFF -> R.string.companion_bluetooth_off
                    else -> R.string.companion_bluetooth_unavailable
                }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settingsError) Text(stringResource(R.string.companion_bluetooth_settings_failed), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.companion_setup), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                StyledList(key = Pair(peers, pairingState.busy), title = stringResource(R.string.companion_pc)) {
                    peers.forEach { peer ->
                        styledListItem(contentText = peer.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.companion_unnamed_pc),
                            supportingText = peer.address, orientation = StyledListItemOrientation.Vertical,
                            enabled = !pairingState.busy,
                            selected = selectedPeer == peer.address, onClick = { selectedPeer = peer.address })
                    }
                }
                StyledList(key = Pair(pods, pairingState.busy), title = stringResource(R.string.companion_airpods)) {
                    pods.forEach { pod ->
                        styledListItem(contentText = pod.name ?: "AirPods", supportingText = pod.address,
                            orientation = StyledListItemOrientation.Vertical,
                            enabled = !pairingState.busy,
                            selected = selectedPods == pod.address, onClick = { selectedPods = pod.address })
                    }
                }
                if (paired.access == CompanionPairingAccess.READY && peers.isEmpty()) Text(stringResource(R.string.companion_no_paired_pc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (paired.access == CompanionPairingAccess.READY && pods.isEmpty()) Text(stringResource(R.string.companion_no_paired_airpods), color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompanionLinkCodeField(token, error, enabled = !pairingState.busy, shortCodes = true) { token = it.take(64); error = false }
                if (pairingState.busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.companion_pairing_progress), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pairingState.failed) Text(stringResource(R.string.companion_pairing_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (pairingState.pendingPeer != null && pairingState.pendingDevice != null) StyledList(
                    key = Pair(retryReady, pairingState.busy),
                    description = stringResource(R.string.companion_pairing_retry_description,
                        peers.firstOrNull { it.address == pairingState.pendingPeer }?.name ?: pairingState.pendingPeer,
                        pods.firstOrNull { it.address == pairingState.pendingDevice }?.name ?: pairingState.pendingDevice)) {
                    styledListItem(contentText = stringResource(R.string.companion_pairing_retry),
                        enabled = retryReady && !pairingState.busy, onClick = onRetryPending)
                }
                Text(stringResource(R.string.companion_access_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!ready) Text(stringResource(R.string.companion_service_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { token = ""; onDismiss() }) { Text(stringResource(android.R.string.cancel)) }
            TextButton(enabled = canLink, onClick = {
                focus.clearFocus(); keyboard?.hide()
                try { onLink(selectedPeer!!, selectedPods!!, code!!); token = "" }
                catch (_: Exception) { token = ""; error = true }
            }) { Text(stringResource(R.string.companion_link)) }
        }
    }
}

@Composable
internal fun CompanionLinkCodeField(value: String, error: Boolean, enabled: Boolean = true, shortCodes: Boolean = false, onValueChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None },
        label = { Text(stringResource(R.string.companion_token)) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
            keyboardType = if (shortCodes && value.none { it.isLetter() }) KeyboardType.NumberPassword else KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        isError = error, supportingText = { Text(stringResource(if (error) R.string.companion_setup_error else R.string.companion_token_hint)) })
}
