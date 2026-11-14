package me.kavishdevar.librepods.dongle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import android.os.SystemClock
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.InformationPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.hostClockPacket
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingDiagnosticUuids
import me.kavishdevar.librepods.core.bluetooth.att.types.SavedHearingDiagnostics
import me.kavishdevar.librepods.core.bluetooth.att.types.customTransparencyEnabled
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseSavedHearingDiagnostics
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.accommodationsTransparencyPreset
import me.kavishdevar.librepods.core.devices.AccommodationsAudiogram
import me.kavishdevar.librepods.core.devices.AccommodationsPlan
import me.kavishdevar.librepods.core.devices.ReceivedAccommodations
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsSendOutcome
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.data.LiveRecordEditor
import me.kavishdevar.librepods.data.LiveRecordSource
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource
import me.kavishdevar.librepods.data.LiveAccommodationsEditor
import me.kavishdevar.librepods.data.LiveAccommodationsSource
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.MediaController

internal data class DongleChoice(val address: String, val name: String, val usb: Boolean = false)
private val accommodationsSessions = AtomicLong(0)
internal data class DongleState(
    val selected: Boolean = false, val usb: Boolean = false, val attached: Boolean = false,
    val usbAudioAvailable: Boolean = false, val usbPermissionRequired: Boolean = false,
    val usbVolumeAvailable: Boolean = false, val usbVolumeEnabled: Boolean = false, val usbVolumePending: Boolean = false,
    val usbVolumeRequested: Boolean = false,
    val name: String = "AirPods Dongle", val connected: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val status: DongleProtocol.Status? = null,
    val metadata: AppleMetadata? = null, val settings: Map<Int, DongleProtocol.Setting> = emptyMap(),
    val requested: Map<Int, Long> = emptyMap(), val savedChoices: Map<Int, Long> = emptyMap(),
    val unconfirmed: Set<Int> = emptySet(), val automaticPause: Boolean = false,
    val hearingModes: HearingModes = HearingModes.empty(), val hearingProfile: HearingAidSettings? = null,
    val controlSession: Long = 0, val accommodationsRequest: AccommodationsRequestState = AccommodationsRequestState(),
    val hearingAidData: ByteArray = byteArrayOf(), val hearingAidReadStatus: SettingsReadStatus = SettingsReadStatus.WAITING,
    val transparencyData: ByteArray = byteArrayOf(), val transparencyReadStatus: SettingsReadStatus = SettingsReadStatus.WAITING,
    val transparencyResult: AccommodationsResult? = null,
    val diagnostics: SavedHearingDiagnostics? = null,
    val audio: DongleProtocol.AudioProfile? = null, val performance: DongleProtocol.Performance? = null,
    val finderOpen: Boolean = false, val nearby: DongleProtocol.Nearby? = null,
) {
    val accommodationsReport: AccommodationsReport? get() = hearingModes.accommodationsReport(controlSession)
}

/** One foreground-service-owned control link. Creating it never selects an audio route. */
@SuppressLint("MissingPermission")
internal class DongleController(context: Context) : Closeable {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE)
    private val savedChoiceStore = DongleSavedChoiceStore(preferences)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge by lazy { ShizukuAudioClient(this.context) }
    private val io = Mutex()
    private val refreshRequested = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = DongleLifecycle()
    private val earClaim = DongleEarClaim()
    private val mutableState = MutableStateFlow(DongleState(selected = preferences.contains("address"),
        automaticPause = preferences.getBoolean("automatic_pause", false), usbVolumeRequested = volumeRequested()))
    val state = mutableState.asStateFlow()
    @Volatile private var socket: Closeable? = null
    private val usbManager = this.context.getSystemService(UsbManager::class.java)
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val audioDevices = object : AudioDeviceCallback() {
        private fun changed() { scope.launch {
            if (usbVolume.isInitialized()) usbVolume.value.topologyChanged()
            refreshRequested.trySend(Unit)
        } }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }
    private var usbWasPresent = false
    private var usbLostAt: Long? = null
    private var usbPermissionPending: String? = null
    private var retry: Job? = null
    private var retryDelay = 1_000L
    private var lastUsbOpenFailure: String? = null
    private var reconnectWanted = true
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (!DongleUsb.candidate(device)) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) observeUsbPresence(true)
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && activeUsbPath != device.deviceName) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && activeUsbPath != null) return
            if (intent.action == USB_PERMISSION) {
                if (usbPermissionPending != device.deviceName) return
                usbPermissionPending = null
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    mutableState.update { it.copy(busy = false, error = "Allow USB access to connect the dongle.") }; return
                }
            }
            if (preferences.getString("address", null)?.startsWith("usb:") == true && reconnectWanted) {
                retry?.cancel()
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && activeUsbPath == device.deviceName) {
                    observeUsbPresence(false, ownedLoss = true)
                    retire()
                }
                retry = scope.launch { delay(400); retry = null; reconnect() }
            }
        }
    }
    private var activeUsbPath: String? = null
    private var session: DongleSession? = null
    private var connection: Job? = null
    private val cachedRevisions = mutableMapOf<Int, Long>()
    private val pendingWrites = DonglePendingWrites(SystemClock::elapsedRealtime)
    private data class AccommodationsWriteBase(val client: DongleSession, val session: Long, val revision: Long,
        val plan: AccommodationsPlan, val clinical: ByteArray)
    private var accommodationsWriteBase: AccommodationsWriteBase? = null
    private var profileData: ByteArray? = null
    private var profileCharacteristic: DongleAtt.Characteristic? = null
    private val transparencyUuid = UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390")
    @Volatile private var finderWanted = false
    private var finderEpoch = 0L
    @Volatile private var pauseEpoch = 0L
    @Volatile private var callActive = false
    @Volatile private var controlsVisible = false
    private val controlObservers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private var volumeObservation: Pair<DongleUsbVolume.Target, DongleUsbVolume.Observation>? = null
    private var audioUpdated = 0L
    private data class EditorOwner(val client: DongleSession, val generation: Long,
        val address: List<Int>, val controlSession: Long)
    private enum class RecordKind { HEARING, TRANSPARENCY, ACCOMMODATIONS }
    private data class RecordOwner(val transport: EditorOwner, val guards: List<Any?>)
    private fun recordGuards(): List<Any?> = mutableState.value.hearingModes.let {
        listOf(it.enrolled, it.clinicalReportsKnown, it.hearingAidEnabled, it.mediaAssistEnabled,
            it.customTransparencyAllowed, it.pending, it.uncertain)
    }
    @Volatile private var liveOperation: Pair<EditorOwner, RecordKind>? = null
    private val protectedWork = AtomicInteger()
    private var automaticHealthOwner: EditorOwner? = null
    private var hostClockOwner: EditorOwner? = null
    private var healthAttemptOwner: EditorOwner? = null
    private var healthAttempts = 0
    private var healthRetryAfter = 0L
    private var automaticSettingsOwner: EditorOwner? = null
    private val liveSettings = ConcurrentHashMap<Int, LiveValueEditor<Long>>()
    private val transparencyEditor = lazy { recordEditor(RecordKind.TRANSPARENCY) }
    private val hearingEditor = lazy { recordEditor(RecordKind.HEARING) }
    private val accommodationsEditor = lazy {
        LiveAccommodationsEditor(scope, read = {
            val value = mutableState.value
            val report = value.accommodationsReport
            val captured = editorOwner()
            val own = liveOperation == (captured to RecordKind.ACCOMMODATIONS)
            val owner = captured?.takeIf { writable(it) && report != null &&
                (value.accommodationsRequest.editable(report) || own) }
            val source = profileData?.let { AccommodationsAudiogram.parse(it, value.controlSession) }
            LiveAccommodationsSource(owner, report, value.hearingModes.receivedAccommodations(value.controlSession, source),
                value.accommodationsRequest, owner != null && (!value.busy || own), source)
        }, write = { captured, report, revision, choice, changeCurves ->
            val owner = captured as? EditorOwner
            if (owner == null) AccommodationsResult.NOT_SENT else accommodationsAwaited(owner, report, choice.phone,
                choice.media, choice.preset.takeIf { changeCurves }, revision, choice.audiogram && changeCurves).result ?: AccommodationsResult.NOT_SENT
        })
    }
    private val usbVolume = lazy {
        DongleUsbVolume(context, scope, bridge, ::volumeTarget, canStart = {
            val value = mutableState.value
            protectedWork.get() == 0 && !value.busy && liveOperation == null && value.status?.flags?.and(4 or 64 or 16) == 0 &&
                !callActive && value.audio?.flags?.and(7) == 0 &&
                runCatching { !audioManager.isMusicActive && audioManager.activeRecordingConfigurations.isEmpty() }.getOrDefault(false)
        }, read = { selected -> io.withLock {
            check(volumeTarget() == selected)
            val owner = selected.owner as EditorOwner
            owner.client.withTarget(checkNotNull(mutableState.value.status)) {
                val at = SystemClock.elapsedRealtime()
                cacheVolumeObservation(selected, DongleUsbVolume.Observation(owner.client.readPhysicalVolume(), at))
            }
        } }, action = { selected, action, usbGeneration, token, level, guard -> io.withLock {
            check(volumeTarget() == selected)
            val owner = selected.owner as EditorOwner
            owner.client.withTarget(checkNotNull(mutableState.value.status)) {
                val at = SystemClock.elapsedRealtime()
                val value = owner.client.volumeAuthority(action, usbGeneration, token, level,
                    beforePhysicalCommit = { physical -> check(volumeTarget() == selected); guard(physical) })
                cacheVolumeObservation(selected, DongleUsbVolume.Observation(value, at))
            }
        } }, changed = { value -> mutableState.update { it.copy(usbVolumeAvailable = value.available,
            usbVolumeEnabled = value.enabled, usbVolumePending = value.pending,
            error = when {
                value.cleanupFailed -> context.getString(me.kavishdevar.librepods.R.string.dongle_volume_restore_failed)
                value.failed -> context.getString(me.kavishdevar.librepods.R.string.dongle_volume_failed)
                value.enabled && it.error in listOf(context.getString(me.kavishdevar.librepods.R.string.dongle_volume_failed),
                    context.getString(me.kavishdevar.librepods.R.string.dongle_volume_restore_failed)) -> null
                else -> it.error
            }) } }, requested = { serial -> preferences.getBoolean(volumeRequestKey(serial), false) })
    }
    private fun selectedVolumeSerial() = preferences.getString("address", null)
        ?.takeIf { it.startsWith("usb:serial:") }?.removePrefix("usb:serial:")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    private fun volumeRequestKey(serial: String) = "hardware_volume:$serial"
    private fun volumeRequested() = selectedVolumeSerial()?.let { preferences.getBoolean(volumeRequestKey(it), false) } == true
    private fun volumeTarget(): DongleUsbVolume.Target? {
        val value = mutableState.value
        val status = value.status ?: return null
        if (!value.usb || !value.connected || !value.usbAudioAvailable || status.capabilities and 128 == 0 || status.microphoneActive || callActive) return null
        val owner = editorOwner() ?: return null
        val serial = preferences.getString("address", null)?.takeIf { it.startsWith("usb:serial:") }?.removePrefix("usb:serial:") ?: return null
        return DongleUsbVolume.Target(owner, serial, value.controlSession, status.generation, status.capabilities)
    }
    private fun cacheVolumeObservation(selected: DongleUsbVolume.Target, observation: DongleUsbVolume.Observation) = observation.also {
        synchronized(lifecycle) { if (volumeTarget() == selected) volumeObservation = selected to observation }
    }
    internal fun dumpVolume(writer: PrintWriter) = synchronized(lifecycle) {
        val value = mutableState.value
        val cached = volumeObservation
        val age = cached?.let { SystemClock.elapsedRealtime() - it.second.observedAt }
        val current = volumeTarget()?.let { it == cached?.first } == true && age != null && age >= 0
        val error = when (value.error) {
            null -> "none"
            context.getString(me.kavishdevar.librepods.R.string.dongle_volume_failed) -> "volume_stopped"
            context.getString(me.kavishdevar.librepods.R.string.dongle_volume_restore_failed) -> "volume_restore_failed"
            else -> "present"
        }
        writer.println("dongle_volume cached_only=true connected=${value.connected} busy=${value.busy} requested=${value.usbVolumeRequested} enabled=${value.usbVolumeEnabled} pending=${value.usbVolumePending} error=$error")
        writer.println("control_session=${value.controlSession} status_generation=${value.status?.generation ?: -1} fresh_scope=$current age_ms=${age ?: -1}")
        val physical = cached?.second?.value?.takeIf { current }
        if (physical == null) writer.println("physical=unavailable") else writer.println(
            "physical_gain=${physical.confirmed ?: "unknown"} revision=${physical.observationRevision} usb_generation=${physical.usbGeneration} " +
                "pending_at_snapshot=${physical.pending} inflight_at_snapshot=${physical.inflight} authority_at_snapshot=${physical.authorityActive} " +
                "token_present=${physical.ownToken != null} remaining_ttl_at_snapshot_ms=${physical.remainingMs} usb_scope_ready_at_snapshot=${physical.usbScopeReady}")
    }
    fun usbHardwareVolume(enabled: Boolean) {
        val serial = selectedVolumeSerial() ?: return
        val edit = preferences.edit()
        if (enabled) edit.putBoolean(volumeRequestKey(serial), true) else edit.remove(volumeRequestKey(serial))
        if (!edit.commit()) return
        mutableState.update { it.copy(usbVolumeRequested = enabled) }
        if (enabled) {
            mutableState.update { if (it.error in listOf(context.getString(me.kavishdevar.librepods.R.string.dongle_volume_failed),
                context.getString(me.kavishdevar.librepods.R.string.dongle_volume_restore_failed))) it.copy(error = null) else it }
            usbVolume.value.enable(true)
        } else if (usbVolume.isInitialized()) usbVolume.value.enable(false)
        refreshRequested.trySend(Unit)
    }
    fun inhibitsAutomaticNative(): Boolean {
        if (observeUsbPresence(usbManager?.let(DongleUsb::devices).orEmpty().isNotEmpty())) return true
        val value = mutableState.value
        if (!value.selected || value.usb) return false
        if (value.connected || value.busy) return true
        val address = preferences.getString("address", null) ?: return false
        return runCatching {
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                    it.address.equals(address, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    @Synchronized private fun observeUsbPresence(present: Boolean, ownedLoss: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (present) usbLostAt = null
        else if (usbWasPresent || ownedLoss) usbLostAt = now
        usbWasPresent = present
        // PID changes briefly remove USB. Repeated absence cannot prolong a real unplug.
        return present || usbLostAt?.let { now - it < 2_000 } == true
    }
    private val audioEditor = lazy {
        LiveValueEditor(scope, {
            val owner = editorOwner()?.takeIf { mutableState.value.status?.microphoneActive == false && !callActive }
            LiveValueSource(owner, mutableState.value.audio?.let { DongleAudioIntent(it.requestedCodec, it.rateMode, it.requestedBitrate) }, writable(owner))
        }, { value: DongleAudioIntent -> value.codec in 0..3 && value.rateMode in 0..1 && value.bitrate in 0L..512_000L },
            write = { captured, value ->
                val owner = captured as? EditorOwner
                if (owner == null) false else try {
                    ownedCommand(owner, bindTarget = false) { client ->
                        val profile = client.requestAudio(value.codec, value.rateMode, value.bitrate, checkNotNull(mutableState.value.status))
                        val status = checkNotNull(client.status)
                        publish(client) {
                            if (mutableState.value.status?.sameConnection(status) != true) clearReports()
                            mutableState.update { it.copy(status = status) }
                            recordAudio(profile)
                        }
                    }; true
                } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); false }
                catch (_: Exception) { reportLiveFailure(owner); false }
            })
    }
    private val gameEditor = lazy {
        LiveValueEditor(scope, {
            val owner = editorOwner()?.takeIf { mutableState.value.status?.microphoneActive == false && !callActive }
            LiveValueSource(owner, mutableState.value.audio?.gameRequested, writable(owner))
        }, ::gameModeAllowed, write = { captured, value ->
            val owner = captured as? EditorOwner
            if (owner == null || !gameModeAllowed(value)) false else try {
                ownedCommand(owner) { client ->
                    client.transact(7, byteArrayOf(4, if (value) 1 else 0)) {
                        check(writable(owner) && gameModeAllowed(value)) { "Game Mode is unavailable" }
                    }
                    val profile = DongleProtocol.audio(client.transact(7, byteArrayOf(9)))
                    publish(client) { recordAudio(profile) }
                }; true
            } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); false }
            catch (_: Exception) { reportLiveFailure(owner); false }
        })
    }
    private fun gameModeAllowed(enabled: Boolean) = !enabled || mutableState.value.audio?.flags?.and(8) == 8
    init {
        audioManager.registerAudioDeviceCallback(audioDevices, Handler(Looper.getMainLooper()))
        this.context.registerReceiver(usbEvents, IntentFilter().apply {
            addAction(USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        scope.launch { state.collect {
        liveSettings.values.forEach { it.observe() }
        if (transparencyEditor.isInitialized()) transparencyEditor.value.observe()
        if (hearingEditor.isInitialized()) hearingEditor.value.observe()
        if (audioEditor.isInitialized()) audioEditor.value.observe()
        if (gameEditor.isInitialized()) gameEditor.value.observe()
        if (accommodationsEditor.isInitialized()) accommodationsEditor.value.observe()
    } } }

    private fun editorOwner(): EditorOwner? = runCatching { lifecycle.publish(lifecycle.current()) {
        val current = mutableState.value
        val client = session ?: return@publish null
        val status = current.status ?: return@publish null
        if (!current.connected || !status.controlsReady || client.controlEpoch != lifecycle.current()) null
        else EditorOwner(client, status.generation, status.address.toList(), current.controlSession)
    } }.getOrNull()
    private fun owns(owner: EditorOwner): Boolean = editorOwner() == owner
    private fun writable(owner: EditorOwner?, kind: RecordKind? = null): Boolean {
        val value = mutableState.value
        return owner != null && owns(owner) && value.status?.microphoneActive == false && !callActive &&
            (kind == null || !value.busy || liveOperation == (owner to kind))
    }
    private suspend fun <T> ownedCommand(owner: EditorOwner, kind: RecordKind? = null, bindTarget: Boolean = true,
        block: suspend (DongleSession) -> T): T {
        if (kind != null) protectedWork.incrementAndGet()
        try {
        return io.withLock {
        check(owns(owner)) { "AirPods connection changed" }
        val target = checkNotNull(mutableState.value.status)
        val client = owner.client
        publish(client) {
            check(writable(owner))
            liveOperation = kind?.let { owner to it }
            mutableState.update { it.copy(busy = true, error = null) }
        }
        try {
            if (bindTarget) client.withTarget(target) { withVolumeCheckpoints(client) { block(client) } }
            else withVolumeCheckpoints(client) { block(client) }
        }
        finally { runCatching { publish(client) {
            liveOperation = null
            mutableState.update { it.copy(busy = false) }
        } }; refreshRequested.trySend(Unit) }
    }
        } finally { if (kind != null) { protectedWork.decrementAndGet(); refreshRequested.trySend(Unit) } }
    }

    private suspend fun <T> withVolumeCheckpoints(client: DongleSession, block: suspend () -> T): T =
        client.withRpcCheckpoints({
            if (!usbVolume.isInitialized()) null else usbVolume.value.keepAliveForSettings { selected, usbGeneration, token ->
                val owner = selected.owner as? EditorOwner
                check(owner != null && owner.client === client && owns(owner) && volumeTarget() == selected)
                val observedAt = SystemClock.elapsedRealtime()
                val physical = client.volumeAuthority(1, usbGeneration, token) { check(volumeTarget() == selected) }
                cacheVolumeObservation(selected, DongleUsbVolume.Observation(physical, observedAt))
            }?.let { it - SystemClock.elapsedRealtime() }
        }, block)
    fun liveSettingEditor(id: Int): LiveValueEditor<Long> = liveSettings.getOrPut(id) {
        fun source(): LiveValueSource<Long> {
            val owner = editorOwner()?.takeIf { mutableState.value.status?.microphoneActive == false && !callActive }
            val current = mutableState.value
            return LiveValueSource(owner, current.requested[id] ?: current.settings[id]?.value?.let { currentDongleSetting(id, it) }
                ?: current.savedChoices[id], writable(owner) && current.canEditSetting(id))
        }
        LiveValueEditor(scope, ::source, { validLiveSetting(id, it) }, write = { captured, value ->
            val owner = captured as? EditorOwner ?: return@LiveValueEditor false
            try { ownedCommand(owner) { client -> setSettingNow(client, id, value) }; true }
            catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); false }
            catch (_: Exception) { reportLiveFailure(owner); false }
        })
    }
    private fun reportLiveFailure(owner: EditorOwner) { runCatching { publish(owner.client) {
        mutableState.update { it.copy(error = "The request was not confirmed.") }
    } } }
    private suspend fun setSettingNow(client: DongleSession, id: Int, value: Long) {
        val before = mutableState.value
        check(before.canWriteSetting(id, value))
        val owner = checkNotNull(editorOwner())
        val baseline = before.settings[id]
        val peer = savedChoiceStore.peer(before.status)
        client.setting(id, value) {
            check(writable(owner) && mutableState.value.canWriteSetting(id, value) && savedChoiceStore.peer(mutableState.value.status) == peer)
        }
        publish(client) {
            check(peer != null && savedChoiceStore.peer(mutableState.value.status) == peer)
            val persist = savedChoiceStore.persists(id, before.metadata?.model)
            val stored = !persist || savedChoiceStore.save(peer, id, value)
            val saved = if (persist && stored) mutableState.value.savedChoices + (id to value) else mutableState.value.savedChoices
            pendingWrites.recordSent(id, value, baseline, stored)
            mutableState.update { it.copy(savedChoices = saved, requested = pendingWrites.requestedValues(),
                error = if (!stored) "The setting was sent, but the choice could not be saved." else it.error) }
        }
    }
    private fun recordEditor(kind: RecordKind): LiveRecordEditor {
        fun source(): LiveRecordSource {
            val owner = editorOwner()?.takeIf { mutableState.value.status?.microphoneActive == false && !callActive }
            val value = mutableState.value
            val data = if (kind == RecordKind.HEARING) profileData ?: byteArrayOf() else value.transparencyData
            val ready = writable(owner, kind) && hearingSupported() &&
                (kind != RecordKind.HEARING || value.hearingModes.enrolled == true && value.hearingModes.clinicalReportsKnown)
            return LiveRecordSource(owner?.takeIf { kind != RecordKind.HEARING ||
                value.hearingModes.enrolled == true && value.hearingModes.clinicalReportsKnown &&
                value.hearingModes.pending == null && value.hearingModes.uncertain == null }?.let { RecordOwner(it, recordGuards()) }, data, ready)
        }
        fun valid(data: ByteArray) = if (kind == RecordKind.HEARING) hearingAidSettingsWritable(data)
            else data.size == 104 && parseTransparencySettingsResponse(data) != null
        fun matches(actual: ByteArray, target: ByteArray) = actual.contentEquals(target) ||
            kind == RecordKind.HEARING && valid(actual) && valid(target) &&
                actual.copyOfRange(0, 2).contentEquals(target.copyOfRange(0, 2)) &&
                actual.copyOfRange(4, 104).contentEquals(target.copyOfRange(4, 104))
        return LiveRecordEditor(scope, ::source, ::valid, ::matches, write = { captured, expected, requested ->
            val recordOwner = captured as? RecordOwner
            val owner = recordOwner?.transport
            if (owner == null || source().owner != captured) false else try {
                ownedCommand(owner, kind) { client ->
                    check(source().owner == captured)
                    if (kind == RecordKind.TRANSPARENCY) updateTransparencyNow(client, expected, requested) { source().owner == captured }
                    else updateHearingNow(client, expected, requested) { source().owner == captured }
                }
                true
            } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); false }
            catch (_: Exception) { reportLiveFailure(owner); false }
        })
    }
    fun liveTransparencyEditor(): LiveRecordEditor = transparencyEditor.value
    fun liveHearingEditor(): LiveRecordEditor = hearingEditor.value
    fun liveAudioEditor(): LiveValueEditor<DongleAudioIntent> = audioEditor.value
    fun liveGameModeEditor(): LiveValueEditor<Boolean> = gameEditor.value
    fun liveAccommodationsEditor(): LiveAccommodationsEditor = accommodationsEditor.value


    fun choices(): List<DongleChoice> {
        val devices = usbManager?.let(DongleUsb::devices).orEmpty().sortedBy(DongleUsb::key)
        val usb = devices.mapIndexed { index, device ->
            val name = device.productName ?: "AirPods Dongle"
            DongleChoice(DongleUsb.key(device), if (devices.size > 1) "$name ${index + 1}" else name, usb = true)
        }
        val bluetooth = runCatching {
            context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
                .filter { it.bondState == BluetoothDevice.BOND_BONDED }
                .map { DongleChoice(it.address, it.name?.takeIf(String::isNotBlank) ?: "Paired Bluetooth device") }
        }.getOrDefault(emptyList())
        return usb + bluetooth.sortedBy { it.name }
    }

    fun restore() { if (preferences.contains("address")) reconnect() }
    fun select(address: String) {
        if (choices().none { it.address.equals(address, true) } || !runCatching { lifecycle.publish(lifecycle.current()) { preferences.edit().putString("address", address).commit() } }.getOrDefault(false)) {
            mutableState.update { it.copy(error = "Attach the USB dongle or pair it in Bluetooth settings, then choose it again.") }
            return
        }
        retryDelay = 1_000; reconnectWanted = true
        mutableState.update { it.copy(selected = true) }
        reconnect()
    }
    fun reconnect() {
        retry?.cancel(); retry = null
        val address = preferences.getString("address", null) ?: return
        reconnectWanted = true
        val mine = retire()
        val usb = address.startsWith("usb:")
        val devices = usbManager?.let(DongleUsb::devices).orEmpty()
        observeUsbPresence(devices.isNotEmpty())
        val usbDevice = if (usb) DongleUsb.resolve(devices, address) else null
        mutableState.update { it.copy(selected = true, usb = usb, attached = usbDevice != null,
            usbAudioAvailable = usbDevice?.let(DongleUsb::audioAvailable) == true,
            usbPermissionRequired = usbDevice != null && !usbManager.hasPermission(usbDevice)) }
        if (usb && usbDevice == null) {
            mutableState.update { it.copy(error = if (devices.size > 1) "Choose the USB dongle to reconnect its controls." else null) }
            return
        }
        if (usb && !usbManager.hasPermission(usbDevice)) {
            usbPermissionPending = usbDevice!!.deviceName
            usbManager.requestPermission(usbDevice, PendingIntent.getBroadcast(context, 0,
                Intent(USB_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return
        }
        val job = lifecycle.publish(mine) {
            mutableState.update { it.copy(selected = true, busy = true, error = null) }
            scope.launch(start = CoroutineStart.LAZY) {
            var ownTransport: Closeable? = null
            try {
                val exchange: suspend (Int, Int, ByteArray) -> ByteArray
                val name: String
                if (usb) {
                    val device = checkNotNull(usbDevice)
                    val serial = checkNotNull(DongleUsb.serial(device)) { "The USB dongle did not report a board identity" }
                    check(address.startsWith("usb:path:") || address == "usb:serial:$serial") { "Choose the attached USB dongle again" }
                    val transport = DongleUsb.open(usbManager, device) { intf ->
                        bridge.connect() // Reuses authorized root; USB attachment never asks for a root grant.
                        if (!bridge.hasRootPrivileges) withTimeoutOrNull(8_000) {
                            bridge.state.first { it.connected }
                        }
                        bridge.openDongleHid(device.deviceName, serial, intf.id) {
                            ownsUsbSelection(mine, address, serial) &&
                                usbManager.hasPermission(device) &&
                                usbManager.deviceList[device.deviceName]?.let(DongleUsb::serial) == serial
                        }
                    }
                    ownTransport = transport
                    lifecycle.publish(mine) {
                        check(preferences.getString("address", null) == address) { "Selected dongle changed" }
                        check(preferences.edit().putString("address", "usb:serial:$serial").commit())
                        socket = transport; activeUsbPath = device.deviceName
                    }
                    exchange = { operation, report, data -> transport.exchange(operation, report, data) }
                    name = device.productName ?: "AirPods Dongle"
                } else {
                    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                    check(adapter != null && adapter.isEnabled) { "Turn on Bluetooth to connect the dongle" }
                    val device = adapter.getRemoteDevice(address)
                    check(device.bondState == BluetoothDevice.BOND_BONDED) { "Pair the dongle in Bluetooth settings first" }
                    val transport = device.createRfcommSocketToServiceRecord(UUID.fromString(DongleProtocol.UUID))
                    ownTransport = transport
                    lifecycle.publish(mine) { socket = transport }
                    guarded(transport, 8_000) { transport.connect() }
                    exchange = { operation, report, data -> DongleProtocol.exchange(transport.inputStream, transport.outputStream, operation, report, data) }
                    name = device.name ?: "AirPods Dongle"
                }
                val transport = checkNotNull(ownTransport)
                val boundedExchange: suspend (Int, Int, ByteArray, Long) -> ByteArray = { operation, report, data, timeout ->
                    lifecycle.publish(mine) { check(socket === transport) }
                    guarded(transport, minOf(3_000, timeout)) {
                        try { exchange(operation, report, data) }
                        catch (busy: DongleBusyException) { throw busy }
                        catch (rejected: DongleRequestException) { throw rejected }
                        catch (error: Exception) { runCatching { transport.close() }; throw error }
                    }
                }
                val client = DongleSession({ operation, report, data -> boundedExchange(operation, report, data, 3_000) },
                    now = SystemClock::elapsedRealtime, controlEpoch = mine, closeTransport = { transport.close() },
                    resumeSequences = usb, boundedIo = boundedExchange)
                io.withLock {
                    val status = client.refresh()
                    lifecycle.publish(mine) {
                        session = client; retryDelay = 1_000
                        lastUsbOpenFailure = null
                        mutableState.update { it.copy(name = name, connected = true, busy = false, status = status) }
                    }
                }
                while (isActive && lifecycle.current() == mine) {
                    try { io.withLock { refresh(client) } }
                    catch (_: DongleBusyException) { publish(client) { mutableState.update { it.copy(error = CONTROL_BUSY) } } }
                    withTimeoutOrNull(pollInterval()) { refreshRequested.receive() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                runCatching { lifecycle.publish(mine) {
                    if (usb && error is DongleUsbControlsUnavailable) {
                        val cause = generateSequence<Throwable>(error) { it.cause }.last()
                        val summary = Regex("USB HID [a-z ]+ failed \\(errno [0-9]+")
                            .find(cause.message.orEmpty())?.value ?: cause.javaClass.simpleName.take(80)
                        if (lastUsbOpenFailure != summary) {
                            lastUsbOpenFailure = summary
                            android.util.Log.w("DongleUsb", "Shared control startup failed: $summary")
                        }
                    }
                    mutableState.update { it.copy(connected = false, busy = false,
                    error = if (error is DongleUsbControlsUnavailable) error.message else
                        "Could not reach the dongle. Keep it powered, then reconnect its controls.") }
                } }
            } finally {
                runCatching { ownTransport?.close() }
                runCatching { lifecycle.publish(mine) {
                    socket = null; session = null; activeUsbPath = null; finderWanted = false; finderEpoch++
                    clearReports()
                    mutableState.update { it.copy(connected = false, busy = false, status = null, finderOpen = false, nearby = null) }
                    if (reconnectWanted) {
                        val wait = retryDelay; retryDelay = (retryDelay * 2).coerceAtMost(15_000)
                        retry = scope.launch { delay(wait); retry = null; reconnect() }
                    }
                } }
            }
            }.also { connection = it }
        }
        job.start()
    }
    internal fun ownsUsbSelection(ownerEpoch: Long, original: String, serial: String): Boolean =
        lifecycle.current() == ownerEpoch && preferences.getString("address", null) in
            setOf(original, "usb:serial:$serial")
    fun pairingCommand(opcode: Int) {
        require(opcode in listOf(1, 2, 4))
        val mine = lifecycle.current()
        val started = lifecycle.publish(mine) {
            if (mutableState.value.busy) return@publish false
            mutableState.update { it.copy(busy = true, error = null) }
            true
        }
        if (!started) return
        scope.launch {
            try { io.withLock {
            val client = session ?: return@withLock
            if (client.controlEpoch != mine) return@withLock
            client.command(opcode)
            refresh(client)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { runCatching { lifecycle.publish(mine) {
                mutableState.update { it.copy(error = when (error) {
                    is DongleBusyException -> CONTROL_BUSY
                    is DongleRequestException -> error.message
                    else -> "The dongle did not confirm the request."
                }) }
            } } }
            finally { runCatching { lifecycle.publish(mine) { mutableState.update { it.copy(busy = false) } } } }
        }
    }
    private fun <T> publish(client: DongleSession, block: () -> T): T = lifecycle.publish(client.controlEpoch) {
        if (session !== client) throw CancellationException("Dongle session changed")
        block()
    }
    private suspend fun <T> guarded(transport: Closeable, timeoutMs: Long, block: suspend () -> T): T {
        val timer = scope.launch { delay(timeoutMs); runCatching { transport.close() } }
        return try { block() } finally { timer.cancel() }
    }

    private fun clearReports() {
        if (usbVolume.isInitialized()) usbVolume.value.retire()
        cachedRevisions.clear(); pendingWrites.clear(); profileData = null; profileCharacteristic = null; accommodationsWriteBase = null
        automaticHealthOwner = null; hostClockOwner = null; automaticSettingsOwner = null; liveOperation = null
        healthAttemptOwner = null; healthAttempts = 0; healthRetryAfter = 0
        cancelEar()
        audioUpdated = 0
        mutableState.update { it.copy(metadata = null, settings = emptyMap(), requested = emptyMap(), savedChoices = emptyMap(), unconfirmed = emptySet(),
            hearingModes = HearingModes.empty(), controlSession = accommodationsSessions.incrementAndGet(),
            accommodationsRequest = AccommodationsRequestState(), transparencyData = byteArrayOf(), transparencyResult = null,
            hearingProfile = null, hearingAidData = byteArrayOf(), hearingAidReadStatus = SettingsReadStatus.WAITING,
            transparencyReadStatus = SettingsReadStatus.WAITING, diagnostics = null, audio = null, performance = null) }
    }
    private fun pollInterval(): Long = lifecycle.publish(lifecycle.current()) {
        if (controlsVisible || mutableState.value.automaticPause || volumeRequested() || finderWanted ||
            pendingWrites.awaitingConfirmation()) 600L else 5_000L
    }
    fun controlsVisible(visible: Boolean, owner: Any = this) {
        synchronized(lifecycle) {
            if (visible) controlObservers.add(owner) else controlObservers.remove(owner)
            controlsVisible = controlObservers.isNotEmpty()
        }
        refreshRequested.trySend(Unit)
    }
    private suspend fun refresh(client: DongleSession) {
        val status = client.refresh()
        publish(client) {
            val before = mutableState.value.status
            if (before == null || before.generation != status.generation || before.address != status.address) clearReports()
            if (before != null && before.address != status.address) {
                finderWanted = false; finderEpoch++
                mutableState.update { it.copy(finderOpen = false, nearby = null) }
            }
            mutableState.update { it.copy(status = status, usbVolumeRequested = volumeRequested(), savedChoices = savedChoiceStore.load(status),
                unconfirmed = pendingWrites.unconfirmed()) }
            if (status.microphoneActive) cancelEar()
            mutableState.update { if (it.error == CONTROL_BUSY) it.copy(error = null) else it }
        }
        val finderOwner = publish(client) { finderEpoch.takeIf { finderWanted && mutableState.value.finderOpen } }
        if (status.capabilities and 0x10 != 0 && finderOwner != null) {
            try {
                val nearby = DongleProtocol.nearby(client.transact(7, byteArrayOf(7)))
                publish(client) { if (finderWanted && finderEpoch == finderOwner) mutableState.update { it.copy(nearby = nearby) } }
            } catch (_: DongleBusyException) {
                publish(client) { mutableState.update { it.copy(error = CONTROL_BUSY) } }
            }
        }
        if (status.capabilities and 0x10 != 0 && (controlsVisible || mutableState.value.automaticPause || volumeRequested()) &&
            SystemClock.elapsedRealtime() - audioUpdated >= 2_000) {
            readAudio(client)
        }
        if (!status.controlsReady) { publish(client) { cancelEar() }; return }
        val detailsWanted = publish(client) { controlsVisible || automaticSettingsOwner != editorOwner() ||
            hearingSupported() && automaticHealthOwner != editorOwner() && healthAttempts < 3 && SystemClock.elapsedRealtime() >= healthRetryAfter ||
            pendingWrites.awaitingConfirmation() }
        if (detailsWanted) {
            val settings = client.settings()
            publish(client) {
                pendingWrites.dropAnswered(settings)
                mutableState.update { it.copy(settings = settings, requested = pendingWrites.requestedValues(),
                    unconfirmed = pendingWrites.unconfirmed()) }
            }
            if (mutableState.value.metadata == null) client.cached(1)?.let { cached ->
                runCatching { InformationPacket.parse(cached.packet).metadata }.getOrNull()?.let { metadata ->
                    publish(client) { mutableState.update { it.copy(metadata = metadata) } }
                }
            }
            refreshHearing(client)
            val owner = editorOwner()
            automaticSettingsOwner = owner
            if (owner != null && owner != automaticHealthOwner && hearingSupported() && !status.microphoneActive && !callActive &&
                (owner != healthAttemptOwner || healthAttempts < 3 && SystemClock.elapsedRealtime() >= healthRetryAfter)) {
                if (healthAttemptOwner != owner) { healthAttemptOwner = owner; healthAttempts = 0; healthRetryAfter = 0 }
                protectedWork.incrementAndGet()
                try {
                healthAttempts++
                automaticHealthOwner = owner
                var failed = false
                publish(client) { mutableState.update { it.copy(busy = true) } }
                try { client.withTarget(status) { withVolumeCheckpoints(client) {
                    for (read in listOf<suspend () -> Unit>(
                        { if (hostClockOwner != owner) {
                            check(writable(owner) && hearingSupported())
                            val packet = checkNotNull(hostClockPacket())
                            check(client.transact(2, packet.rawPacket) {
                                check(writable(owner) && hearingSupported() && client.status?.microphoneActive == false)
                            }.isEmpty())
                            publish(client) { check(owns(owner)); hostClockOwner = owner }
                        } },
                        { readProfile(client) }, { readTransparency(client) }, { readDiagnosticsNow(client) })) {
                        try { read() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (busy: DongleBusyException) { throw busy }
                        catch (_: Exception) { failed = true }
                    }
                } } } catch (busy: DongleBusyException) {
                    publish(client) { if (owns(owner) && healthAttemptOwner == owner) {
                        healthAttempts = (healthAttempts - 1).coerceAtLeast(0)
                        automaticHealthOwner = null
                        healthRetryAfter = SystemClock.elapsedRealtime() + 5_000
                    } }
                    throw busy
                } finally { publish(client) {
                    if (failed) { automaticHealthOwner = null; healthRetryAfter = SystemClock.elapsedRealtime() + 5_000 }
                    mutableState.update { it.copy(busy = false) }
                } }
                } finally { protectedWork.decrementAndGet(); refreshRequested.trySend(Unit) }
            }
        }
        if (mutableState.value.usb && status.capabilities and 128 != 0 && (controlsVisible || volumeRequested())) {
            if (volumeRequested()) usbVolume.value.reconcile() else usbVolume.value.probe()
        }
        if (!mutableState.value.automaticPause || mutableState.value.usb) return
        val ear = client.cached(5)
        val (action, pauseOwner) = publish(client) {
            val newEar = ear == null || cachedRevisions[5] != ear.revision
            if (ear != null) cachedRevisions[5] = ear.revision else cachedRevisions.remove(5)
            if (!newEar && mutableState.value.automaticPause && !status.microphoneActive) return@publish null
            val inEars = ear?.takeIf { it.ageMs <= 2_500 }?.let { DongleProtocol.ears(it.packet) }
            earClaim.observe(status.generation, inEars, mutableState.value.automaticPause && !status.microphoneActive &&
                !callActive && mutableState.value.audio?.input != 1) to pauseEpoch
        } ?: return
        when (action) {
            DongleEarClaim.Action.PAUSE -> {
                bridge.connect()
                if (withTimeoutOrNull(2_000) { bridge.state.first { it.connected } } != null) {
                    if (!mediaOwnershipCurrent(client, status, pauseOwner)) return
                    val generation = status.generation
                    val acquired = bridge.pauseOwnedMedia(preferences.getString("address", null) ?: return) {
                        mutableState.value.connected && mutableState.value.automaticPause &&
                            !callActive && mutableState.value.audio?.input != 1 && pauseEpoch == pauseOwner && lifecycle.current() == client.controlEpoch &&
                            mutableState.value.status?.generation == generation
                    }
                    publish(client) { if (pauseEpoch == pauseOwner) earClaim.acquired(acquired) }
                }
            }
            DongleEarClaim.Action.RESUME -> {
                if (mediaOwnershipCurrent(client, status, pauseOwner)) bridge.finishOwnedMedia(true)
            }
            DongleEarClaim.Action.CANCEL -> publish(client) { bridge.cancelOwnedMedia() }
            DongleEarClaim.Action.NONE -> Unit
        }
    }
    private fun recordAudio(profile: DongleProtocol.AudioProfile) {
        audioUpdated = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(audio = profile) }
        if (profile.input == 1) cancelEar()
    }
    private suspend fun readAudio(client: DongleSession): DongleProtocol.AudioProfile =
        DongleProtocol.audio(client.transact(7, byteArrayOf(9))).also { profile -> publish(client) { recordAudio(profile) } }

    private suspend fun mediaOwnershipCurrent(client: DongleSession, target: DongleProtocol.Status, owner: Long): Boolean {
        try {
            if (target.capabilities and 0x10 != 0) client.withTarget(target) { readAudio(client) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(client) { cancelEar() }; return false }
        return publish(client) { pauseEpoch == owner && !callActive && mutableState.value.automaticPause &&
            mutableState.value.audio?.input != 1 }
    }
    private suspend fun refreshHearing(client: DongleSession) {
        publish(client) { if (mutableState.value.controlSession == 0L) mutableState.update {
            it.copy(controlSession = accommodationsSessions.incrementAndGet()) } }
        var modes = publish(client) { mutableState.value.hearingModes }
        for (slot in 2..4) {
            val cached = client.cached(slot)
            publish(client) {
                if (cached == null) {
                    if (cachedRevisions.remove(slot) != null) modes = HearingModes.empty()
                } else if (cachedRevisions[slot] != cached.revision) {
                    cachedRevisions[slot] = cached.revision
                    val next = modes.withReport(cached.packet)
                    if (next === modes) { modes = HearingModes.empty(); cachedRevisions.keys.removeAll(2..4) }
                    else modes = next
                }
            }
        }
        publish(client) {
            val base = accommodationsWriteBase
            val request = mutableState.value.accommodationsRequest
            val report = modes.accommodationsReport(mutableState.value.controlSession)
            if (base != null && !request.pending && report != null && report.receptionRevision > request.baselineRevision) {
                val outcome = base.plan.result(report, AccommodationsSendOutcome.COMPLETE)
                accommodationsWriteBase = null
                mutableState.update { it.copy(accommodationsRequest = AccommodationsRequestState(false,
                    outcome, request.baselineRevision, editRevision = request.editRevision + 1)) }
            }
            mutableState.update { it.copy(hearingModes = modes) }
        }
    }
    private fun cancelEar() { pauseEpoch++; earClaim.cancel(); bridge.cancelOwnedMedia() }
    fun mediaRouteChanged() = lifecycle.publish(lifecycle.current()) {
        if (!MediaController.isMediaRoute(preferences.getString("address", null))) cancelEar()
    }
    fun callStateChanged(active: Boolean) = lifecycle.publish(lifecycle.current()) {
        callActive = active
        if (active) cancelEar()
    }
    fun automaticPause(enabled: Boolean) {
        if (!preferences.edit().putBoolean("automatic_pause", enabled).commit()) return
        lifecycle.publish(lifecycle.current()) {
            mutableState.update { it.copy(automaticPause = enabled) }
            cancelEar()
        }
        refreshRequested.trySend(Unit)
        if (enabled) bridge.requestPermission()
    }
    fun setSetting(id: Int, value: Long) {
        val editor = liveSettingEditor(id)
        editor.edit(editor.state.value.revision, value)
    }
    private fun command(bindTarget: Boolean = true, block: suspend (DongleSession) -> Unit) {
        val mine = lifecycle.current()
        val target = mutableState.value.status
        val started = lifecycle.publish(mine) {
            if (mutableState.value.busy) return@publish false
            mutableState.update { it.copy(busy = true, error = null) }
            true
        }
        if (!started) return
        scope.launch {
            try {
                io.withLock {
                val client = checkNotNull(session) { "Dongle is disconnected" }
                lifecycle.publish(mine) { check(session === client) }
                if (bindTarget) client.withTarget(checkNotNull(target) { "Read the dongle settings first" }) {
                    withVolumeCheckpoints(client) { block(client) }
                } else withVolumeCheckpoints(client) { block(client) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { runCatching { lifecycle.publish(mine) { mutableState.update {
                it.copy(error = "The request was not confirmed.")
            } } } } finally { runCatching { lifecycle.publish(mine) { mutableState.update { it.copy(busy = false) } } } }
        }
    }
    private fun att(client: DongleSession) = DongleAtt { client.transact(1, it) }
    private fun hearingSupported() = mutableState.value.metadata?.modelNumber in setOf(
        "A2931", "A2699", "A2698", "A3047", "A3048", "A3049", "A3063", "A3064", "A3065")
    private suspend fun readProfile(client: DongleSession): ByteArray {
        publish(client) { mutableState.update { it.copy(hearingAidReadStatus = SettingsReadStatus.READING) } }
        try {
        val read = checkNotNull(att(client).read(HearingDiagnosticUuids.fitting)) { "Hearing profile is unavailable" }
        val profile = checkNotNull(parseHearingAidSettingsResponse(read.second)) { "Unsupported hearing profile" }
        publish(client) {
            profileCharacteristic = read.first; profileData = read.second.copyOf()
            mutableState.update { it.copy(hearingProfile = profile, hearingAidData = read.second.copyOf(),
                hearingAidReadStatus = SettingsReadStatus.AVAILABLE) }
        }
        return read.second
        } catch (error: Exception) {
            runCatching { publish(client) {
                profileData = null; profileCharacteristic = null
                mutableState.update { it.copy(hearingProfile = null, hearingAidData = byteArrayOf(),
                    hearingAidReadStatus = if (error is CancellationException) SettingsReadStatus.WAITING else SettingsReadStatus.UNAVAILABLE) }
            } }
            throw error
        }
    }
    fun readHearing() = command { client -> readProfile(client); refreshHearing(client) }

    private suspend fun readTransparency(client: DongleSession): Pair<DongleAtt.Characteristic, ByteArray> {
        publish(client) { mutableState.update { it.copy(transparencyReadStatus = SettingsReadStatus.READING) } }
        try {
        val read = checkNotNull(att(client).read(transparencyUuid)) { "Custom Transparency is unavailable" }
        check(read.second.size == 104 && parseTransparencySettingsResponse(read.second) != null)
        publish(client) { mutableState.update { it.copy(transparencyData = read.second.copyOf(), transparencyReadStatus = SettingsReadStatus.AVAILABLE) } }
        return read
        } catch (error: Exception) {
            runCatching { publish(client) { mutableState.update { it.copy(transparencyData = byteArrayOf(),
                transparencyReadStatus = if (error is CancellationException) SettingsReadStatus.WAITING else SettingsReadStatus.UNAVAILABLE) } } }
            throw error
        }
    }

    fun readAccommodations() = command { client ->
        client.acquireSettingsLease()
        refreshHearing(client)
        readTransparency(client)
    }

    private suspend fun writeTransparency(client: DongleSession, characteristic: DongleAtt.Characteristic,
        baseline: ByteArray, requested: ByteArray) {
        check(baseline.contentEquals(att(client).read(characteristic))) { "Custom Transparency changed" }
        att(client).write(characteristic, requested)
        repeat(5) { attempt ->
            val readback = att(client).read(characteristic)
            check(readback.size == 104 && parseTransparencySettingsResponse(readback) != null)
            if (readback.contentEquals(requested)) {
                publish(client) { mutableState.update { it.copy(transparencyData = readback.copyOf(), transparencyReadStatus = SettingsReadStatus.AVAILABLE) } }
                return
            }
            check(readback.contentEquals(baseline) && attempt < 4) { "Custom Transparency was not confirmed" }
            delay(75)
        }
    }

    fun accommodations(expected: AccommodationsReport, phone: Boolean?, media: Boolean?, preset: AccommodationsPreset?, editRevision: Long = 0,
        audiogram: Boolean = false) {
        scope.launch { accommodationsAwaited(expected, phone, media, preset, editRevision, audiogram) }
    }
    suspend fun accommodationsAwaited(expected: AccommodationsReport, phone: Boolean?, media: Boolean?,
        preset: AccommodationsPreset?, editRevision: Long = 0, audiogram: Boolean = false): AccommodationsRequestState {
        val owner = editorOwner() ?: return AccommodationsRequestState(false, AccommodationsResult.NOT_SENT, expected.receptionRevision)
        return accommodationsAwaited(owner, expected, phone, media, preset, editRevision, audiogram)
    }
    private suspend fun accommodationsAwaited(owner: EditorOwner, expected: AccommodationsReport, phone: Boolean?, media: Boolean?,
        preset: AccommodationsPreset?, editRevision: Long, audiogram: Boolean): AccommodationsRequestState {
        try { ownedCommand(owner, RecordKind.ACCOMMODATIONS) { client ->
            accommodationsNow(client, expected, phone, media, preset, editRevision, audiogram)
        } } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            return AccommodationsRequestState(false, AccommodationsResult.NOT_SENT, expected.receptionRevision)
        } catch (_: Exception) {
            reportLiveFailure(owner)
            val result = mutableState.value.accommodationsRequest
            if (result.baselineRevision != expected.receptionRevision || result.result != AccommodationsResult.UNCONFIRMED)
                return AccommodationsRequestState(false, AccommodationsResult.NOT_SENT, expected.receptionRevision)
        }
        return mutableState.value.accommodationsRequest
    }
    private suspend fun accommodationsNow(client: DongleSession, expected: AccommodationsReport,
        phone: Boolean?, media: Boolean?, preset: AccommodationsPreset?, editRevision: Long, audiogram: Boolean) {
        val snapshot = mutableState.value
        check(!(preset != null && audiogram) && editRevision == snapshot.accommodationsRequest.editRevision)
        client.acquireSettingsLease()
        val target = client.refresh()
        check(!target.microphoneActive)
        refreshHearing(client)
        val original = checkNotNull(mutableState.value.accommodationsReport)
        val receivedPlan = expected.plan(phone ?: expected.phoneEnabled, media ?: expected.mediaEnabled, preset)
        check(receivedPlan.canStart(original) && mutableState.value.accommodationsRequest.editable(original))
        val previous = accommodationsWriteBase?.takeIf {
            it.client === client && it.session == snapshot.controlSession && it.revision == editRevision &&
                snapshot.accommodationsRequest.requested != null && it.plan.canStart(original)
        }
        var plan = previous?.plan?.followup(phone, media, preset) ?: receivedPlan
        val transparency = readTransparency(client)
        val clinical = readProfile(client)
        if (previous != null && !previous.clinical.contentEquals(clinical)) {
            accommodationsWriteBase = null
            publish(client) { mutableState.update { it.copy(accommodationsRequest = AccommodationsRequestState(false,
                AccommodationsResult.UNCONFIRMED, original.receptionRevision, editRevision = editRevision + 1)) } }
            return
        }
        val source = AccommodationsAudiogram.parse(clinical, snapshot.controlSession)
        if (audiogram) plan = plan.withAudiogram(checkNotNull(source))
        val requestedTransparency = when {
            audiogram -> checkNotNull(source?.transparency(transparency.second))
            preset != null -> checkNotNull(accommodationsTransparencyPreset(transparency.second, preset))
            else -> transparency.second
        }
        val selectedAudiogram = preset == null && (audiogram ||
            (previous?.let { snapshot.accommodationsRequest.requested?.audiogram } ?:
                (ReceivedAccommodations.parse(plan.packet(), snapshot.controlSession, 1, source)?.audiogram == true)))
        refreshHearing(client)
        check(mutableState.value.accommodationsReport?.let(plan::canStart) == true)
        check(!client.refresh().microphoneActive)
        val transparencyChanges = !requestedTransparency.contentEquals(transparency.second)
        if (!plan.changesRequired && !transparencyChanges) {
            publish(client) { mutableState.update { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                pending = false, result = AccommodationsResult.NO_CHANGE, baselineRevision = original.receptionRevision)) } }
            return
        }
        publish(client) { mutableState.update { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
            pending = true, result = null, baselineRevision = original.receptionRevision)) } }
        var attempted = false
        var result = AccommodationsResult.NOT_SENT
        try {
            if (plan.changesRequired) { attempted = true; client.transact(2, plan.packet()) }
            if (transparencyChanges) {
                attempted = true
                writeTransparency(client, transparency.first, transparency.second, requestedTransparency)
            }
            check(client.refresh().sameConnection(target))
            val actualTransparency = readTransparency(client).second
            val actualClinical = readProfile(client)
            check(requestedTransparency.contentEquals(actualTransparency) && clinical.contentEquals(actualClinical))
            refreshHearing(client)
            val current = mutableState.value.accommodationsReport
            val reported = plan.result(current, if (plan.changesRequired) AccommodationsSendOutcome.COMPLETE else AccommodationsSendOutcome.NOT_ATTEMPTED)
            val fresh = current != null && current.receptionRevision > original.receptionRevision
            result = when {
                reported == AccommodationsResult.CONFIRMED -> AccommodationsResult.CONFIRMED
                fresh && reported != AccommodationsResult.NO_CHANGE -> AccommodationsResult.UNCONFIRMED
                previous != null || plan.changesRequired -> AccommodationsResult.SENT
                else -> AccommodationsResult.CONFIRMED
            }
            check(result != AccommodationsResult.UNCONFIRMED)
            if (result == AccommodationsResult.SENT) {
                val revision = editRevision + 1
                accommodationsWriteBase = AccommodationsWriteBase(client, snapshot.controlSession, revision, plan, clinical.copyOf())
                publish(client) { mutableState.update { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                    requested = plan.selection.copy(preset = plan.selection.preset.takeUnless { selectedAudiogram },
                        audiogram = selectedAudiogram), editRevision = revision)) } }
            } else {
                accommodationsWriteBase = null
                publish(client) { mutableState.update { it.copy(accommodationsRequest = it.accommodationsRequest.copy(requested = null)) } }
            }
        } finally {
            if (attempted && result !in listOf(AccommodationsResult.CONFIRMED, AccommodationsResult.SENT, AccommodationsResult.NO_CHANGE))
                result = AccommodationsResult.UNCONFIRMED
            if (result == AccommodationsResult.UNCONFIRMED) accommodationsWriteBase = null
            runCatching { publish(client) { mutableState.update { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                pending = false, result = result, baselineRevision = original.receptionRevision,
                requested = it.accommodationsRequest.requested.takeUnless { result == AccommodationsResult.UNCONFIRMED },
                editRevision = it.accommodationsRequest.editRevision + if (result == AccommodationsResult.UNCONFIRMED) 1 else 0)) } } }
        }
    }

    fun updateTransparency(expected: ByteArray, requested: ByteArray) {
        val owner = editorOwner() ?: return
        scope.launch { try { ownedCommand(owner, RecordKind.TRANSPARENCY) { client ->
            updateTransparencyNow(client, expected, requested)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reportLiveFailure(owner) } }
    }
    private suspend fun updateTransparencyNow(client: DongleSession, expected: ByteArray, requested: ByteArray,
        canSend: () -> Boolean = { true }) {
        client.acquireSettingsLease()
        val target = client.refresh()
        check(!target.microphoneActive && expected.size == 104 && requested.size == 104)
        val settings = checkNotNull(parseTransparencySettingsResponse(requested))
        refreshHearing(client)
        check(!settings.enabled || mutableState.value.hearingModes.customTransparencyAllowed)
        val read = readTransparency(client)
        check(expected.contentEquals(read.second))
        check(canSend())
        var attempted = false
        var confirmed = false
        try {
            if (!requested.contentEquals(expected)) { attempted = true; writeTransparency(client, read.first, expected, requested) }
            val current = client.refresh()
            check(current.sameConnection(target) && !current.microphoneActive && canSend())
            confirmed = true
        } finally {
            runCatching { publish(client) { mutableState.update { it.copy(
                transparencyData = if (confirmed) it.transparencyData else byteArrayOf(),
                transparencyReadStatus = if (confirmed) it.transparencyReadStatus else SettingsReadStatus.UNAVAILABLE,
                transparencyResult = if (confirmed) AccommodationsResult.CONFIRMED else if (attempted) AccommodationsResult.UNCONFIRMED else AccommodationsResult.NOT_SENT) } } }
        }
    }
    fun hearingMode(mode: HearingMode, enabled: Boolean) = command { client ->
        check(hearingSupported()) { "Hearing assistance is unavailable on this model" }
        client.acquireSettingsLease()
        val generation = client.refresh().generation
        refreshHearing(client)
        val original = mutableState.value.hearingModes
        val plan = checkNotNull(original.plan(mode, enabled)) { "Read hearing settings first" }
        val fitting = if (enabled) readProfile(client).also {
            check(hearingAidSettingsWritable(it)) { "AirPods have no writable hearing fitting" }
            val transparency = att(client).read(UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390"))?.second
            check(transparency != null && customTransparencyEnabled(transparency) == false)
        } else null
        check(!client.refresh().microphoneActive)
        publish(client) { mutableState.update { it.copy(hearingModes = original.withRequestState(mode, original.uncertain)) } }
        var attempted = false
        var confirmed = false
        try {
            for (packet in plan.packets) { attempted = true; client.transact(2, packet) }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var matched = false
            while (SystemClock.elapsedRealtime() < deadline) {
                check(client.refresh().generation == generation)
                refreshHearing(client)
                if (mutableState.value.hearingModes.confirms(plan)) { matched = true; break }
                delay(100)
            }
            check(matched)
            if (fitting != null) check(fitting.contentEquals(readProfile(client)))
            confirmed = true
        } finally {
            runCatching { publish(client) { mutableState.update { it.copy(hearingModes = it.hearingModes.withRequestState(null,
                if (attempted && !confirmed) mode else null)) } } }
        }
    }
    fun updateHearing(transform: (HearingAidSettings) -> HearingAidSettings) {
        val editor = liveHearingEditor()
        editor.edit(editor.state.value.revision) { original ->
            val profile = parseHearingAidSettingsResponse(original) ?: return@edit null
            var requested: ByteArray? = null
            sendHearingAidSettings(original, transform(profile)) { _, data, _ -> requested = data }
            requested
        }
    }
    private suspend fun updateHearingNow(client: DongleSession, baseline: ByteArray, requested: ByteArray,
        canSend: () -> Boolean = { true }) {
        check(hearingSupported() && hearingAidSettingsWritable(baseline) && hearingAidSettingsWritable(requested))
        client.acquireSettingsLease()
        check(!client.refresh().microphoneActive)
        refreshHearing(client)
        check(mutableState.value.hearingModes.enrolled == true && mutableState.value.hearingModes.clinicalReportsKnown)
        val characteristic = checkNotNull(profileCharacteristic)
        check(baseline.contentEquals(att(client).read(characteristic))) { "Hearing profile changed" }
        check(canSend())
        att(client).write(characteristic, requested)
        repeat(5) { attempt ->
            check(canSend())
            val readback = att(client).read(characteristic)
            check(canSend() && hearingAidSettingsWritable(readback))
            val payload = readback.copyOfRange(4, 104)
            if (payload.contentEquals(requested.copyOfRange(4, 104))) {
                publish(client) {
                    check(canSend())
                    profileData = readback.copyOf()
                    mutableState.update { it.copy(hearingProfile = checkNotNull(parseHearingAidSettingsResponse(readback)),
                        hearingAidData = readback.copyOf(), hearingAidReadStatus = SettingsReadStatus.AVAILABLE) }
                }
                return
            }
            check(payload.contentEquals(baseline.copyOfRange(4, 104)) && attempt < 4) { "Hearing profile was not confirmed" }
            delay(75)
        }
    }
    fun readDiagnostics() = command { client -> readDiagnosticsNow(client) }
    private suspend fun readDiagnosticsNow(client: DongleSession) {
        val product = when (mutableState.value.metadata?.modelNumber) {
            "A2931", "A2699", "A2698" -> 0x2014
            "A3047", "A3048", "A3049" -> 0x2024
            "A3063", "A3064", "A3065" -> 0x2027
            else -> error("Saved hardware checks are unavailable on this model")
        }
        val data = checkNotNull(att(client).read(HearingDiagnosticUuids.savedResults)).second
        val parsed = checkNotNull(parseSavedHearingDiagnostics(data, product))
        publish(client) { mutableState.update { it.copy(diagnostics = parsed) } }
    }
    fun audioProfile(codec: Int, rateMode: Int, bitrate: Long) {
        val editor = liveAudioEditor()
        editor.edit(editor.state.value.revision, DongleAudioIntent(codec, rateMode, bitrate))
    }
    fun gameMode(enabled: Boolean) {
        val editor = liveGameModeEditor()
        editor.edit(editor.state.value.revision, enabled)
    }
    fun readPerformance() = command { client ->
        val snapshot = DongleProtocol.performance(client.transact(7, byteArrayOf(5)))
        publish(client) { mutableState.update { it.copy(performance = snapshot) } }
    }
    fun startFinder() = lifecycle.publish(lifecycle.current()) {
        if (mutableState.value.busy) return@publish
        val owner = ++finderEpoch
        finderWanted = true
        refreshRequested.trySend(Unit)
        command { client ->
            if (!publish(client) { finderWanted && finderEpoch == owner }) return@command
            try {
                val available = DongleProtocol.nearby(client.transact(7, byteArrayOf(7)))
                val proceed = publish(client) {
                    if (!finderWanted || finderEpoch != owner) false
                    else if (!available.identityAvailable) {
                        finderWanted = false
                        mutableState.update { it.copy(finderOpen = false, nearby = available) }
                        false
                    } else true
                }
                if (!proceed) return@command
                val nearby = DongleProtocol.nearby(client.transact(7, byteArrayOf(6)))
                publish(client) {
                    if (finderWanted && finderEpoch == owner) mutableState.update { it.copy(finderOpen = true, nearby = nearby) }
                }
            } catch (error: Exception) {
                publish(client) { if (finderEpoch == owner) {
                    finderWanted = false
                    finderEpoch++
                    mutableState.update { it.copy(finderOpen = false, nearby = null) }
                } }
                throw error
            }
        }
    }
    fun stopFinder() = lifecycle.publish(lifecycle.current()) {
        if (!finderWanted) return@publish
        val owner = ++finderEpoch
        finderWanted = false
        mutableState.update { it.copy(finderOpen = false, nearby = null) }
        val mine = lifecycle.current()
        val client = session ?: return@publish
        val target = mutableState.value.status ?: return@publish
        scope.launch { io.withLock {
            // A cancelled in-flight Start finishes before this owner-bound Stop; a newer Start supersedes it.
            if (lifecycle.current() == mine && publish(client) { finderEpoch == owner && !finderWanted }) {
                runCatching {
                    val current = client.refresh()
                    check(current.address == target.address) { "Selected AirPods changed" }
                    client.withTarget(current) { client.transact(7, byteArrayOf(8)) }
                }
            }
        } }
        Unit
    }
    fun disconnect() { reconnectWanted = false; retry?.cancel(); retire() }
    fun forget() {
        val key = selectedVolumeSerial()?.let(::volumeRequestKey)
        val edit = preferences.edit().remove("address")
        if (key != null) edit.remove(key)
        edit.apply()
        disconnect()
        mutableState.update { DongleState(automaticPause = it.automaticPause) }
    }
    private fun retire(): Long = lifecycle.retire {
        if (usbVolume.isInitialized()) usbVolume.value.retire()
        connection?.cancel(); connection = null
        runCatching { socket?.close() }; socket = null; session = null; activeUsbPath = null
        finderWanted = false; finderEpoch++
        mutableState.update { it.copy(connected = false, busy = false, status = null, finderOpen = false, nearby = null,
            usbVolumeRequested = volumeRequested()) }
        clearReports()
    }
    override fun close() {
        synchronized(lifecycle) { controlObservers.clear(); controlsVisible = false }
        audioManager.unregisterAudioDeviceCallback(audioDevices)
        disconnect(); context.unregisterReceiver(usbEvents); bridge.close(); scope.cancel()
    }

    companion object { private const val USB_PERMISSION = "me.kavishdevar.librepods.DONGLE_USB_PERMISSION"
        private const val CONTROL_BUSY = "Controls are in use by another client. Waiting for them to finish." }
}
