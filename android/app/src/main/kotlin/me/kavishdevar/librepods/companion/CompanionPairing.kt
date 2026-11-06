package me.kavishdevar.librepods.companion

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal object CompanionPairingProtocol {
    val SERVICE: UUID = UUID.fromString("bbc1e9f2-6f73-4c61-ae15-6e74706f6474")
    fun shortCode(value: String): String? = value.filterNot { it == ' ' || it == '-' }
        .takeIf { it.matches(Regex("[0-9]{8}")) }
    fun fullCode(value: String): String? = value.trim().lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    fun request(device: String, nonce: String, code: String): JSONObject {
        require(code.matches(Regex("[0-9]{8}")))
        CompanionProtocol.address(device); CompanionProtocol.hexBytes(nonce, 32)
        return CompanionProtocol.frame("pair_request", device).put("nonce", nonce).put("code", code)
    }
    fun validateRequest(frame: JSONObject, device: String) {
        common(frame, device, setOf("v", "type", "tx", "device", "nonce", "code"))
        require(frame.get("type") == "pair_request" && (frame.get("code") as? String)?.matches(Regex("[0-9]{8}")) == true)
        CompanionProtocol.hexBytes(frame.get("nonce") as? String ?: error("Invalid setup nonce"), 32)
    }
    private fun common(frame: JSONObject, device: String, keys: Set<String>) {
        require(frame.keys().asSequence().toSet() == keys && frame.get("v") == 2 &&
            frame.get("tx") == CompanionProtocol.HANDSHAKE_TX && frame.get("device") == device)
        CompanionProtocol.address(device)
    }
    fun validateReply(frame: JSONObject, device: String, nonce: String) {
        val type = frame.getString("type")
        val keys = setOf("v", "type", "tx", "device", "nonce") + when (type) {
            "pair_key" -> setOf("key")
            "pair_failed" -> setOf("code")
            else -> error("Invalid setup response")
        }
        common(frame, device, keys)
        require(frame.get("nonce") == nonce)
        CompanionProtocol.hexBytes(nonce, 32)
        if (type == "pair_failed") require(frame.get("code") == "failed")
        else CompanionProtocol.hexBytes(frame.get("key") as? String ?: error("Invalid setup key"), 32).fill(0)
    }
    fun key(frame: JSONObject, device: String, nonce: String): ByteArray {
        validateReply(frame, device, nonce)
        check(frame.get("type") == "pair_key") { "Setup failed" }
        return CompanionProtocol.hexBytes(frame.getString("key"), 32)
    }
}

internal data class CompanionPairingState(val owner: Any? = null, val busy: Boolean = false,
    val failed: Boolean = false, val complete: Boolean = false, val pendingPeer: String? = null, val pendingDevice: String? = null)

/** Setup owns its own socket; neither provisioning nor recovery admits an operational session. */
internal class CompanionPairingClient(
    private val scope: CoroutineScope,
    private val pendingStore: CompanionLinkStorage,
    private val socketFactory: (String, UUID) -> BluetoothSocket,
    private val bonded: (String, String) -> Boolean,
    private val fence: () -> (() -> Boolean),
    private val authenticate: (BluetoothSocket, CompanionLink) -> Unit,
    private val promote: suspend (CompanionLink, () -> Boolean, (() -> Boolean) -> Boolean) -> Boolean,
    private val timeoutMs: Long = 28_000,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : AutoCloseable {
    private val lock = Any()
    private val storage = Mutex()
    private val mutableState = MutableStateFlow(CompanionPairingState())
    val state = mutableState.asStateFlow()
    private var pending: CompanionLink? = null
    private var active: Attempt? = null
    private var closed = false
    private var pendingRetired = false
    private var discardedThrough = 0L
    private var pendingWrittenAt = 0L
    private var generation = 0L
    private class Attempt(val owner: Any, val generation: Long, val current: () -> Boolean,
        val peer: String, val device: String, val deadline: Long) {
        var socket: BluetoothSocket? = null
        var job: Job? = null
    }

    init {
        scope.launch {
            storage.withLock {
                val saved = runCatching { withContext(Dispatchers.IO) { pendingStore.load() } }.getOrNull()
                synchronized(lock) {
                    if (!closed && !pendingRetired && pending == null) {
                        pending = saved
                        mutableState.value = state.value.copy(pendingPeer = saved?.peer, pendingDevice = saved?.device)
                    } else saved?.key?.fill(0)
                }
            }
        }
    }

    fun start(owner: Any, peer: String, device: String, input: String) {
        val code = CompanionPairingProtocol.shortCode(input) ?: error("Invalid setup code")
        CompanionProtocol.address(peer); CompanionProtocol.address(device); require(peer != device)
        begin(owner, peer, device, code)
    }

    fun retry(owner: Any) {
        val saved = synchronized(lock) { pending?.let { it.copy(key = it.key.copyOf()) } } ?: return
        try { begin(owner, saved.peer, saved.device, null, saved) } finally { saved.key.fill(0) }
    }

    private fun begin(owner: Any, peer: String, device: String, code: String?, recovery: CompanionLink? = null) {
        val candidate = recovery?.copy(key = recovery.key.copyOf())
        val attempt: Attempt
        synchronized(lock) {
            if (closed) { candidate?.key?.fill(0); return }
            cancelLocked()
            attempt = Attempt(owner, ++generation, fence(), peer, device, now() + timeoutMs)
            active = attempt
            mutableState.value = state.value.copy(owner = owner, busy = true, failed = false, complete = false)
            attempt.job = scope.launch {
                var selected = candidate
                val guard = scope.launch {
                    while (isActive) {
                        val remaining = attempt.deadline - now()
                        if (remaining <= 0) { cancelAttempt(attempt, failed = true); return@launch }
                        if (!current(attempt)) { cancelAttempt(attempt, failed = now() >= attempt.deadline); return@launch }
                        delay(minOf(remaining, 100))
                    }
                }
                try {
                    check(current(attempt) && bonded(peer, device))
                    val captured = socketFactory(peer, if (code == null) CompanionProtocol.SERVICE else CompanionPairingProtocol.SERVICE)
                    synchronized(lock) {
                        if (!current(attempt)) { captured.close(); error("Setup retired") }
                        attempt.socket = captured
                    }
                    withContext(Dispatchers.IO) { captured.connect() }
                    check(current(attempt) && captured.remoteDevice.address == peer && bonded(peer, device))
                    if (code != null) {
                        val nonce = CompanionProtocol.nonce()
                        val key = withContext(Dispatchers.IO) {
                            CompanionProtocol.write(captured.outputStream, CompanionPairingProtocol.request(device, nonce, code))
                            CompanionPairingProtocol.key(CompanionProtocol.read(captured.inputStream), device, nonce)
                        }
                        selected = CompanionLink(peer, device, key)
                        check(current(attempt) && bonded(peer, device))
                        storage.withLock {
                            withContext(Dispatchers.IO) {
                                check(current(attempt))
                                pendingStore.save(selected!!)
                                synchronized(lock) {
                                    pendingWrittenAt = attempt.generation
                                    if (!closed && attempt.generation > discardedThrough) {
                                        pending?.key?.fill(0)
                                        pending = selected!!.copy(key = key.copyOf())
                                        mutableState.value = state.value.copy(pendingPeer = peer, pendingDevice = device)
                                    }
                                }
                            }
                        }
                    }
                    val link = checkNotNull(selected)
                    check(current(attempt) && bonded(peer, device))
                    withContext(Dispatchers.IO) { authenticate(captured, link) }
                    check(current(attempt) && bonded(peer, device))
                    runCatching { captured.close() }
                    val committed = promote(link, { current(attempt) }) { commit ->
                        synchronized(lock) {
                            if (!current(attempt) || !bonded(peer, device)) false
                            else {
                                guard.cancel()
                                val accepted = commit()
                                if (accepted && active === attempt) {
                                    active = null
                                    pending?.key?.fill(0); pending = null
                                    mutableState.value = CompanionPairingState(owner = owner, complete = true)
                                }
                                accepted
                            }
                        }
                    }
                    check(committed)
                    // Approval is already durable; cleanup failure must not turn success into a retry.
                    try {
                        storage.withLock {
                            withContext(Dispatchers.IO) {
                                synchronized(lock) {
                                    if (pendingWrittenAt <= attempt.generation) pendingStore.clear()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    synchronized(lock) {
                        if (active === attempt) mutableState.value = state.value.copy(busy = false, failed = true, complete = false)
                    }
                } finally {
                    guard.cancel()
                    selected?.key?.fill(0)
                    runCatching { attempt.socket?.close() }
                    synchronized(lock) {
                        if (active === attempt) { active = null; mutableState.value = state.value.copy(busy = false) }
                    }
                }
            }
        }
    }

    private fun current(attempt: Attempt) = synchronized(lock) {
        !closed && active === attempt && generation == attempt.generation && now() < attempt.deadline && attempt.current()
    }

    fun cancel(owner: Any, failed: Boolean = false) = synchronized(lock) {
        if (active?.owner === owner) {
            cancelLocked()
            mutableState.value = state.value.copy(busy = false, failed = failed, complete = false)
        }
    }

    private fun cancelAttempt(attempt: Attempt, failed: Boolean = false) = synchronized(lock) {
        if (active === attempt) cancel(attempt.owner, failed)
    }

    fun bluetoothChanged() {
        val attempt = synchronized(lock) { active } ?: return
        if (!bonded(attempt.peer, attempt.device)) cancelAttempt(attempt, failed = true)
    }

    private fun cancelLocked() {
        generation++
        active?.let { runCatching { it.socket?.close() }; it.job?.cancel() }
        active = null
    }

    fun discardPending() {
        val retired = synchronized(lock) {
            pendingRetired = true; cancelLocked(); pending?.key?.fill(0); pending = null
            mutableState.value = CompanionPairingState(); discardedThrough = generation; generation
        }
        scope.launch { storage.withLock { withContext(Dispatchers.IO) {
            synchronized(lock) { if (pendingWrittenAt <= retired) pendingStore.clear() }
        } } }
    }

    override fun close() = synchronized(lock) {
        closed = true; cancelLocked(); pending?.key?.fill(0); pending = null
    }
}
