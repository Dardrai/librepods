package me.kavishdevar.librepods.companion

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class CompanionState(
    val linked: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val detail: String = "Pair this phone with your PC in Bluetooth settings, then link NTPods.",
    val settingsIssue: Boolean = false,
)

internal data class CompanionStatus(val connected: Boolean, val busy: Boolean, val available: Boolean)
internal data class CompanionCallContext(val purpose: String = "manual", val episode: String = "0".repeat(32),
    val generation: String = CompanionProtocol.HANDSHAKE_TX, val token: String = "0".repeat(32))

/** The PC coordinates every transaction. Transport reconnect never restores a retired audio route. */
class CompanionHandoffController internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val status: suspend (String, () -> Boolean) -> CompanionStatus,
    private val operation: suspend (String, String, () -> Boolean) -> String,
    private val preferences: CompanionPreferences? = null,
    private val callAllowed: (String) -> Boolean = { false },
    private val callReturnAllowed: (String) -> Boolean = { false },
    private val callStatus: suspend (String, () -> Boolean) -> CompanionStatus = { _, _ -> CompanionStatus(false, true, false) },
    private val callOperation: suspend (String, () -> Boolean) -> String = { _, _ -> "unavailable" },
    private val callReceipt: suspend (String, () -> Boolean) -> Int = { _, _ -> 0 },
    private val phoneQuiet: suspend (String, () -> Boolean) -> Boolean = { _, _ -> false },
    private val allCallsIdle: () -> Boolean = { false },
    private val media: suspend (String) -> CompanionMediaSample = { CompanionMediaSample() },
    private val retryWait: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val store = CompanionLinkStore(context)
    private val mutableState = MutableStateFlow(CompanionState())
    val state = mutableState.asStateFlow()
    @Volatile private var link: CompanionLink? = null
    private var connection: Job? = null
    private var socket: BluetoothSocket? = null
    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    @Volatile private var epoch = 0L
    @Volatile private var intentGeneration = 0L
    private data class PendingSwitch(val id: String, val target: String)
    private var pendingSwitch: PendingSwitch? = null
    private val writeLock = Any()
    private val storage = Mutex()
    internal val pairing = CompanionPairingClient(scope, CompanionLinkStore(context, pending = true),
        socketFactory = ::createPairingSocket,
        bonded = ::hasPairingBonds,
        fence = { synchronized(this) {
            val observedIntent = intentGeneration
            val observedEpoch = epoch
            val binding = link
            val valid: () -> Boolean = { !closed && observedIntent == intentGeneration && observedEpoch == epoch &&
                link === binding && call == null && session?.transaction == null && pendingSwitch == null }
            valid
        } },
        authenticate = { socket, candidate -> authenticate(socket, candidate); Unit },
        promote = ::promotePairingLink)

    private fun createPairingSocket(peer: String, service: UUID): BluetoothSocket {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw IOException("Nearby devices permission is unavailable")
        return try {
            context.getSystemService(BluetoothManager::class.java).adapter
                .bondedDevices.single { it.address == peer }.createRfcommSocketToServiceRecord(service)
        } catch (denied: SecurityException) { throw IOException("Bluetooth permission changed", denied) }
    }

    private fun hasPairingBonds(peer: String, device: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            context.getSystemService(BluetoothManager::class.java).adapter.let { adapter ->
                adapter.isEnabled && adapter.bondedDevices.map { it.address }.toSet().containsAll(setOf(peer, device))
            }
        } catch (_: SecurityException) { false } catch (_: Exception) { false }
    }

    private suspend fun promotePairingLink(candidate: CompanionLink, current: () -> Boolean,
        commit: (() -> Boolean) -> Boolean): Boolean = storage.withLock {
        withContext(Dispatchers.IO) {
            commit {
                synchronized(this@CompanionHandoffController) {
                    if (!current() || closed) return@synchronized false
                    val selected = candidate.copy(key = candidate.key.copyOf())
                    try { store.save(selected) } catch (_: Exception) { selected.key.fill(0); return@synchronized false }
                    disconnect("PC link changed")
                    link?.key?.fill(0)
                    link = selected
                    mutableState.value = CompanionState(linked = true, detail = "Connecting to NTPods…")
                    reconnect()
                    true
                }
            }
        }
    }

    private class Transaction(val id: String, val target: String, val generation: Long,
        val call: CompanionCallContext = CompanionCallContext(), val mediaGeneration: String? = null) {
        val live = AtomicBoolean(true)
        @Volatile var stage = "preparing"
        val started = SystemClock.elapsedRealtime()
        @Volatile var phaseStarted = started
        var work: Job? = null
        var mediaWatch: Job? = null
        @Volatile var sourceGeneration: Long? = null
    }
    private class Session(val socket: BluetoothSocket, val link: CompanionLink, val sid: String, val caps: Int = 3) {
        val live = AtomicBoolean(true)
        @Volatile var transportStopped = false
        @Volatile var protocolStopped = false
        @Volatile var transaction: Transaction? = null
        var lastReceived = SystemClock.elapsedRealtime()
        val transactions = mutableSetOf<String>()
        val offeredWinners = mutableSetOf<String>()
        val failedPreferences = mutableSetOf<String>()
        @Volatile var callOrigin: String? = null
        val media = CompanionMedia()
        val mediaLock = Mutex()
        @Volatile var mediaSampling = false
        @Volatile var mediaObservedAt = 0L
    }
    private class Incoming(val active: Session, val intent: Long, val deadline: Long) {
        var state = "ringing"
        val origins = mutableSetOf<String>()
        var retryReady = false
    }
    @Volatile private var incoming: Incoming? = null
    private class Call(val active: Session, val context: CompanionCallContext, val intent: Long, val incoming: Incoming) {
        @Volatile var state = "ringing"
        @Volatile var ended = false
        var lastStatus = ""
        var lastSent = 0L
        @Volatile var checking = false
        var flags = 0
        @Volatile var hadHfp = false
        @Volatile var hadSco = false
        var sampling = false
        @Volatile var acquireStarted = false
        @Volatile var rejectedBeforePrepare = false
    }
    @Volatile private var call: Call? = null
    private var callGeneration = 0L
    val hasCallReservation get() = call != null
    fun linkedDevice(): String? = link?.device

    @Synchronized internal fun beginLocalIntent(): () -> Boolean {
        invalidate()
        val intent = intentGeneration
        val binding = link
        val connectionEpoch = epoch
        return { !closed && intentGeneration == intent && link === binding && epoch == connectionEpoch }
    }

    fun preferencesChanged(device: String) {
        scope.launch { runCatching {
            preferences?.localChanges(device)?.forEach { record ->
                session?.takeIf { it.link.device == device && it.caps and 1 != 0 }?.let { send(it, record.frame(device, it.sid)) }
            }
        } }
    }

    @Synchronized fun onCallState(value: String, freshIncoming: Boolean) {
        if (value !in setOf("ringing", "active")) incoming = null
        else incoming?.state = value
        val existing = call
        if (existing != null) {
            if (value == "unknown" || value == "ringing" && existing.state != "ringing") { invalidate(); return }
            existing.state = value
            if (value == "idle") endCall(existing) else scope.launch { updateCall(existing) }
            return
        }
        val active = session ?: return
        if (!active.live.get() || active.caps and 2 == 0 || !callAllowed(active.link.device)) return
        if (freshIncoming && value == "ringing") incoming = Incoming(active, intentGeneration, SystemClock.elapsedRealtime() + 5000)
        incoming?.let(::admitIncoming)
    }

    private fun incomingCurrent(event: Incoming) = !closed && incoming === event && session === event.active &&
        event.active.live.get() && event.intent == intentGeneration && SystemClock.elapsedRealtime() < event.deadline &&
        event.state in setOf("ringing", "active") && event.active.caps and 2 != 0 && callAllowed(event.active.link.device)

    @Synchronized private fun admitIncoming(event: Incoming) {
        if (!incomingCurrent(event)) { if (incoming === event) incoming = null; return }
        val active = event.active
        val token = active.callOrigin ?: return
        if (call != null || token in event.origins || event.origins.size >= 2 || event.origins.isNotEmpty() && !event.retryReady) return
        if (active.transaction?.call?.purpose == "media") {
            val retired = retire(active)
            if (retired != null) scope.launch {
                send(active, CompanionProtocol.frame("cancel", active.link.device, retired, active.sid).put("reason", "superseded"))
            }
        }
        if (active.transaction != null || pendingSwitch != null) return
        val context = CompanionCallContext("call_takeover", CompanionProtocol.nonce(16),
            (++callGeneration).toString(16).padStart(16, '0'), token)
        val lease = Call(active, context, intentGeneration, event).also { it.state = event.state }
        event.origins += token
        event.retryReady = false
        call = lease
        val id = CompanionProtocol.nonce(8)
        if (id == CompanionProtocol.HANDSHAKE_TX || active.transactions.size >= 128) { call = null; return }
        active.transactions += id
        val pending = PendingSwitch(id, "android")
        pendingSwitch = pending
        mutableState.value = state.value.copy(busy = true, detail = "Preparing audio transfer…")
        scope.launch {
            send(active, callFrame("call_begin", lease, id))
            updateCall(lease)
            delay(30_000)
            if (call === lease && pendingSwitch === pending) { invalidate(); }
        }
    }

    private fun callCurrent(lease: Call) = !closed && call === lease && session === lease.active &&
        lease.active.live.get() && intentGeneration == lease.intent && callAllowed(lease.active.link.device)

    @Synchronized private fun retryRejectedIncoming(lease: Call) {
        if (!lease.rejectedBeforePrepare || !callCurrent(lease) || !incomingCurrent(lease.incoming) ||
            lease.acquireStarted || lease.hadHfp || lease.hadSco || lease.flags and 8 == 0 ||
            lease.incoming.origins.size != 1) return
        call = null
        lease.incoming.retryReady = true
        admitIncoming(lease.incoming)
    }

    private fun callFrame(type: String, lease: Call, tx: String = CompanionProtocol.HANDSHAKE_TX) =
        CompanionProtocol.frame(type, lease.active.link.device, tx, lease.active.sid)
            .put("episode", lease.context.episode).put("call_gen", lease.context.generation).apply {
                if (type != "call_state") put("origin_token", lease.context.token)
            }

    private fun endCall(lease: Call) {
        if (!callCurrent(lease) || lease.ended || lease.checking) return
        lease.checking = true
        // Retire the call-acquire owner first; the serialized read-only receipt drains its native worker.
        retire(lease.active)
        scope.launch {
            val quiet = runCatching { phoneQuiet(lease.active.link.device) { callCurrent(lease) && lease.state == "idle" } }.getOrDefault(false)
            if (!callCurrent(lease)) return@launch
            lease.checking = false
            if (!quiet || !callReturnAllowed(lease.active.link.device)) { invalidate(); return@launch }
            lease.ended = true
            mutableState.value = state.value.copy(busy = false, detail = "PC linked. Choose where to listen.")
            updateCall(lease)
            send(lease.active, callFrame("call_end", lease).put("reason", "ended"))
        }
    }

    private suspend fun updateCall(lease: Call) {
        if (!callCurrent(lease) || lease.checking || lease.sampling) return
        lease.sampling = true
        try {
        val flags = if (lease.active.transaction != null) 0 else
            runCatching { callReceipt(lease.active.link.device) { callCurrent(lease) } }.getOrDefault(0).also { lease.flags = it }
        if (!callCurrent(lease)) return
        val idle = lease.state == "idle" && runCatching(allCallsIdle).getOrDefault(false)
        if (lease.state == "idle" && !idle) { invalidate(); return }
        if (!lease.ended && !lease.checking && lease.active.transaction == null) {
            if (lease.hadHfp && flags and 3 != 3 || lease.state == "active" && lease.hadSco && flags and 4 == 0) {
                invalidate(); return
            }
            lease.hadHfp = lease.hadHfp || flags and 3 == 3
            lease.hadSco = lease.hadSco || flags and 4 != 0
        }
        val sco = when { flags and 4 != 0 -> "connected"; flags and 8 != 0 -> "idle"; else -> "unknown" }
        val status = "${lease.state}:${flags and 3 == 3}:$sco"
        val now = SystemClock.elapsedRealtime()
        if (status == lease.lastStatus && now - lease.lastSent < 10_000) return
        lease.lastStatus = status; lease.lastSent = now
        send(lease.active, callFrame("call_state", lease).put("state", lease.state)
            .put("hfp_ready", flags and 3 == 3).put("sco", sco).put("all_idle", idle))
        } finally { lease.sampling = false; retryRejectedIncoming(lease) }
    }

    fun callRouteChanged() {
        call?.takeIf { !it.ended && !it.checking && it.active.transaction == null }?.let { lease ->
            scope.launch { updateCall(lease) }
        }
    }

    init {
        scope.launch {
            val generation = epoch
            try {
                val saved = storage.withLock { withContext(Dispatchers.IO) { store.load() } }
                if (!closed && generation == epoch && link == null && saved != null) {
                    link = saved
                    mutableState.value = CompanionState(linked = true, detail = "Connecting to NTPods…")
                    reconnect()
                }
            } catch (_: Exception) {
                if (!closed && generation == epoch) mutableState.value = CompanionState(detail = "Saved PC link is unavailable. Link your PC again.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun link(peer: String, device: String, token: String) {
        if (closed) return
        val selected = CompanionLink(CompanionProtocol.address(peer), CompanionProtocol.address(device),
            CompanionProtocol.hexBytes(token.trim().lowercase(), 32))
        require(peer != device) { "Select your PC and AirPods separately" }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        require(adapter.bondedDevices.any { it.address == peer } && adapter.bondedDevices.any { it.address == device }) {
            "Pair both devices in Bluetooth settings first"
        }
        pairing.discardPending()
        disconnect("PC link changed")
        val generation = ++epoch
        scope.launch {
            try {
                storage.withLock {
                    if (closed || generation != epoch) return@launch
                    withContext(Dispatchers.IO) { store.save(selected) }
                }
                if (closed || generation != epoch) { selected.key.fill(0); return@launch }
                link?.key?.fill(0)
                link = selected
                mutableState.value = CompanionState(linked = true, detail = "Connecting to NTPods…")
                reconnect()
            } catch (cancelled: CancellationException) {
                selected.key.fill(0)
                throw cancelled
            } catch (_: Exception) {
                selected.key.fill(0)
                if (!closed && generation == epoch) mutableState.value = CompanionState(detail = "Could not save this PC link. Try again.")
            }
        }
    }

    fun unlink() {
        pairing.discardPending()
        ++epoch
        disconnect("PC unlinked")
        link?.key?.fill(0)
        link = null
        scope.launch { storage.withLock { withContext(Dispatchers.IO) { store.clear() } } }
        mutableState.value = CompanionState()
    }

    @Synchronized fun reconnect() {
        if (closed) return
        val selected = link ?: return
        disconnect("Connecting to NTPods…")
        val generation = epoch
        connection = scope.launch {
            var failures = 0
            while (currentCoroutineContext().isActive && connectionCurrent(selected, generation)) {
                val observedIntent = intentGeneration
                var retry = false
                var authenticating = false
                connectionDetail(selected, generation, "Connecting to NTPods…")
                var captured: BluetoothSocket? = null
                var authenticated: Session? = null
                var heartbeat: Job? = null
                try {
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Nearby devices permission is unavailable")
                    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                    check(hasPairingBonds(selected.peer, selected.device))
                    val peer = adapter.bondedDevices.singleOrNull { it.address == selected.peer } ?: error("Peer no longer paired")
                    captured = peer.createRfcommSocketToServiceRecord(CompanionProtocol.SERVICE)
                    val liveSocket = captured
                    synchronized(this@CompanionHandoffController) {
                        check(connectionCurrent(selected, generation) && observedIntent == intentGeneration)
                        socket = liveSocket
                    }
                    val timeout = scope.launch { delay(18_000); if (session == null && socket === liveSocket) liveSocket.close() }
                    try { withContext(Dispatchers.IO) { liveSocket.connect() } } finally { timeout.cancel() }
                    check(connectionCurrent(selected, generation) && observedIntent == intentGeneration &&
                        peer.bondState == BluetoothDevice.BOND_BONDED &&
                        liveSocket.remoteDevice.address == selected.peer)
                    authenticating = true
                    val authTimeout = scope.launch { delay(10_000); if (session == null && socket === liveSocket) liveSocket.close() }
                    val authenticatedPeer = try { withContext(Dispatchers.IO) { authenticate(liveSocket, selected) } } finally { authTimeout.cancel() }
                    val sid = authenticatedPeer.first
                    val active = Session(liveSocket, selected, sid, authenticatedPeer.second)
                    synchronized(this@CompanionHandoffController) {
                        check(connectionCurrent(selected, generation) && observedIntent == intentGeneration &&
                            hasPairingBonds(selected.peer, selected.device))
                        authenticated = active
                        session = active
                        mutableState.value = CompanionState(linked = true, connected = true, detail = "PC linked. Choose where to listen.")
                    }
                    failures = 0
                    sendStatus(active)
                    if (active.caps and 1 != 0) {
                        val records = try { preferences?.snapshot(selected.device).orEmpty() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { mutableState.value = state.value.copy(settingsIssue = true); emptyList() }
                        records.forEach { send(active, it.frame(selected.device, sid)) }
                    }
                    heartbeat = scope.launch {
                        var lastPing = SystemClock.elapsedRealtime()
                        var ping: Job? = null
                        while (isActive && active.live.get()) {
                            delay(250)
                            val now = SystemClock.elapsedRealtime()
                            incoming?.takeIf { it.active === active && !incomingCurrent(it) }?.let { if (incoming === it) incoming = null }
                            if (now - active.lastReceived >= 45_000) {
                                active.transportStopped = true
                                active.live.set(false)
                                runCatching { liveSocket.close() }
                                break
                            }
                            if (now - lastPing >= 10_000 && ping?.isActive != true) {
                                lastPing = now
                                // A blocked RFCOMM writer must not suspend the silence/phase watchdog.
                                ping = launch {
                                    send(active, CompanionProtocol.frame("ping", selected.device, sid = sid))
                                    call?.takeIf { it.active === active }?.let { updateCall(it) }
                                }
                            }
                            val tx = active.transaction
                            if (tx != null && (SystemClock.elapsedRealtime() - tx.phaseStarted >= 30_000 ||
                                    SystemClock.elapsedRealtime() - tx.started >= 75_000)) launch { cancel(active, "timeout") }
                        }
                    }
                    while (currentCoroutineContext().isActive && active.live.get()) {
                        val frame = withContext(Dispatchers.IO) { CompanionProtocol.read(liveSocket.inputStream) }
                        CompanionProtocol.validate(frame, selected.device, sid)
                        active.lastReceived = SystemClock.elapsedRealtime()
                        handle(active, frame)
                    }
                    if (active.protocolStopped) error("Invalid companion writer")
                    if (active.transportStopped) throw IOException("Companion transport stopped")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    retry = authenticated?.protocolStopped != true
                    connectionDetail(selected, generation,
                        if (retry) "PC connection lost. Reconnecting to NTPods…" else "PC link could not be verified. Link your PC again.")
                } catch (_: SecurityException) {
                    connectionDetail(selected, generation, "Allow Nearby devices access, then tap Reconnect.")
                } catch (_: Exception) {
                    connectionDetail(selected, generation,
                        if (authenticating) "PC link could not be verified. Link your PC again." else
                            "Bluetooth is unavailable. Pair both devices, then tap Reconnect.")
                } finally {
                    heartbeat?.cancel()
                    synchronized(this@CompanionHandoffController) {
                        authenticated?.let { active ->
                            if (call?.active === active) call = null
                            if (incoming?.active === active) incoming = null
                            active.live.set(false)
                            active.transaction?.let { it.live.set(false); it.work?.cancel(); it.mediaWatch?.cancel() }
                            if (session === active) session = null
                        }
                        if (socket === captured) socket = null
                        if (generation == epoch) pendingSwitch = null
                    }
                    runCatching { captured?.close() }
                }
                if (!retry || !connectionCurrent(selected, generation)) break
                // Closing the reader can finish before an admitted writer reports its protocol failure.
                val protocolStopped = withContext(Dispatchers.IO) { synchronized(writeLock) {
                    authenticated?.protocolStopped == true
                } }
                if (protocolStopped) {
                    connectionDetail(selected, generation, "PC link could not be verified. Link your PC again.")
                    break
                }
                val waitingIntent = intentGeneration
                retryWait(controlRetryDelay(failures++))
                if (waitingIntent != intentGeneration) break
            }
        }
    }

    private fun connectionCurrent(selected: CompanionLink, generation: Long) =
        !closed && generation == epoch && link === selected

    @Synchronized private fun connectionDetail(selected: CompanionLink, generation: Long, detail: String) {
        if (connectionCurrent(selected, generation)) mutableState.value = CompanionState(linked = true, detail = detail)
    }

    internal fun controlRetryDelay(failures: Int): Long =
        longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)[failures.coerceIn(0, 5)]

    private fun authenticate(socket: BluetoothSocket, selected: CompanionLink): Pair<String, Int> {
        val nonce = CompanionProtocol.nonce()
        CompanionProtocol.write(socket.outputStream, CompanionProtocol.frame("hello", selected.device).put("nonce", nonce).put("caps", CompanionProtocol.CAPS))
        val challenge = CompanionProtocol.read(socket.inputStream)
        CompanionProtocol.validate(challenge, selected.device)
        require(challenge.getString("type") == "challenge")
        val transcript = CompanionProtocol.transcript(selected.device, nonce, challenge.getString("nonce"),
            CompanionProtocol.CAPS, challenge.getString("caps"))
        val sid = CompanionProtocol.sid(transcript)
        require(challenge.getString("sid") == sid && CompanionProtocol.verifyProof(
            CompanionProtocol.proof(selected.key, "server", transcript), challenge.getString("proof"))) { "PC authentication failed" }
        CompanionProtocol.write(socket.outputStream, CompanionProtocol.frame("auth", selected.device, sid = sid)
            .put("proof", CompanionProtocol.proof(selected.key, "client", transcript)))
        val accepted = CompanionProtocol.read(socket.inputStream)
        CompanionProtocol.validate(accepted, selected.device, sid)
        require(accepted.getString("type") == "authenticated")
        return sid to (CompanionProtocol.capabilities(challenge.getString("caps")) and CompanionProtocol.capabilities(CompanionProtocol.CAPS))
    }

    fun transfer(target: String) {
        require(target in setOf("pc", "android"))
        invalidate()
        val active = session ?: return
        if (active.transaction != null || pendingSwitch != null || !active.live.get()) return
        val id = CompanionProtocol.nonce(8).takeIf { it != CompanionProtocol.HANDSHAKE_TX } ?: "0000000000000001"
        if (active.transactions.size >= 128) { disconnect("Reconnect to PC before another transfer."); return }
        active.transactions += id
        val pending = PendingSwitch(id, target)
        pendingSwitch = pending
        mutableState.value = state.value.copy(busy = true, detail = "Requesting audio transfer…")
        scope.launch {
            send(active, CompanionProtocol.frame("switch", active.link.device, id, active.sid).put("target", target))
            delay(30_000)
            if (pendingSwitch === pending && active.transaction == null && session === active) {
                pendingSwitch = null
                send(active, CompanionProtocol.frame("cancel", active.link.device, id, active.sid).put("reason", "timeout"))
                mutableState.value = state.value.copy(busy = false, detail = "PC did not start the transfer. Try again.")
            }
        }
    }

    private suspend fun handle(active: Session, frame: JSONObject) {
        val observedGeneration = intentGeneration
        val id = frame.getString("tx")
        if (id != CompanionProtocol.HANDSHAKE_TX && frame.getString("type") != "prepare" &&
            active.transaction?.id != id && pendingSwitch?.id != id && id !in active.transactions) {
            require(active.transactions.size < 128) { "Companion session transaction limit" }
            active.transactions += id
        }
        when (frame.getString("type")) {
            "ping" -> sendStatus(active)
            "status" -> Unit
            "pref_ack" -> {
                require(active.caps and 1 != 0)
                if (frame.getString("code") == "failed") active.failedPreferences += frame.getString("key")
                else active.failedPreferences -= frame.getString("key")
                mutableState.value = state.value.copy(settingsIssue = active.failedPreferences.isNotEmpty())
            }
            "pref" -> {
                require(active.caps and 1 != 0)
                val incoming = CompanionPreferences.decode(frame)
                val (code, winner) = preferences?.receive(active.link.device, incoming) ?: ("unsupported" to null)
                if (code == "failed") active.failedPreferences += incoming.key else active.failedPreferences -= incoming.key
                mutableState.value = state.value.copy(settingsIssue = active.failedPreferences.isNotEmpty())
                send(active, CompanionProtocol.frame("pref_ack", active.link.device, sid = active.sid)
                    .put("key", incoming.key).put("rev", frame.getString("rev")).put("origin", incoming.origin).put("code", code))
                val offer = "${incoming.key}:${frame.getString("rev")}:${incoming.origin}"
                if (winner != null && active.offeredWinners.add(offer)) {
                    require(active.offeredWinners.size <= 128)
                    send(active, winner.frame(active.link.device, active.sid))
                }
            }
            "call_origin" -> {
                require(active.caps and 2 != 0)
                active.callOrigin = frame.getString("origin_token").takeIf { frame.getBoolean("ready") }
                incoming?.takeIf { it.active === active }?.let(::admitIncoming)
            }
            "media_query" -> {
                require(active.caps and 4 != 0) { "Media transfer was not negotiated" }
                if (active.mediaSampling) return
                active.mediaSampling = true
                scope.launch {
                    try {
                        val value = sampleMedia(active)
                        if (session !== active || !active.live.get()) return@launch
                        send(active, CompanionProtocol.frame("media_state", active.link.device, sid = active.sid)
                            .put("probe", frame.getString("probe")).put("generation", value.generation.toString(16).padStart(16, '0'))
                            .put("event", value.event.toString(16).padStart(16, '0')).put("known", value.sample.known)
                            .put("playing", value.sample.playing).put("connected", value.sample.connected)
                            .put("available", value.sample.available && (!value.sample.playing || value.sample.eligible)))
                    } finally { active.mediaSampling = false }
                }
            }
            "prepare" -> {
                val context = CompanionCallContext(frame.getString("purpose"), frame.getString("episode"),
                    frame.getString("call_gen"), frame.getString("origin_token"))
                val lease = call
                if (context.purpose == "media" && (active.caps and 4 == 0 || lease != null)) {
                    result(active, id, "prepare", "superseded"); return
                }
                if (context.purpose !in setOf("manual", "media") && (active.caps and 2 == 0 || lease == null || !callCurrent(lease) ||
                    context.copy(purpose = "call_takeover") != lease.context ||
                    context.purpose == "call_takeover" && (lease.ended || lease.checking || lease.state !in setOf("ringing", "active")) ||
                    context.purpose == "call_return" && (!lease.ended || lease.state != "idle" || !callReturnAllowed(active.link.device)))) {
                    result(active, id, "prepare", "superseded"); return
                }
                if (context.purpose == "manual" && lease != null) { invalidate(); result(active, id, "prepare", "superseded"); return }
                val pending = pendingSwitch
                require(id !in active.transactions || pending?.id == id) { "Replayed transaction" }
                if (id !in active.transactions) {
                    require(active.transactions.size < 128) { "Companion session transaction limit" }
                    active.transactions += id
                }
                if (active.transaction != null) { result(active, id, "prepare", "busy"); return }
                if (pending != null && (pending.id != id || pending.target != frame.getString("target"))) {
                    if (pending.id == id) {
                        pendingSwitch = null
                        mutableState.value = state.value.copy(busy = false, detail = "PC requested a different destination. Try again.")
                    }
                    result(active, id, "prepare", "superseded"); return
                }
                pendingSwitch = null
                val tx = synchronized(this) {
                    if (observedGeneration != intentGeneration || active.transaction != null) null
                    else Transaction(id, frame.getString("target"), observedGeneration, context,
                        frame.optString("media_gen").takeIf { it.isNotEmpty() }).also { active.transaction = it }
                }
                if (tx == null) { result(active, id, "prepare", "superseded"); return }
                if (tx.call.purpose == "media") tx.mediaWatch = scope.launch {
                    while (reservationCurrent(active, tx)) {
                        sampleMedia(active)
                        if (!reservationCurrent(active, tx)) break
                        if (tx.stage in setOf("ready", "release", "acquire", "done") && !current(active, tx)) {
                            tx.mediaWatch = null
                            cancel(active, "superseded", tx); break
                        }
                        delay(250)
                    }
                }
                mutableState.value = state.value.copy(busy = true, detail = "Preparing audio transfer…")
                Log.i("CompanionHandoff", "phase=prepare event=start")
                tx.work = scope.launch {
                    val available = try {
                        if (context.purpose == "media" && !mediaPermits(active, tx)) CompanionStatus(false, true, false)
                        else if (context.purpose == "call_takeover") callStatus(active.link.device) { current(active, tx) }
                        else if (context.purpose == "call_return" && !phoneQuiet(active.link.device) { current(active, tx) }) CompanionStatus(false, true, false)
                        else status(active.link.device) { current(active, tx) }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { CompanionStatus(false, false, false) }
                    if (!reservationCurrent(active, tx)) return@launch
                    val code = if (available.busy) "busy" else if (!available.available) "unavailable" else "ready"
                    tx.stage = if (code == "ready") "ready" else "failed"
                    tx.phaseStarted = SystemClock.elapsedRealtime()
                    result(active, id, "prepare", code)
                }
            }
            "release", "acquire" -> {
                val phase = frame.getString("type")
                val tx = active.transaction
                if (tx == null || tx.id != id || tx.stage != "ready" ||
                    (phase == "release") != (tx.target == "pc")) {
                    result(active, id, phase, "invalid_state"); return
                }
                tx.stage = phase
                if (phase == "acquire" && tx.call.purpose == "call_takeover") call?.acquireStarted = true
                tx.phaseStarted = SystemClock.elapsedRealtime()
                Log.i("CompanionHandoff", "phase=$phase event=start")
                mutableState.value = state.value.copy(detail = if (phase == "release") "Releasing phone audio…" else "Connecting phone audio…")
                tx.work = scope.launch {
                    val code = try {
                        withTimeoutOrNull(30_000) {
                            if (tx.call.purpose == "media" && !mediaPermits(active, tx)) "superseded"
                            else if (tx.call.purpose == "call_takeover" && phase == "acquire")
                                callOperation(active.link.device) { current(active, tx) }
                            else operation(phase, active.link.device) { current(active, tx) }
                        } ?: "timeout"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { "failed" }
                    if (!reservationCurrent(active, tx)) return@launch
                    val confirmed = if (code in setOf("released", "acquired", "hfp_ready") && !current(active, tx)) "superseded" else code
                    tx.stage = if (confirmed in setOf("released", "acquired", "hfp_ready")) "done" else "failed"
                    result(active, id, phase, confirmed)
                }
            }
            "cancel" -> {
                if (active.transaction?.id != id && pendingSwitch?.id != id) return
                retire(active)
                call = null
                result(active, id, "cancel", "cancelled")
                mutableState.value = state.value.copy(busy = false, detail = "Transfer cancelled. Choose where to listen to try again.")
            }
            "result" -> {
                synchronized(this) {
                    require(frame.getString("phase") == "complete") { "Unexpected PC result" }
                    if (active.transaction?.id != id && pendingSwitch?.id != id) return
                    val tx = active.transaction
                    val lease = call
                    val expected = if (tx?.call?.purpose == "call_takeover") "hfp_ready" else "acquired"
                    val ok = frame.getBoolean("ok") && frame.getString("code") == expected
                    require(!frame.getBoolean("ok") || frame.getString("code") == expected)
                    require(!ok || active.transaction?.stage == "done") { "Premature completion" }
                    if (ok && tx?.call?.purpose == "call_takeover") call?.hadHfp = true
                    val retryIncoming = !ok && tx == null && pendingSwitch?.id == id && lease != null &&
                        lease.active === active && !lease.acquireStarted && !lease.hadHfp && !lease.hadSco &&
                        incomingCurrent(lease.incoming) && lease.incoming.origins.size == 1
                    retire(active)
                    if (retryIncoming && call === lease) lease.rejectedBeforePrepare = true
                    if (tx?.call?.purpose == "call_return") call = null
                    mutableState.value = state.value.copy(busy = false,
                        detail = if (ok) "Audio transfer complete." else "Audio transfer stopped. Choose where to listen to try again.")
                }
                sendStatus(active)
                call?.let { updateCall(it) }
            }
            else -> error("Unexpected PC command")
        }
    }

    private fun reservationCurrent(active: Session, tx: Transaction): Boolean = !closed && session === active && active.live.get() &&
        active.transaction === tx && tx.generation == intentGeneration && tx.live.get() && SystemClock.elapsedRealtime() - tx.started < 75_000 &&
        SystemClock.elapsedRealtime() - tx.phaseStarted < 30_000 && (tx.call.purpose in setOf("manual", "media") ||
            call?.let { callCurrent(it) && if (tx.call.purpose == "call_takeover") !it.ended && !it.checking && it.state in setOf("ringing", "active")
                else it.ended && it.state == "idle" } == true)

    private fun current(active: Session, tx: Transaction): Boolean = reservationCurrent(active, tx) &&
        (tx.call.purpose != "media" || SystemClock.elapsedRealtime() - active.mediaObservedAt < 1000 && active.media.latest?.let { value ->
                value.sample.known && if (tx.target == "android") value.sample.playing &&
                    value.generation.toString(16).padStart(16, '0') == tx.mediaGeneration
                else (tx.sourceGeneration == null || tx.sourceGeneration == value.generation) &&
                    (!value.sample.playing || !value.sample.connected)
            } == true)

    private suspend fun sampleMedia(active: Session) = active.mediaLock.withLock {
        val value = try { withTimeoutOrNull(1200) { media(active.link.device) } ?: CompanionMediaSample() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CompanionMediaSample() }
        active.media.observe(value).also { active.mediaObservedAt = SystemClock.elapsedRealtime() }
    }

    private suspend fun mediaPermits(active: Session, tx: Transaction): Boolean {
        val value = sampleMedia(active)
        if (tx.target == "pc" && tx.sourceGeneration == null && value.sample.known &&
            (!value.sample.playing || !value.sample.connected)) tx.sourceGeneration = value.generation
        return current(active, tx) && value.sample.known && if (tx.target == "android")
            value.sample.playing && value.sample.available && value.sample.eligible && value.generation.toString(16).padStart(16, '0') == tx.mediaGeneration
        else tx.sourceGeneration == value.generation && (!value.sample.playing || !value.sample.connected)
    }

    private suspend fun result(active: Session, id: String, phase: String, code: String) {
        Log.i("CompanionHandoff", "phase=$phase event=result code=$code")
        send(active, CompanionProtocol.frame("result", active.link.device, id, active.sid).put("phase", phase)
            .put("ok", code in setOf("ready", "released", "acquired", "hfp_ready", "cancelled")).put("code", code))
    }

    private suspend fun sendStatus(active: Session) {
        if (session !== active || !active.live.get()) return
        val value = status(active.link.device) { session === active && active.live.get() && active.transaction == null }
        send(active, CompanionProtocol.frame("status", active.link.device, sid = active.sid)
            .put("connected", value.connected).put("busy", value.busy || active.transaction != null)
            .put("can_release", value.available).put("can_acquire", value.available))
    }

    private suspend fun send(active: Session, frame: JSONObject) {
        if (!active.live.get()) return
        withContext(Dispatchers.IO) {
            val failed = synchronized(writeLock) {
                if (!active.live.get()) false else try {
                    CompanionProtocol.write(active.socket.outputStream, frame)
                    false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (error is IOException) active.transportStopped = true else active.protocolStopped = true
                    active.live.set(false)
                    true
                }
            }
            if (failed) runCatching { active.socket.close() }
        }
    }

    @Synchronized private fun retire(active: Session): String? {
        val id = active.transaction?.id ?: pendingSwitch?.id
        active.transaction?.let { it.live.set(false); it.work?.cancel(); it.mediaWatch?.cancel() }
        active.transaction = null
        pendingSwitch = null
        return id
    }

    private suspend fun cancel(active: Session, reason: String, expected: Transaction? = null) {
        val id = synchronized(this) {
            if (expected != null && active.transaction !== expected) null else retire(active)?.also {
                mutableState.value = state.value.copy(busy = false, detail = "Transfer stopped. Choose where to listen to try again.")
            }
        } ?: return
        Log.i("CompanionHandoff", "event=cancel reason=$reason")
        send(active, CompanionProtocol.frame("cancel", active.link.device, id, active.sid).put("reason", reason))
    }

    /** A local route action invalidates the remote operation before that action can start. */
    @Synchronized fun invalidate() {
        intentGeneration++
        if (session == null && connection?.isActive == true) {
            disconnect("PC connection superseded by a phone action.")
            reconnect()
        }
        incoming = null
        val previousCall = call.also { call = null }
        previousCall?.let { scope.launch { send(it.active, callFrame("call_end", it).put("reason", "invalidated")) } }
        val active = session ?: return
        val id = retire(active) ?: return
        Log.i("CompanionHandoff", "event=superseded")
        mutableState.value = state.value.copy(busy = false, detail = "Transfer superseded by a phone action.")
        scope.launch { send(active, CompanionProtocol.frame("cancel", active.link.device, id, active.sid).put("reason", "superseded")) }
    }

    fun cancelTransfer() {
        if (call != null) { invalidate(); return }
        val active = session ?: return
        scope.launch { cancel(active, "user") }
    }

    fun bluetoothChanged() {
        pairing.bluetoothChanged()
        val selected = link ?: return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            disconnect("Allow Nearby devices access, then tap Reconnect.")
            return
        }
        val valid = try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            adapter.isEnabled && adapter.bondedDevices.any { it.address == selected.peer } &&
                adapter.bondedDevices.any { it.address == selected.device }
        } catch (_: SecurityException) { false }
        if (!valid) disconnect("Bluetooth is unavailable. Pair both devices, then tap Reconnect.")
    }

    @Synchronized private fun disconnect(detail: String) {
        ++epoch
        incoming = null
        call = null
        session?.let { it.live.set(false); retire(it) }
        session = null
        connection?.cancel()
        connection = null
        runCatching { socket?.close() }
        socket = null
        mutableState.value = CompanionState(linked = link != null, detail = detail)
    }

    override fun close() {
        closed = true
        pairing.close()
        disconnect("PC link stopped")
        link?.key?.fill(0)
    }
}
