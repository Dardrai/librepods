/*
    LibrePods - AirPods liberated from Apple’s ecosystem
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

package me.kavishdevar.librepods.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.bluetooth.BluetoothSocket
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AllowOffPolicy
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTCCCDHandles
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsFirmware
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.services.AirPodsService
import java.util.concurrent.atomic.AtomicLong

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String = "AirPods",
    val deviceAddress: String? = null,

    val isLocallyConnected: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val requestedControls: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val offListeningMode: Boolean = false,

    val battery: List<Battery> = emptyList(),
    val ancMode: Int = 3,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headGesturesEnabled: Boolean = true,

    val eqData: FloatArray = floatArrayOf(),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val loudSoundReductionEnabled: Boolean = false,
    val transparencyData: ByteArray = byteArrayOf(),
    val hearingAidData: ByteArray = byteArrayOf(),

    val isPremium: Boolean = false,
    val vendorIdHook: Boolean = false,

    val dynamicEndOfCharge: Boolean = false,

    val connectionSuccessful: Boolean = false,
    val timeUntilFOSSPremiumExpiry: Long = 0L,

    val customEq: CustomEq = CustomEq(1, 50, 50, 50), // disabled
    val requestedCustomEq: CustomEq? = null
)

internal fun normalizedControl(value: ByteArray): ByteArray =
    value.dropLastWhile { it == 0.toByte() }.toByteArray().takeIf { it.isNotEmpty() } ?: byteArrayOf(0)

internal fun offListeningModeAllowed(state: AirPodsUiState): Boolean {
    val id = ControlCommandIdentifiers.ALLOW_OFF_OPTION
    val major = AirPodsFirmware.publicVersion(state.version1, state.version3)?.let(AirPodsFirmware::major)
    return AllowOffPolicy.allowed(state.requestedControls[id]?.firstOrNull()?.toInt(), state.controlStates[id]?.firstOrNull()?.toInt(),
        state.controlStates[ControlCommandIdentifiers.LISTENING_MODE]?.firstOrNull()?.toInt(),
        Capability.LISTENING_MODE in state.capabilities, Capability.ADAPTIVE_AUDIO in state.capabilities, major)
}

internal fun AirPodsUiState.withControlReport(identifier: ControlCommandIdentifiers, rawValue: ByteArray): AirPodsUiState {
    if (rawValue.isEmpty() || rawValue.size > 4) return this
    val value = normalizedControl(rawValue)
    if (identifier == ControlCommandIdentifiers.AUTO_ANC_STRENGTH &&
        (value.size != 1 || (value[0].toInt() and 255) !in 0..100)) return this
    if (identifier == ControlCommandIdentifiers.ALLOW_OFF_OPTION &&
        (value.size != 1 || value[0].toInt() !in 1..2)) return this
    val requests = if (requestedControls[identifier]?.let { normalizedControl(it).contentEquals(value) } == true)
        requestedControls - identifier else requestedControls
    val next = copy(controlStates = controlStates + (identifier to value), requestedControls = requests,
        dynamicEndOfCharge = if (identifier == ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE)
            (requests[identifier] ?: value).firstOrNull() == 1.toByte() else dynamicEndOfCharge)
    return next.copy(offListeningMode = offListeningModeAllowed(next))
}

internal fun AirPodsUiState.withCustomEqReport(report: CustomEq): AirPodsUiState =
    copy(customEq = report, requestedCustomEq = requestedCustomEq?.takeUnless { it == report })

internal fun controlDeviceMatches(ui: String?, loaded: String?, selected: String?, remote: String?): Boolean =
    ui != null && ui == loaded && ui == selected && ui == remote

val demoInstance = AirPodsInstance(
    name = "[DEMO] AirPods Pro",
    model = AirPodsModels.getModelByModelNumber("A3064")!!,
    actualModelNumber = "A3064",
    serialNumber = "JXF9Q94A40",
    leftSerialNumber = "L-DEMO",
    rightSerialNumber = "R-DEMO",
    version1 = "90.3388000000000000.1786",
    version2 = "90.3388000000000000.1786",
    version3 = "9441861",
)

val demoState = AirPodsUiState(
    deviceName = demoInstance.name,

    isLocallyConnected = true,

    capabilities = demoInstance.model.capabilities,

    battery = listOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.RIGHT, 18, BatteryStatus.CHARGING),
        Battery(BatteryComponent.CASE, 76, BatteryStatus.NOT_CHARGING)
    ),

    ancMode = 3,
    offListeningMode = false,

    modelName = demoInstance.model.displayName,
    actualModel = demoInstance.actualModelNumber,
    serialNumbers =  listOf(
        demoInstance.serialNumber?: "",
        demoInstance.leftSerialNumber?: "",
        demoInstance.rightSerialNumber?: ""
    ),

    version1 = demoInstance.version1?: "",
    version2 = demoInstance.version2?: "",
    version3 = demoInstance.version3?: "",

    headTrackingActive = true,
    headGesturesEnabled = true,

    automaticEarDetectionEnabled = true,
    automaticConnectionEnabled = true,

    leftAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    rightAction = StemAction.DIGITAL_ASSISTANT,

    loudSoundReductionEnabled = true,

    isPremium = true,
    vendorIdHook = true,

    dynamicEndOfCharge = true,

    customEq = CustomEq(state = 2, low = 65, mid = 50, high = 70),

    controlStates = mapOf(
        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.STEM_CONFIG to byteArrayOf(0x00),
        ControlCommandIdentifiers.CLICK_HOLD_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG to byteArrayOf(0x00, 0x03),
        ControlCommandIdentifiers.CHIME_VOLUME to byteArrayOf(0x46, 0x50),
        ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_AID to byteArrayOf(0x01, 0x02),
        ControlCommandIdentifiers.HPS_GAIN_SWIPE to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_ASSIST_CONFIG to byteArrayOf(0x02),
        ControlCommandIdentifiers.HRM_STATE to byteArrayOf(0x01),
        ControlCommandIdentifiers.AUTO_ANC_STRENGTH to byteArrayOf(0x45),
        ControlCommandIdentifiers.ONE_BUD_ANC_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_TOGGLE_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_CAP_LEVEL_CONFIG to byteArrayOf(0x52),
        ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE to byteArrayOf(0x01),
        ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(0x04)
    )
)

class AirPodsViewModel(

) : ViewModel() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var appContext: Context
    private lateinit var service: AirPodsService
    val experimentalAudio get() = if (!isDemoMode && ::service.isInitialized) service.experimentalAudio else null
    private lateinit var controlRepo: ControlCommandRepository

    var isReady by mutableStateOf(false)
        private set

    fun init(service: AirPodsService, controlRepo: ControlCommandRepository, sharedPreferences: SharedPreferences, appContext: Context) {
        if (isReady && this::service.isInitialized && this.service === service) {
            loadCurrentStatus()
            return
        }
        stopObservingService()
        this.service = service
        this.controlRepo = controlRepo
        this.sharedPreferences = sharedPreferences
        this.appContext = appContext

        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        observeAACP()
        loadCurrentStatus()
        loadEq()
        loadATT()
        observeATT()
        observeSharedPreferences()
        observeBilling()
        if (isDemoMode) activateDemoMode()
        isReady = true
    }

    private val _uiState = MutableStateFlow(AirPodsUiState())

    val uiState: StateFlow<AirPodsUiState> = _uiState

    private var isDemoMode = false
    @Volatile
    private var adaptiveRequest: Job? = null
    private val adaptiveGeneration = AtomicLong()
    private var controlSocket: BluetoothSocket? = null
    private var controlAddress: String? = null
    private var preferencesObserver: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var billingObserver: Job? = null

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    private lateinit var broadcastReceiver: BroadcastReceiver

//    private val _cameraAction = MutableStateFlow(
//        sharedPreferences.getString("camera_action", null)
//            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } })
//
//    val cameraAction: StateFlow<AACPManager.Companion.StemPressType?> = _cameraAction
//
//    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
//        sharedPreferences.edit {
//            if (action == null) remove("camera_action")
//            else putString("camera_action", action.name)
//        }
//        _cameraAction.value = action
//    }

    fun setCustomEq(low: Int, mid: Int, high: Int): Boolean {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        val state = _uiState.value
        return sendCustomEqRequest((state.requestedCustomEq ?: state.customEq).copy(low = low, mid = mid, high = high))
    }

    fun setCustomEqEnabled(enabled: Boolean): Boolean {
        val state = _uiState.value
        return sendCustomEqRequest((state.requestedCustomEq ?: state.customEq).copy(state = if (enabled) 2 else 1))
    }

    private fun currentControlSocket(): BluetoothSocket? {
        val socket = controlSocket ?: return null
        val state = _uiState.value
        return socket.takeIf { it === BluetoothConnectionManager.aacpSocket && it.isConnected &&
            state.isLocallyConnected && controlDeviceMatches(state.deviceAddress, controlAddress,
                service.device?.address, runCatching { it.remoteDevice.address }.getOrNull()) }
    }

    private fun sendCustomEqRequest(request: CustomEq): Boolean {
        if (isDemoMode) {
            _uiState.update { it.withCustomEqReport(request) }
            return true
        }
        val socket = currentControlSocket() ?: return false
        val address = _uiState.value.deviceAddress
        if (!service.aacpManager.sendSensorPacket(socket, service.aacpManager.createDataPacket(request.toPacket()))) {
            showControlError(R.string.setting_send_failed)
            return false
        }
        if (currentControlSocket() !== socket) return false
        _uiState.update { state ->
            if (state.deviceAddress != address) state else
                state.copy(requestedCustomEq = request.takeUnless { it == service.aacpManager.customEq })
        }
        return true
    }

    override fun onCleared() {
        stopObservingService()
        super.onCleared()
    }

    private fun stopObservingService() {
        cancelAdaptiveRequest()
        if (::controlRepo.isInitialized) listeners.forEach { (id, listener) -> controlRepo.remove(id, listener) }
        listeners.clear()
        if (::service.isInitialized) service.aacpManager.customEqCallback = null
        if (::appContext.isInitialized && ::broadcastReceiver.isInitialized)
            runCatching { appContext.unregisterReceiver(broadcastReceiver) }
        preferencesObserver?.let { if (::sharedPreferences.isInitialized) sharedPreferences.unregisterOnSharedPreferenceChangeListener(it) }
        preferencesObserver = null
        billingObserver?.cancel()
        billingObserver = null
    }

    fun onServiceDisconnected() {
        stopObservingService()
        controlSocket = null
        controlAddress = null
        isReady = false
        _uiState.update { it.copy(isLocallyConnected = false, controlStates = emptyMap(), requestedControls = emptyMap(),
            requestedCustomEq = null) }
    }

    private fun loadName() {
        val name = sharedPreferences.getString("name", "AirPods Pro")!!
        _uiState.update { it.copy(deviceName = name) }
    }

    private fun observeBilling() {
        if (isDemoMode) return
        billingObserver = viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        setControlCommandBoolean(
                            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                            false
                        )
                        setHeadGesturesEnabled(false)
                        _uiState.update { it.copy(isPremium = false) }
                    }
                }
            }
        }
    }

    private fun observeSharedPreferences() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "name" -> loadName()
                "off_listening_mode", "automatic_ear_detection", "automatic_connection_ctrl_cmd",
                "head_gestures", "left_long_press_action", "right_long_press_action",
                "dynamic_end_of_charge", "foss_upgraded", "premium_expiry_time" -> loadSharedPreferences()
            }
        }
        preferencesObserver = listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    AirPodsNotifications.AIRPODS_CONNECTED, AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        loadInstance()
                        loadCurrentStatus()
                        loadSharedPreferences()
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        cancelAdaptiveRequest()
                        controlSocket = null
                        controlAddress = null
                        _uiState.update {
                            val next = it.copy(isLocallyConnected = false, controlStates = emptyMap(), requestedControls = emptyMap(),
                                requestedCustomEq = null)
                            next.copy(offListeningMode = offListeningModeAllowed(next))
                        }
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(battery = service.getBattery())
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadInstance()
                        loadCurrentStatus()
                        loadSharedPreferences()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_L2CAP_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ): Boolean {
        if (value.isEmpty() || value.size > 4) return false
        if (identifier == ControlCommandIdentifiers.AUTO_ANC_STRENGTH &&
            (value.size != 1 || (value[0].toInt() and 255) !in 0..100)) return false
        if (identifier == ControlCommandIdentifiers.ALLOW_OFF_OPTION &&
            (value.size != 1 || value[0].toInt() !in 1..2)) return false
        if (identifier == ControlCommandIdentifiers.AUTO_ANC_STRENGTH &&
            Capability.ADAPTIVE_AUDIO !in _uiState.value.capabilities) return false
        val generation = if (identifier in listOf(ControlCommandIdentifiers.AUTO_ANC_STRENGTH, ControlCommandIdentifiers.LISTENING_MODE))
            cancelAdaptiveRequest() else null
        if (isDemoMode) {
            _uiState.update { it.withControlReport(identifier, value) }
            return true
        }
        val socket = currentControlSocket() ?: return false
        val address = service.device?.address ?: return false
        if (!socket.isConnected) return false
        val request = normalizedControl(value)
        if (!isDemoMode && identifier == ControlCommandIdentifiers.AUTO_ANC_STRENGTH &&
            _uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE]?.firstOrNull() != 4.toByte()) {
            if (!sendControlRequest(ControlCommandIdentifiers.LISTENING_MODE, byteArrayOf(4), address, socket, generation)) return false
            adaptiveRequest = viewModelScope.launch {
                try {
                    withTimeout(3_000) {
                        uiState.first { it.isLocallyConnected && it.deviceAddress == address &&
                            it.controlStates[ControlCommandIdentifiers.LISTENING_MODE]?.firstOrNull() == 4.toByte() }
                    }
                    if (generation == adaptiveGeneration.get() && BluetoothConnectionManager.aacpSocket === socket &&
                        service.device?.address == address && Capability.ADAPTIVE_AUDIO in uiState.value.capabilities)
                        sendControlRequest(identifier, request, address, socket, generation)
                } catch (_: TimeoutCancellationException) {
                    if (generation == adaptiveGeneration.get()) showControlError(R.string.adaptive_mode_not_confirmed)
                }
            }
            return true
        }
        return sendControlRequest(identifier, request, address, socket, generation)
    }

    private fun cancelAdaptiveRequest(): Long {
        val generation = adaptiveGeneration.incrementAndGet()
        adaptiveRequest?.cancel()
        adaptiveRequest = null
        return generation
    }

    private fun showControlError(resource: Int) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(appContext, resource, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendControlRequest(identifier: ControlCommandIdentifiers, value: ByteArray, address: String, socket: BluetoothSocket,
        generation: Long? = null): Boolean {
        if (generation != null && generation != adaptiveGeneration.get()) return false
        if (service.device?.address != address || BluetoothConnectionManager.aacpSocket !== socket || !socket.isConnected) return false
        val packet = service.aacpManager.createDataPacket(service.aacpManager.createControlCommandPacket(identifier.value, value))
        // Pin delayed writes to their original transport across a device handoff.
        if (!service.aacpManager.sendSensorPacket(socket, packet)) {
            showControlError(R.string.setting_send_failed)
            return false
        }
        if (service.device?.address != address || BluetoothConnectionManager.aacpSocket !== socket) return false
        if (generation != null && generation != adaptiveGeneration.get()) return false
        _uiState.update { state ->
            if (state.deviceAddress != address) state else {
                val requests = if (state.controlStates[identifier]?.contentEquals(value) == true)
                    state.requestedControls - identifier else state.requestedControls + (identifier to value.copyOf())
                val next = state.copy(requestedControls = requests)
                next.copy(offListeningMode = offListeningModeAllowed(next))
            }
        }
        if (_uiState.value.actualModel in setOf("A3063", "A3064", "A3065") &&
            identifier in listOf(ControlCommandIdentifiers.ALLOW_OFF_OPTION, ControlCommandIdentifiers.AUTO_ANC_STRENGTH))
            sharedPreferences.edit { putInt("requested_${address}_${identifier.name}", value[0].toInt() and 255) }
        return true
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        require(value in 0..255)
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        listeners.remove(identifier)?.let { controlRepo.remove(identifier, it) }
        val listener = controlRepo.observe(identifier) { value ->
            _uiState.update { state ->
                if (state.deviceAddress != service.device?.address || !state.isLocallyConnected) state
                else state.withControlReport(identifier, value)
            }
        }

        listeners[identifier] = listener
    }

    fun observeAACP() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
        service.aacpManager.customEqCallback = { customEq ->
            _uiState.update {
                if (it.isLocallyConnected && currentControlSocket() != null) it.withCustomEqReport(customEq) else it
            }
        }
    }

    fun loadCurrentStatus() {
        if (isDemoMode) return
        val socket = BluetoothConnectionManager.aacpSocket
        val address = service.device?.address
        val connected = socket?.isConnected == true && address != null &&
            runCatching { socket.remoteDevice.address == address }.getOrDefault(false)
        val changed = socket !== controlSocket || address != controlAddress
        if (changed) {
            cancelAdaptiveRequest()
            controlSocket = socket
            controlAddress = address
        }
        val reports = if (connected) controlRepo.getMap().mapValues { normalizedControl(it.value) } else emptyMap()
        _uiState.update { previous ->
            val requests = (if (changed || !connected) emptyMap() else previous.requestedControls).toMutableMap()
            if (connected && previous.actualModel in setOf("A3063", "A3064", "A3065")) {
                address?.let { address ->
                    for (id in listOf(ControlCommandIdentifiers.ALLOW_OFF_OPTION, ControlCommandIdentifiers.AUTO_ANC_STRENGTH)) {
                        val key = "requested_${address}_${id.name}"
                        val saved = sharedPreferences.getInt(key, -1)
                        val valid = if (id == ControlCommandIdentifiers.ALLOW_OFF_OPTION) saved in 1..2 else saved in 0..100
                        if (valid && id !in reports && id !in requests) requests[id] = byteArrayOf(saved.toByte())
                    }
                }
            }
            requests.entries.removeAll { (id, value) -> reports[id]?.contentEquals(value) == true }
            val next = previous.copy(deviceAddress = address, isLocallyConnected = connected, battery = service.getBattery(),
                ancMode = reports[ControlCommandIdentifiers.LISTENING_MODE]?.firstOrNull()?.toInt() ?: 1,
                controlStates = reports, requestedControls = requests,
                customEq = if (connected) service.aacpManager.customEq else CustomEq(1, 50, 50, 50),
                requestedCustomEq = previous.requestedCustomEq?.takeUnless {
                    changed || !connected || it == service.aacpManager.customEq
                })
            next.copy(offListeningMode = offListeningModeAllowed(next))
        }
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = offListeningModeAllowed(_uiState.value)
        val automaticEarDetectionEnabled =
            sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled =
            sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val leftAction = StemAction.valueOf(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val rightAction = StemAction.valueOf(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false)
        val chargeId = ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        val dynamicEndOfCharge = (_uiState.value.requestedControls[chargeId] ?: _uiState.value.controlStates[chargeId])
            ?.firstOrNull() == 1.toByte()

        val connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                headGesturesEnabled = headGesturesEnabled,
                leftAction = leftAction,
                rightAction = rightAction,
                vendorIdHook = vendorIdHook,
                dynamicEndOfCharge = dynamicEndOfCharge,
                connectionSuccessful = connectionSuccessful,
            )
        }

        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase
        if (BuildConfig.PLAY_BUILD) {
            val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
            val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
            val now = System.currentTimeMillis()

            when {
                // existing temporary premium
                expiryTime > 0L -> {
                    if (expiryTime <= now) {
                        sharedPreferences.edit {
                            remove("premium_expiry_time")
                            remove("foss_upgraded")
                        }

                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = 0L,
                                isPremium = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = expiryTime - now,
                                isPremium = true
                            )
                        }
                    }
                }

                // First migration from accidental FOSS Play build
                fossUpgraded && !_uiState.value.isPremium -> {
                    val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                    sharedPreferences.edit {
                        putLong("premium_expiry_time", newExpiry)
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = newExpiry - now,
                            isPremium = true
                        )
                    }
                }
            }
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        if (!setControlCommandValue(ControlCommandIdentifiers.ALLOW_OFF_OPTION, byteArrayOf(if (enabled) 1 else 2))) return
        _uiState.update {
            it.copy(offListeningMode = enabled)
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setDynamicEndOfCharge(enabled: Boolean) {
        if (!setControlCommandValue(ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE, byteArrayOf(if (enabled) 1 else 2))) return
        sharedPreferences.edit { putBoolean("dynamic_end_of_charge", enabled) }
        _uiState.update {
            it.copy(dynamicEndOfCharge = enabled)
        }
    }

    private fun loadEq() {
        _uiState.update {
            it.copy(
                customEq = service.aacpManager.customEq
            )
        }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance
        if (instance == null) {
            cancelAdaptiveRequest()
            _uiState.update { it.copy(instance = null, capabilities = emptySet(), modelName = "", actualModel = "") }
            return
        }
        if (_uiState.value.actualModel != instance.actualModelNumber || Capability.ADAPTIVE_AUDIO !in instance.model.capabilities)
            cancelAdaptiveRequest()

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(
                    instance.serialNumber ?: "",
                    instance.leftSerialNumber ?: "",
                    instance.rightSerialNumber ?: ""
                ),
                version1 = instance.version1 ?: "",
                version2 = instance.version2 ?: "",
                version3 = instance.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        service.reconnectFromSavedMac()
    }

    fun setName(name: String) {
        service.setName(name)
    }

    fun startHeadTracking() {
        service.startHeadTracking()
        _uiState.update { it.copy(headTrackingActive = true) }
    }

    fun stopHeadTracking() {
        service.stopHeadTracking()
        _uiState.update { it.copy(headTrackingActive = false) }
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray) {
        when (handle) {
            // ideally should be using a different viewmodel for ATT based things because there are a lot of values, and I am not going to add all to this state, but there's loudsoundreduction.
            ATTHandles.LOUD_SOUND_REDUCTION -> {
                _uiState.value = _uiState.value.copy(loudSoundReductionEnabled = value[0].toInt() == 0x01)
            }
            ATTHandles.HEARING_AID -> {
                _uiState.value = _uiState.value.copy(hearingAidData = value)
            }
            ATTHandles.TRANSPARENCY -> {
                _uiState.value = _uiState.value.copy(transparencyData = value)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                service.attManager.writeCharacteristic(handle, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadATT() {
        val loudSoundReduction = service.attManager.getCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION) ?: byteArrayOf()
        val loudSoundReductionEnabled = if (loudSoundReduction.isNotEmpty()) {
            loudSoundReduction[0].toInt() == 1
        } else false
        val hearingAidData = service.attManager.getCharacteristic(ATTHandles.HEARING_AID) ?: byteArrayOf()
        val transparencyData = service.attManager.getCharacteristic(ATTHandles.TRANSPARENCY) ?: byteArrayOf()
        _uiState.update {
            it.copy(
                loudSoundReductionEnabled = loudSoundReductionEnabled,
                transparencyData = transparencyData,
                hearingAidData = hearingAidData
            )
        }
    }

    fun observeATT() {
        viewModelScope.launch(Dispatchers.IO) {
            service.attManager.enableNotification(ATTCCCDHandles.HEARING_AID)
            service.attManager.enableNotification(ATTCCCDHandles.TRANSPARENCY)
        }
        service.attManager.setOnNotificationReceived { handle, value ->
            when (handle) {
                ATTHandles.LOUD_SOUND_REDUCTION.value.toByte() -> {
                    val loudSoundReductionEnabled = if (value.isNotEmpty()) {
                        value[0].toInt() == 1
                    } else false
                    _uiState.update {
                        it.copy(loudSoundReductionEnabled = loudSoundReductionEnabled)
                    }
                }
                ATTHandles.HEARING_AID.value.toByte() -> {
                    _uiState.update {
                        it.copy(hearingAidData = value)
                    }
                }
                ATTHandles.TRANSPARENCY.value.toByte() -> {
                    _uiState.update {
                        it.copy(transparencyData = value)
                    }
                }
            }
        }
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticEarDetectionEnabled = enabled
            )
        }
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_connection_ctrl_cmd", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticConnectionEnabled = enabled
            )
        }
    }

    fun activateDemoMode() {
        isDemoMode = true
        _uiState.update {demoState}
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
        sharedPreferences.edit { putInt("long_press_byte", newValue) }
    }

    fun disconnect() {
        cancelAdaptiveRequest()
        if (isDemoMode) {
            isDemoMode = false
            _uiState.update {
                                it.copy(isLocallyConnected = false, controlStates = emptyMap(), requestedControls = emptyMap(),
                                    requestedCustomEq = null)
            }
        } else {
            service.disconnectAirPods()
            if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    appContext, "App has disconnected, disconnect from Android Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
