package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import me.kavishdevar.librepods.BuildConfig
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class BridgeState(
    val availability: String = "Shizuku is not running",
    val connected: Boolean = false,
    val detail: String = "",
)

/** Owned by the foreground service. Nothing is captured or routed just by creating this client. */
class ShizukuAudioClient(context: Context) : Closeable {
    private class PlaybackRequest(val remote: IAudioBridge, val address: String,
        private val stillCurrent: () -> Boolean, private val job: Job?) {
        val active = AtomicBoolean(true)
        val owner = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent() = Binder.getCallingUid() == 2000 && active.get() &&
                job?.isActive != false && runCatching { stillCurrent() }.getOrDefault(false)
        }
    }

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutableState.asStateFlow()
    private val lock = Any()
    private val ipc = Mutex()
    private var playbackRequest: PlaybackRequest? = null
    private var pairingRequest: PlaybackRequest? = null
    private var mediaRequest: PlaybackRequest? = null
    @Volatile private var bridge: IAudioBridge? = null
    @Volatile private var closed = false
    private var connecting = false
    private var bound = false
    private var settingsRefresh = false
    private val args = Shizuku.UserServiceArgs(ComponentName(this.context, ShizukuAudioService::class.java))
        .daemon(false)
        .processNameSuffix("audio_bridge")
        .tag("librepods.audio.bridge.${android.os.Process.myUid()}.${UUID.randomUUID()}")
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                if (closed) {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    return
                }
                bridge = IAudioBridge.Stub.asInterface(service)
                connecting = false
                mutableState.update { it.copy(connected = true, availability = "Connected through Shizuku") }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected("Shizuku audio service stopped")
    }
    private val received = Shizuku.OnBinderReceivedListener {
        refreshAvailability()
        if (connecting) connect()
    }
    private val dead = Shizuku.OnBinderDeadListener { disconnected("Shizuku stopped; restart it and connect again") }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST && !closed) {
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else mutableState.update { it.copy(availability = "Shizuku permission denied") }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun requestPermission() {
        if (closed) return
        runCatching {
            check(Shizuku.pingBinder()) { "Start Shizuku before connecting" }
            check(!Shizuku.isPreV11()) { "Update Shizuku to version 13 or newer" }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect()
            else Shizuku.requestPermission(PERMISSION_REQUEST)
        }.onFailure { error -> mutableState.update { it.copy(availability = error.message.orEmpty()) } }
    }

    fun connect() {
        runCatching {
            synchronized(lock) {
                if (closed || bridge != null || bound) return
                check(Shizuku.pingBinder()) { "Start Shizuku before connecting" }
                check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Allow LibrePods in Shizuku first" }
                check(Shizuku.getVersion() >= 13) { "Update Shizuku to version 13 or newer" }
                connecting = true
                bound = true
                mutableState.update { it.copy(availability = "Starting audio service") }
            }
            Shizuku.bindUserService(args, connection)
        }.onFailure { error ->
            synchronized(lock) {
                connecting = false
                bound = false
                if (!closed) mutableState.update { it.copy(availability = error.message.orEmpty()) }
            }
        }
    }

    /** Restarts only the selected pair's native A2DP profile after the AirPods uplink has stopped. */
    suspend fun restoreNativePlayback(address: String, stillCurrent: () -> Boolean = { true }): Boolean =
        nativePlayback(address, stillCurrent, connectOnly = false)

    /** Explicit takeover joins Android's pending connection and never cycles an already connected profile. */
    suspend fun connectNativePlayback(address: String, stillCurrent: () -> Boolean): Boolean =
        nativePlayback(address, stillCurrent, connectOnly = true)

    suspend fun releaseNativePlayback(address: String, stillCurrent: () -> Boolean): Boolean =
        nativePlayback(address, stillCurrent, connectOnly = false, releaseOnly = true)

    suspend fun inspectNativePlayback(address: String, stillCurrent: () -> Boolean): Boolean =
        nativePlayback(address, stillCurrent, connectOnly = false, inspectOnly = true)

    /** Its owner outlives the pause IPC, and is retired synchronously on a newer local choice. */
    suspend fun pauseOwnedMedia(address: String, stillCurrent: () -> Boolean): Boolean {
        val request = synchronized(lock) {
            check(!closed && playbackRequest == null && !settingsRefresh)
            mediaRequest?.active?.set(false)
            PlaybackRequest(checkNotNull(bridge), A2dpRecoveryGuard(address).address, stillCurrent, null)
                .also { mediaRequest = it }
        }
        return try { withContext(Dispatchers.IO) { ipc.withLock {
            request.active.get() && request.remote.pauseOwnedMedia(request.address, request.owner)
        } } } catch (cancelled: CancellationException) { request.active.set(false); throw cancelled }
        catch (_: Exception) { request.active.set(false); false }
    }

    suspend fun finishOwnedMedia(resume: Boolean): Boolean {
        val request = synchronized(lock) { mediaRequest.also { mediaRequest = null } } ?: return false
        return try { withContext(Dispatchers.IO) { ipc.withLock {
            request.remote.finishOwnedMedia(request.owner, resume && request.active.get())
        } } } catch (_: Exception) { false } finally { request.active.set(false) }
    }

    fun cancelOwnedMedia() {
        val request = synchronized(lock) { mediaRequest.also { it?.active?.set(false); mediaRequest = null } } ?: return
        scope.launch { ipc.withLock { runCatching { request.remote.finishOwnedMedia(request.owner, false) } } }
    }

    /** A read-only receipt also drains a cancelled connect on this bridge before it can report quiet. */
    suspend fun nativeCall(address: String, connect: Boolean, stillCurrent: () -> Boolean): Int = try {
        withTimeoutOrNull(if (connect) 22_000L else 4_000L) {
            val callerJob = currentCoroutineContext()[Job]
            suspendCancellableCoroutine { continuation ->
                val request = synchronized(lock) {
                    check(!closed && !settingsRefresh)
                    cancelPlaybackRecoveryLocked()
                    PlaybackRequest(checkNotNull(bridge), A2dpRecoveryGuard(address).address, stillCurrent, callerJob)
                        .also { playbackRequest = it }
                }
                continuation.invokeOnCancellation { request.active.set(false) }
                scope.launch {
                    val value = try { ipc.withLock {
                        if (request.active.get()) request.remote.nativeCall(request.address, request.owner, connect) else 0
                    } } catch (_: Exception) { 0 } finally {
                        request.active.set(false)
                        synchronized(lock) { if (playbackRequest === request) playbackRequest = null }
                    }
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        } ?: 0
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { 0 }

    private suspend fun nativePlayback(address: String, stillCurrent: () -> Boolean, connectOnly: Boolean,
        releaseOnly: Boolean = false, inspectOnly: Boolean = false): Boolean = try {
        withTimeoutOrNull(if (inspectOnly) 3_000L else if (releaseOnly) 9_500L else if (connectOnly) 19_000L else 6500L) {
            val callerJob = currentCoroutineContext()[Job]
            suspendCancellableCoroutine { continuation ->
                val request = synchronized(lock) {
                    check(!closed && !settingsRefresh) { "Another audio operation is active" }
                    cancelPlaybackRecoveryLocked()
                    PlaybackRequest(checkNotNull(bridge) { "Connect Shizuku to restore playback" },
                        A2dpRecoveryGuard(address).address, stillCurrent, callerJob).also { playbackRequest = it }
                }
                continuation.invokeOnCancellation {
                    request.active.set(false)
                    synchronized(lock) { if (playbackRequest === request) playbackRequest = null }
                }
                scope.launch {
                    val result = try {
                        ipc.withLock {
                            if (!request.active.get()) false
                            else if (inspectOnly) request.remote.inspectNativePlayback(request.address, request.owner)
                            else if (releaseOnly) request.remote.releaseNativePlayback(request.address, request.owner)
                            else if (connectOnly) request.remote.connectNativePlayback(request.address, request.owner)
                            else request.remote.restoreNativePlayback(request.address, request.owner)
                        }
                    } catch (error: Exception) {
                        synchronized(lock) {
                            if (!closed && playbackRequest === request) mutableState.update { it.copy(detail = error.message.orEmpty()) }
                        }
                        false
                    } finally {
                        request.active.set(false)
                        synchronized(lock) { if (playbackRequest === request) playbackRequest = null }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } ?: false
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        synchronized(lock) { if (!closed) mutableState.update { it.copy(detail = error.message.orEmpty()) } }
        false
    }

    /** Optional consent assistance: never waits for binding, asks permission or starts an audio route. */
    suspend fun confirmPairing(address: String, stillCurrent: () -> Boolean): Boolean {
        val remote = bridge
        if (remote == null) {
            android.util.Log.i("NearbyPairing", "Consent bridge unavailable: connected=false")
            return false
        }
        val allowed = pairingAllowed(remote)
        val ownerCurrent = if (allowed) runCatching(stillCurrent).getOrDefault(false) else null
        if (ownerCurrent != true) {
            android.util.Log.i("NearbyPairing", "Consent bridge declined: allowed=$allowed owner=$ownerCurrent")
            return false
        }
        val selected = runCatching { A2dpRecoveryGuard(address).address }.getOrNull() ?: return false
        return try {
            withTimeoutOrNull(1_200) {
                val callerJob = currentCoroutineContext()[Job]
                suspendCancellableCoroutine { continuation ->
                    val request = synchronized(lock) {
                        if (closed || bridge !== remote) null else {
                            pairingRequest?.active?.set(false)
                            PlaybackRequest(remote, selected,
                                { pairingAllowed(remote) && stillCurrent() }, callerJob).also { pairingRequest = it }
                        }
                    }
                    if (request == null) { continuation.resume(false); return@suspendCancellableCoroutine }
                    continuation.invokeOnCancellation { request.active.set(false) }
                    scope.launch {
                        val result = try {
                            if (!request.active.get() || !pairingAllowed(remote)) false
                            else request.remote.confirmPairing(request.address, request.owner)
                        } catch (error: Exception) {
                            android.util.Log.i("NearbyPairing", "Consent bridge failed: ${error.javaClass.simpleName}")
                            false
                        }
                        finally {
                            request.active.set(false)
                            synchronized(lock) { if (pairingRequest === request) pairingRequest = null }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            } ?: false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }

    internal fun canConfirmPairing(): Boolean = bridge?.let(::pairingAllowed) == true

    private fun pairingAllowed(remote: IAudioBridge): Boolean = !closed && bridge === remote && runCatching {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun cancelPlaybackRecovery() = synchronized(lock) { cancelPlaybackRecoveryLocked() }

    fun canRefreshSettings(): Boolean = synchronized(lock) {
        !closed && !settingsRefresh && playbackRequest == null && !ipc.isLocked
    }

    fun tryBeginSettingsRefresh(): Boolean = synchronized(lock) {
        if (!canRefreshSettings()) false else { settingsRefresh = true; true }
    }

    fun endSettingsRefresh() = synchronized(lock) { settingsRefresh = false }

    /** AACP does not use AudioRecord, so Android's software sensor privacy must be checked explicitly. */
    fun isMicrophonePrivacyBlocked(): Boolean {
        val remote = bridge ?: return true
        return runCatching {
            if (closed) true else remote.isMicrophonePrivacyBlocked() || bridge !== remote || closed
        }.getOrDefault(true)
    }

    private fun cancelPlaybackRecoveryLocked() {
        playbackRequest?.active?.set(false)
        playbackRequest = null
    }

    private fun refreshAvailability() {
        if (closed) return
        val message = runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku is available; connect to use audio controls"
            else "Shizuku is available; permission required"
        }.getOrDefault("Shizuku is not running")
        mutableState.update { it.copy(availability = message) }
    }

    private fun disconnected(reason: String) {
        synchronized(lock) {
            mediaRequest?.active?.set(false)
            mediaRequest = null
            pairingRequest?.active?.set(false)
            pairingRequest = null
            cancelPlaybackRecoveryLocked()
            bridge = null
            connecting = false
            bound = false
            mutableState.update { it.copy(connected = false, availability = reason, detail = reason) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            mediaRequest?.active?.set(false)
            mediaRequest = null
            pairingRequest?.active?.set(false)
            pairingRequest = null
            cancelPlaybackRecoveryLocked()
            // The tag is unique to this foreground-service instance; a late close cannot kill its replacement.
            if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
            bound = false
            bridge = null
        }
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        scope.cancel()
    }

    private companion object {
        const val PERMISSION_REQUEST = 18570
    }
}
