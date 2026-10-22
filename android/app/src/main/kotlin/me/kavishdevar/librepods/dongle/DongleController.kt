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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.MediaController

internal data class DongleChoice(val address: String, val name: String)
internal data class DongleState(
    val selected: Boolean = false, val name: String = "AirPods Dongle", val connected: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val status: DongleProtocol.Status? = null,
    val metadata: AppleMetadata? = null, val settings: Map<Int, DongleProtocol.Setting> = emptyMap(),
    val requested: Map<Int, Long> = emptyMap(), val unconfirmed: Set<Int> = emptySet(), val automaticPause: Boolean = false,
    val hearingModes: HearingModes = HearingModes.empty(), val hearingProfile: HearingAidSettings? = null,
    val diagnostics: SavedHearingDiagnostics? = null,
    val audio: DongleProtocol.AudioProfile? = null, val performance: DongleProtocol.Performance? = null,
    val finderOpen: Boolean = false, val nearby: DongleProtocol.Nearby? = null,
)

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
    @Volatile private var finderWanted = false
    private var finderEpoch = 0L
    @Volatile private var pauseEpoch = 0L
    @Volatile private var callActive = false
    @Volatile private var controlsVisible = false
    private var audioUpdated = 0L

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
                }, now = SystemClock::elapsedRealtime, controlEpoch = mine)
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
        cancelEar()
        audioUpdated = 0
        mutableState.update { it.copy(metadata = null, settings = emptyMap(), requested = emptyMap(), unconfirmed = emptySet(),
            hearingModes = HearingModes.empty(), hearingProfile = null, diagnostics = null, audio = null, performance = null) }
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
        val detailsWanted = publish(client) { controlsVisible || pending.values.any { SystemClock.elapsedRealtime() < it.expires } }
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
    fun setSetting(id: Int, value: Long) = command { client ->
        val revision = mutableState.value.settings[id]?.revision ?: 0L
        client.setting(id, value)
        publish(client) {
            pending[id] = Pending(value, revision, SystemClock.elapsedRealtime() + 10_000)
            mutableState.update { it.copy(requested = pending.mapValues { entry -> entry.value.value }) }
        }
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
                it.copy(error = "The request was not confirmed. Read the settings before trying again.")
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
    fun hearingMode(mode: HearingMode, enabled: Boolean) = command { client ->
        check(hearingSupported()) { "Hearing assistance is unavailable on this model" }
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
    fun updateHearing(transform: (HearingAidSettings) -> HearingAidSettings) = command { client ->
        check(hearingSupported())
        val baseline = publish(client) { checkNotNull(profileData).copyOf() }
        val original = checkNotNull(parseHearingAidSettingsResponse(baseline))
        val characteristic = publish(client) { checkNotNull(profileCharacteristic) }
        check(baseline.contentEquals(att(client).read(characteristic))) { "Hearing profile changed; read it again" }
        var requested: ByteArray? = null
        sendHearingAidSettings(baseline, transform(original)) { _, value, _ -> requested = value }
        val value = checkNotNull(requested) { "Invalid hearing settings" }
        att(client).write(characteristic, value)
        val readback = att(client).read(characteristic)
        check(readback.size == value.size && readback.copyOfRange(4, readback.size).contentEquals(value.copyOfRange(4, value.size)))
        publish(client) {
            profileData = readback
            mutableState.update { it.copy(hearingProfile = checkNotNull(parseHearingAidSettingsResponse(readback))) }
        }
    }
    fun readDiagnostics() = command { client ->
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
        val target = mutableState.value.status
        command(bindTarget = false) { client ->
            val profile = client.requestAudio(codec, rateMode, bitrate, checkNotNull(target))
            val status = checkNotNull(client.status)
            publish(client) {
                if (mutableState.value.status?.generation != status.generation) clearReports()
                mutableState.update { it.copy(status = status) }
                recordAudio(profile)
            }
        }
    }
    fun gameMode(enabled: Boolean) = command { client ->
        client.transact(7, byteArrayOf(4, if (enabled) 1 else 0))
        val profile = DongleProtocol.audio(client.transact(7, byteArrayOf(9)))
        publish(client) { recordAudio(profile) }
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
