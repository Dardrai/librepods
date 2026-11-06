package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CompanionMediaInspectionTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private fun inspect(usage: Int, session: Boolean, routeAddress: String = address, uid: Int = 12345,
        permission: Int = PackageManager.PERMISSION_GRANTED, failAt: MediaInspectionStage? = null,
        missingManager: Boolean = false, runtimeUnavailable: Boolean = false): android.os.Bundle {
        val context = mock(Context::class.java)
        `when`(context.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(permission)
        val audio = mock(AudioManager::class.java)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        val config = mock(AudioPlaybackConfiguration::class.java)
        `when`(config.audioAttributes).thenReturn(AudioAttributes.Builder().setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        val active = AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive").apply { isAccessible = true }
        val client = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true }
        `when`(active.invoke(config) as Boolean).thenReturn(true)
        `when`(client.invoke(config) as Int).thenReturn(uid)
        val route = mock(AudioDeviceInfo::class.java)
        `when`(route.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(route.address).thenReturn(routeAddress)
        `when`(config.audioDeviceInfo).thenReturn(route)
        `when`(audio.activePlaybackConfigurations).thenReturn(listOf(config))
        if (failAt == MediaInspectionStage.PLAYERS) `when`(audio.activePlaybackConfigurations)
            .thenThrow(SecurityException("private.player/title/key"))
        val manager = mock(MediaSessionManager::class.java)
        `when`(context.getSystemService(MediaSessionManager::class.java)).thenReturn(manager)
        if (missingManager) `when`(context.getSystemService(MediaSessionManager::class.java)).thenReturn(null)
        if (runtimeUnavailable) `when`(context.getSystemService(MediaSessionManager::class.java))
            .thenThrow(MediaRuntimeUnavailableException(NullPointerException("private runtime detail")))
        val player = mock(MediaController::class.java)
        `when`(player.packageName).thenReturn("test.player")
        val mediaSession = MediaSession(RuntimeEnvironment.getApplication(), "companion-test")
        val token = mediaSession.sessionToken
        mediaSession.release()
        `when`(player.sessionToken).thenReturn(token)
        `when`(player.playbackState).thenReturn(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        val info = MediaController.PlaybackInfo::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, AudioAttributes::class.java, String::class.java).apply { isAccessible = true }
            .newInstance(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL, 2, 15, 5,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(), null)
        `when`(player.playbackInfo).thenReturn(info)
        `when`(manager.getActiveSessions(null)).thenReturn(if (session) listOf(player) else emptyList())
        if (failAt == MediaInspectionStage.SESSIONS) `when`(manager.getActiveSessions(null))
            .thenThrow(SecurityException("private.player/title/key"))
        val packages = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getApplicationInfo("test.player", 0)).thenReturn(ApplicationInfo().apply { this.uid = 12345 })
        if (failAt == MediaInspectionStage.PLAYER_IDENTITY) `when`(packages.getApplicationInfo("test.player", 0))
            .thenThrow(PackageManager.NameNotFoundException("private.player/title/key"))
        return CompanionMediaInspection.read(context, address)
    }

    @Test fun notificationUsageCannotAcquireEvenIfItsContentTypeSaysMusic() {
        val notification = inspect(AudioAttributes.USAGE_NOTIFICATION, false)
        assertTrue(notification.getBoolean("known"))
        assertFalse(notification.getBoolean("playing"))
        assertFalse(notification.getBoolean("eligible"))
        assertFalse(inspect(AudioAttributes.USAGE_NOTIFICATION, true).getBoolean("eligible"))
    }

    @Test fun localMediaNeedsTheSamePlayerUidAndAnAllowedRoute() {
        assertTrue(inspect(AudioAttributes.USAGE_MEDIA, true).getBoolean("eligible"))
        assertFalse(inspect(AudioAttributes.USAGE_MEDIA, false).getBoolean("eligible"))
        assertFalse(inspect(AudioAttributes.USAGE_MEDIA, true, "11:22:33:44:55:66").getBoolean("eligible"))
        assertFalse(inspect(AudioAttributes.USAGE_MEDIA, true, uid = 12346).getBoolean("eligible"))
    }

    @Test fun unknownUsageAndAnonymizedPlayersCannotAuthorizeTransfer() {
        assertFalse(inspect(AudioAttributes.USAGE_UNKNOWN, true).getBoolean("known"))
        val anonymized = inspect(AudioAttributes.USAGE_MEDIA, true, uid = -1)
        assertFalse(anonymized.getBoolean("known"))
        assertEquals(MediaInspectionReason.PLAYER_IDENTITY_UNAVAILABLE, CompanionMediaInspection.reason(anonymized))
    }

    @Test fun deniedMediaAccessIsNamedWithoutGrantingAnyAuthority() {
        val value = inspect(AudioAttributes.USAGE_MEDIA, true, permission = PackageManager.PERMISSION_DENIED)
        assertEquals(MediaInspectionStage.HELPER_PERMISSION, CompanionMediaInspection.stage(value))
        assertEquals(MediaInspectionReason.PERMISSION_DENIED, CompanionMediaInspection.reason(value))
        assertFalse(value.getBoolean("known")); assertFalse(value.getBoolean("playing")); assertFalse(value.getBoolean("eligible"))
        assertEquals("", value.getString("player"))
    }

    @Test fun helperFailuresReportOnlyFixedStageAndReasonWithoutPlayerOrExceptionDetails() {
        for (stage in listOf(MediaInspectionStage.PLAYERS, MediaInspectionStage.SESSIONS, MediaInspectionStage.PLAYER_IDENTITY)) {
            val value = inspect(AudioAttributes.USAGE_MEDIA, true, failAt = stage)
            assertEquals(stage, CompanionMediaInspection.stage(value))
            assertFalse(value.getBoolean("known")); assertFalse(value.getBoolean("eligible"))
            assertEquals("", value.getString("player"))
            assertFalse(value.toString().contains("private.player"))
            assertFalse(value.toString().contains("test.player"))
        }
    }

    @Test fun unknownDiagnosticTextIsNeverPassedThroughToTheServiceDump() {
        val value = android.os.Bundle().apply {
            putString("diagnostic_stage", "private.player/title/key")
            putString("diagnostic_reason", "private.player/title/key")
        }
        assertEquals(MediaInspectionStage.CLIENT, CompanionMediaInspection.stage(value))
        assertEquals(MediaInspectionReason.FAILED, CompanionMediaInspection.reason(value))
    }

    @Test fun missingManagerAndUnverifiedRuntimeHaveDistinctFailClosedReasons() {
        val missing = inspect(AudioAttributes.USAGE_MEDIA, false, missingManager = true)
        val runtime = inspect(AudioAttributes.USAGE_MEDIA, false, runtimeUnavailable = true)
        assertEquals(MediaInspectionReason.MEDIA_MANAGER_UNAVAILABLE, CompanionMediaInspection.reason(missing))
        assertEquals(MediaInspectionReason.MEDIA_RUNTIME_UNAVAILABLE, CompanionMediaInspection.reason(runtime))
        for (value in listOf(missing, runtime)) {
            assertEquals(MediaInspectionStage.SESSIONS, CompanionMediaInspection.stage(value))
            assertFalse(value.getBoolean("known")); assertFalse(value.getBoolean("eligible"))
            assertFalse(value.toString().contains("private runtime detail"))
        }
    }
}
