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
import com.topjohnwu.superuser.Shell
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class BridgeState(
    val availability: String = "Shizuku is not running",
    val connected: Boolean = false,
    val detail: String = "",
    val backend: String? = null,
)

/** Owned by the foreground service. Nothing is captured or routed just by creating this client. */
class ShizukuAudioClient(context: Context) : Closeable {
    private class PlaybackRequest(val remote: IAudioBridge, val address: String,
        @Volatile private var stillCurrent: () -> Boolean, private val job: Job?, private val helperUid: Int) {
        val active = AtomicBoolean(true)
        fun updateCurrent(current: () -> Boolean) { stillCurrent = current }
        val owner = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent() = AudioBridgeRules.helperOwnsRequest(helperUid, Binder.getCallingUid()) && active.get() &&
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
    private var volumeRequest: Pair<Any, PlaybackRequest>? = null
    @Volatile private var bridge: IAudioBridge? = null
    @Volatile private var backendUid: Int? = null
    @Volatile private var rootBackend = false
    val isRoot: Boolean get() = !closed && bridge != null && rootBackend
    @Volatile private var closed = false
    private var connecting = false
    private var bound = false
    private var settingsRefresh = false
    private var rootTried = false
    private var rootPermissionRequested = false
    private val rootPreferences = this.context.getSharedPreferences("privileged_audio", Context.MODE_PRIVATE)
    private var bridgeDeath: IBinder.DeathRecipient? = null
    private val root = RootAudioConnection(this.context,
        connected = { service ->
            rootPreferences.edit().putBoolean("authorized_root", true).apply()
            rootPermissionRequested = false
            acceptBridge(service, 0, fromRoot = true)
        },
        disconnected = { if (rootBackend) disconnected("Root audio service stopped") },
        unavailable = {
            val requestFallback = synchronized(lock) {
                connecting = false
                rootPermissionRequested.also { rootPermissionRequested = false }
            }
            if (requestFallback) requestShizukuPermission() else connectShizuku()
        })
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
                if (rootBackend) {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    bound = false
                    return
                }
                val helperUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                if (!AudioBridgeRules.helperOwnsRequest(helperUid, helperUid)) {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    disconnected("Unsupported Shizuku identity")
                    return
                }
                acceptBridge(service, helperUid, fromRoot = false)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (!rootBackend && bridge != null) disconnected("Shizuku audio service stopped")
        }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        refreshAvailability()
        if (connecting) connect()
    }
    private val dead = Shizuku.OnBinderDeadListener {
        if (!rootBackend && bridge != null) disconnected("Shizuku stopped; restart it and connect again")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST && !closed) {
            if (result == PackageManager.PERMISSION_GRANTED) connectShizuku()
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
        if (isRoot) return
        synchronized(lock) {
            if (connecting || bridge != null) return
            connecting = true
            rootTried = true
            rootPermissionRequested = true
        }
        mutableState.update { it.copy(availability = "Requesting root access") }
        root.start()
    }

    private fun requestShizukuPermission() {
        if (closed) return
        runCatching {
            check(Shizuku.pingBinder()) { "Start Shizuku before connecting" }
            check(!Shizuku.isPreV11()) { "Update Shizuku to version 13 or newer" }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connectShizuku()
            else Shizuku.requestPermission(PERMISSION_REQUEST)
        }.onFailure { error -> mutableState.update { it.copy(availability = error.message.orEmpty()) } }
    }

    fun connect() {
        synchronized(lock) {
            if (closed || bridge != null || bound || connecting) return
            if (AudioBridgeRules.mayReconnectRoot(Shell.isAppGrantedRoot(),
                    rootPreferences.getBoolean("authorized_root", false), attempted = rootTried)) {
                rootTried = true
                connecting = true
                mutableState.update { it.copy(availability = "Starting root audio service") }
                root.start()
                return
            }
        }
        connectShizuku()
    }

    private fun connectShizuku() {
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

    private fun acceptBridge(service: IBinder, uid: Int, fromRoot: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        val recipient = IBinder.DeathRecipient {
            disconnected("Audio service stopped", expected = service)
        }
        try {
            service.linkToDeath(recipient, 0)
            bridgeDeath = recipient
            backendUid = uid
            rootBackend = fromRoot
            bridge = IAudioBridge.Stub.asInterface(service)
            connecting = false
            android.util.Log.i("BluetoothAccess", "Connected: transport=${if (fromRoot) "root" else "shizuku"} helperUid=$uid")
            mutableState.update { it.copy(connected = true, detail = "",
                backend = if (fromRoot) "root" else "shizuku",
                availability = if (fromRoot) "Connected through root" else "Connected through Shizuku") }
        } catch (_: Exception) { disconnected("Audio service stopped before connecting") }
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
            PlaybackRequest(checkNotNull(bridge), A2dpRecoveryGuard(address).address, stillCurrent, null, checkNotNull(backendUid))
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
                    PlaybackRequest(checkNotNull(bridge), A2dpRecoveryGuard(address).address, stillCurrent, callerJob, checkNotNull(backendUid))
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
                    PlaybackRequest(checkNotNull(bridge) { "Connect privileged access to restore playback" },
                        A2dpRecoveryGuard(address).address, stillCurrent, callerJob, checkNotNull(backendUid)).also { playbackRequest = it }
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
                                { pairingAllowed(remote) && stillCurrent() }, callerJob, checkNotNull(backendUid)).also { pairingRequest = it }
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
            (isRoot || (!rootBackend && Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED))
    }.getOrDefault(false)

    /** The helper can authorize only this package's own CONTROL_AUDIO operation. */
    fun ensureOwnBackgroundAudio(): Boolean {
        val remote = bridge ?: return false
        return runCatching { !closed && remote.ensureOwnBackgroundAudio() && bridge === remote }.getOrDefault(false)
    }

    @androidx.annotation.WorkerThread
    fun checkVolumeSync(address: String, owner: Any, stillCurrent: () -> Boolean): Boolean {
        val selected = runCatching { A2dpRecoveryGuard(address).address }.getOrNull() ?: return false
        val request = synchronized(lock) {
            if (closed || bridge == null || settingsRefresh || playbackRequest != null) return false
            volumeRequest?.takeIf { it.first === owner && it.second.address == selected &&
                it.second.remote === bridge && it.second.active.get() }
                ?.second?.also { it.updateCurrent(stillCurrent) } ?: run {
                volumeRequest?.second?.active?.set(false)
                PlaybackRequest(checkNotNull(bridge), selected, stillCurrent, null, checkNotNull(backendUid))
                    .also { volumeRequest = owner to it }
            }
        }
        val completed = CountDownLatch(1)
        val result = AtomicBoolean(false)
        scope.launch {
            try {
                result.set(ipc.withLock {
                    request.active.get() && request.remote.checkVolumeSync(selected, request.owner)
                })
            } catch (_: Exception) { result.set(false) }
            finally { completed.countDown() }
        }
        return try {
            if (!completed.await(3_000, TimeUnit.MILLISECONDS)) {
                request.active.set(false)
                false
            } else result.get()
        } catch (_: InterruptedException) {
            request.active.set(false)
            Thread.currentThread().interrupt()
            false
        }
    }

    fun forgetVolumeSync(owner: Any) = forgetVolumeSyncMatching { it.first === owner }
    fun forgetVolumeSync(address: String) = forgetVolumeSyncMatching { it.second.address.equals(address, true) }
    private fun forgetVolumeSyncMatching(matches: (Pair<Any, PlaybackRequest>) -> Boolean) {
        val request = synchronized(lock) {
            volumeRequest?.takeIf(matches)?.second?.also { it.active.set(false); volumeRequest = null }
        } ?: return
        scope.launch { ipc.withLock { runCatching { request.remote.forgetVolumeSync(request.owner) } } }
    }

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
        if (closed || bridge != null || connecting) return
        val message = runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku is available; connect to use audio controls"
            else "Shizuku is available; permission required"
        }.getOrDefault("Shizuku is not running")
        mutableState.update { it.copy(availability = message) }
    }

    private fun disconnected(reason: String, expected: IBinder? = null) {
        synchronized(lock) {
            if (expected != null && bridge?.asBinder() !== expected) return
            if (rootBackend) {
                rootTried = false
                root.retireBinding()
            }
            mediaRequest?.active?.set(false)
            mediaRequest = null
            pairingRequest?.active?.set(false)
            pairingRequest = null
            cancelPlaybackRecoveryLocked()
            volumeRequest?.second?.active?.set(false)
            volumeRequest = null
            bridgeDeath?.let { death -> bridge?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } } }
            bridgeDeath = null
            bridge = null
            backendUid = null
            rootBackend = false
            connecting = false
            bound = false
            mutableState.update { it.copy(connected = false, backend = null, availability = reason, detail = reason) }
        }
    }

    override fun close() {
        val retiring = mutableListOf<PlaybackRequest>()
        synchronized(lock) {
            if (closed) return
            closed = true
            mediaRequest?.also { it.active.set(false); retiring += it }
            mediaRequest = null
            pairingRequest?.active?.set(false)
            pairingRequest = null
            cancelPlaybackRecoveryLocked()
            volumeRequest?.second?.also { it.active.set(false); retiring += it }
            volumeRequest = null
            bridgeDeath?.let { death -> bridge?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } } }
            bridgeDeath = null
            // The tag is unique to this foreground-service instance; a late close cannot kill its replacement.
            if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
            bound = false
            bridge = null
            backendUid = null
            rootBackend = false
        }
        // A one-way owner-specific release cannot close another client's shared root bridge.
        retiring.forEach { runCatching { it.remote.retireOwner(it.owner) } }
        root.close()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        scope.cancel()
    }

    private companion object {
        const val PERMISSION_REQUEST = 18570
    }
}
