package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Retains one exact player and only the pause caused by this app. */
internal class OwnedMediaPause private constructor(private val context: Context, private val address: String,
    private val owner: IPlaybackRecoveryOwner, private val player: MediaController) : Closeable, IBinder.DeathRecipient {
    private val closed = AtomicBoolean(false)
    private val claim = PauseClaim()
    private val callbacks = HandlerThread("LibrePods-media-owner").apply { start() }
    private val listener = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            claim.observe(state?.state == PlaybackState.STATE_PAUSED)
        }
        override fun onSessionDestroyed() { close() }
        override fun onMetadataChanged(value: MediaMetadata?) {
            claim.observe(false)
        }
    }

    private fun current() = !closed.get() && runCatching { owner.isCurrent && selectedRoute(context, address) }.getOrDefault(false)

    private fun pause(): Boolean {
        owner.asBinder().linkToDeath(this, 0)
        player.registerCallback(listener, Handler(callbacks.looper))
        if (!current() || player.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        player.transportControls.pause()
        val deadline = SystemClock.elapsedRealtime() + 1200
        while (current() && SystemClock.elapsedRealtime() < deadline) {
            if (player.playbackState?.state == PlaybackState.STATE_PAUSED) return true
            Thread.sleep(20)
        }
        return false
    }

    fun resume(): Boolean = try {
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
        if (!current() || !claim.mayResume() || sessions.firstOrNull()?.sessionToken != player.sessionToken ||
            player.playbackState?.state != PlaybackState.STATE_PAUSED) false
        else { player.transportControls.play(); true }
    } finally { close() }

    override fun binderDied() = close()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { player.unregisterCallback(listener) }
        runCatching { owner.asBinder().unlinkToDeath(this, 0) }
        callbacks.quitSafely()
    }

    companion object {
        @SuppressLint("MissingPermission")
        private fun selectedRoute(context: Context, address: String): Boolean {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            val routes = context.getSystemService(AudioManager::class.java).getAudioDevicesForAttributes(attributes)
            return routes.isNotEmpty() && routes.all {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(address, true)
            }
        }

        /** The media default may differ from an application's preferred route. Require its actual player. */
        private fun selectedPlayer(context: Context, address: String, player: MediaController): Boolean {
            val uid = context.packageManager.getApplicationInfo(player.packageName, 0).uid
            val configurations = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            val uidGetter = android.media.AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid")
                .apply { isAccessible = true }
            val activeGetter = android.media.AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive")
                .apply { isAccessible = true }
            val active = configurations.filter { activeGetter.invoke(it) == true && uidGetter.invoke(it) == uid }
            return active.isNotEmpty() && active.all { configuration ->
                configuration.audioDeviceInfo?.let {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(address, true)
                } == true
            }
        }

        fun acquire(context: Context, address: String, owner: IPlaybackRecoveryOwner): OwnedMediaPause? {
            check(context.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", Process.myPid(), Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED) { "Exact-player media access is unavailable" }
            val selected = A2dpRecoveryGuard(address).address
            if (!owner.isCurrent || !selectedRoute(context, selected)) return null
            val player = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null).firstOrNull() ?: return null
            if (context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                    .count { it.packageName == player.packageName } != 1) return null
            if (player.playbackState?.state != PlaybackState.STATE_PLAYING ||
                player.playbackInfo?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
                player.playbackState!!.actions and PlaybackState.ACTION_PAUSE == 0L ||
                !selectedPlayer(context, selected, player)) return null
            val lease = OwnedMediaPause(context, selected, owner, player)
            return try { if (lease.pause()) lease else { lease.close(); null } }
            catch (error: Exception) { lease.close(); throw error }
        }
    }
}

internal class PauseClaim {
    private var changes = 0
    @Synchronized fun observe(paused: Boolean) {
        changes = if (paused && changes == 0) 1 else 2
    }
    @Synchronized fun mayResume(): Boolean = changes == 1
}
