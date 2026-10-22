package me.kavishdevar.librepods.presentation.screens.settings

internal data class DongleAudioRequest(val codec: Int, val rateMode: Int, val bitrate: Long)

internal fun dongleAudioRateModes(codec: Int): List<Int> = if (codec == 3) listOf(0, 1) else listOf(0)

internal fun dongleAudioBitrates(codec: Int): List<Long> = when (codec) {
    2 -> listOf(0L, 96000L, 128000L, 174000L, 192000L, 220000L)
    3 -> listOf(0L, 128000L, 174000L, 192000L, 220000L, 256000L)
    else -> listOf(0L)
}

// Match the dongle firmware's playback_options_valid matrix for every picker.
internal fun dongleAudioRequest(codec: Int, rateMode: Int, bitrate: Long): DongleAudioRequest {
    require(codec in 0..3)
    return DongleAudioRequest(codec, rateMode.takeIf { it in dongleAudioRateModes(codec) } ?: 0,
        bitrate.takeIf { it in dongleAudioBitrates(codec) } ?: 0L)
}
