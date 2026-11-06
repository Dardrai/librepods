package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Process
import android.os.RemoteException
import java.lang.reflect.InvocationTargetException

internal enum class MediaInspectionStage {
    ADMISSION, OUTPUT, CLIENT, HELPER_CONTEXT, HELPER_PERMISSION, PLAYER_METHODS, PLAYERS, SESSIONS, PLAYER_IDENTITY, COMPLETE
}

internal enum class MediaInspectionReason {
    NOT_READ, PENDING, OK, DEVICE_MISSING, HOLD_UNAVAILABLE, DESTROYING, PERMISSION_DENIED, BOND_MISSING,
    BLUETOOTH_OFF, ROOT_UNAVAILABLE, BRIDGE_UNAVAILABLE, SETTINGS_BUSY, COMMUNICATION_BUSY, BRIDGE_REPLACED,
    CLEANUP_FAILED, UNKNOWN_USAGE, PLAYER_IDENTITY_UNAVAILABLE, METHOD_UNAVAILABLE, SECURITY, REMOTE,
    INVOCATION, INVALID_STATE, CANCELLED, FAILED, DEVICE_CHANGED, BUSY, DONGLE_SELECTED,
    MEDIA_RUNTIME_UNAVAILABLE, MEDIA_MANAGER_UNAVAILABLE
}

/** Read-only media evidence. A notification never becomes a destination play request. */
internal object CompanionMediaInspection {
    fun failureReason(error: Exception): MediaInspectionReason = when (error) {
        is MediaRuntimeUnavailableException -> MediaInspectionReason.MEDIA_RUNTIME_UNAVAILABLE
        is SecurityException -> MediaInspectionReason.SECURITY
        is NoSuchMethodException, is NoSuchFieldException -> MediaInspectionReason.METHOD_UNAVAILABLE
        is RemoteException -> MediaInspectionReason.REMOTE
        is InvocationTargetException -> MediaInspectionReason.INVOCATION
        is IllegalStateException, is IllegalArgumentException -> MediaInspectionReason.INVALID_STATE
        else -> MediaInspectionReason.FAILED
    }

    fun failure(stage: MediaInspectionStage, reason: MediaInspectionReason) = Bundle().apply {
        putBoolean("known", false)
        putBoolean("playing", false)
        putBoolean("eligible", false)
        putString("player", "")
        putString("diagnostic_stage", stage.name)
        putString("diagnostic_reason", reason.name)
    }

    fun stage(value: Bundle) = MediaInspectionStage.entries.firstOrNull {
        it.name == value.getString("diagnostic_stage")
    } ?: MediaInspectionStage.CLIENT

    fun reason(value: Bundle) = MediaInspectionReason.entries.firstOrNull {
        it.name == value.getString("diagnostic_reason")
    } ?: MediaInspectionReason.FAILED

    fun read(context: Context, address: String): Bundle {
        var stage = MediaInspectionStage.HELPER_PERMISSION
        try {
            if (context.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", Process.myPid(), Process.myUid()) !=
                PackageManager.PERMISSION_GRANTED) return failure(stage, MediaInspectionReason.PERMISSION_DENIED)
            val selected = A2dpRecoveryGuard(address).address
            val audio = context.getSystemService(AudioManager::class.java)
            stage = MediaInspectionStage.PLAYER_METHODS
            val activeGetter = AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive").apply { isAccessible = true }
            val uidGetter = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true }
            stage = MediaInspectionStage.PLAYERS
            val active = audio.activePlaybackConfigurations.filter { activeGetter.invoke(it) == true }
            if (!active.all { (uidGetter.invoke(it) as Int) >= 0 })
                return failure(stage, MediaInspectionReason.PLAYER_IDENTITY_UNAVAILABLE)
            val unknown = active.any { it.audioAttributes.usage == AudioAttributes.USAGE_UNKNOWN }
            val media = active.filter { it.audioAttributes.usage in setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME) }
            stage = MediaInspectionStage.SESSIONS
            val manager = context.getSystemService(MediaSessionManager::class.java)
                ?: return failure(stage, MediaInspectionReason.MEDIA_MANAGER_UNAVAILABLE)
            val sessions = manager.getActiveSessions(null).filter {
                it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
            }
            stage = MediaInspectionStage.PLAYER_IDENTITY
            val qualified = sessions.filter { player ->
                val uid = context.packageManager.getApplicationInfo(player.packageName, 0).uid
                val configs = media.filter { uidGetter.invoke(it) == uid }
                sessions.count { it.packageName == player.packageName } == 1 && configs.isNotEmpty() && configs.all { config ->
                    config.audioDeviceInfo?.let { route ->
                        route.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) ||
                            route.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && route.address.equals(selected, true)
                    } == true
                }
            }
            val playing = sessions.isNotEmpty() || media.isNotEmpty()
            val key = sessions.singleOrNull()?.let { it.packageName + ":" + it.sessionToken.hashCode() }.orEmpty()
            return Bundle().apply {
                putBoolean("known", !unknown)
                putBoolean("playing", playing)
                putBoolean("eligible", !unknown && qualified.size == 1 && sessions.size == 1)
                putString("player", key)
                putString("diagnostic_stage", MediaInspectionStage.COMPLETE.name)
                putString("diagnostic_reason", if (unknown) MediaInspectionReason.UNKNOWN_USAGE.name else MediaInspectionReason.OK.name)
            }
        } catch (error: Exception) { return failure(stage, failureReason(error)) }
    }
}
