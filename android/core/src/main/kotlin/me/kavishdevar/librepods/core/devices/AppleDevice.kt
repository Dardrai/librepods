package me.kavishdevar.librepods.core.devices

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.ATTManager
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.aacp.publishForTransport
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.audio.MicrophoneFrame
import me.kavishdevar.librepods.core.data.audio.MicrophoneState
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.data.recording.RecordingState
import me.kavishdevar.librepods.core.utils.GestureDetector
import me.kavishdevar.librepods.core.utils.HeadTracking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AppleDevice"

@SuppressLint("MissingPermission")
class AppleDevice(
    override val context: Context,
    override val bluetoothAdapter: BluetoothAdapter,
    override val bluetoothDevice: BluetoothDevice,
    currentState: ConnectionState
) : Device<AppleState, AppleSettings, AppleMetadata> {
    override val macAddress = MacAddress(bluetoothDevice.address)
    private val _state = MutableStateFlow(AppleState())
    override val state = _state.asStateFlow()

    private val _settings = MutableStateFlow(AppleSettings())
    override val settings = _settings.asStateFlow()

    private val _metadata = MutableStateFlow(AppleMetadata())
    override val metadata = _metadata.asStateFlow()

    private val _connectionState = MutableStateFlow(currentState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<AppleEvent>()
    val events = _events.asSharedFlow()

    private val _microphoneFrames = MutableSharedFlow<MicrophoneFrame>(
        extraBufferCapacity = 64
    )
    val microphoneFrames = _microphoneFrames.asSharedFlow()

    private val _connectionNumber = MutableStateFlow(0)
    override val connectionNumber = _connectionNumber.asStateFlow()

    fun loadInitialState(state: AppleState, settings: AppleSettings, metadata: AppleMetadata) {
        _state.value = state.copy(aacpPackets = retainedPacketHistory(state.aacpPackets),
            controlStates = state.cachedControlStates(settings, metadata.modelNumber))
        _settings.value = settings
        _metadata.value = metadata
    }

    fun receiveNearbyBattery(batteries: Set<Battery>) {
        synchronized(aacp) {
            val keepLive = connectionState.value == ConnectionState.CONNECTED && aacp.captureAudioSocket() != null
            val now = System.currentTimeMillis()
            _state.update { state ->
                // shortcut: keep all live slots; add source arbitration if live BLE refresh is needed during AACP.
                val reported = if (!keepLive) batteries else batteries.filter { incoming ->
                    incoming.isLive(now) && incoming.status != BatteryStatus.UNKNOWN &&
                        incoming.level in 0..100 && state.battery.none { old ->
                            old.component == incoming.component && old.level in 0..100 &&
                                old.status != BatteryStatus.UNKNOWN && old.isLive(now)
                        }
                }.toSet()
                state.copy(battery = mergeBatteryReports(state.battery, reported,
                    settings.value.cacheDisconnectedComponentBattery))
            }
            _connectionState.update { if (it == ConnectionState.DISCONNECTED) ConnectionState.AVAILABLE else it }
        }
    }

    fun expireNearbyBattery(now: Long) {
        if (connectionState.value == ConnectionState.CONNECTED) return
        _state.update { state -> state.copy(battery = state.battery.map {
            if (it.present && !it.isLive(now)) it.copy(status = BatteryStatus.DISCONNECTED, present = false) else it
        }.toSet()) }
        if (_state.value.battery.none { it.isLive(now) })
            _connectionState.update { if (it == ConnectionState.AVAILABLE) ConnectionState.DISCONNECTED else it }
    }

    // exists because audio processing is done by Service and other parts use Device as source of truth for microphone state and I didn't want to make whole updateState public
    fun updateMicrophoneState(
        transform: (MicrophoneState) -> MicrophoneState
    ) {
        _state.update {
            it.copy(
                microphoneState = transform(it.microphoneState)
            )
        }
    }

    internal inline fun updateState(
        transform: (AppleState) -> AppleState
    ) {
        _state.update { current ->
            val next = transform(current)
            next.copy(aacpPackets = retainedPacketHistory(next.aacpPackets))
        }
    }

    internal inline fun updateMetadata(
        transform: (AppleMetadata) -> AppleMetadata
    ) {
        _metadata.update(transform)
    }

    fun updateSettings(
        transform: (AppleSettings) -> AppleSettings
    ) {
        _settings.update(transform)
    }

    /** Call only inside the successful write's current-transport publication. */
    internal fun rememberAllowOffRequest(value: ByteArray, previousReport: ByteArray?) {
        _settings.update { it.withAllowOffRequest(metadata.value.modelNumber, state.value, value, previousReport) }
    }

    /** A fresh report also replaces a silent choice for the next reconnect. */
    internal fun rememberAllowOffReport(value: ByteArray) {
        _settings.update { it.withAllowOffReport(metadata.value.modelNumber, value) }
    }

    internal suspend fun emitEvent(event: AppleEvent) {
        _events.emit(event)
    }

    internal suspend fun emitMicrophoneFrame(frame: MicrophoneFrame) {
        _microphoneFrames.emit(frame)
    }

    val aacp = AACPManager(this)
    val att = ATTManager(this)
    private val hearingLock = Mutex()


    // ik, probably wrong place. TODO
    private val gestureDetector by lazy {
        GestureDetector()
    }

    init {
        updateMetadata {
            it.copy(
                name = bluetoothDevice.alias ?: bluetoothDevice.name ?: "Unknown"
            )
        }

        if (currentState == ConnectionState.AVAILABLE) {
            connect()
        }
    }

    override fun connect(): Boolean {
        val connected = connectControl(aacp::connect)
        CoroutineScope(Dispatchers.IO).launch {
            _state.update {
                it.copy(
                    loudSoundReductionEnabled = readATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION)?.getOrNull(0)?.toInt() == 1,
                    transparencyData = readATTCharacteristic(ATTHandle.TRANSPARENCY)?: byteArrayOf(),
                    hearingAidData = readATTCharacteristic(ATTHandle.HEARING_AID)?: byteArrayOf()
                )
            }
        }
        return connected
    }

    /** Explicit takeover establishes only AACP; ATT reads remain part of the normal connection path. */
    fun connect(stillCurrent: () -> Boolean, onPendingSocket: (BluetoothSocket) -> Unit): Boolean {
        if (!stillCurrent()) return false
        return connectControl { aacp.connect(stillCurrent, onPendingSocket) }
    }

    private fun connectControl(connect: () -> Boolean): Boolean {
        _connectionState.update { ConnectionState.CONNECTING }
        val success = connect()

        val connected = synchronized(aacp) {
            val current = aacp.captureAudioSocket()
            val connected = success && current != null
            if (connected || current == null)
                _connectionState.value = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            connected
        }
        _connectionNumber.update {
            it + 1
        }

        return connected
    }

    override fun disconnect() {
        _connectionState.update {
            ConnectionState.DISCONNECTING
        }

        aacp.disconnect()
//        att.disconnect()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            try {
                bluetoothDevice.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "couldn't disconnect bluetooth device", e)
            }
        }

        _connectionState.update {
            ConnectionState.DISCONNECTED
        }
    }

    internal fun onAacpDisconnected() {
        recordingHandler?.invoke(null)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun setControlCommand(identifier: ControlCommandIdentifier, value: ByteArray): Boolean {
        return aacp.sendControlCommand(identifier.value, value)
    }
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Byte): Boolean = setControlCommand(identifier, byteArrayOf(value))
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Int): Boolean =
        value in 0..255 && setControlCommand(identifier, byteArrayOf(value.toByte()))
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Boolean): Boolean =
        setControlCommand(identifier, if (value) byteArrayOf(1) else byteArrayOf(2))

    suspend fun setHearingAidEnabled(enabled: Boolean): Boolean = setHearingMode(HearingMode.HEARING_AID, enabled)
    suspend fun setMediaAssistEnabled(enabled: Boolean): Boolean = setHearingMode(HearingMode.MEDIA_ASSIST, enabled)

    private suspend fun setHearingMode(mode: HearingMode, enabled: Boolean): Boolean = hearingLock.withLock {
        if (metadata.value.model == AirPodsModel.UNKNOWN ||
            BaseCapability.HEARING_AID !in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities) return@withLock false
        val transport = aacp.captureAudioSocket() ?: return@withLock false
        val original = state.value.hearingModes
        val plan = original.plan(mode, enabled) ?: return@withLock false
        if (plan.packets.isEmpty()) return@withLock true
        val current = { aacp.ownsAudioSocket(transport) && state.value.hearingModes.pending == mode }
        val reserved = synchronized(aacp) {
            if (!aacp.ownsAudioSocket(transport) || state.value.hearingModes !== original ||
                (enabled && (state.value.microphoneState.isActive || aacp.isMicrophoneRequested()))) false
            else { updateState { it.copy(hearingModes = original.copy(pending = mode)) }; true }
        }
        if (!reserved) return@withLock false
        var attempted = false
        var confirmed = false
        try {
            val fitting = if (enabled) readATTCharacteristic(ATTHandle.HEARING_AID) else null
            if (!current()) return@withLock false
            if (enabled) {
                if (fitting?.size != 104 || parseHearingAidSettingsResponse(fitting) == null) return@withLock false
                val transparency = readATTCharacteristic(ATTHandle.TRANSPARENCY) ?: return@withLock false
                if (!current() || parseTransparencySettingsResponse(transparency)?.enabled != false) return@withLock false
            }
            if (!state.value.hearingModes.sameConfiguration(original)) return@withLock false
            if (enabled && (state.value.microphoneState.isActive || aacp.isMicrophoneRequested())) return@withLock false
            for (packet in plan.packets) {
                if (!current()) return@withLock false
                attempted = true
                if (!aacp.sendAudioPacket(transport, packet)) return@withLock false
            }
            val report = withTimeoutOrNull(10.seconds) {
                state.first { !current() || it.hearingModes.confirms(plan) }
            } ?: return@withLock false
            if (!current() || !report.hearingModes.confirms(plan)) return@withLock false
            if (enabled) {
                val readback = readATTCharacteristic(ATTHandle.HEARING_AID)
                if (!current() || !fitting.contentEquals(readback) || !state.value.hearingModes.confirms(plan)) return@withLock false
            }
            confirmed = true
            true
        } finally {
            publishForTransport(aacp, transport, aacp::captureAudioSocket) {
                updateState { state ->
                    if (state.hearingModes.pending != mode) state else state.copy(hearingModes = state.hearingModes.copy(
                        pending = null, uncertain = if (confirmed) null else if (attempted) mode else original.uncertain))
                }
            }
        }
    }

    suspend fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray): Boolean = hearingLock.withLock {
        val transport = aacp.captureAudioSocket() ?: return@withLock false
        if (handle == ATTHandle.TRANSPARENCY) {
            val settings = parseTransparencySettingsResponse(value) ?: return@withLock false
            if (settings.enabled && BaseCapability.HEARING_AID in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities &&
                !state.value.hearingModes.customTransparencyAllowed) return@withLock false
        }
        if (handle == ATTHandle.HEARING_AID && (!hearingAidSettingsWritable(state.value.hearingAidData) ||
                !hearingAidSettingsWritable(value) || !value.take(4).equals(state.value.hearingAidData.take(4)))) return@withLock false
        val success = att.writeCharacteristic(handle, value)
        success && publishForTransport(aacp, transport, aacp::captureAudioSocket) {
            when (handle) {
                ATTHandle.LOUD_SOUND_REDUCTION -> _state.update { it.copy(loudSoundReductionEnabled = value.getOrNull(0)?.toInt() == 1) }
                ATTHandle.TRANSPARENCY -> _state.update { it.copy(transparencyData = value) }
                ATTHandle.HEARING_AID -> _state.update { it.copy(hearingAidData = value) }
            }
        }
    }

    // can't use att notifications; keeping ATT connected causes airpods to disconnect every few seconds for some reason
    // we will poll the characteristic every 1 seconds instead.
    fun observeATTCharacteristic(handle: ATTHandle): Job = CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            val value = readATTCharacteristic(handle)
            if (value != null) {
                when (handle) {
                    ATTHandle.LOUD_SOUND_REDUCTION -> _state.update { it.copy(loudSoundReductionEnabled = value.getOrNull(0)?.toInt() == 1) }
                    ATTHandle.TRANSPARENCY -> _state.update { it.copy(transparencyData = value) }
                    ATTHandle.HEARING_AID -> _state.update { it.copy(hearingAidData = value) }
                }
            }
            delay(1000.milliseconds)
        }
    }

    suspend fun readATTCharacteristic(handle: ATTHandle): ByteArray? = att.readCharacteristic(handle)

    fun requestMicrophoneStream() = aacp.requestMicrophoneStream()
    fun endMicrophoneStream() {
        aacp.endMicrophoneStream()
        reconnectA2dp()
    }

    fun startRecording(recording: Recording) {
        if (recordingHandler?.invoke(recording) != true) return
        updateState {
            it.copy(
                recordingState = RecordingState(
                    isRecording = true,
                    recording = recording
                )
            )
        }
    }
    fun stopRecording() {
        recordingHandler?.invoke(null)
        updateState {
            it.copy(
                recordingState = RecordingState(
                    isRecording = false,
                    recording = null
                ),
                microphoneState = MicrophoneState(),
            )
        }
    }

    /** The application owns permission, foreground access, decoding and playback recovery. */
    var recordingHandler: ((Recording?) -> Boolean)? = null

    fun updateRecordingState(transform: (RecordingState) -> RecordingState) {
        updateState { it.copy(recordingState = transform(it.recordingState)) }
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommand(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, newValue)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        _settings.update {
            if (side.lowercase() == "left") it.copy(leftLongPressAction = action) else it.copy(rightLongPressAction = action)
        }
    }

    fun renameDevice(newName: String): Boolean = aacp.sendRename(newName)

    fun startHeadTracking() {
        aacp.setHeadTrackingEnabled(true)
    }

    fun stopHeadTracking() {
        aacp.setHeadTrackingEnabled(false)
        HeadTracking.reset()
        gestureDetector.stopDetection()
    }

    /** Pause sensor transport while the HQ microphone owns it; preserve user sensor intent. */
    fun setSensorsSuspended(suspended: Boolean) {
        aacp.setSensorsSuspended(suspended)
    }

    // TODO: multiple callbacks
    fun detectHeadGestures(callback: (Boolean) -> Unit) {
        if (state.value.headTrackingState != BuddyState.ACTIVE) startHeadTracking()
        val started = gestureDetector.startDetection(HeadTracking.acceleration, callback)
        if (!started) {
            Log.w(TAG, "Failed to start gesture detection, probably because already running.")
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        _settings.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setHeadGesturesVerticalOffset(offset: Int) {
        _settings.update {
            it.copy(headGesturesVerticalOffset = offset)
        }
    }

    fun setHeadGesturesHorizontalOffset(offset: Int) {
        _settings.update {
            it.copy(headGesturesHorizontalOffset = offset)
        }
    }

    fun setHeadTrackingInterval(interval: Duration) {
        _settings.update { it.copy(headTrackingInterval = interval) }
        // Captured native service uses 20 ms; the recognized legacy layout uses 40 ms.
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        aacp.setCustomEq((_state.value.requestedCustomEq ?: _state.value.customEq).copy(state = if(enabled) 2 else 1))
    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        aacp.setCustomEq((_state.value.requestedCustomEq ?: _state.value.customEq).copy(low = low, mid = mid, high = high))
    }

    // ACTIVE is reported only after a validated sample, never after a successful write alone.
    fun startHr(): Boolean = aacp.setHeartRateEnabled(true)
    fun stopHr(): Boolean = aacp.setHeartRateEnabled(false)

    fun sendRawPacket(data: ByteArray): Boolean = aacp.sendRawPacket(data)

////    private val _cameraAction = MutableStateFlow(
////        sharedPreferences.getString("camera_action", null)
////            ?.let { value -> StemPressType.entries.find { it.name == value } })
////
////    val cameraAction: StateFlow<StemPressType?> = _cameraAction
////
////    fun setCameraAction(action: StemPressType?) {
////        sharedPreferences.edit {
////            if (action == null) remove("camera_action")
////            else putString("camera_action", action.name)
////        }
////        _cameraAction.value = action
////    }

}

private fun countEnabledModes(byteValue: Int): Int {
    var count = 0
    if ((byteValue and 0x01) != 0) count++
    if ((byteValue and 0x02) != 0) count++
    if ((byteValue and 0x04) != 0) count++
    if ((byteValue and 0x08) != 0) count++
    return count
}
