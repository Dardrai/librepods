/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration

internal data class AtmosPlaybackWitness(val uid: Int, val pid: Int, val player: Int, val session: Int,
    val routeId: Int, val address: String)

internal sealed interface AtmosPlaybackObservation {
    data object Absent : AtmosPlaybackObservation
    data class Present(val witness: AtmosPlaybackWitness) : AtmosPlaybackObservation
    data class Paused(val witness: AtmosPlaybackWitness) : AtmosPlaybackObservation
    data class Invalid(val reason: String) : AtmosPlaybackObservation
}

/** Root observations of the actual started player, separate from global route policy/connected devices. */
internal class AtmosPlaybackObserver(private val audio: AudioManager) {
    private val type = AudioPlaybackConfiguration::class.java
    private val active = type.getDeclaredMethod("isActive").apply { isAccessible = true }
    private val state = type.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
    private val uid = type.getDeclaredMethod("getClientUid").apply { isAccessible = true }
    private val pid = type.getDeclaredMethod("getClientPid").apply { isAccessible = true }
    private val player = type.getDeclaredMethod("getPlayerInterfaceId").apply { isAccessible = true }
    private val session = type.getDeclaredMethod("getSessionId").apply { isAccessible = true }
    private val devices = type.getDeclaredMethod("getAudioDeviceInfos").apply { isAccessible = true }
    private val spatialized = type.getDeclaredMethod("isSpatialized").apply { isAccessible = true }
    private val rate = type.getDeclaredMethod("getSampleRate").apply { isAccessible = true }
    private val channels = type.getDeclaredMethod("getChannelMask").apply { isAccessible = true }

    fun read(targetUid: Int, address: String, routeId: Int): AtmosPlaybackWitness? =
        (observe(targetUid, address, routeId) as? AtmosPlaybackObservation.Present)?.witness

    fun observe(targetUid: Int, address: String, routeId: Int, known: AtmosPlaybackWitness? = null): AtmosPlaybackObservation {
        val configs = audio.activePlaybackConfigurations.filter { uid.invoke(it) == targetUid }
        val candidates = configs.filter { active.invoke(it) == true }
        if (candidates.isEmpty()) {
            // Pausing may clear device IDs. This grants no route/publication authority; resume is fully rechecked.
            if (known != null && configs.count { state.invoke(it) == 3 && pid.invoke(it) == known.pid &&
                player.invoke(it) == known.player && session.invoke(it) == known.session } == 1)
                return AtmosPlaybackObservation.Paused(known)
            return AtmosPlaybackObservation.Absent
        }
        val selected = candidates.singleOrNull() ?: return AtmosPlaybackObservation.Invalid("ambiguous_player")
        if (selected.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) return AtmosPlaybackObservation.Invalid("player_usage")
        if (spatialized.invoke(selected) != false || rate.invoke(selected) != 48_000 ||
            channels.invoke(selected) != AudioFormat.CHANNEL_OUT_STEREO) return AtmosPlaybackObservation.Invalid("player_format_or_spatialization")
        val device = (devices.invoke(selected) as? List<*>)?.singleOrNull() as? AudioDeviceInfo
            ?: return AtmosPlaybackObservation.Invalid("player_route")
        if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.id != routeId || !device.address.equals(address, true))
            return AtmosPlaybackObservation.Invalid("player_route")
        val witness = AtmosPlaybackWitness(targetUid, pid.invoke(selected) as Int, player.invoke(selected) as Int,
            session.invoke(selected) as Int, routeId, address)
        return if (witness.pid > 0 && witness.player > 0 && witness.session > 0) AtmosPlaybackObservation.Present(witness)
            else AtmosPlaybackObservation.Invalid("player_identity")
    }
}
