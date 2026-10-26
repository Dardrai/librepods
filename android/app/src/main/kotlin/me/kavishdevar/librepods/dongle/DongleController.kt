package me.kavishdevar.librepods.dongle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsSendOutcome
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.data.LiveRecordEditor
import me.kavishdevar.librepods.data.LiveRecordSource
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource
import me.kavishdevar.librepods.data.LiveAccommodationsEditor
import me.kavishdevar.librepods.data.LiveAccommodationsSource
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.MediaController

internal data class DongleChoice(val address: String, val name: String)
private val accommodationsSessions = AtomicLong(0)
internal data class DongleState(
    val selected: Boolean = false, val name: String = "AirPods Dongle", val connected: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val status: DongleProtocol.Status? = null,
    val metadata: AppleMetadata? = null, val settings: Map<Int, DongleProtocol.Setting> = emptyMap(),
    val requested: Map<Int, Long> = emptyMap(), val unconfirmed: Set<Int> = emptySet(), val automaticPause: Boolean = false,
    val hearingModes: HearingModes = HearingModes.empty(), val hearingProfile: HearingAidSettings? = null,
    val controlSession: Long = 0, val accommodationsRequest: AccommodationsRequestState = AccommodationsRequestState(),
    val transparencyData: ByteArray = byteArrayOf(), val transparencyResult: AccommodationsResult? = null,
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge by lazy { ShizukuAudioClient(this.context) }
    private val io = Mutex()
    private val refreshRequested = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = DongleLifecycle()
    private val earClaim = DongleEarClaim()
    private val mutableState = MutableStateFlow(DongleState(selected = preferences.contains("address"),
        automaticPause = preferences.getBoolean("automatic_pause", false)))
    val state = mutableState.asStateFlow()
    @Volatile private var socket: BluetoothSocket? = null
    private var session: DongleSession? = null
    private var connection: Job? = null
    private val cachedRevisions = mutableMapOf<Int, Long>()
    private data class Pending(val value: Long, val revision: Long, val expires: Long)
    private val pending = mutableMapOf<Int, Pending>()
    private var profileData: ByteArray? = null
    private var profileCharacteristic: DongleAtt.Characteristic? = null
    private val transparencyUuid = UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390")
    @Volatile private var finderWanted = false
    private var finderEpoch = 0L
    @Volatile private var pauseEpoch = 0L
    @Volatile private var callActive = false
    @Volatile private var controlsVisible = false
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
    private var automaticHealthOwner: EditorOwner? = null
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
            LiveAccommodationsSource(owner, report, value.hearingModes.receivedAccommodations(value.controlSession),
                value.accommodationsRequest, owner != null && (!value.busy || own))
        }, write = { captured, report, revision, choice, changeCurves ->
            val owner = captured as? EditorOwner
            if (owner == null) AccommodationsResult.NOT_SENT else accommodationsAwaited(owner, report, choice.phone,
                choice.media, choice.preset.takeIf { changeCurves }, revision, choice.audiogram && changeCurves).result ?: AccommodationsResult.NOT_SENT
        })
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
    init { scope.launch { state.collect {
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
        block: suspend (DongleSession) -> T): T = io.withLock {
        check(owns(owner)) { "AirPods connection changed" }
        val target = checkNotNull(mutableState.value.status)
        val client = owner.client
        publish(client) {
            check(writable(owner))
            liveOperation = kind?.let { owner to it }
            mutableState.update { it.copy(busy = true, error = null) }
        }
        try { if (bindTarget) client.withTarget(target) { block(client) } else block(client) }
        finally { runCatching { publish(client) {
            liveOperation = null
            mutableState.update { it.copy(busy = false) }
        } }; refreshRequested.trySend(Unit) }
    }

    fun liveSettingEditor(id: Int): LiveValueEditor<Long> = liveSettings.getOrPut(id) {
        fun source(): LiveValueSource<Long> {
            val owner = editorOwner()?.takeIf { mutableState.value.status?.microphoneActive == false && !callActive }
            val current = mutableState.value
            return LiveValueSource(owner, current.requested[id] ?: current.settings[id]?.value, writable(owner))
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
        check(validLiveSetting(id, value))
        val revision = mutableState.value.settings[id]?.revision ?: 0L
        client.setting(id, value)
        publish(client) {
            pending[id] = Pending(value, revision, SystemClock.elapsedRealtime() + 10_000)
            mutableState.update { it.copy(requested = pending.mapValues { entry -> entry.value.value }) }
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


    fun choices(): List<DongleChoice> = runCatching {
        context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
            .filter { it.bondState == BluetoothDevice.BOND_BONDED }
            .map { DongleChoice(it.address, it.name?.takeIf(String::isNotBlank) ?: "Paired Bluetooth device") }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    fun restore() { if (preferences.contains("address")) reconnect() }
    fun select(address: String) {
        if (choices().none { it.address.equals(address, true) } || !preferences.edit().putString("address", address).commit()) {
            mutableState.update { it.copy(error = "Pair the dongle in Bluetooth settings, then choose it again.") }
            return
        }
        mutableState.update { it.copy(selected = true) }
        reconnect()
    }
    fun reconnect() {
        val address = preferences.getString("address", null) ?: return
        val mine = retire()
        val job = lifecycle.publish(mine) {
            mutableState.update { it.copy(selected = true, busy = true, error = null) }
            scope.launch(start = CoroutineStart.LAZY) {
            var ownTransport: BluetoothSocket? = null
            try {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                check(adapter != null && adapter.isEnabled) { "Turn on Bluetooth to connect the dongle" }
                val device = adapter.getRemoteDevice(address)
                check(device.bondState == BluetoothDevice.BOND_BONDED) { "Pair the dongle in Bluetooth settings first" }
                val transport = device.createRfcommSocketToServiceRecord(UUID.fromString(DongleProtocol.UUID))
                ownTransport = transport
                lifecycle.publish(mine) { socket = transport }
                guarded(transport, 8_000) { transport.connect() }
                val client = DongleSession({ operation, report, data ->
                    lifecycle.publish(mine) { check(socket === transport) }
                    guarded(transport, 3_000) {
                        try { DongleProtocol.exchange(transport.inputStream, transport.outputStream, operation, report, data) }
                        catch (busy: DongleBusyException) { throw busy }
                        catch (rejected: DongleRequestException) { throw rejected }
                        catch (error: Exception) { runCatching { transport.close() }; throw error }
                    }
                }, now = SystemClock::elapsedRealtime, controlEpoch = mine,
                    closeTransport = { transport.close() })
                io.withLock {
                    val status = client.refresh()
                    lifecycle.publish(mine) {
                        session = client
                        mutableState.update { it.copy(name = device.name ?: "AirPods Dongle", connected = true, busy = false, status = status) }
                    }
                }
                while (isActive && lifecycle.current() == mine) {
                    try { io.withLock { refresh(client) } }
                    catch (_: DongleBusyException) { publish(client) { mutableState.update { it.copy(error = CONTROL_BUSY) } } }
                    withTimeoutOrNull(pollInterval()) { refreshRequested.receive() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                runCatching { lifecycle.publish(mine) { mutableState.update { it.copy(connected = false, busy = false,
                    status = null, settings = emptyMap(), requested = emptyMap(), hearingModes = HearingModes.empty(),
                    hearingProfile = null, diagnostics = null, error = "Could not reach the dongle. Keep it powered and paired, then reconnect.") } } }
            } finally {
                runCatching { ownTransport?.close() }
                runCatching { lifecycle.publish(mine) {
                    socket = null; session = null; finderWanted = false; finderEpoch++
                    clearReports()
                    mutableState.update { it.copy(connected = false, busy = false, status = null, finderOpen = false, nearby = null) }
                } }
            }
            }.also { connection = it }
        }
        job.start()
    }
    private fun <T> publish(client: DongleSession, block: () -> T): T = lifecycle.publish(client.controlEpoch) {
        if (session !== client) throw CancellationException("Dongle session changed")
        block()
    }
    private suspend fun <T> guarded(transport: BluetoothSocket, timeoutMs: Long, block: () -> T): T {
        val timer = scope.launch { delay(timeoutMs); runCatching { transport.close() } }
        return try { block() } finally { timer.cancel() }
    }

    private fun clearReports() {
        cachedRevisions.clear(); pending.clear(); profileData = null; profileCharacteristic = null
        automaticHealthOwner = null; automaticSettingsOwner = null; liveOperation = null
        cancelEar()
        audioUpdated = 0
        mutableState.update { it.copy(metadata = null, settings = emptyMap(), requested = emptyMap(), unconfirmed = emptySet(),
            hearingModes = HearingModes.empty(), controlSession = accommodationsSessions.incrementAndGet(),
            accommodationsRequest = AccommodationsRequestState(), transparencyData = byteArrayOf(), transparencyResult = null,
            hearingProfile = null, diagnostics = null, audio = null, performance = null) }
    }
    private fun pollInterval(): Long = lifecycle.publish(lifecycle.current()) {
        if (controlsVisible || mutableState.value.automaticPause || finderWanted ||
            pending.values.any { SystemClock.elapsedRealtime() < it.expires }) 600L else 5_000L
    }
    fun controlsVisible(visible: Boolean) {
        controlsVisible = visible
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
            mutableState.update { it.copy(status = status,
                unconfirmed = pending.filterValues { entry -> SystemClock.elapsedRealtime() >= entry.expires }.keys) }
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
        if (status.capabilities and 0x10 != 0 && (controlsVisible || mutableState.value.automaticPause) &&
            SystemClock.elapsedRealtime() - audioUpdated >= 2_000) {
            readAudio(client)
        }
        if (!status.controlsReady) { publish(client) { cancelEar() }; return }
        val detailsWanted = publish(client) { controlsVisible || automaticSettingsOwner != editorOwner() ||
            hearingSupported() && automaticHealthOwner != editorOwner() ||
            pending.values.any { SystemClock.elapsedRealtime() < it.expires } }
        if (detailsWanted) {
            val settings = client.settings()
            publish(client) {
                pending.entries.removeAll { (id, request) ->
                    settings[id]?.let { it.revision != request.revision && it.value == request.value } == true
                }
                mutableState.update { it.copy(settings = settings, requested = pending.mapValues { e -> e.value.value },
                    unconfirmed = pending.filterValues { entry -> SystemClock.elapsedRealtime() >= entry.expires }.keys) }
            }
            if (mutableState.value.metadata == null) client.cached(1)?.let { cached ->
                runCatching { InformationPacket.parse(cached.packet).metadata }.getOrNull()?.let { metadata ->
                    publish(client) { mutableState.update { it.copy(metadata = metadata) } }
                }
            }
            refreshHearing(client)
            val owner = editorOwner()
            automaticSettingsOwner = owner
            if (owner != null && owner != automaticHealthOwner && hearingSupported() && !status.microphoneActive && !callActive) {
                automaticHealthOwner = owner
                publish(client) { mutableState.update { it.copy(busy = true) } }
                try { client.withTarget(status) {
                    for (read in listOf<suspend () -> Unit>(
                        { readProfile(client); Unit }, { readTransparency(client); Unit }, { readDiagnosticsNow(client) })) {
                        try { read() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (busy: DongleBusyException) { automaticHealthOwner = null; throw busy }
                        catch (_: Exception) { /* Unsupported saved settings do not block the other bounded reads. */ }
                    }
                } } finally { publish(client) { mutableState.update { it.copy(busy = false) } } }
            }
        }
        if (!mutableState.value.automaticPause) return
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
        publish(client) { mutableState.update { it.copy(hearingModes = modes) } }
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
            try { io.withLock {
                val client = checkNotNull(session) { "Dongle is disconnected" }
                lifecycle.publish(mine) { check(session === client) }
                if (bindTarget) client.withTarget(checkNotNull(target) { "Read the dongle settings first" }) { block(client) }
                else block(client)
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
        val read = checkNotNull(att(client).read(HearingDiagnosticUuids.fitting)) { "Hearing profile is unavailable" }
        val profile = checkNotNull(parseHearingAidSettingsResponse(read.second)) { "Unsupported hearing profile" }
        publish(client) {
            profileCharacteristic = read.first; profileData = read.second.copyOf()
            mutableState.update { it.copy(hearingProfile = profile) }
        }
        return read.second
    }
    fun readHearing() = command { client -> readProfile(client); refreshHearing(client) }

    private suspend fun readTransparency(client: DongleSession): Pair<DongleAtt.Characteristic, ByteArray> {
        val read = checkNotNull(att(client).read(transparencyUuid)) { "Custom Transparency is unavailable" }
        check(read.second.size == 104 && parseTransparencySettingsResponse(read.second) != null)
        publish(client) { mutableState.update { it.copy(transparencyData = read.second.copyOf()) } }
        return read
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
                publish(client) { mutableState.update { it.copy(transparencyData = readback.copyOf()) } }
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
        check(editRevision == 0L && !audiogram)
        client.acquireSettingsLease()
        val target = client.refresh()
        check(!target.microphoneActive)
        refreshHearing(client)
        val original = checkNotNull(mutableState.value.accommodationsReport)
        val plan = expected.plan(phone ?: expected.phoneEnabled, media ?: expected.mediaEnabled, preset)
        check(plan.canStart(original) && mutableState.value.accommodationsRequest.editable(original))
        val transparency = if (preset != null) readTransparency(client) else null
        val requestedTransparency = if (preset != null)
            checkNotNull(accommodationsTransparencyPreset(checkNotNull(transparency).second, preset)) else null
        refreshHearing(client)
        check(mutableState.value.accommodationsReport?.let(plan::canStart) == true)
        check(!client.refresh().microphoneActive)
        val transparencyChanges = requestedTransparency != null && !requestedTransparency.contentEquals(transparency?.second)
        if (!plan.changesRequired && !transparencyChanges) {
            publish(client) { mutableState.update { it.copy(accommodationsRequest = AccommodationsRequestState(false,
                AccommodationsResult.NO_CHANGE, original.receptionRevision)) } }
            return
        }
        publish(client) { mutableState.update { it.copy(accommodationsRequest = AccommodationsRequestState(true, null, original.receptionRevision)) } }
        var attempted = false
        var result = AccommodationsResult.NOT_SENT
        try {
            if (plan.changesRequired) { attempted = true; client.transact(2, plan.packet()) }
            if (transparencyChanges) {
                attempted = true
                writeTransparency(client, checkNotNull(transparency).first, transparency.second, checkNotNull(requestedTransparency))
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (true) {
                check(client.refresh().sameConnection(target))
                refreshHearing(client)
                val current = mutableState.value.accommodationsReport
                result = plan.result(current, if (plan.changesRequired) AccommodationsSendOutcome.COMPLETE else AccommodationsSendOutcome.NOT_ATTEMPTED)
                if (result == AccommodationsResult.NO_CHANGE && transparencyChanges) result = AccommodationsResult.CONFIRMED
                if (result in listOf(AccommodationsResult.CONFIRMED, AccommodationsResult.NO_CHANGE) ||
                    SystemClock.elapsedRealtime() >= deadline) break
                delay(100)
            }
            check(result in listOf(AccommodationsResult.CONFIRMED, AccommodationsResult.NO_CHANGE))
        } finally {
            if (attempted && result !in listOf(AccommodationsResult.CONFIRMED, AccommodationsResult.NO_CHANGE)) result = AccommodationsResult.UNCONFIRMED
            runCatching { publish(client) { mutableState.update { it.copy(accommodationsRequest = AccommodationsRequestState(false, result, original.receptionRevision)) } } }
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
        val readback = att(client).read(characteristic)
        check(canSend() && hearingAidSettingsWritable(readback) && readback.copyOfRange(0, 2).contentEquals(requested.copyOfRange(0, 2)) &&
            readback.copyOfRange(4, 104).contentEquals(requested.copyOfRange(4, 104)))
        publish(client) {
            profileData = readback.copyOf()
            mutableState.update { it.copy(hearingProfile = checkNotNull(parseHearingAidSettingsResponse(readback))) }
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
    fun disconnect() { retire() }
    fun forget() {
        disconnect(); preferences.edit().remove("address").apply()
        mutableState.update { DongleState(automaticPause = it.automaticPause) }
    }
    private fun retire(): Long = lifecycle.retire {
        connection?.cancel(); connection = null
        runCatching { socket?.close() }; socket = null; session = null
        finderWanted = false; finderEpoch++
        mutableState.update { it.copy(connected = false, busy = false, status = null, finderOpen = false, nearby = null) }
        clearReports()
    }
    override fun close() { retire(); bridge.close(); scope.cancel() }

    companion object { private const val CONTROL_BUSY = "Controls are in use by another client. Waiting for them to finish." }
}
