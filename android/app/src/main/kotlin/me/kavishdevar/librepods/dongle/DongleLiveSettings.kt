package me.kavishdevar.librepods.dongle

internal data class DongleAudioIntent(val codec: Int, val rateMode: Int, val bitrate: Long)

internal fun validLiveSetting(id: Int, value: Long): Boolean = when (id) {
    0x01, 0x17, 0x18, 0x23 -> value in 0L..2L
    0x0d -> value in 1L..4L
    0x0a, 0x1b, 0x25, 0x26, 0x28, 0x31, 0x34, 0x35 -> value in 1L..2L
    0x16 -> value in setOf(0x0101L, 0x0105L, 0x0501L, 0x0505L)
    0x1a -> value in 0L..15L && java.lang.Long.bitCount(value) >= 2
    0x24 -> value in setOf(0x0200L, 0x0300L)
    0x1f -> value in 0x5000L..0x5064L
    0x2e, 0x40 -> value in 0L..100L
    else -> false
}
