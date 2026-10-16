/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.bluetooth.AACPManager
import org.junit.Assert.*
import org.junit.Test

class IslandListeningModeTest {
    @Test fun paddedWireReportsReachTheModeBaseline() {
        val modes = IslandListeningMode()
        fun report(mode: Byte): Boolean {
            val packet = byteArrayOf(4, 0, 4, 0, 9, 0, 13, mode, 0, 0, 0)
            val command = AACPManager.ControlCommand.fromByteArray(packet)
            return modes.observe(command.value.single().toInt())
        }
        assertFalse(report(2))
        assertFalse(report(2))
        assertTrue(report(4))
    }

    @Test fun initialRepeatedAndInvalidReportsDoNotAnnounceAChange() {
        val modes = IslandListeningMode()
        assertFalse(modes.observe(0))
        assertFalse(modes.observe(2))
        assertFalse(modes.observe(2))
        assertFalse(modes.observe(5))
        assertFalse(modes.observe(-1))
        assertFalse(modes.observe(2))
        assertTrue(modes.observe(3))
        assertFalse(modes.observe(3))
        assertTrue(modes.observe(1))
        assertTrue(modes.observe(4))
    }

    @Test fun aNewConnectionMustEstablishItsOwnBaseline() {
        val modes = IslandListeningMode()
        assertFalse(modes.observe(2))
        assertTrue(modes.observe(3))
        modes.reset()
        assertFalse(modes.observe(1))
        assertFalse(modes.observe(1))
        assertTrue(modes.observe(4))
    }
}
