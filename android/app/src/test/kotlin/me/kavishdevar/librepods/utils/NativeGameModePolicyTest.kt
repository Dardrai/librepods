/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils
import org.junit.Assert.*
import org.junit.Test
class NativeGameModePolicyTest {
    private val peer = listOf(1,2,3,4,5,6)
    private val owner = NativeGameModePolicy.Owner(peer, 3, 4)
    private val host = NativeGameModePolicy.Host(peer, 3, 1000, 2, 1500, 1500, true, true, true, true, true, false)
    @Test fun missingControllerFlushKeepsApplyUnavailableWithoutPreventingOwnedCleanup() {
        assertFalse(NativeGameModePolicy.canApply(owner,4,host.copy(flushReady=false),1500))
        assertTrue(NativeGameModePolicy.canRestore(owner,4,host.copy(flushReady=false)))
    }
    @Test fun originalOwnerCleanupSurvivesPauseScoAndMissingEncoderButNeverChangesSession() {
        val stopped = host.copy(streaming=false, encoderReady=false, clockReady=false,
            flushReady=false, scoOrMicrophone=true)
        assertTrue(NativeGameModePolicy.canRestore(owner,4,stopped))
        assertFalse(NativeGameModePolicy.canApply(owner,4,stopped,1600))
        assertFalse(NativeGameModePolicy.canRestore(owner,5,stopped))
        assertFalse(NativeGameModePolicy.canRestore(owner,4,stopped.copy(mediaGeneration=99)))
        assertTrue(NativeGameModePolicy.freshRestoreReport(owner,4,stopped,1,1400,1600))
        assertFalse(NativeGameModePolicy.freshRestoreReport(owner,4,stopped,2,1400,1600))
    }
    @Test fun startSessionPeerAndScoGuardsCannotBeBypassedByAnEncoderBeingReady() {
        assertFalse(NativeGameModePolicy.canApply(owner,4,host,1499))
        assertTrue(NativeGameModePolicy.canApply(owner,4,host,1500))
        assertFalse(NativeGameModePolicy.canApply(owner,5,host,1500))
        assertFalse(NativeGameModePolicy.canRestore(owner,4,host.copy(peer=peer.reversed())))
        assertFalse(NativeGameModePolicy.canApply(owner,4,host.copy(scoOrMicrophone=true),1500))
    }
    @Test fun onlyFreshSameOwnerDelayReportsWithinTheDeadlineCanDriveTheClock() {
        assertFalse(NativeGameModePolicy.freshReport(owner,4,host,2,1400,1600))
        assertTrue(NativeGameModePolicy.freshReport(owner,4,host,1,1400,1600))
        assertFalse(NativeGameModePolicy.freshReport(owner,4,host,1,1600,1700))
        assertFalse(NativeGameModePolicy.freshReport(owner,4,host,1,1400,3401))
        assertFalse(NativeGameModePolicy.freshReport(owner,4,host.copy(delayTenths=0),1,1400,1600))
    }
    @Test fun clockUsesPostWriteReportAndTheExplicit150RestoreException() {
        assertEquals(NativeGameModePolicy.Clock(9,22),NativeGameModePolicy.clock(280,60))
        assertEquals(NativeGameModePolicy.Clock(9,60),NativeGameModePolicy.clock(900,150))
        assertEquals(NativeGameModePolicy.Clock(11,80),NativeGameModePolicy.clock(1500,60))
        assertEquals(NativeGameModePolicy.Clock(10,0),NativeGameModePolicy.clock(1500,150))
        assertNull(NativeGameModePolicy.clock(65535,60))
        assertNull(NativeGameModePolicy.clock(1500,30))
    }
    @Test fun sameMillisecondPrewriteReportCannotConfirmEitherTarget() {
        val boundary = 1_500_000_500L
        val before = host.copy(delayReceivedMs = 1500, delayReceivedNs = boundary - 1)
        val after = before.copy(delayReceivedNs = boundary + 1)
        assertFalse(NativeGameModePolicy.freshReport(owner,4,before,1,1500,1600,boundary))
        assertFalse(NativeGameModePolicy.freshRestoreReport(owner,4,before,1,1500,1600,boundary))
        assertTrue(NativeGameModePolicy.freshReport(owner,4,after,1,1500,1600,boundary))
        assertTrue(NativeGameModePolicy.freshRestoreReport(owner,4,after,1,1500,1600,boundary))
    }
}
