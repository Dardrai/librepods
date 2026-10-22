package me.kavishdevar.librepods.core.devices

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.ATTManager
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.customTransparencyEnabled
import me.kavishdevar.librepods.core.bluetooth.aacp.publishForTransport
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
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
        _settings.value = if (settings.allowOffChoice != null && "desired_allow_off" !in settings.savedUnconfirmedChoices)
            settings.copy(savedUnconfirmedChoices = settings.savedUnconfirmedChoices +
                ("desired_allow_off" to if (settings.allowOffChoice) "on" else "off")) else settings
        _metadata.value = metadata
    }

    fun receiveNearbyBattery(batteries: Set<Battery>) {
        synchronized(aacp) {
            val keepLive = connectionState.value == ConnectionState.CONNECTED && aacp.captureAudioSocket() != null
            val now = System.currentTimeMillis()
            _state.update { state ->
                val reported = if (!keepLive) batteries else batteries.filter { incoming ->
                    incoming.isLive(now) && incoming.status != BatteryStatus.UNKNOWN &&
                        incoming.level in 0..100 && state.battery.none { old ->
                            old.component == incoming.component && old.level in 0..100 &&
                                old.status != BatteryStatus.UNKNOWN && old.isLive(now) &&
                                // The case reports independently of the connected earbuds over BLE.
                                (incoming.component != BatteryComponent.CASE || incoming.observedAt!! <= old.observedAt!!)
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
        synchronized(_settings) {
            val old = _settings.value
            val next = transform(old)
            if (!LocalPreferenceEdits.remember(context, macAddress.value, LocalPreferenceEdits.changed(old, next))) {
                onSettingsSaveFailed?.invoke()
                return
            }
            _settings.value = next
        }
        if (!settings.value.cacheDisconnectedComponentBattery)
            _state.update { it.copy(battery = it.battery.filter { battery -> battery.isLive() && battery.status != BatteryStatus.DISCONNECTED }.toSet()) }
    }

    var onSettingsSaveFailed: (() -> Unit)? = null

    /** The received stamp carries remote provenance; projecting it is never a new local edit. */
    fun updateSettingsFromCompanion(transform: (AppleSettings) -> AppleSettings) = synchronized(_settings) {
        _settings.value = transform(_settings.value)
    }

    /** Call only inside the successful write's current-transport publication. */
    internal fun rememberAllowOffRequest(value: ByteArray, previousReport: ByteArray?) {
        updateSettings { current ->
            val next = current.withAllowOffRequest(metadata.value.modelNumber, state.value, value, previousReport)
            if (next == current) current else next.copy(savedUnconfirmedChoices = next.savedUnconfirmedChoices +
                ("desired_allow_off" to if (next.allowOffChoice == true) "on" else "off"))
        }
    }

    internal fun rememberSavedControlRequest(id: ControlCommandIdentifier, value: ByteArray) {
        val key = savedControlKey(id) ?: return
        val choice = savedControlChoice(id, value) ?: return
        updateSettings { it.copy(savedUnconfirmedChoices = it.savedUnconfirmedChoices + (key to choice)) }
    }

    /** A fresh report also replaces a silent choice for the next reconnect. */
    internal fun rememberAllowOffReport(value: ByteArray) {
        synchronized(_settings) { _settings.value = _settings.value.withAllowOffReport(metadata.value.modelNumber, value) }
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
    @Volatile internal var attOperationPending = false
        private set


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
    }

    var onExplicitConnect: (() -> (() -> Boolean)?)? = null

    override fun connect(): Boolean {
        val authorize = onExplicitConnect
        val current = if (authorize == null) ({ true }) else authorize() ?: return false
        return connect(current)
    }

    /** Native-route attachment retires if Android no longer has this pair's audio output. */
    fun connect(stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        val connected = connectControl { aacp.connect(stillCurrent) {} }
        val transport = aacp.captureAudioSocket()
        if (connected && transport != null) CoroutineScope(Dispatchers.IO).launch {
            for (handle in ATTHandle.entries) {
                if (!stillCurrent()) break
                readATTCharacteristic(handle, transport)
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

    var onExplicitDisconnect: (() -> Unit)? = null

    fun disconnectControl() {
        gestureDetector.stopDetection()
        _connectionState.update {
            ConnectionState.DISCONNECTING
        }

        aacp.disconnect()
//        att.disconnect()

        _connectionState.update { ConnectionState.DISCONNECTED }
    }

    override fun disconnect() {
        onExplicitDisconnect?.invoke()
        disconnectControl()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            try {
                bluetoothDevice.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "couldn't disconnect bluetooth device", e)
            }
        }

    }

    internal fun onAacpDisconnected() {
        gestureDetector.stopDetection()
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
        aacp.sendControlCommand(identifier.value, value)

    fun setListeningModeFromUi(mode: Int): Boolean = mode in 1..4 &&
        aacp.sendControlCommand(ControlCommandIdentifier.LISTENING_MODE.value,
            byteArrayOf(mode.toByte()), quietListeningMode = true)

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
            val fitting = if (enabled) att.readCharacteristic(ATTHandle.HEARING_AID) else null
            if (!current()) return@withLock false
            if (enabled) {
                if (fitting == null || !hearingAidSettingsWritable(fitting)) return@withLock false
                val transparency = att.readCharacteristic(ATTHandle.TRANSPARENCY) ?: return@withLock false
                if (!current() || customTransparencyEnabled(transparency) != false) return@withLock false
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
                val readback = att.readCharacteristic(ATTHandle.HEARING_AID)
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

    suspend fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray, expected: ByteArray? = null): Boolean {
        val requested = value.copyOf()
        val baseline = expected?.copyOf() ?: if (handle == ATTHandle.LOUD_SOUND_REDUCTION)
            byteArrayOf(if (state.value.loudSoundReductionEnabled) 1 else 0) else return false
        val transport = aacp.captureAudioSocket() ?: return false
        return hearingLock.withLock {
            val modes = state.value.hearingModes
            val requiresModes = handle == ATTHandle.HEARING_AID ||
                (handle == ATTHandle.TRANSPARENCY && customTransparencyEnabled(requested) == true)
            fun cacheMatches(): Boolean = when (handle) {
                ATTHandle.HEARING_AID -> baseline.contentEquals(state.value.hearingAidData)
                ATTHandle.TRANSPARENCY -> baseline.contentEquals(state.value.transparencyData)
                ATTHandle.LOUD_SOUND_REDUCTION -> baseline.contentEquals(byteArrayOf(if (state.value.loudSoundReductionEnabled) 1 else 0))
            }
            fun current(): Boolean {
                val state = state.value
                return aacp.ownsAudioSocket(transport) && connectionState.value == ConnectionState.CONNECTED &&
                    !state.microphoneState.isActive && !aacp.isMicrophoneRequested() && cacheMatches() &&
                    (!requiresModes || (state.hearingModes.pending == null && state.hearingModes.uncertain == null &&
                        state.hearingModes.sameConfiguration(modes)))
            }
            when (handle) {
                ATTHandle.HEARING_AID -> if (!hearingAidSettingsWritable(baseline) ||
                    !hearingAidSettingsWritable(requested) || !requested.copyOfRange(0, 4).contentEquals(byteArrayOf(2, 2, 0x64, 0)) ||
                    metadata.value.model == AirPodsModel.UNKNOWN ||
                    BaseCapability.HEARING_AID !in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities ||
                    modes.enrolled != true || modes.hearingAidEnabled == null || modes.mediaAssistEnabled == null) return@withLock false
                ATTHandle.TRANSPARENCY -> {
                    if (parseTransparencySettingsResponse(baseline) == null || baseline.size != requested.size) return@withLock false
                    val settings = parseTransparencySettingsResponse(requested) ?: return@withLock false
                    if (settings.enabled && (metadata.value.model == AirPodsModel.UNKNOWN ||
                        (BaseCapability.HEARING_AID in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities &&
                            !modes.customTransparencyAllowed))) return@withLock false
                }
                ATTHandle.LOUD_SOUND_REDUCTION -> if (!validATTValue(handle, requested)) return@withLock false
            }
            val reserved = synchronized(aacp) {
                if (attOperationPending || !current()) false else { attOperationPending = true; true }
            }
            if (!reserved) return@withLock false
            var confirmed = false
            try {
                val fresh = att.readCharacteristic(handle) ?: return@withLock false
                if (!current() || !baseline.contentEquals(fresh)) return@withLock false
                if (!att.writeCharacteristic(handle, requested)) return@withLock false
                for (attempt in 0..4) {
                    val readback = att.readCharacteristic(handle) ?: return@withLock false
                    if (!current() || !validATTValue(handle, readback)) return@withLock false
                    val matches = if (handle == ATTHandle.HEARING_AID)
                        hearingAidSettingsWritable(readback) && readback.copyOfRange(4, 104).contentEquals(requested.copyOfRange(4, 104))
                    else readback.contentEquals(requested)
                    if (matches) {
                        synchronized(aacp) {
                            if (current()) { publishATTValue(handle, readback); confirmed = true }
                        }
                        return@withLock confirmed
                    }
                    val unchanged = if (handle == ATTHandle.HEARING_AID)
                        hearingAidSettingsWritable(readback) && readback.copyOfRange(4, 104).contentEquals(baseline.copyOfRange(4, 104))
                    else readback.contentEquals(baseline)
                    if (!unchanged || attempt == 4) return@withLock false
                    delay(75.milliseconds)
                }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "ATT setting could not be confirmed: ${error.javaClass.simpleName}")
                false
            } finally {
                synchronized(aacp) {
                    if (!confirmed && aacp.ownsAudioSocket(transport) && cacheMatches()) publishATTValue(handle, null)
                    attOperationPending = false
                }
            }
        }
    }

    private fun validATTValue(handle: ATTHandle, value: ByteArray): Boolean = when (handle) {
        ATTHandle.HEARING_AID -> parseHearingAidSettingsResponse(value) != null
        ATTHandle.TRANSPARENCY -> customTransparencyEnabled(value) != null
        ATTHandle.LOUD_SOUND_REDUCTION -> value.size == 1 && value[0] in byteArrayOf(0, 1)
    }

    private fun publishATTValue(handle: ATTHandle, value: ByteArray?) {
        _state.update {
            when (handle) {
                ATTHandle.LOUD_SOUND_REDUCTION -> if (value == null) it else it.copy(loudSoundReductionEnabled = value[0] == 1.toByte())
                ATTHandle.TRANSPARENCY -> it.copy(transparencyData = value?.copyOf() ?: byteArrayOf())
                ATTHandle.HEARING_AID -> it.copy(hearingAidData = value?.copyOf() ?: byteArrayOf())
            }
        }
    }

    fun observeATTCharacteristic(handle: ATTHandle): Job = CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            readATTCharacteristic(handle)
            delay(1000.milliseconds)
        }
    }

    suspend fun readATTCharacteristic(handle: ATTHandle): ByteArray? {
        val transport = aacp.captureAudioSocket() ?: return null
        return readATTCharacteristic(handle, transport)
    }

    private suspend fun readATTCharacteristic(handle: ATTHandle, transport: BluetoothSocket): ByteArray? {
        return hearingLock.withLock {
            fun current() = aacp.ownsAudioSocket(transport) && connectionState.value == ConnectionState.CONNECTED &&
                !state.value.microphoneState.isActive && !aacp.isMicrophoneRequested()
            val reserved = synchronized(aacp) {
                if (attOperationPending || !current()) false else { attOperationPending = true; true }
            }
            if (!reserved) return@withLock null
            try {
                val value = att.readCharacteristic(handle)?.copyOf()?.takeIf { validATTValue(handle, it) }
                synchronized(aacp) {
                    if (!current()) null else { publishATTValue(handle, value); value }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "ATT setting read failed: ${error.javaClass.simpleName}")
                synchronized(aacp) { if (aacp.ownsAudioSocket(transport)) publishATTValue(handle, null) }
                null
            } finally {
                synchronized(aacp) { attOperationPending = false }
            }
        }
    }

    fun requestMicrophoneStream() = aacp.requestMicrophoneStream()
    fun endMicrophoneStream() {
        aacp.endMicrophoneStream()
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
        val currentByte = state.value.effectiveControl(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, settings.value)?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommand(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, newValue)
    }

    private val holdActionLock = Any()
    private var holdActionSocket: BluetoothSocket? = null
    private var rawHoldActions = false

    /** 0x39 delegates only holds; normal single/double/triple presses stay native. */
    fun configureHoldActions(expected: BluetoothSocket): Boolean = synchronized(holdActionLock) {
        applyHoldActions(expected, settings.value)
    }

    private fun applyHoldActions(expected: BluetoothSocket, choice: AppleSettings): Boolean {
        val customized = choice.leftLongPressAction == StemAction.DIGITAL_ASSISTANT ||
            choice.rightLongPressAction == StemAction.DIGITAL_ASSISTANT
        if (!aacp.ownsAudioSocket(expected)) return false
        if (holdActionSocket === expected && rawHoldActions == customized) return true
        val packet = ControlCommandPacket.create(
            ControlCommand(
                ControlCommandIdentifier.RAW_GESTURES_CONFIG, byteArrayOf(if (customized) 8 else 0)))
        if (!aacp.sendPacket(packet, expected) || !aacp.ownsAudioSocket(expected)) return false
        holdActionSocket = expected
        rawHoldActions = customized
        return true
    }

    fun setLongPressAction(side: String, action: StemAction): Boolean = synchronized(holdActionLock) {
        val left = when (side.lowercase(java.util.Locale.ROOT)) { "left" -> true; "right" -> false; else -> return@synchronized false }
        if (action !in setOf(StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.DIGITAL_ASSISTANT)) return@synchronized false
        val expected = aacp.captureAudioSocket() ?: return@synchronized false
        val choice = if (left) settings.value.copy(leftLongPressAction = action) else settings.value.copy(rightLongPressAction = action)
        if (!applyHoldActions(expected, choice)) return@synchronized false
        updateSettings { if (left) it.copy(leftLongPressAction = action) else it.copy(rightLongPressAction = action) }
        true
    }

    fun hostHoldAction(event: AppleEvent.StemPress): StemAction? = synchronized(holdActionLock) {
        if (event.pressType != StemPressType.LONG_PRESS || !rawHoldActions ||
            holdActionSocket !== event.sourceSocket || !aacp.ownsAudioSocket(event.sourceSocket)) return@synchronized null
        if (event.bud == StemPressBud.LEFT) settings.value.leftLongPressAction else settings.value.rightLongPressAction
    }

    fun cycleHoldListeningMode(event: AppleEvent.StemPress): Boolean = synchronized(holdActionLock) {
        if (hostHoldAction(event) != StemAction.CYCLE_NOISE_CONTROL_MODES) return@synchronized false
        val mode = state.value.nextHoldListeningMode(settings.value, metadata.value.modelNumber) ?: return@synchronized false
        aacp.sendPacket(ControlCommandPacket.create(
            ControlCommand(ControlCommandIdentifier.LISTENING_MODE, byteArrayOf(mode.toByte()))), event.sourceSocket)
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
        updateSettings {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setHeadGesturesVerticalOffset(offset: Int) {
        updateSettings {
            it.copy(headGesturesVerticalOffset = offset)
        }
    }

    fun setHeadGesturesHorizontalOffset(offset: Int) {
        updateSettings {
            it.copy(headGesturesHorizontalOffset = offset)
        }
    }

    fun setHeadTrackingInterval(interval: Duration) {
        updateSettings { it.copy(headTrackingInterval = interval) }
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
