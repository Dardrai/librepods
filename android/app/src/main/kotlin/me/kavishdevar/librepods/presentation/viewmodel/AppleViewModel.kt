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

import android.widget.Toast
import me.kavishdevar.librepods.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.hasSameDisplayValues
import me.kavishdevar.librepods.data.xposed.XposedRemotePrefProvider
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.repository.RecordingRepository
import kotlin.time.Duration
import kotlin.time.Instant

internal fun sameDisplayedAppleState(previous: AppleState, current: AppleState): Boolean =
    previous.battery.hasSameDisplayValues(current.battery) && previous.copy(battery = current.battery) == current

data class AppleUiState(
    val state: AppleState = AppleState(),
    val settings: AppleSettings = AppleSettings(),
    val metadata: AppleMetadata = AppleMetadata(),

    val appState: AppStateEntity = AppStateEntity(),
    val appSettings: AppSettingsEntity = AppSettingsEntity(),

    val vendorIdHook: Boolean = false,
    val recordings: List<Recording> = emptyList(),

    val heartRateSamples: List<HeartRateSample> = emptyList(),

    val heartRateRange: ClosedRange<Long>? = null,
)

class AppleViewModel(
    private val device: AppleDevice,
    private val appDataRepository: AppDataRepository,
    private val recordingRepository: RecordingRepository,
    private val heartRateRepository: HeartRateRepository,
    private val audioControllerProvider: () -> ExperimentalAudioController? = { null },
) : ViewModel(), DeviceViewModel {
    val audioController: ExperimentalAudioController? get() = audioControllerProvider()

    val events = device.events
    val connectionState = device.connectionState
    private val _refreshingSettings = MutableStateFlow(false)
    val refreshingSettings = _refreshingSettings.asStateFlow()
    private var hearingModeJob: Job? = null
    private val _writingNoiseModeCycle = MutableStateFlow(false)
    val writingNoiseModeCycle = _writingNoiseModeCycle.asStateFlow()
    private val _writingCallControls = MutableStateFlow(false)
    val writingCallControls = _writingCallControls.asStateFlow()

    fun refreshDeviceSettings(refresh: suspend (AppleDevice) -> DeviceSettingsRefreshResult) {
        if (device.state.value.hearingModes.pending != null) return
        if (!_refreshingSettings.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { refresh(device) }
                val message = when (result) {
                    DeviceSettingsRefreshResult.RECONNECTED -> R.string.refresh_device_settings_requested
                    DeviceSettingsRefreshResult.ALREADY_RUNNING -> R.string.refresh_device_settings_running
                    DeviceSettingsRefreshResult.NOT_CONNECTED -> R.string.refresh_device_settings_disconnected
                    DeviceSettingsRefreshResult.AUDIO_BUSY -> R.string.refresh_device_settings_audio_busy
                    DeviceSettingsRefreshResult.CALL_ACTIVE -> R.string.refresh_device_settings_call_active
                    DeviceSettingsRefreshResult.UNAVAILABLE -> R.string.refresh_device_settings_unavailable
                    DeviceSettingsRefreshResult.FAILED -> R.string.refresh_device_settings_failed
                }
                Toast.makeText(device.context, message, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(device.context, R.string.refresh_device_settings_failed, Toast.LENGTH_LONG).show()
            } finally {
                _refreshingSettings.value = false
            }
        }
    }

    private var attObserveJob: Job? = null

    private val _heartRateRange = MutableStateFlow<ClosedRange<Long>?>(null)

    private val heartRateSamples: Flow<List<HeartRateSample>> = _heartRateRange
        .transformLatest { range ->
            if (range == null) {
                emit(emptyList())
            } else {
                val startInstant = Instant.fromEpochMilliseconds(range.start)
                val endInstant = Instant.fromEpochMilliseconds(range.endInclusive)
                val samples = heartRateRepository.get(startInstant, endInstant)
                emit(samples)
            }
        }

    private val deviceDetails = combine(
        device.state.distinctUntilChanged(::sameDisplayedAppleState),
        device.settings,
        device.metadata
    ) { state, settings, metadata ->
        Triple(state, settings, metadata)
    }

    private val appData = combine(
        appDataRepository.state,
        appDataRepository.settings
    ) { appState, appSettings ->
        Pair(appState, appSettings)
    }

    val uiState = combine(
        deviceDetails,
        appData,
        _heartRateRange,
        heartRateSamples
    ) { (state, settings, metadata), (appState, appSettings), heartRateRange, heartRateSamples ->
        AppleUiState(
            // Ear detection validates pending choices separately from the reported fallback.
            state = state.copy(controlStates = state.controlStates +
                (state.requestedControls - ControlCommandIdentifier.EAR_DETECTION_CONFIG),
                customEq = state.requestedCustomEq ?: state.customEq),
            settings = settings,
            metadata = metadata,
            vendorIdHook = XposedRemotePrefProvider.create().getBoolean(
                "vendor_id_hook",
                false
            ), // TODO: make this a Flow (even if it means polling every few seconds?)
            heartRateRange = heartRateRange,
            heartRateSamples = heartRateSamples,
            appState = appState,
            appSettings = appSettings
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppleUiState(
            state = device.state.value,
            settings = device.settings.value,
            metadata = device.metadata.value,
            vendorIdHook = XposedRemotePrefProvider.create()
                .getBoolean("vendor_id_hook", false)
        )
    )

    fun disconnect() = device.disconnect()
    fun recordings(): List<Recording> = recordingRepository.recordings()

    fun setHearingMode(mode: HearingMode, enabled: Boolean) {
        hearingModeJob?.cancel()
        hearingModeJob = viewModelScope.launch {
            val confirmed = try {
                withContext(Dispatchers.IO) {
                    when (mode) {
                        HearingMode.HEARING_AID -> device.setHearingAidEnabled(enabled)
                        HearingMode.MEDIA_ASSIST -> device.setMediaAssistEnabled(enabled)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (!confirmed) Toast.makeText(device.context, R.string.hearing_mode_unconfirmed, Toast.LENGTH_LONG).show()
        }
    }

    fun setControlCommand(identifier: ControlCommandIdentifier, value: ByteArray): Boolean =
        device.setControlCommand(identifier, value)

    fun setControlCommand(identifier: ControlCommandIdentifier, value: Byte): Boolean =
        device.setControlCommand(identifier, value)

    fun setControlCommand(identifier: ControlCommandIdentifier, value: Int): Boolean =
        device.setControlCommand(identifier, value)

    fun setListeningModeFromUi(mode: Int): Boolean = device.setListeningModeFromUi(mode)

    fun setControlCommand(identifier: ControlCommandIdentifier, value: Boolean): Boolean =
        device.setControlCommand(identifier, value)

    fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray) {
        writeATTCharacteristic(handle, value, null)
    }

    fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray, expected: ByteArray?) {
        val request = value.copyOf()
        val baseline = expected?.copyOf()
        val transport = device.aacp.captureAudioSocket()
        val connection = device.connectionNumber.value
        viewModelScope.launch {
            val confirmed = withContext(Dispatchers.IO) {
                transport != null && device.aacp.captureAudioSocket() === transport &&
                    device.connectionNumber.value == connection && device.writeATTCharacteristic(handle, request, baseline)
            }
            if (!confirmed) {
                Toast.makeText(device.context, R.string.att_setting_not_confirmed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun observeATTCharacteristic(handle: ATTHandle) {
            attObserveJob = device.observeATTCharacteristic(handle)
    }
    fun stopObservingATTCharacteristic() {
        attObserveJob?.cancel()
        attObserveJob = null
    }

    fun startRecording() = device.startRecording(recordingRepository.createRecording())
    fun stopRecording() = device.stopRecording()

    fun setListeningModeCycle(mask: Int, metadata: AppleMetadata) {
        if (!_writingNoiseModeCycle.compareAndSet(false, true)) return
        val socket = device.aacp.captureAudioSocket()
        viewModelScope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { device.setListeningModeCycle(mask, metadata, socket) }
                if (!sent) Toast.makeText(device.context, R.string.noise_cycle_update_failed, Toast.LENGTH_LONG).show()
            } finally { _writingNoiseModeCycle.value = false }
        }
    }

    fun setCallControls(singlePress: Boolean, metadata: AppleMetadata) {
        if (!_writingCallControls.compareAndSet(false, true)) return
        val socket = device.aacp.captureAudioSocket()
        viewModelScope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { device.setCallControls(singlePress, metadata, socket) }
                if (!sent) Toast.makeText(device.context, R.string.call_control_update_failed, Toast.LENGTH_LONG).show()
            } finally { _writingCallControls.value = false }
        }
    }

    private val holdActionMutex = Mutex()
    fun setLongPressAction(side: String, action: StemAction) = viewModelScope.launch {
        val sent = holdActionMutex.withLock {
            withContext(Dispatchers.IO) { device.setLongPressAction(side, action) }
        }
        if (!sent) Toast.makeText(device.context, R.string.press_and_hold_update_failed, Toast.LENGTH_LONG).show()
    }

    fun renameDevice(newName: String) = viewModelScope.launch {
        val sent = withContext(Dispatchers.IO) { device.renameDevice(newName) }
        if (!sent) Toast.makeText(device.context, R.string.rename_not_sent, Toast.LENGTH_SHORT).show()
    }


    fun startHeadTracking() = device.startHeadTracking()
    fun stopHeadTracking() = device.stopHeadTracking()

    fun setHeadGesturesEnabled(enabled: Boolean) = device.setHeadGesturesEnabled(enabled)

    fun setCustomEqEnabled(enabled: Boolean) = device.setCustomEqEnabled(enabled)
    fun setCustomEq(low: Int, mid: Int, high: Int) = device.setCustomEq(low, mid, high)

    fun detectHeadGestures(callback: (Boolean) -> Unit) = device.detectHeadGestures(callback)

    fun setHeadGesturesVerticalOffset(offset: Int) = device.setHeadGesturesVerticalOffset(offset)
    fun setHeadGesturesHorizontalOffset(offset: Int) = device.setHeadGesturesHorizontalOffset(offset)
    fun setHeadTrackingInterval(interval: Duration) = device.setHeadTrackingInterval(interval)

    fun startHr() = device.startHr()
    fun stopHr() = device.stopHr()
    fun setHrRange(range: ClosedRange<Long>) {
        _heartRateRange.value = range
    }

    fun updateSettings( transform : (AppleSettings) -> AppleSettings) {
        device.updateSettings(transform)
    }

    fun sendRawPacket(data: ByteArray): Boolean = device.sendRawPacket(data)
}
