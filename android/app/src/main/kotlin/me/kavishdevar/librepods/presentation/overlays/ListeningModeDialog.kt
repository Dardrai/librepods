/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.services.LibrePodsService

/** Uses the clicked tile's dialog token, without starting an app task or requesting overlay access. */
class ListeningModeDialog(context: Context, private val address: MacAddress) :
    ComponentDialog(context, R.style.Theme_LibrePods_ListeningMode) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ServiceConnection? = null
    private var bound = false
    private var observation: Job? = null
    private var service: LibrePodsService? = null
    private var owner: AppleDevice? = null
    private var snapshot by mutableStateOf<ListeningModeSnapshot?>(null)
    private var commandFailed by mutableStateOf(false)
    private val islandWindow by lazy { (context.applicationContext as LibrePodsApplication).islandWindow }
    private val pickerToken = Any()
    private var ownsIslandSuppression = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCanceledOnTouchOutside(true)
        window?.apply {
            val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val density = context.resources.displayMetrics.density
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                width = minOf(metrics.bounds.width() - insets.left - insets.right - (32 * density).toInt(), (600 * density).toInt())
                height = WindowManager.LayoutParams.WRAP_CONTENT
                y = (16 * density).toInt()
            }
        }
        setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LibrePodsTheme(designSystem = DesignSystem.Apple, darkTheme = isSystemInDarkTheme()) {
                    Box(Modifier.padding(vertical = 4.dp)) {
                        ListeningModeContent(snapshot, commandFailed,
                            onMode = { writeControl(ControlCommandIdentifier.LISTENING_MODE, it) },
                            onStrength = { writeControl(ControlCommandIdentifier.AUTO_ANC_STRENGTH, it) })
                    }
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { close(); return }
        islandWindow.setListeningPickerVisible(pickerToken, true)
        ownsIslandSuppression = true
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                val connected = (binder as? LibrePodsService.LocalBinder)?.getService()
                if (connected == null) { close(); return }
                service = connected
                observation = scope.launch {
                    connected.devices.collectLatest { devices ->
                        val device = devices[address] as? AppleDevice
                        if (device == null || (owner != null && owner !== device)) { close(); return@collectLatest }
                        owner = device
                        combine(device.connectionState, device.state, device.settings, device.metadata) { connection, state, settings, metadata ->
                            if (connection != ConnectionState.CONNECTED || !supportsListening(metadata)) null
                            else ListeningModeSnapshot(metadata, state, settings)
                        }.collect { current ->
                            snapshot = current
                            if (current == null) close()
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
        val device = owner ?: return false
        val targetAddress = address
        val current = service?.devices?.value?.get(targetAddress)
        if (!isShowing || !bound || current !== device || device.connectionState.value != ConnectionState.CONNECTED ||
            !supportsListening(device.metadata.value) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val state = ListeningModeSnapshot(device.metadata.value, device.state.value, device.settings.value)
        if (state.mode == null || when (identifier) {
                ControlCommandIdentifier.LISTENING_MODE -> value !in state.modes
                ControlCommandIdentifier.AUTO_ANC_STRENGTH -> !state.adaptive || state.mode != 4 || value !in 0..100
                else -> true
            }) return false
        return device.setControlCommand(identifier, value).also {
            commandFailed = !it
            if (!it) Toast.makeText(context, R.string.listening_mode_failed, Toast.LENGTH_SHORT).show()
        }
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
        releaseBinding()
        releaseIslandSuppression()
        scope.cancel()
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
        service = null
        snapshot = null
        if (bound && previous != null) runCatching { context.unbindService(previous) }
        bound = false
    }

    companion object {
        private fun supportsListening(metadata: AppleMetadata) =
            BaseCapability.LISTENING_MODE in AirPodsSpecs.getSpec(metadata.model).baseCapabilities
    }
}

internal data class ListeningModeSnapshot(val metadata: AppleMetadata, val state: AppleState, val settings: AppleSettings) {
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
