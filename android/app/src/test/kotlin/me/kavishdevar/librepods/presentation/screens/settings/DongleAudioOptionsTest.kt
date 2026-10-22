package me.kavishdevar.librepods.presentation.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DongleAudioOptionsTest {
    @Test fun offered_combinations_match_the_firmware_matrix() {
        val expectedBitrates = listOf(listOf(0L), listOf(0L),
            listOf(0L, 96000L, 128000L, 174000L, 192000L, 220000L),
            listOf(0L, 128000L, 174000L, 192000L, 220000L, 256000L))
        for (codec in 0..3) {
            val rateModes = if (codec == 3) listOf(0, 1) else listOf(0)
            assertEquals(expectedBitrates[codec], dongleAudioBitrates(codec))
            assertEquals(rateModes, dongleAudioRateModes(codec))
            for (rateMode in rateModes) for (bitrate in expectedBitrates[codec])
                assertEquals(DongleAudioRequest(codec, rateMode, bitrate), dongleAudioRequest(codec, rateMode, bitrate))
        }
    }

    @Test fun switching_codecs_preserves_supported_choices_and_resets_incompatible_ones() {
        assertEquals(DongleAudioRequest(0, 0, 0), dongleAudioRequest(0, 1, 256000))
        assertEquals(DongleAudioRequest(1, 0, 0), dongleAudioRequest(1, 1, 256000))
        assertEquals(DongleAudioRequest(2, 0, 0), dongleAudioRequest(2, 1, 256000))
        assertEquals(DongleAudioRequest(2, 0, 174000), dongleAudioRequest(2, 1, 174000))
        assertEquals(DongleAudioRequest(3, 0, 0), dongleAudioRequest(3, 0, 96000))
        assertEquals(DongleAudioRequest(3, 1, 256000), dongleAudioRequest(3, 1, 256000))
    }
}
