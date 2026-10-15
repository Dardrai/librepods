package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class ConversationVolumeTest {
    @Test fun quietAudioIsNeverRaisedWhileSpeaking() {
        val volume = ConversationVolume()
        volume.begin(1, 20, 25, false, 1)
        assertNull(volume.step(1))
    }

    @Test fun manualAdjustmentStopsDuckingAndRestoration() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 25, false, 1)
        assertEquals(9, volume.step(10))
        assertNull(volume.step(12))
        volume.begin(12, 20, 25, false, 2)
        assertNull(volume.step(12))
        assertFalse(volume.end(12))
    }

    @Test fun secondStageAndNormalRestoration() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 2)
        var current = 10
        while (true) { current = volume.step(current) ?: break }
        assertEquals(3, current)
        assertTrue(volume.end(current))
        while (true) { current = volume.step(current) ?: break }
        assertEquals(10, current)
        assertFalse(volume.end(current))
    }

    @Test fun manualChangeDuringRestorationWins() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 1)
        var current = 10
        while (true) { current = volume.step(current) ?: break }
        assertTrue(volume.end(5))
        assertEquals(6, volume.step(5))
        assertNull(volume.step(2))
    }
}
