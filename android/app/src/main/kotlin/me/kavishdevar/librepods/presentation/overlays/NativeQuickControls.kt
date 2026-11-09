/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.effectiveControl
import me.kavishdevar.librepods.core.devices.effectiveEarDetection
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource

/** Ordinary Bluetooth controls keep socket and durable preference writes off Main. */
internal class NativeQuickControls(private val device: AppleDevice, private val scope: CoroutineScope,
    private val idle: () -> Boolean, private val failed: (ControlCommandIdentifier) -> Unit) {
    private data class Owner(val socket: BluetoothSocket, val session: Long, val connection: Int, val modeContext: Long = 0)
    private val writer = Mutex()
    private data class Choice(val value: Long, val connection: Int)
    private val accepted = ConcurrentHashMap<ControlCommandIdentifier, Choice>()
    private val ordinary = setOf(ControlCommandIdentifier.EAR_DETECTION_CONFIG,
        ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG, ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG)
    private val editors = linkedMapOf<ControlCommandIdentifier, LiveValueEditor<Long>>()
    private val modeLock = Any()
    private var observedMode: Long? = null
    private var modeRevision = 0L
    init {
        for (id in listOf(ControlCommandIdentifier.LISTENING_MODE, ControlCommandIdentifier.AUTO_ANC_STRENGTH,
            ControlCommandIdentifier.EAR_DETECTION_CONFIG, ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG)) {
            editors[id] = LiveValueEditor(scope, { source(id) }, { valid(id, it) }, { captured, value ->
                writer.withLock {
                    val owner = captured as Owner
                    val success = withContext(Dispatchers.IO) {
                        fun current() = owns(owner) && source(id).writable && valid(id, value)
                        if (!current()) false else {
                            val written = device.aacp.sendControlCommand(id.value, byteArrayOf(value.toByte()),
                                quietListeningMode = id == ControlCommandIdentifier.LISTENING_MODE,
                                expectedSocket = owner.socket, isValid = ::current)
                            if (written && owns(owner)) {
                                if (id in ordinary) accepted[id] = Choice(value, owner.connection)
                                if (id == ControlCommandIdentifier.EAR_DETECTION_CONFIG)
                                    device.updateSettings { it.copy(earDetectionEnabled = value == 2L) }
                            }
                            written
                        }
                    }
                    if (!success && scope.isActive) withContext(Dispatchers.Main.immediate) { failed(id) }
                    success
                }
            }, intervalMs = if (id == ControlCommandIdentifier.LISTENING_MODE) 0 else 150)
        }
    }
    val changes = combine(editors.values.map { it.state }) { it.toList() }

    fun observe() {
        val state = device.state.value
        ordinary.filter { it in state.liveControlReports && it !in state.requestedControls }.forEach { accepted.remove(it) }
        editors[ControlCommandIdentifier.LISTENING_MODE]?.observe()
        if (listeningMode() != 4L) cancelStrength()
        editors.filterKeys { it != ControlCommandIdentifier.LISTENING_MODE }.values.forEach { it.observe() }
    }
    fun canWrite(id: ControlCommandIdentifier) = source(id).writable
    fun edit(id: ControlCommandIdentifier, value: Int): Boolean {
        observe()
        if (id == ControlCommandIdentifier.AUTO_ANC_STRENGTH && listeningMode() != 4L) return false
        if (!valid(id, value.toLong())) return false
        val editor = editors[id] ?: return false
        val accepted = editor.edit(editor.state.value.revision, value.toLong())
        if (id == ControlCommandIdentifier.LISTENING_MODE) {
            currentModeContext()
            if (value != 4) cancelStrength()
        }
        return accepted
    }
    val busy: Boolean get() = editors.values.any { it.state.value.pending || it.state.value.requested != null }
    private fun cancelStrength() {
        val editor = editors[ControlCommandIdentifier.AUTO_ANC_STRENGTH] ?: return
        if (editor.state.value.requested != null) editor.cancel(editor.state.value.revision)
    }
    fun displayState(state: AppleState): AppleState = state.copy(requestedControls = state.requestedControls +
        ordinary.mapNotNull { id -> retainedChoice(id)?.let { id to byteArrayOf(it.toByte()) } }.toMap() +
        editors.mapNotNull { (id, editor) -> editor.state.value.requested?.let { id to byteArrayOf(it.toByte()) } }.toMap())

    private fun owns(owner: Owner): Boolean = scope.isActive && device.connectionState.value == ConnectionState.CONNECTED &&
        device.aacp.captureAudioSocket() === owner.socket && device.state.value.controlSession == owner.session &&
        device.connectionNumber.value == owner.connection && (owner.modeContext == 0L || owner.modeContext == currentModeContext())

    private fun source(id: ControlCommandIdentifier): LiveValueSource<Long> {
        val state = device.state.value
        val socket = device.aacp.captureAudioSocket()
        val owner = socket?.takeIf { scope.isActive && state.controlSession > 0 &&
            device.connectionState.value == ConnectionState.CONNECTED }?.let { Owner(it, state.controlSession, device.connectionNumber.value,
                if (id == ControlCommandIdentifier.AUTO_ANC_STRENGTH) currentModeContext() else 0) }
        val value = retainedChoice(id) ?: if (id == ControlCommandIdentifier.EAR_DETECTION_CONFIG)
            if (state.effectiveEarDetection(device.settings.value)) 2L else 1L
        else state.effectiveControl(id, device.settings.value)?.firstOrNull()?.toLong()?.and(255)
        val writable = owner != null && device.metadata.value.model != AirPodsModel.UNKNOWN && idle() &&
            !state.microphoneState.isActive && !device.aacp.isMicrophoneRequested() && !state.recordingState.isRecording &&
            state.audioSource.type != AudioSourceType.CALL && state.hearingModes.pending == null &&
            !state.accommodationsRequest.pending && (id == ControlCommandIdentifier.LISTENING_MODE || device.settingsSocketReady) &&
            (id != ControlCommandIdentifier.AUTO_ANC_STRENGTH || listeningMode() == 4L)
        return LiveValueSource(owner, value?.takeIf { range(id, it) }, writable && supported(id))
    }
    private fun retainedChoice(id: ControlCommandIdentifier): Long? {
        val state = device.state.value
        if (id !in ordinary || id in state.liveControlReports || id in state.requestedControls) return null
        return accepted[id]?.takeIf { it.connection == device.connectionNumber.value }?.value
    }
    private fun listeningMode() = editors[ControlCommandIdentifier.LISTENING_MODE]?.state?.value?.displayed
        ?: device.state.value.effectiveControl(ControlCommandIdentifier.LISTENING_MODE, device.settings.value)?.firstOrNull()?.toLong()?.and(255)
    private fun currentModeContext(): Long = synchronized(modeLock) {
        val mode = listeningMode()
        if (mode != observedMode) { observedMode = mode; modeRevision++ }
        modeRevision
    }

    private fun supported(id: ControlCommandIdentifier): Boolean {
        val capabilities = AirPodsSpecs.getSpec(device.metadata.value.model).baseCapabilities
        return when (id) {
            ControlCommandIdentifier.LISTENING_MODE -> BaseCapability.LISTENING_MODE in capabilities
            ControlCommandIdentifier.AUTO_ANC_STRENGTH -> BaseCapability.ADAPTIVE_AUDIO in capabilities
            ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG -> BaseCapability.CONVERSATION_AWARENESS in capabilities
            ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG -> BaseCapability.ADAPTIVE_VOLUME in capabilities
            ControlCommandIdentifier.EAR_DETECTION_CONFIG -> device.metadata.value.model != AirPodsModel.UNKNOWN
            else -> false
        }
    }
    private fun range(id: ControlCommandIdentifier, value: Long): Boolean = when (id) {
        ControlCommandIdentifier.LISTENING_MODE -> value in 1L..4L
        ControlCommandIdentifier.AUTO_ANC_STRENGTH -> value in 0L..100L
        ControlCommandIdentifier.EAR_DETECTION_CONFIG, ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
        ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG -> value in 1L..2L
        else -> false
    }
    private fun valid(id: ControlCommandIdentifier, value: Long) = range(id, value) && supported(id) &&
        (id != ControlCommandIdentifier.LISTENING_MODE || value.toInt() in
            ListeningModeSnapshot(device.metadata.value, device.state.value, device.settings.value).modes)

}
