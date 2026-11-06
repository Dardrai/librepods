/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class UsbVolumeMuteTest {
    @Test fun aLateOlderAcknowledgementCannotReplaceANewerHostTargetButNativeTruthCan() {
        val state = UsbPhysicalVolumeState(127, 25, false)
        assertEquals(122, state.request(122, 24, true, false, false))
        val older = state.requestSequence
        assertEquals(76, state.request(76, 15, true, false, false))
        assertTrue(state.acknowledgementSuperseded(older, 122))
        assertFalse(state.acknowledgementSuperseded(0, 73))
        assertFalse(state.acknowledgementSuperseded(state.requestSequence, 76))
        state.observe(73, 14, false)
        assertTrue(state.acknowledgementSuperseded(older, 122))
    }

    @Test fun newerSameTargetCallbacksStillAllowTheOlderAcknowledgementToPublish() {
        val state = UsbPhysicalVolumeState(127, 25, false)
        assertEquals(76, state.request(76, 15, true, false, false))
        val older = state.requestSequence
        assertEquals(76, state.request(76, 15, true, false, false))
        assertEquals(older + 1, state.requestSequence)
        assertFalse(state.acknowledgementSuperseded(older, 76))
    }

    @Test fun unmuteFromHostZeroCanPublishTheExactRestoredMaximumDespiteANewerDuplicate() {
        val state = UsbPhysicalVolumeState(127, 25, false)
        assertEquals(0, state.request(127, 25, true, true, false))
        val muted = state.requestSequence
        state.observe(0, null, true)
        assertEquals(127, state.request(127, 25, true, false, true))
        val unmuted = state.requestSequence
        assertEquals(127, state.request(127, 25, true, false, false))
        assertTrue(state.acknowledgementSuperseded(muted, 0))
        assertFalse(state.acknowledgementSuperseded(unmuted, 127))
    }

    @Test fun unknownAcknowledgementSequencesAreRejectedAndInvalidCallbacksDoNotAdvanceThem() {
        val state = UsbPhysicalVolumeState(127, 25, false)
        assertNull(state.request(255, null, true, false, false))
        assertEquals(0L, state.requestSequence)
        assertThrows(IllegalArgumentException::class.java) { state.acknowledgementSuperseded(-1, 127) }
        assertThrows(IllegalArgumentException::class.java) { state.acknowledgementSuperseded(1, 127) }
        assertFalse(state.acknowledgementSuperseded(0, 127))
    }

    @Test fun unmuteRestoresTheExactObservedLevelRatherThanItsRoundedHostDisplay() {
        val state = UsbPhysicalVolumeState(113, 22, false)
        assertEquals(0, state.request(112, 22, true, true, false))
        state.observe(0, 22, true)
        assertEquals(113, state.request(112, 22, true, false, true))
        assertEquals(113, state.request(112, 22, true, false, false))
        state.observe(113, 22, false)
        assertEquals(113, state.request(112, 22, true, false, false))
        state.observe(1, 1, false)
        assertEquals(0, state.request(5, 1, true, true, false))
        state.observe(0, null, true) // A muted framework getter would report zero; keep its remembered index.
        assertEquals(1, state.request(5, 1, true, false, true))
        assertEquals(1, state.request(5, 1, true, false, false))
    }

    @Test fun anExplicitSliderChangeIsNotMistakenForUnmuteRounding() {
        val state = UsbPhysicalVolumeState(113, 22, false)
        assertEquals(0, state.request(112, 22, true, true, false))
        assertEquals(102, state.request(102, 20, true, false, true))
        assertEquals(102, state.request(102, 20, true, false, false))
        state.observe(102, 20, false)
        assertEquals(0, state.request(102, 20, true, true, false))
        assertEquals(0, state.request(91, 18, true, true, true))
        state.observe(0, 18, true)
        assertEquals(91, state.request(91, 18, true, false, true))
    }

    @Test fun aFreshPhysicalReportReplacesTheRememberedMutedLevel() {
        val state = UsbPhysicalVolumeState(113, 22, true)
        state.observe(65, 13, true)
        assertEquals(65, state.request(66, 13, true, false, true))
        assertEquals(0, state.request(66, 13, true, true, false))
        assertNull(state.request(255, 13, true, false, false))
        assertEquals(65, state.request(66, 13, true, false, true))
    }

    @Test fun aMuteWithoutAnIndexDoesNotDiscardTheRememberedDisplayOrExactGain() {
        val state = UsbPhysicalVolumeState(113, 22, false)
        assertEquals(0, state.request(-100, null, true, true, false))
        state.observe(0, null, true)
        assertEquals(113, state.request(112, 22, true, false, true))
    }
    @Test fun allPhysicalStepsRemainAudibleUntilTrueZero() {
        assertEquals(listOf(0, 1, 1, 1, 25, 25), listOf(0, 1, 2, 3, 126, 127).map { usbHostVolumeIndex(it, 25) })
        for (maximum in listOf(1, 15, 25, 127)) {
            assertEquals(0, usbHostVolumeIndex(0, maximum))
            for (physical in 1..127) assertTrue(usbHostVolumeIndex(physical, maximum) in 1..maximum)
            assertEquals(maximum, usbHostVolumeIndex(127, maximum))
        }
        assertThrows(IllegalArgumentException::class.java) { usbHostVolumeIndex(128, 25) }
        assertThrows(IllegalArgumentException::class.java) { usbHostVolumeIndex(1, 0) }
    }
    @Test fun explicitMuteRequestsZeroWhileTheFrameworkRetainsItsUnmutedIndex() {
        assertEquals(0, usbPhysicalVolumeRequest(64, true, true, false))
        assertEquals(64, usbPhysicalVolumeRequest(64, true, false, true))
        assertEquals(0, usbPhysicalVolumeRequest(-100, true, true, false))
    }
    @Test fun anOlderCallbackWithoutMuteStateUsesTheActualFrameworkMuteState() {
        assertEquals(0, usbPhysicalVolumeRequest(64, false, false, true))
        assertEquals(64, usbPhysicalVolumeRequest(64, false, false, false))
        assertEquals(0, usbPhysicalVolumeRequest(0, false, false, false))
        assertNull(usbPhysicalVolumeRequest(255, false, false, false))
    }
}
