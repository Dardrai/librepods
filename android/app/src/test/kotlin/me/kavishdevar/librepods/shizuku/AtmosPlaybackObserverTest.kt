/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosPlaybackObserverTest {
    @Test fun realFrameworkConvertsDumpNativeStereoMaskToJavaStereoMask() {
        val type = AudioPlaybackConfiguration::class.java
        val config = type.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(1)
        val formatType = Class.forName("android.media.AudioPlaybackConfiguration\$FormatInfo")
        val format = formatType.getDeclaredConstructor(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(false, 0x3, 48000)
        ReflectionHelpers.setField(config, "mFormatInfo", format)
        assertTrue(format.toString().contains("channelMask=0x3"))
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, type.getDeclaredMethod("getChannelMask").invoke(config))
        assertEquals(0xc, type.getDeclaredMethod("getChannelMask").invoke(config))
        assertEquals(false, type.getDeclaredMethod("isSpatialized").invoke(config))
        assertEquals(48000, type.getDeclaredMethod("getSampleRate").invoke(config))
        assertEquals(List::class.java, type.getDeclaredMethod("getAudioDeviceInfos").returnType)
    }

    @Test fun exactActualPlayerRequiredAndReplacementChangesWitness() {
        val audio = mock(AudioManager::class.java)
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.id).thenReturn(77); `when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(device.address).thenReturn("02:11:22:33:44:55")
        var active = true; var spatialized = false; var playerId = 10; var session = 20; var inactiveState = 3
        var routes = listOf(device); var rate = 48000
        val target = mock(AudioPlaybackConfiguration::class.java, withSettings().defaultAnswer { call ->
            when (call.method.name) {
                "isActive" -> active; "getPlayerState" -> if (active) 2 else inactiveState
                "getClientUid" -> 10123; "getClientPid" -> 1234
                "getPlayerInterfaceId" -> playerId; "getSessionId" -> session; "getAudioDeviceInfos" -> routes
                "isSpatialized" -> spatialized; "getSampleRate" -> rate; "getChannelMask" -> AudioFormat.CHANNEL_OUT_STEREO
                "getAudioAttributes" -> AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
        var players = listOf(target)
        `when`(audio.activePlaybackConfigurations).thenAnswer { players }
        val observer = AtmosPlaybackObserver(audio)
        fun read() = observer.read(10123, "02:11:22:33:44:55", 77)
        val original = requireNotNull(read())
        playerId++; assertNotEquals(original, read()); playerId--
        session++; assertNotEquals(original, read()); session--
        fun observation() = observer.observe(10123, "02:11:22:33:44:55", 77)
        active = false; assertNull(read()); assertEquals(AtmosPlaybackObservation.Absent, observation()); active = true
        spatialized = true; assertNull(read()); assertTrue(observation() is AtmosPlaybackObservation.Invalid); spatialized = false
        rate = 44100; assertNull(read()); assertTrue(observation() is AtmosPlaybackObservation.Invalid); rate = 48000
        routes = listOf(device, device); assertNull(read()); assertTrue(observation() is AtmosPlaybackObservation.Invalid); routes = listOf(device)
        players = listOf(target, target); assertNull(read()); assertTrue(observation() is AtmosPlaybackObservation.Invalid); players = listOf(target)
        assertNull(observer.read(10124, "02:11:22:33:44:55", 77))
        assertNull(observer.read(10123, "02:11:22:33:44:56", 77))
        assertNull(observer.read(10123, "02:11:22:33:44:55", 78))
        assertEquals(original, read())
        active = false; routes = emptyList()
        assertEquals(AtmosPlaybackObservation.Paused(original), observer.observe(10123, original.address, 77, original))
        assertEquals(AtmosPlaybackObservation.Absent, observer.observe(10123, original.address, 77))
        inactiveState = 4
        assertEquals(AtmosPlaybackObservation.Absent, observer.observe(10123, original.address, 77, original))
        inactiveState = 3; session++
        assertEquals(AtmosPlaybackObservation.Absent, observer.observe(10123, original.address, 77, original))
    }
}
