/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Consumer
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.utils.NativeGameModeController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.services.LibrePodsService

/** Uses the clicked tile's dialog token, without starting an app task or requesting overlay access. */
class ListeningModeDialog(context: Context, private val address: MacAddress) :
    ComponentDialog(context, R.style.Theme_LibrePods_ListeningMode) {
    internal constructor(context: Context, target: DongleListeningTarget) : this(context, target.macAddress) {
        dongleTarget = target
    }

    private var dongleTarget: DongleListeningTarget? = null
    private var observedDongle: DongleController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ServiceConnection? = null
    private var bound = false
    private var observation: Job? = null
    private var service: LibrePodsService? = null
    private var owner: AppleDevice? = null
    private var nativeControls: NativeQuickControls? = null
    private var clinicalPresentation: ListeningModeSnapshot? = null
    private var snapshot by mutableStateOf<ListeningModeSnapshot?>(null)
    private var commandFailed by mutableStateOf(false)
    private val quickPending = MutableStateFlow<Set<QuickSetting>>(emptySet())
    private val islandWindow by lazy { (context.applicationContext as LibrePodsApplication).islandWindow }
    private val pickerToken = Any()
    private var ownsIslandSuppression = false
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var listeningForBlur = false
    private val blurListener = Consumer<Boolean> { enabled ->
        if (scope.coroutineContext[Job]?.isActive == true) updateBackdrop(enabled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCanceledOnTouchOutside(true)
        window?.apply {
            val density = context.resources.displayMetrics.density
            val decor = decorView
            setDecorFitsSystemWindows(true)
            setClipToOutline(false)
            // Elevation reserves an out-of-window buffer for the expanded glass bubble.
            setElevation(8 * density)
            decor.setPadding(0, 0, 0, 0)
            decor.setOnApplyWindowInsetsListener { _, insets ->
                updateWidth()
                insets
            }
            attributes = attributes.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                height = WindowManager.LayoutParams.WRAP_CONTENT
                y = (8 * density).toInt()
                setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            }
        }
        updateWidth()
        updateBackdrop(windowManager.isCrossWindowBlurEnabled)
        setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = isSystemInDarkTheme()) {
                    ListeningModePanel(snapshot, commandFailed,
                        onMode = { writeControl(ControlCommandIdentifier.LISTENING_MODE, it) },
                        onStrength = { writeControl(ControlCommandIdentifier.AUTO_ANC_STRENGTH, it) },
                        onToggle = ::writeQuickSetting, onDismiss = { dismiss() })
                }
            }
        })
        fun allowOverflow(view: View) {
            if (view is ViewGroup) {
                view.clipChildren = false
                view.clipToPadding = false
                for (index in 0 until view.childCount) allowOverflow(view.getChildAt(index))
            }
        }
        window?.decorView?.let(::allowOverflow)
    }

    override fun onStart() {
        super.onStart()
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { close(); return }
        runCatching {
            windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
            listeningForBlur = true
        }.onFailure { updateBackdrop(false) }
        islandWindow.setListeningPickerVisible(pickerToken, true)
        ownsIslandSuppression = true
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                val connected = (binder as? LibrePodsService.LocalBinder)?.getService()
                if (connected == null) { close(); return }
                service = connected
                val dongle = dongleTarget
                if (dongle != null) {
                    val controller = connected.dongleControls
                    if (!dongle.isCurrent(controller)) { close(); return }
                    observedDongle = controller
                    controller.controlsVisible(true, owner = pickerToken)
                    observation = scope.launch {
                        val edits = combine(listOf(0x0d, 0x2e, 0x0a, 0x26, 0x28).map { controller.liveSettingEditor(it).state }) { it.toList() }
                        combine(controller.state, edits, controller.liveGameModeEditor().state) { _, _, _ -> dongle.snapshot() }
                            .collect { current ->
                                snapshot = current
                                if (current == null) close()
                            }
                    }
                    return
                }
                observation = scope.launch {
                    connected.devices.collectLatest { devices ->
                        val device = devices[address] as? AppleDevice
                        if (device == null || (owner != null && owner !== device)) { close(); return@collectLatest }
                        owner = device
                        val controls = nativeControls ?: NativeQuickControls(device, scope,
                            idle = { quickPending.value.isEmpty() && isShowing && bound && currentNativeDevice() === device &&
                                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked },
                            failed = { id ->
                                if (id == ControlCommandIdentifier.LISTENING_MODE || id == ControlCommandIdentifier.AUTO_ANC_STRENGTH)
                                    reportWrite(false) else reportQuickWrite(false)
                            }).also { nativeControls = it }
                        val game: Flow<NativeGameModeController.State?> =
                            (context.applicationContext as LibrePodsApplication).nativeGameModeControllers[address]?.state ?: flowOf(null)
                        val extra = combine(game, connected.dongleControls.state, quickPending, controls.changes) { gameState, dongle, pending, _ ->
                            Triple(gameState, dongle.usesDongleListeningControls, pending)
                        }
                        combine(device.connectionState, device.state, device.settings, device.metadata,
                            extra) { connection, state, settings, metadata, (gameState, dongle, pending) ->
                            if (dongle || connection != ConnectionState.CONNECTED || !supportsListening(metadata)) null
                            else {
                                controls.observe()
                                val displayed = controls.displayState(state)
                                ListeningModeSnapshot(metadata, displayed, settings, nativeQuickSettings(metadata, displayed, settings,
                                gameState, device.loudSoundReductionWritable, device.transparencyTransportAvailable, pending,
                                connected = connection == ConnectionState.CONNECTED, canWrite = controls::canWrite,
                                ordinaryBusy = controls.busy))
                            }
                        }.collect { current ->
                            if (current == null) { snapshot = null; clinicalPresentation = null; close() }
                            else {
                                val pendingClinical = quickPending.value.any { it == QuickSetting.HEARING_AID || it == QuickSetting.MEDIA_ASSIST }
                                val reading = pendingClinical || listOf(current.state.hearingAidReadStatus,
                                    current.state.transparencyReadStatus, current.state.loudSoundReductionReadStatus)
                                    .any { it == SettingsReadStatus.READING }
                                val displayed = retainQuickPresentation(clinicalPresentation, current,
                                    ownedRefresh = clinicalPresentation != null, reading = reading)
                                snapshot = displayed
                                if (!pendingClinical && displayed.quickSettings.none { it.retained }) clinicalPresentation = null
                            }
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) { if (connection === this) { releaseBinding(); close() } }
            override fun onBindingDied(name: ComponentName) { if (connection === this) { releaseBinding(); close() } }
            override fun onNullBinding(name: ComponentName) { if (connection === this) { releaseBinding(); close() } }
        }
        connection = callback
        bound = runCatching { context.bindService(Intent(context, LibrePodsService::class.java), callback, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) { connection = null; close() }
    }

    /** Recheck the current map and exact instance at dispatch, including before collectors run. */
    internal fun writeControl(identifier: ControlCommandIdentifier, value: Int): Boolean {
        if (!isShowing || !bound || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (quickPending.value.isNotEmpty()) return false
        dongleTarget?.let { target ->
            val controller = service?.dongleControls ?: return false
            if (!target.isCurrent(controller)) return false
            val current = target.snapshot() ?: return false
            if (!validSelection(current, identifier, value)) return false
            return reportWrite(target.write(identifier, value))
        }
        val device = owner ?: return false
        val targetAddress = address
        val current = service?.devices?.value?.get(targetAddress)
        if (current !== device || device.connectionState.value != ConnectionState.CONNECTED ||
            !supportsListening(device.metadata.value) || service?.dongleControls?.state?.value?.usesDongleListeningControls == true) return false
        val controls = nativeControls ?: return false
        val state = ListeningModeSnapshot(device.metadata.value, controls.displayState(device.state.value), device.settings.value)
        if (!validSelection(state, identifier, value)) return false
        return reportWrite(controls.edit(identifier, value))
    }

    internal fun writeQuickSetting(setting: QuickSetting, enabled: Boolean): Boolean {
        if (!isShowing || !bound || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        dongleTarget?.let { target ->
            val controller = service?.dongleControls ?: return false
            return target.isCurrent(controller) && reportQuickWrite(target.write(setting, enabled))
        }
        val device = currentNativeDevice() ?: return false
        val controls = nativeControls ?: return false
        val game = (context.applicationContext as LibrePodsApplication).nativeGameModeControllers[address]
        val control = nativeQuickSettings(device.metadata.value, controls.displayState(device.state.value), device.settings.value,
            game?.state?.value, device.loudSoundReductionWritable, device.transparencyTransportAvailable, quickPending.value,
            canWrite = controls::canWrite, ordinaryBusy = controls.busy)
            .first { it.setting == setting }
        if (!control.enabled || (control.checked == null && !control.recoveryOff) ||
            (control.recoveryOff && enabled) || control.checked == enabled) return false
        setting.control?.let { id -> return reportQuickWrite(controls.edit(id, id.encodeBoolean(enabled).toInt() and 255)) }
        if (setting == QuickSetting.GAME_MODE) {
            device.updateSettings { it.copy(nativeGameModeEnabled = enabled) }
            return reportQuickWrite(true)
        }
        if (quickPending.value.isNotEmpty()) return false
        val transport = device.aacp.captureAudioSocket() ?: return false
        val session = device.state.value.controlSession
        val connection = device.connectionNumber.value
        if (setting == QuickSetting.HEARING_AID || setting == QuickSetting.MEDIA_ASSIST) {
            clinicalPresentation = snapshot
        }
        quickPending.value = setOf(setting)
        scope.launch {
            try {
                val confirmed = withContext(Dispatchers.IO) {
                    if (service?.devices?.value?.get(address) !== device || device.aacp.captureAudioSocket() !== transport ||
                        device.state.value.controlSession != session || device.connectionNumber.value != connection ||
                        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) false
                    else when (setting) {
                        QuickSetting.LOUD_SOUND_REDUCTION -> device.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION,
                            byteArrayOf(if (enabled) 1 else 0), byteArrayOf(if (control.checked == true) 1 else 0), transport, session)
                        QuickSetting.HEARING_AID -> device.setHearingAidEnabled(enabled, transport, session)
                        QuickSetting.MEDIA_ASSIST -> device.setMediaAssistEnabled(enabled, transport, session)
                        else -> false
                    }
                }
                if (isShowing && service?.devices?.value?.get(address) === device) reportQuickWrite(confirmed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isShowing && service?.devices?.value?.get(address) === device) reportQuickWrite(false) }
            finally { quickPending.value = emptySet() }
        }
        return reportQuickWrite(true)
    }

    private fun reportQuickWrite(success: Boolean): Boolean = success.also {
        if (!it) Toast.makeText(context, R.string.att_setting_not_confirmed, Toast.LENGTH_SHORT).show()
    }

    private fun currentNativeDevice(): AppleDevice? {
        val device = owner ?: return null
        return device.takeIf { service?.devices?.value?.get(address) === it &&
            it.connectionState.value == ConnectionState.CONNECTED && supportsListening(it.metadata.value) &&
            service?.dongleControls?.state?.value?.usesDongleListeningControls != true }
    }

    private fun validSelection(state: ListeningModeSnapshot, identifier: ControlCommandIdentifier, value: Int) =
        state.mode != null && when (identifier) {
            ControlCommandIdentifier.LISTENING_MODE -> value in state.modes
            ControlCommandIdentifier.AUTO_ANC_STRENGTH -> state.adaptive && state.mode == 4 && value in 0..100
            else -> false
        }

    private fun reportWrite(success: Boolean): Boolean = success.also {
        commandFailed = !it
        if (!it) Toast.makeText(context, R.string.listening_mode_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        retire()
        super.onStop()
    }

    override fun dismiss() {
        retire()
        super.dismiss()
    }

    private fun retire() {
        if (listeningForBlur) {
            listeningForBlur = false
            runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurListener) }
        }
        runCatching { window?.setBackgroundBlurRadius(0) }
        releaseBinding()
        releaseIslandSuppression()
        scope.cancel()
    }

    private fun updateBackdrop(blurEnabled: Boolean) {
        val density = context.resources.displayMetrics.density
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = 32 * density
                setColor(if (dark) {
                    Color.argb(if (blurEnabled) 148 else 242, 20, 20, 24)
                } else {
                    Color.argb(if (blurEnabled) 160 else 245, 248, 248, 250)
                })
            })
            // Background blur stays inside this short window; blur-behind would affect the entire screen.
            setBackgroundBlurRadius(if (blurEnabled) (28 * density).toInt() else 0)
        }
    }

    private fun updateWidth() {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val density = context.resources.displayMetrics.density
        val width = minOf(metrics.bounds.width() - insets.left - insets.right - (32 * density).toInt(), (600 * density).toInt())
        window?.let { if (it.attributes.width != width) it.attributes = it.attributes.apply { this.width = width } }
    }

    // Dialog.show sets isShowing after onStart; invalid initial bindings must close after that.
    private fun close() { window?.decorView?.post { dismiss() } }

    private fun releaseIslandSuppression() {
        if (!ownsIslandSuppression) return
        ownsIslandSuppression = false
        islandWindow.setListeningPickerVisible(pickerToken, false)
    }

    private fun releaseBinding() {
        val previous = connection
        connection = null
        observation?.cancel()
        observation = null
        observedDongle?.controlsVisible(false, owner = pickerToken)
        observedDongle = null
        service = null
        nativeControls = null
        clinicalPresentation = null
        snapshot = null
        if (bound && previous != null) runCatching { context.unbindService(previous) }
        bound = false
    }

    companion object {
        private fun supportsListening(metadata: AppleMetadata) =
            BaseCapability.LISTENING_MODE in AirPodsSpecs.getSpec(metadata.model).baseCapabilities
    }
}

internal data class ListeningModeSnapshot(val metadata: AppleMetadata, val state: AppleState, val settings: AppleSettings,
    val quickSettings: List<QuickSettingState> = emptyList()) {
    val adaptive get() = BaseCapability.ADAPTIVE_AUDIO in AirPodsSpecs.getSpec(metadata.model).baseCapabilities
    val allowOff get() = state.effectiveAllowOff(settings, metadata.modelNumber) == true
    val modes get() = buildList { if (allowOff) add(1); add(3); if (adaptive) add(4); add(2) }
    val mode get() = (state.requestedControls[ControlCommandIdentifier.LISTENING_MODE]
        ?: state.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.firstOrNull()?.toInt()?.takeIf { it in 1..4 }
    val pending get() = ControlCommandIdentifier.LISTENING_MODE in state.requestedControls
}

@Composable
internal fun ListeningModeContent(snapshot: ListeningModeSnapshot?, commandFailed: Boolean = false,
    onMode: (Int) -> Unit, onStrength: (Int) -> Unit) {
    val mode = snapshot?.mode
    val status = stringResource(when {
        commandFailed -> R.string.listening_mode_failed
        mode == null -> R.string.listening_mode_reading
        snapshot?.pending == true -> R.string.listening_mode_pending
        mode == 1 -> R.string.off
        mode == 3 -> R.string.transparency
        mode == 4 -> R.string.adaptive
        else -> R.string.noise_cancellation
    })
    Box(Modifier.fillMaxWidth().height(64.dp).testTag("listening-mode-control").semantics {
        stateDescription = status
        liveRegion = LiveRegionMode.Polite
    }, contentAlignment = Alignment.Center) {
        if (snapshot != null && mode != null) {
            NoiseControlSettings(modifier = Modifier.fillMaxWidth(),
                showOffListeningMode = snapshot.allowOff,
                showAdaptiveMode = snapshot.adaptive,
                noiseControlModeValue = mode,
                onNoiseControlModeChanged = onMode,
                adaptiveStrength = snapshot.state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt() ?: 50,
                requestedAdaptiveStrength = snapshot.state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt(),
                onAdaptiveStrengthChanged = onStrength,
                showLabels = false)
        } else {
            Box(Modifier.fillMaxWidth().height(64.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(percent = 100)),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}
