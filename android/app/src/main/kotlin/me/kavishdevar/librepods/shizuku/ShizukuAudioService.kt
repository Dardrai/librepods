package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import me.kavishdevar.librepods.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.SystemServiceHelper

/** App-owned native audio controls and selected-pair media recovery. */
class ShizukuAudioService @JvmOverloads constructor(context: Context,
    private val exitOnDestroy: Boolean = true,
) : IAudioBridge.Stub(), AutoCloseable {
    private val originalContext = context
    private val expectedUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
    private val shellContext by lazy { ShellAudioContext(context).also { it.installProcessAttribution() } }
    private val lock = Any()
    private val cleanupFailed = AtomicBoolean(false)
    private var playbackRecovery: A2dpPlaybackRecovery? = null
    private var playbackRelease: NativePlaybackRelease? = null
    private var callConnection: NativeCallConnection? = null
    private var mediaPause: Pair<IPlaybackRecoveryOwner, OwnedMediaPause>? = null
    private var volumeChecking = false
    private val volumeSync by lazy {
        BluetoothVolumeSync(shellContext, audio = ShizukuVolumeSyncAudio(shellContext,
            binder = { checkNotNull(SystemServiceHelper.getSystemService(it)) },
            packageName = shellContext.packageName), granted = { !cleanupFailed.get() })
    }

    override fun ensureOwnBackgroundAudio(): Boolean {
        enforceCaller()
        if (Build.VERSION.SDK_INT < 37) return true
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!cleanupFailed.get())
            allowOwnBackgroundAudio(expectedUid, BuildConfig.APPLICATION_ID) {
                checkNotNull(SystemServiceHelper.getSystemService(Context.APP_OPS_SERVICE))
            }
            @Suppress("DEPRECATION")
            val applied = originalContext.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpRawNoThrow("android:control_audio", expectedUid, BuildConfig.APPLICATION_ID) ==
                AppOpsManager.MODE_ALLOWED
            android.util.Log.i("BluetoothAccess", "Own background audio allowed=$applied")
            applied
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun checkVolumeSync(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
            BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED)
        val selected = A2dpRecoveryGuard(address).address
        synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && playbackRecovery == null &&
                playbackRelease == null && callConnection == null)
            volumeChecking = true
        }
        val identity = Binder.clearCallingIdentity()
        return try {
            if (!owner.isCurrent) return false
            volumeSync.selectRoute(selected)
            volumeSync.check(selected, owner.asBinder()) {
                !cleanupFailed.get() && runCatching { owner.isCurrent }.getOrDefault(false)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { volumeChecking = false }
        }
    }

    override fun forgetVolumeSync(owner: IPlaybackRecoveryOwner) {
        enforceCaller()
        volumeSync.forget(owner.asBinder())
    }

    override fun retireOwner(owner: IPlaybackRecoveryOwner) {
        enforceCaller()
        finishOwnedMedia(owner, false)
        volumeSync.forget(owner.asBinder())
    }

    override fun nativeCall(address: String, owner: IPlaybackRecoveryOwner, connect: Boolean): Int {
        enforceCaller()
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_STATE))
            check(originalContext.packageManager.checkPermission(permission, BuildConfig.APPLICATION_ID) ==
                PackageManager.PERMISSION_GRANTED) { "LibrePods call access is unavailable" }
        val operation = synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && playbackRecovery == null &&
                playbackRelease == null && callConnection == null) { "Another audio operation is active" }
            NativeCallConnection(shellContext, address, owner).also { callConnection = it }
        }
        val identity = Binder.clearCallingIdentity()
        return try { operation.run(connect) } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (callConnection === operation) callConnection = null }
        }
    }

    override fun pauseOwnedMedia(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        val identity = Binder.clearCallingIdentity()
        return try { synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && playbackRecovery == null &&
                playbackRelease == null && callConnection == null) { "Another audio operation is active" }
            mediaPause?.second?.close(); mediaPause = null
            OwnedMediaPause.acquire(shellContext, address, owner)?.let { mediaPause = owner to it; true } ?: false
        } } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun finishOwnedMedia(owner: IPlaybackRecoveryOwner, resume: Boolean): Boolean {
        enforceCaller()
        val lease = synchronized(lock) {
            mediaPause?.takeIf { it.first.asBinder() == owner.asBinder() }?.also { mediaPause = null }?.second
        } ?: return false
        val identity = Binder.clearCallingIdentity()
        return try { if (resume) lease.resume() else { lease.close(); false } }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun enforceCaller() {
        AudioBridgeRules.requireCaller(expectedUid, Binder.getCallingUid())
    }

    override fun isMicrophonePrivacyBlocked(): Boolean {
        enforceCaller()
        return microphonePrivacyBlocked()
    }

    private fun microphonePrivacyBlocked(): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            ShellMicrophonePrivacy.isBlocked(shellContext)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun restoreNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        nativePlayback(address, owner, connectOnly = false)

    override fun connectNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        nativePlayback(address, owner, connectOnly = true)

    override fun releaseNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        releaseNativePlayback(address, owner, inspectOnly = false)

    override fun inspectNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        releaseNativePlayback(address, owner, inspectOnly = true)

    private fun releaseNativePlayback(address: String, owner: IPlaybackRecoveryOwner, inspectOnly: Boolean): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission("android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) ==
            PackageManager.PERMISSION_GRANTED) { "LibrePods Bluetooth permission was revoked" }
        val release = synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && playbackRecovery == null && playbackRelease == null && callConnection == null) {
                "Another audio operation is active"
            }
            NativePlaybackRelease(shellContext, address, owner).also { playbackRelease = it }
        }
        val identity = Binder.clearCallingIdentity()
        return try { release.run(inspectOnly) } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (playbackRelease === release) playbackRelease = null }
        }
    }

    private fun nativePlayback(address: String, owner: IPlaybackRecoveryOwner, connectOnly: Boolean): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission("android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) ==
            PackageManager.PERMISSION_GRANTED) { "LibrePods Bluetooth permission was revoked" }
        val recovery = synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && playbackRecovery == null && playbackRelease == null && callConnection == null) { "Another audio operation is active" }
            A2dpPlaybackRecovery(shellContext, address, owner, connectOnly).also { playbackRecovery = it }
        }
        val identity = Binder.clearCallingIdentity()
        try {
            return recovery.run()
        } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (playbackRecovery === recovery) playbackRecovery = null }
        }
    }

    override fun confirmPairing(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        if (cleanupFailed.get() || originalContext.packageManager.checkPermission(
                "android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) != PackageManager.PERMISSION_GRANTED)
            return false
        val identity = Binder.clearCallingIdentity()
        return try {
            ShellPairing.confirm(shellContext, address, owner)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == expectedUid || caller == 2000 || caller == 0) { "Invalid bridge owner" }
        close()
        if (exitOnDestroy) kotlin.system.exitProcess(0)
    }

    override fun close() {
        if (!cleanupFailed.compareAndSet(false, true)) return
        synchronized(lock) {
            playbackRecovery?.cancel(); playbackRelease?.cancel(); callConnection?.binderDied()
            mediaPause?.second?.close(); mediaPause = null
        }
        volumeSync.close()
    }
}
