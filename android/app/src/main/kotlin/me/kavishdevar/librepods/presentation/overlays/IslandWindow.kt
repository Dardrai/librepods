/*
    LibrePods - AirPods liberated from Appleâ€™s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.data.app.IslandColor
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.NightTheme
import me.kavishdevar.librepods.services.LibrePodsAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.IdentityHashMap

enum class IslandType {
    CONNECTED,
    TAKING_OVER,
    MOVED_TO_REMOTE,
    MOVED_TO_OTHER_DEVICE,
}

enum class IslandState {
    COMPACT,
    EXPANDED,
    CLOSE
}

enum class IslandConnectState { IDLE, CONNECTING, FAILED }

@Suppress("unused")
class IslandWindow(
    private var context: Context,
    private var appSettings: AppSettingsEntity
) {
    private var windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private val statusBarAppearance = IslandStatusBarAppearance(context)
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var transitionGeneration by mutableLongStateOf(0L)
    private var retainedWindowWidth = 0

    private var autoCloseHandler: Handler? = null
    private var autoCloseRunnable: Runnable? = null
    private var keepOpenRequested = false

    private var currentAppSettings by mutableStateOf(appSettings)

    private var name by mutableStateOf("[Test] AirPods Pro")
    private var type by mutableStateOf(IslandType.CONNECTED)
    private var batteryLevel by mutableIntStateOf(70)
    private var reversed by mutableStateOf(false)
    private var otherDeviceName by mutableStateOf(null as String?)
    private var statusText by mutableStateOf(null as String?)
    private var ownerAddress: String? = null
    private var ownerDevice by mutableStateOf<AppleDevice?>(null)
    private val listeningPickerTokens = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var ownerAnnouncementAtNanos = Long.MIN_VALUE
    private var onConnect: (suspend () -> Boolean)? = null
    private var isAudioConnected: (() -> Boolean)? = null
    private var deferConnectAction: (() -> Boolean)? = null
    private var connectActionDeferred by mutableStateOf(false)
    private var connectState by mutableStateOf(IslandConnectState.IDLE)
    private var connectJob: Job? = null
    private var connectGeneration = 0L
    private val pairingOpenings = mutableMapOf<String, Long>()
    private var model by mutableStateOf(AirPodsModel.UNKNOWN)
    private var batteries by mutableStateOf(emptySet<Battery>())
    private var nearby by mutableStateOf(false)
    private var batteryNow by mutableStateOf(System.currentTimeMillis())
    private var effectiveSettings by mutableStateOf(appSettings.islandSettings)
    private var cameraPadding by mutableIntStateOf(0)
    private var maximumHeight by mutableIntStateOf(Int.MAX_VALUE)
    private var cameraCapsuleWidth by mutableFloatStateOf(0f)
    private var minimumStatusBarWidth by mutableFloatStateOf(Float.POSITIVE_INFINITY)
    private var statusBarIconCenterY by mutableFloatStateOf(-1f)
    private val ageHandler = Handler(Looper.getMainLooper())
    private val updateAge = object : Runnable {
        override fun run() {
            if (!isVisible || ownerAddress == null) return
            refreshConnectionState()
            batteryNow = System.currentTimeMillis()
            ageHandler.postDelayed(this, 1000)
        }
    }

    private var showOffListeningMode by mutableStateOf(false)
    private var noiseControlModeValue by mutableIntStateOf(4)
    private var adaptiveStrength by mutableIntStateOf(30)
    private var requestedAdaptiveStrength by mutableStateOf<Int?>(null)
    private var listeningControlsEnabled by mutableStateOf(false)

    private var onNoiseControlModeChanged: (Int) -> Unit = { noiseControlModeValue = it }
    private var onAdaptiveStrengthChanged: (Int) -> Unit = { requestedAdaptiveStrength = it }

    private var openApp: () -> Unit = {}

    var usingAccessibilityService by mutableStateOf(false)
        private set

    init {
        usingAccessibilityService = context is LibrePodsAccessibilityService
    }

    var islandState by mutableStateOf(IslandState.CLOSE)
        private set

    val isVisible: Boolean
        get() = islandState != IslandState.CLOSE && composeView?.parent != null && composeView?.visibility == View.VISIBLE

    fun setListeningPickerVisible(token: Any, visible: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { setListeningPickerVisible(token, visible) }
            return
        }
        if (!visible) {
            listeningPickerTokens.remove(token)
            return
        }
        if (!listeningPickerTokens.add(token)) return
        close()
        // Retire the queued first attachment before destroying its composition lifecycle.
        composeView?.let { cleanupAndRemoveView(it, immediately = true) }
    }

    private fun canControlListening(device: AppleDevice?): Boolean {
        if (device == null || ownerDevice !== device || !isVisible || type != IslandType.CONNECTED || nearby || listeningPickerTokens.isNotEmpty() ||
            device.connectionState.value != ConnectionState.CONNECTED) return false
        val capabilities = AirPodsSpecs.getSpec(device.metadata.value.model).baseCapabilities
        val state = device.state.value
        val mode = (state.requestedControls[ControlCommandIdentifier.LISTENING_MODE]
            ?: state.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.singleOrNull()?.toInt()?.and(255)
        return BaseCapability.LISTENING_MODE in capabilities && mode != null && mode in 1..4 &&
            (mode != 4 || BaseCapability.ADAPTIVE_AUDIO in capabilities)
    }

    fun updateContext(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateContext(context) }
            return
        }

        val restore = isVisible
        val state = islandState
        cancelConnect()
        statusBarAppearance.release()
        composeView?.let { if (it.parent != null) windowManager.removeView(it) }
        lifecycleOwner?.stop()
        composeView = null
        lifecycleOwner = null
        retainedWindowWidth = 0
        transitionGeneration++
        this.context = context
        this.usingAccessibilityService = context is LibrePodsAccessibilityService
        this.windowManager = context.getSystemService(WindowManager::class.java)

        if (!restore) return
        setupComposeView()

        if (composeView?.parent == null) {
            val params = createLayoutParams(state)
            try {
                lifecycleOwner?.start()
                statusBarAppearance.prepare()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
                close()
                composeView?.let(::cleanupAndRemoveView)
            }
        }
    }

    fun updateSettings(appSettings: AppSettingsEntity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateSettings(appSettings) }
            return
        }

        this.appSettings = appSettings
        this.currentAppSettings = appSettings

        beginTransition(islandState, force = true)
    }

    fun updateBattery(batteryPercentage: Int) {
        if (ownerAddress != null) return
        this.batteryLevel = batteryPercentage
    }

    fun updatePairedBattery(device: AppleDevice, model: AirPodsModel = device.metadata.value.model) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updatePairedBattery(device, model) }
            return
        }
        if (!isVisible || ownerDevice !== device) return
        this.batteries = device.state.value.battery
        this.name = device.metadata.value.name
        if (model != AirPodsModel.UNKNOWN) this.model = model
        refreshConnectionState(device.connectionState.value != ConnectionState.CONNECTED)
        batteryNow = System.currentTimeMillis()
        listeningControlsEnabled = canControlListening(device)
    }

    private fun refreshConnectionState(fallbackNearby: Boolean = nearby, restartDismissal: Boolean = true) {
        val deferred = shouldDeferConnectAction()
        // The hint can query the audio inventory too; read actual ownership last as outputs appear.
        nearby = ownerAddress != null && (isAudioConnected?.let { !it() } ?: fallbackNearby)
        if (!nearby && connectState == IslandConnectState.FAILED) connectState = IslandConnectState.IDLE
        val wasDeferred = connectActionDeferred
        connectActionDeferred = nearby && deferred
        listeningControlsEnabled = canControlListening(ownerDevice)
        if (restartDismissal && wasDeferred && !connectActionDeferred && !keepOpenRequested && isVisible &&
            connectState == IslandConnectState.IDLE) resetTimer()
    }

    private fun shouldDeferConnectAction(): Boolean = ownerAddress != null && onConnect != null &&
        type == IslandType.CONNECTED && statusText == null && deferConnectAction?.invoke() == true

    fun suppressPairingOpening(address: String, observedAtNanos: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { suppressPairingOpening(address, observedAtNanos) }
            return
        }
        pairingOpenings[address] = maxOf(pairingOpenings[address] ?: Long.MIN_VALUE, observedAtNanos)
        if (ownerAddress == address && type == IslandType.CONNECTED && statusText == null) close()
    }

    fun observePairedCaseLid(address: String, open: Boolean, observedAtNanos: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { observePairedCaseLid(address, open, observedAtNanos) }
            return
        }
        if (!open) {
            // Only a newer authenticated case close ends the opening covered by the pairing card.
            pairingOpenings[address]?.let { if (observedAtNanos > it) pairingOpenings.remove(address) }
            if (ownerAddress == address && type == IslandType.CONNECTED && statusText == null &&
                onConnect != null && observedAtNanos > ownerAnnouncementAtNanos) {
                Log.d("IslandWindow", "Closing island for matching case lid")
                close()
            }
        }
    }

    fun clearPairingOpening(address: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { clearPairingOpening(address) }
            return
        }
        if (address == null) pairingOpenings.clear() else pairingOpenings.remove(address)
    }

    fun showPairedLid(device: AppleDevice, model: AirPodsModel = device.metadata.value.model,
                      observedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
                      onConnect: (suspend () -> Boolean)? = null,
                      isAudioConnected: (() -> Boolean)? = null,
                      deferConnectAction: (() -> Boolean)? = null) {
        val state = device.state.value
        show(name = device.metadata.value.name, owner = device, ownerModel = model, observedAtNanos = observedAtNanos,
            openApp = { context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            showOffListeningMode = state.effectiveAllowOff(device.settings.value, device.metadata.value.modelNumber) == true,
            noiseControlModeValue = (state.requestedControls[ControlCommandIdentifier.LISTENING_MODE]
                ?: state.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.firstOrNull()?.toInt() ?: 3,
            onNoiseControlModeChanged = { device.setListeningModeFromUi(it) },
            adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt() ?: 50,
            requestedAdaptiveStrength = state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt(),
            onAdaptiveStrengthChanged = { device.setControlCommand(ControlCommandIdentifier.AUTO_ANC_STRENGTH, it) },
            onConnect = onConnect, isAudioConnected = isAudioConnected, deferConnectAction = deferConnectAction)
    }

    fun updateAdaptiveStrength(device: AppleDevice) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateAdaptiveStrength(device) }
            return
        }
        if (!isVisible || ownerDevice !== device) return
        val state = device.state.value
        noiseControlModeValue = (state.requestedControls[ControlCommandIdentifier.LISTENING_MODE]
            ?: state.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.firstOrNull()?.toInt() ?: noiseControlModeValue
        showOffListeningMode = state.effectiveAllowOff(device.settings.value, device.metadata.value.modelNumber) == true
        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt() ?: 50
        requestedAdaptiveStrength = state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt()
        listeningControlsEnabled = canControlListening(device)
    }

    fun updateNoiseControl(mode: Int, strength: Int, showOff: Boolean = showOffListeningMode) {
        this.noiseControlModeValue = mode
        this.adaptiveStrength = strength
        this.showOffListeningMode = showOff
    }

    fun changeState(state: IslandState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { changeState(state) }
            return
        }

        beginTransition(state)
    }

    private fun beginTransition(state: IslandState, force: Boolean = false) {
        if (state == IslandState.CLOSE) listeningControlsEnabled = false
        if (state == islandState && !force) return
        retainedWindowWidth = maxOf(retainedWindowWidth,
            (composeView?.layoutParams as? WindowManager.LayoutParams)?.width?.coerceAtLeast(0) ?: 0)
        transitionGeneration++
        islandState = state
        updateWindowParams(state)
    }

    private fun finishTransition(expected: ComposeView, generation: Long, state: IslandState) {
        if (composeView !== expected || transitionGeneration != generation || islandState != state) return
        if (state == IslandState.CLOSE) cleanupAndRemoveView(expected)
        else {
            retainedWindowWidth = 0
            updateWindowParams(state)
        }
    }

    fun show(
        name: String = this.name,
        batteryLevel: Int = this.batteryLevel,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = this.reversed,
        otherDeviceName: String? = this.otherDeviceName,
        onReverseAction: () -> Unit = {},

        openApp: () -> Unit = this.openApp,

        showOffListeningMode: Boolean = this.showOffListeningMode,
        noiseControlModeValue: Int = this.noiseControlModeValue,
        onNoiseControlModeChanged: (Int) -> Unit = {},
        adaptiveStrength: Int = this.adaptiveStrength,
        onAdaptiveStrengthChanged: (Int) -> Unit = {},

        forcedState: IslandState? = null,
        keepOpen: Boolean = false,
        owner: AppleDevice? = null,
        ownerModel: AirPodsModel = owner?.metadata?.value?.model ?: AirPodsModel.UNKNOWN,
        observedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
        statusText: String? = null,
        requestedAdaptiveStrength: Int? = null,
        onConnect: (suspend () -> Boolean)? = null,
        isAudioConnected: (() -> Boolean)? = null,
        deferConnectAction: (() -> Boolean)? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                show(name, batteryLevel, type, reversed, otherDeviceName, onReverseAction,
                    openApp, showOffListeningMode, noiseControlModeValue, onNoiseControlModeChanged,
                    adaptiveStrength, onAdaptiveStrengthChanged, forcedState, keepOpen, owner, ownerModel, observedAtNanos, statusText, requestedAdaptiveStrength,
                    onConnect, isAudioConnected, deferConnectAction)
            }
            return
        }

        if (listeningPickerTokens.isNotEmpty()) return

        if (owner != null && ownerDevice === owner && isVisible &&
            type == IslandType.CONNECTED && this.type == IslandType.CONNECTED && statusText != null) {
            this.name = name
            this.batteryLevel = batteryLevel
            this.batteries = owner.state.value.battery
            this.batteryNow = System.currentTimeMillis()
            this.model = ownerModel
            this.showOffListeningMode = showOffListeningMode
            this.noiseControlModeValue = noiseControlModeValue
            this.adaptiveStrength = adaptiveStrength
            this.requestedAdaptiveStrength = requestedAdaptiveStrength
            if (this.statusText != null) this.statusText = statusText
            listeningControlsEnabled = canControlListening(owner)
            return
        }

        if (owner != null && type == IslandType.CONNECTED && statusText == null &&
            pairingOpenings.containsKey(owner.macAddress.value)) return

        Log.d("IslandWindow", "Showing island window: accessibility = $usingAccessibilityService")

        if (ownerDevice !== owner || islandState == IslandState.CLOSE ||
            type != IslandType.CONNECTED || statusText != null || onConnect == null) cancelConnect()
        this.onConnect = onConnect
        this.isAudioConnected = isAudioConnected
        this.deferConnectAction = deferConnectAction
        this.keepOpenRequested = keepOpen
        this.batteryLevel = batteryLevel
        this.ownerAddress = owner?.macAddress?.value
        this.ownerDevice = owner
        this.ownerAnnouncementAtNanos = observedAtNanos
        this.model = ownerModel
        this.batteries = owner?.state?.value?.battery.orEmpty()
        this.batteryNow = System.currentTimeMillis()

        this.name = name
        this.type = type
        this.reversed = reversed
        this.otherDeviceName = otherDeviceName
        this.statusText = statusText
        refreshConnectionState(owner != null && owner.connectionState.value != ConnectionState.CONNECTED,
            restartDismissal = false)

        this.openApp = openApp

        this.showOffListeningMode = showOffListeningMode
        this.noiseControlModeValue = noiseControlModeValue
        this.adaptiveStrength = adaptiveStrength
        this.requestedAdaptiveStrength = requestedAdaptiveStrength

        this.onNoiseControlModeChanged = {
            val device = owner
            if (device != null && canControlListening(device) && it in 1..4 &&
                (it != 4 || BaseCapability.ADAPTIVE_AUDIO in AirPodsSpecs.getSpec(device.metadata.value.model).baseCapabilities) &&
                (it != 1 || device.state.value.effectiveAllowOff(device.settings.value, device.metadata.value.modelNumber) == true)) {
                resetTimer()
                onNoiseControlModeChanged(it)
                updateAdaptiveStrength(device)
            }
        }

        this.onAdaptiveStrengthChanged = {
            val device = owner
            val state = device?.state?.value
            val mode = (state?.requestedControls?.get(ControlCommandIdentifier.LISTENING_MODE)
                ?: state?.controlStates?.get(ControlCommandIdentifier.LISTENING_MODE))?.singleOrNull()?.toInt()
            if (device != null && canControlListening(device) && mode == 4 && it in 0..100 &&
                BaseCapability.ADAPTIVE_AUDIO in AirPodsSpecs.getSpec(device.metadata.value.model).baseCapabilities) {
                resetTimer()
                onAdaptiveStrengthChanged(it)
                updateAdaptiveStrength(device)
            }
        }

        beginTransition(forcedState ?: if (appSettings.islandSettings.enableExpanded) IslandState.EXPANDED else IslandState.COMPACT)
        createLayoutParams(islandState)
        if (composeView == null) {
            setupComposeView()
        }
        composeView?.logFirstOverlayFrame("island", observedAtNanos)

        if (composeView?.parent == null) {
            val params = createLayoutParams(islandState)
            try {
                lifecycleOwner?.start()
                statusBarAppearance.prepare()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
                close()
                composeView?.let(::cleanupAndRemoveView)
                return
            }
        }

        updateWindowParams(islandState)
        listeningControlsEnabled = canControlListening(owner)
        ageHandler.removeCallbacks(updateAge)
        if (owner != null) ageHandler.post(updateAge)
        if (keepOpen) autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) } else resetTimer()
    }

    private fun updateWindowParams(state: IslandState) {
        if (composeView?.parent != null) {
            val params = createLayoutParams(state)
            windowManager.updateViewLayout(composeView, params)
        }
    }

    private fun createLayoutParams(state: IslandState = islandState): WindowManager.LayoutParams {
        val metrics = windowManager.currentWindowMetrics
        val cutout = metrics.windowInsets.displayCutout
        val camera = cutout?.cutoutPath?.let { path -> RectF().also { path.computeBounds(it, true) } }
            ?.takeIf { !it.isEmpty } ?: cutout?.boundingRects?.firstOrNull()?.let(::RectF)
        val density = context.resources.displayMetrics.density
        val systemInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val placement = islandPlacement(metrics.bounds, systemInsets,
            camera, density, appSettings.islandSettings)
        minimumStatusBarWidth = metrics.bounds.width() - systemInsets.left - systemInsets.right - 32 * density
        statusBarIconCenterY = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top / 2f - placement.y
        effectiveSettings = appSettings.islandSettings.copy(expandedWidth = placement.expandedWidth.toFloat(),
            expandedHeight = placement.expandedHeight.toFloat(), compactWidth = placement.compactWidth.toFloat(),
            compactHeight = placement.compactHeight.toFloat())
        cameraPadding = placement.contentTop
        maximumHeight = placement.maximumHeight
        cameraCapsuleWidth = camera?.let { it.width() + 12 * context.resources.displayMetrics.density }
            ?: placement.compactHeight.toFloat()
        val windowType = if (context is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val targetWidth = when (state) {
            IslandState.EXPANDED -> placement.expandedWidth
            IslandState.COMPACT -> placement.compactWidth
            IslandState.CLOSE -> minOf(placement.compactWidth,
                maxOf(placement.compactHeight, cameraCapsuleWidth.toInt()))
        }
        return WindowManager.LayoutParams(
            maxOf(targetWidth, retainedWindowWidth),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = placement.x
            y = placement.y
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun setupComposeView() {
        lifecycleOwner = OverlayLifecycleOwner()

        composeView = ComposeView(ContextThemeWrapper(context, R.style.Theme_LibrePods_Overlay)).apply {
            val overlayView = this
            isForceDarkAllowed = false
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                val preferredDark = when (currentAppSettings.nightMode) {
                    NightTheme.Dark -> true
                    NightTheme.Light -> false
                    NightTheme.System -> isSystemInDarkTheme()
                }
                val darkTheme = if (ownerDevice == null) preferredDark else {
                    when (val background = currentAppSettings.islandSettings.expandedBackgroundColor) {
                        IslandColor.SurfaceContainer -> true
                        IslandColor.Surface -> preferredDark
                        is IslandColor.Custom -> Color(background.colorValue.toULong()).luminance() < 0.5f
                    }
                }

                LibrePodsTheme(
                    designSystem = currentAppSettings.designSystem,
                    overrideMaterialColor = currentAppSettings.overrideMaterialColor,
                    accessibilitySettings = currentAppSettings.accessibilitySettings,
                    fontSettings = currentAppSettings.fontSettings,
                    darkTheme = darkTheme
                ) {
                    IslandContent(
                        name = name,
                        type = type,
                        batteryLevel = batteryLevel,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,
                        statusText = statusText,

                        islandSettings = effectiveSettings,
                        islandState = islandState,

                        openApp = { openApp() },
                        changeState = { state, _ -> changeState(state) },
                        transitionGeneration = transitionGeneration,
                        onTransitionFinished = { generation, state -> finishTransition(overlayView, generation, state) },
                        resetTimer = { resetTimer() },

                        showOffListeningMode = showOffListeningMode,
                        noiseControlModeValue = noiseControlModeValue,
                        onNoiseControlModeChanged = this@IslandWindow.onNoiseControlModeChanged,
                        adaptiveStrength = this@IslandWindow.adaptiveStrength,
                        requestedAdaptiveStrength = this@IslandWindow.requestedAdaptiveStrength,
                        onAdaptiveStrengthChanged = this@IslandWindow.onAdaptiveStrengthChanged,
                        listeningControlsEnabled = listeningControlsEnabled,
                        model = model, batteries = batteries, batteryNow = batteryNow,
                        batteryOwner = ownerAddress,
                        paired = ownerAddress != null, nearby = nearby, cameraPaddingPx = cameraPadding,
                        cameraCapsuleWidthPx = cameraCapsuleWidth, maximumHeightPx = maximumHeight,
                        onConnect = this@IslandWindow.onConnect
                            ?.takeUnless { connectState == IslandConnectState.IDLE && connectActionDeferred }
                            ?.let { { requestConnect() } },
                        connectState = connectState,
                        minimumStatusBarWidthPx = minimumStatusBarWidth,
                        statusBarIconCenterYpx = statusBarIconCenterY,
                        onStatusBarCoverageChanged = { covered ->
                            if (composeView === overlayView)
                                statusBarAppearance.setEnabled(covered && overlayView.isAttachedToWindow)
                        }
                    )
                }
            }
        }
    }

    private fun resetTimer() {
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        if (connectState == IslandConnectState.CONNECTING ||
            (connectState == IslandConnectState.IDLE && connectActionDeferred)) return
        autoCloseHandler = Handler(Looper.getMainLooper())
        autoCloseRunnable = Runnable {
            if (appSettings.islandSettings.keepCompactVisible) {
                changeState(IslandState.COMPACT)
            } else {
                close()
            }
        }
        autoCloseHandler?.postDelayed(autoCloseRunnable!!, 4500)
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { close() }
            return
        }

        cancelConnect(resetState = false)
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        ageHandler.removeCallbacks(updateAge)
        beginTransition(IslandState.CLOSE)
    }

    fun closePaired(address: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { closePaired(address) }
            return
        }
        if (ownerAddress == address) close()
    }

    private fun requestConnect() {
        val address = ownerAddress ?: return
        val action = onConnect ?: return
        val owner = lifecycleOwner ?: return
        if (!isVisible) return
        refreshConnectionState()
        if (!isVisible || !nearby || connectState == IslandConnectState.CONNECTING ||
            (connectState == IslandConnectState.IDLE && connectActionDeferred) ||
            type != IslandType.CONNECTED || statusText != null) return
        val generation = ++connectGeneration
        connectState = IslandConnectState.CONNECTING
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        connectJob = owner.lifecycleScope.launch {
            val success = try {
                withTimeout(25_000) { action() }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("IslandWindow", "Could not connect nearby AirPods", failure)
                false
            }
            // Completion belongs to this opening, even if the same pair was closed and reopened.
            if (!isActive || generation != connectGeneration || ownerAddress != address || islandState == IslandState.CLOSE) return@launch
            val connected = success || isAudioConnected?.invoke() == true
            connectState = if (connected) IslandConnectState.IDLE else IslandConnectState.FAILED
            if (connected) nearby = false
            resetTimer()
        }
    }

    private fun cancelConnect(resetState: Boolean = true) {
        connectGeneration++
        connectJob?.cancel()
        connectJob = null
        if (resetState) connectState = IslandConnectState.IDLE
    }

    private fun cleanupAndRemoveView(expected: ComposeView) = cleanupAndRemoveView(expected, immediately = false)

    private fun cleanupAndRemoveView(expected: ComposeView, immediately: Boolean) {
        if (composeView !== expected || islandState != IslandState.CLOSE) return
        statusBarAppearance.release()
        try {
            composeView?.let { view ->
                if (view.parent != null) {
                    if (immediately) windowManager.removeViewImmediate(view) else windowManager.removeView(view)
                }
            }
            lifecycleOwner?.stop()
        } catch (e: Exception) {
            Log.e("IslandWindow", "Error removing view: $e")
        } finally {
            ageHandler.removeCallbacks(updateAge)
            ownerAddress = null
            ownerDevice = null
            listeningControlsEnabled = false
            ownerAnnouncementAtNanos = Long.MIN_VALUE
            onConnect = null
            isAudioConnected = null
            deferConnectAction = null
            connectActionDeferred = false
            connectState = IslandConnectState.IDLE
            keepOpenRequested = false
            composeView = null
            lifecycleOwner = null
            retainedWindowWidth = 0
            islandState = IslandState.CLOSE
        }
    }
}

private class OverlayLifecycleOwner: LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
