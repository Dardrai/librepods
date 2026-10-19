/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.services.saveChangedControlCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IslandListeningModeTest {
    @Test fun paddedWireReportsReachTheConfirmedModeBaseline() {
        val modes = IslandListeningMode(); val socket = Any()
        var previous: ByteArray? = null
        fun report(mode: Byte): Int? {
            val packet = byteArrayOf(4, 0, 4, 0, 9, 0, 13, mode, 0, 0, 0)
            val value = ControlCommandPacket.parse(packet).controlCommand.value
            assertEquals(mode, value.singleOrNull())
            return modes.observeReport(socket, previous, value).also { previous = value }
        }
        assertNull(report(2))
        assertNull(report(2))
        assertEquals(4, report(4))
    }

    @Test fun anOldEmissionResumedAfterReconnectCannotSeedTheReplacementBaseline() {
        val modes = IslandListeningMode(); val first = Any(); val replacement = Any()
        val baseline = byteArrayOf(2); val stale = byteArrayOf(3); val fresh = byteArrayOf(1)
        assertNull(modes.observeReport(first, null, baseline))
        assertNull(modes.observeReport(replacement, baseline, stale, currentReport = fresh))
        assertNull(modes.observeReport(replacement, stale, fresh, currentReport = fresh))
        assertEquals(4, modes.observeReport(replacement, fresh, byteArrayOf(4)))
    }

    @Test fun confirmedModeCachePersistsWhenBatteryAndWearChangesAreCoalescedButRequestsDoNotWrite() = runBlocking {
        val id = ControlCommandIdentifier.LISTENING_MODE
        val baseline = AppleState(controlStates = mapOf(id to byteArrayOf(2)))
        val coalesced = baseline.copy(controlStates = mapOf(id to byteArrayOf(4)),
            battery = setOf(Battery(BatteryComponent.LEFT, 83, BatteryStatus.NOT_CHARGING)),
            componentState = setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR)))
        val saved = mutableListOf<AppleState>()
        saveChangedControlCache(baseline, coalesced) { saved += it }
        assertSame(coalesced, saved.single())
        assertEquals(4, saved.single().controlStates.getValue(id).single().toInt())
        assertEquals(83, saved.single().battery.single().level)
        val requested = coalesced.copy(requestedControls = mapOf(id to byteArrayOf(3)))
        saveChangedControlCache(coalesced, requested) { saved += it }
        saveChangedControlCache(coalesced, coalesced.copy(controlStates = emptyMap())) { saved += it }
        assertEquals(1, saved.size)
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
        assertTrue(modes.observe(4))
    }

    @Test fun cachedAndRequestedOnlyChangesDoNotSeedTheConfirmedReportBaseline() {
        val modes = IslandListeningMode(); val socket = Any()
        val cached = byteArrayOf(2)
        assertNull(modes.observeReport(socket, cached, cached))
        val firstReport = byteArrayOf(3)
        assertNull(modes.observeReport(socket, cached, firstReport))
        assertNull(modes.observeReport(socket, firstReport, firstReport))
        assertEquals(4, modes.observeReport(socket, firstReport, byteArrayOf(4)))
        assertNull(modes.observeReport(socket, firstReport, byteArrayOf(4)))
        assertNull(modes.observeReport(socket, firstReport, byteArrayOf(5)))
        assertNull(modes.observeReport(socket, firstReport, byteArrayOf(4, 1)))
        assertEquals(1, modes.observeReport(socket, firstReport, byteArrayOf(1)))
    }

    @Test fun reportBaselineBelongsToOneCapturedTransportEvenIfDisconnectStateWasCoalesced() {
        val modes = IslandListeningMode(); val first = Any(); val replacement = Any()
        assertNull(modes.observeReport(first, null, byteArrayOf(2)))
        assertEquals(3, modes.observeReport(first, byteArrayOf(2), byteArrayOf(3)))
        assertNull(modes.observeReport(replacement, byteArrayOf(3), byteArrayOf(1)))
        assertEquals(4, modes.observeReport(replacement, byteArrayOf(1), byteArrayOf(4)))
        assertNull(modes.observeReport(null, byteArrayOf(4), null))
        assertNull(modes.observeReport(replacement, null, byteArrayOf(2)))
    }

    @Test fun temporaryTransparencyAndRestorationBeforeTheTerminalAreSilent() {
        for (original in listOf(1, 2, 4)) {
            val reports = Reports()
            assertNull(reports.report(original))
            assertNull(reports.stage(1))
            assertNull(reports.report(3, stage = 2))
            assertNull(reports.report(original, stage = 3))
            assertNull(reports.stage(4))
            assertEquals(3, reports.report(3, stage = 4))
            assertEquals(original, reports.report(original, stage = 4))
        }
    }

    @Test fun restorationAfterAnyTerminalOrIdleIsSilentOnlyOnce() {
        for (terminal in listOf(0, 4, 6, 7, 8, 9)) {
            val reports = Reports()
            assertNull(reports.report(2))
            assertNull(reports.report(3, stage = 1))
            assertNull(reports.stage(terminal))
            assertNull(reports.report(2, stage = terminal))
            assertEquals(3, reports.report(3, stage = terminal))
            assertEquals(2, reports.report(2, stage = terminal))
        }
    }

    @Test fun theLatestSpeechStageCanClassifyAnEntryWhoseEarlierStagesWereCoalesced() {
        for (stage in listOf(1, 2, 3)) {
            val reports = Reports()
            assertNull(reports.report(2))
            // A mode-before-speech candidate reaches the helper after the caller's delay.
            assertNull(reports.report(3, stage = stage))
            assertNull(reports.report(2, stage = 4))
            assertEquals(4, reports.report(4, stage = 4))
        }
    }

    @Test fun ordinaryManualChangesWithoutLiveSpeechRemainVisible() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertEquals(3, reports.report(3))
        assertEquals(4, reports.report(4))
        assertEquals(2, reports.report(2))
        assertNull(reports.stage(9))
        assertEquals(3, reports.report(3, stage = 9))
    }

    @Test fun anExplicitTransparencyRequestDuringSpeechAnnouncesAndClearsAutomaticRestoration() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.stage(1))
        assertEquals(3, reports.report(3, stage = 2, explicitRequest = true))
        assertNull(reports.stage(4))
        assertEquals(2, reports.report(2, stage = 4))
        assertEquals(3, reports.report(3, stage = 4))
    }

    @Test fun anExplicitRequestForTheRememberedModeCannotBeMistakenForAutomaticRestoration() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1))
        assertEquals(2, reports.report(2, stage = 2, explicitRequest = true))
        assertNull(reports.stage(4))
        assertEquals(3, reports.report(3, stage = 4))
        assertEquals(2, reports.report(2, stage = 4))
    }

    @Test fun aDifferentModeDuringSpeechAnnouncesAndRetiresTheOldRestorationExpectation() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1))
        assertEquals(4, reports.report(4, stage = 2))
        assertEquals(2, reports.report(2, stage = 4))
        assertEquals(3, reports.report(3, stage = 4))
    }

    @Test fun theRestorationLatchExpiresTwoSecondsAfterSpeechEnds() {
        for (elapsed in listOf(1_999L, 2_001L)) {
            val reports = Reports()
            assertNull(reports.report(2))
            assertNull(reports.report(3, stage = 1))
            reports.now = 10_000
            assertNull(reports.stage(4))
            reports.now += elapsed
            assertEquals(if (elapsed < 2_000) null else 2, reports.report(2, stage = 4))
        }
    }

    @Test fun aPersistedTerminalStageCannotKeepExtendingTheRestorationLatch() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1))
        reports.now = 1_000
        assertNull(reports.stage(4))
        reports.now = 2_500
        assertNull(reports.stage(4))
        reports.now = 3_001
        assertEquals(2, reports.report(2, stage = 4))
    }

    @Test fun resettingOrReplacingTheTransportClearsItsConversationContext() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1))
        assertNull(reports.stage(4))
        reports.modes.reset()
        assertNull(reports.report(4, stage = 4))
        assertEquals(2, reports.report(2, stage = 4))

        assertNull(reports.report(3, stage = 1))
        assertNull(reports.stage(4))
        val replacement = Any()
        val baseline = byteArrayOf(4)
        assertNull(reports.modes.observeReport(replacement, reports.current, baseline, conversationStage = 4))
        assertEquals(2, reports.modes.observeReport(replacement, baseline, byteArrayOf(2), conversationStage = 4))
    }

    @Test fun aStaleReportCannotConsumeThePendingRestorationOrAdvanceItsBaseline() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1))
        assertNull(reports.stage(4))
        assertNull(reports.modes.observeReport(reports.socket, byteArrayOf(3), byteArrayOf(4),
            currentReport = reports.current, conversationStage = 4))
        assertNull(reports.report(2, stage = 4))
        assertEquals(4, reports.report(4, stage = 4))
    }

    @Test fun aCompletedSpeechBurstDuringTheEntryDelayStillSuppressesItsTemporaryModeAndRestoration() {
        for (terminal in listOf(0, 4, 6, 7, 8, 9)) {
            val reports = Reports()
            assertNull(reports.report(2))
            // The caller's 150 ms grace outlasted the entire 1 -> terminal burst.
            assertNull(reports.report(3, stage = terminal, sequence = 1))
            assertNull(reports.report(2, stage = terminal, sequence = 1))
            assertEquals(3, reports.report(3, stage = terminal, sequence = 1))
        }
    }

    @Test fun aLaterSpeechSequenceStartsAnotherCompletedCycleOnTheSameTransport() {
        val reports = Reports()
        assertNull(reports.report(2))
        for (sequence in listOf(1L, 2L)) {
            assertNull(reports.report(3, stage = 4, sequence = sequence))
            assertNull(reports.report(2, stage = 4, sequence = sequence))
        }
        assertEquals(3, reports.report(3, stage = 4, sequence = 2))
    }

    @Test fun aPersistedCompletedSequenceCannotKeepExtendingTheRestorationDeadline() {
        val reports = Reports()
        assertNull(reports.report(2))
        reports.now = 1_000
        assertNull(reports.report(3, stage = 4, sequence = 1))
        reports.now = 2_500
        assertNull(reports.stage(4, sequence = 1))
        reports.now = 3_000
        assertEquals(2, reports.report(2, stage = 4, sequence = 1))
    }

    @Test fun aSuccessfulRequestMarkerOverridesSpeechEvenWhenRequestAndAcknowledgementWereCoalesced() {
        for (requested in listOf(3, 2)) {
            val reports = Reports()
            val marker = byteArrayOf(requested.toByte())
            assertNull(reports.report(2))
            if (requested == 2) assertNull(reports.report(3, stage = 1, sequence = 1))
            // requestedControls has already been cleared by the acknowledgement.
            assertEquals(requested, reports.report(requested, stage = 4, sequence = 1,
                requestedMode = marker))
            val next = if (requested == 3) 2 else 3
            assertEquals(next, reports.report(next, stage = 4, sequence = 1,
                requestedMode = marker))
        }
    }

    @Test fun aRequestMarkerIsNotConsumedUntilItsModeIsConfirmed() {
        val reports = Reports()
        val marker = byteArrayOf(2)
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1, sequence = 1, requestedMode = marker))
        assertEquals(2, reports.report(2, stage = 2, sequence = 2, requestedMode = marker))
        assertNull(reports.stage(4, sequence = 2, requestedMode = marker))
        assertEquals(3, reports.report(3, stage = 4, sequence = 2, requestedMode = marker))
    }

    @Test fun aMatchingRequestOnAnUnchangedReportStillRetiresTheAutomaticRestoration() {
        val reports = Reports()
        val marker = byteArrayOf(3)
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1, sequence = 1))
        assertNull(reports.stage(2, sequence = 2, requestedMode = marker))
        assertNull(reports.stage(4, sequence = 2, requestedMode = marker))
        assertEquals(2, reports.report(2, stage = 4, sequence = 2, requestedMode = marker))
    }

    @Test fun aRequestMarkerIsConsumedByIdentityAndANewSameModeRequestRemainsExplicit() {
        val reports = Reports()
        val marker = byteArrayOf(2)
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1, sequence = 1))
        assertEquals(2, reports.report(2, stage = 2, sequence = 2, requestedMode = marker))
        assertNull(reports.stage(4, sequence = 2, requestedMode = marker))

        assertNull(reports.report(3, stage = 1, sequence = 3, requestedMode = marker))
        assertNull(reports.report(2, stage = 4, sequence = 3, requestedMode = marker))

        assertNull(reports.report(3, stage = 1, sequence = 4, requestedMode = marker))
        assertEquals(2, reports.report(2, stage = 4, sequence = 4, requestedMode = marker.copyOf()))
    }

    @Test fun turningConversationAwarenessOffCannotRewindTheCompletedSpeechSequence() {
        val reports = Reports()
        assertNull(reports.report(2))
        assertNull(reports.report(3, stage = 1, sequence = 1))
        assertNull(reports.report(2, stage = 4, sequence = 1))
        assertNull(reports.stage(0, sequence = 0))
        assertEquals(3, reports.report(3, stage = 4, sequence = 1))
    }

    private class Reports {
        var now = 0L
        val modes = IslandListeningMode(nowMillis = { now })
        val socket = Any()
        var current: ByteArray? = null

        fun report(mode: Int, stage: Int = 0, explicitRequest: Boolean = false,
                   sequence: Long = 0, requestedMode: ByteArray? = null): Int? {
            val previous = current
            current = byteArrayOf(mode.toByte())
            return modes.observeReport(socket, previous, current, conversationStage = stage,
                conversationSequence = sequence,
                requestedMode = if (explicitRequest) byteArrayOf(mode.toByte()) else requestedMode)
        }

        fun stage(stage: Int, sequence: Long = 0, requestedMode: ByteArray? = null): Int? =
            modes.observeReport(socket, current, current, conversationStage = stage,
                conversationSequence = sequence, requestedMode = requestedMode)
    }
}
