/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Mute callbacks retain the remembered host index; they must still request physical zero. */
internal fun usbPhysicalVolumeRequest(index: Int, hasMuteState: Boolean, muted: Boolean,
    currentMute: Boolean): Int? = when {
    if (hasMuteState) muted else currentMute -> 0
    index in 0..127 -> index
    else -> null
}

/** Android's coarse display must not turn a nonzero physical gain into local PCM mute. */
internal fun usbHostVolumeIndex(physical: Int, maximum: Int): Int {
    require(physical in 0..127 && maximum > 0)
    return if (physical == 0) 0 else ((physical.toLong() * maximum + 63) / 127).toInt().coerceAtLeast(1)
}

/** Preserve the exact pre-mute gain even when Android displays fewer steps. Lease-local only. */
internal class UsbPhysicalVolumeState(physical: Int, private var hostIndex: Int, private var muted: Boolean) {
    private var unmutedLevel = physical.takeIf { it in 1..127 }
    var requestSequence = 0L
        private set
    private var requestedPhysical: Int? = null

    fun acknowledgementSuperseded(sequence: Long, physical: Int): Boolean {
        require(sequence in 0..requestSequence && physical in 0..127)
        return sequence != 0L && sequence < requestSequence && physical != requestedPhysical
    }

    fun observe(physical: Int, hostIndex: Int?, muted: Boolean) {
        require(physical in 0..127)
        if (physical > 0) unmutedLevel = physical
        if (hostIndex != null) this.hostIndex = hostIndex
        this.muted = muted
    }

    fun request(index: Int, hostIndex: Int?, hasMuteState: Boolean, muted: Boolean, currentMute: Boolean): Int? {
        val nextMute = if (hasMuteState) muted else currentMute
        val requested = usbPhysicalVolumeRequest(index, hasMuteState, muted, currentMute) ?: return null
        val nextIndex = hostIndex ?: this.hostIndex
        val preserve = !nextMute && nextIndex == this.hostIndex
        // A changed slider while muted is a new explicit intent, not the old display's rounding.
        if (nextMute && this.muted && nextIndex != this.hostIndex && index in 1..127) unmutedLevel = index
        if (!nextMute && !preserve && requested > 0) unmutedLevel = requested
        this.hostIndex = nextIndex
        this.muted = nextMute
        val resolved = if (preserve) unmutedLevel ?: requested else requested
        requestSequence++
        requestedPhysical = resolved
        return resolved
    }
}
