package me.kavishdevar.librepods.core.devices

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HearingModesTest {
    @Test fun legacyDestinationsDoNotMakeExplicitClinicalGateOffLookLikeMissingSettings() {
        val pme = ByteArray(140).also {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 0, 1, 1).copyInto(it)
        }
        val modes = HearingModes.empty()
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 2, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0))
            .withReport(pme)
        assertEquals(false, modes.hearingAidEnabled)
        assertEquals(false, modes.mediaAssistEnabled)
        assertTrue(modes.clinicalReportsKnown)
        assertNotNull(modes.accommodationsReport(7))
        assertFalse(modes.canChange(HearingMode.HEARING_AID, true))
        assertFalse(modes.canChange(HearingMode.MEDIA_ASSIST, true))
        assertNull(modes.copy(assistReport = null).mediaAssistEnabled)
        assertNull(modes.copy(assistReport = null).accommodationsReport(7))
    }

    private fun fixture(hearing: Boolean = false, voice: Boolean = false, media: Boolean = false,
        enrollment: Byte = 1, pmeEnrollment: Byte = 1): HearingModes {
        val pme = ByteArray(140) { (it * 19).toByte() }
        byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, pmeEnrollment,
            flag(voice), flag(media)).copyInto(pme)
        return HearingModes().withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, enrollment, flag(hearing), 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, flag(hearing || voice || media), 0, 0, 0))
            .withReport(pme)
    }

    private fun freshReport(plan: HearingModePlan): HearingModes = plan.original
        .withReport(plan.original.hearingReport!!.copyOf().also { it[8] = flag(plan.hearing) })
        .withReport(plan.original.assistReport!!.copyOf().also { it[7] = flag(plan.gate) })
        .withReport(plan.original.pmeReport!!.copyOf().also { it[10] = flag(plan.voice); it[11] = flag(plan.media) })

    @Test fun unknownAndContradictoryReportsNeverAuthorizeAnEnableOrCustomTransparency() {
        assertNull(HearingModes().hearingAidEnabled)
        assertNull(HearingModes().mediaAssistEnabled)
        assertFalse(HearingModes().customTransparencyAllowed)
        assertFalse(HearingModes().canChange(HearingMode.HEARING_AID, true))
        for (enrollment in 0..2) for (enabled in 0..2) {
            val report = fixture().hearingReport!!.copyOf().also { it[7] = enrollment.toByte(); it[8] = enabled.toByte() }
            val modes = fixture().withReport(report)
            if (enrollment == 0 || (enabled == 0 && enrollment != 2) || (enrollment == 2 && enabled == 1)) {
                assertNull(modes.hearingAidEnabled)
                assertFalse(modes.customTransparencyAllowed)
                assertFalse(modes.canChange(HearingMode.HEARING_AID, true))
            }
        }
        val unknownGate = fixture().withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 0, 0, 0, 0))
        assertFalse(unknownGate.customTransparencyAllowed)
        assertFalse(unknownGate.canChange(HearingMode.MEDIA_ASSIST, true))
    }

    @Test fun completeUnenrolledUnsetEnabledReportIsOffButCannotEnrollTheDevice() {
        val modes = fixture(enrollment = 2).withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 2, 0, 0, 0))
        assertEquals(false, modes.hearingAidEnabled)
        assertEquals(false, modes.enrolled)
        assertNull(modes.plan(HearingMode.HEARING_AID, true))
        assertNull(modes.plan(HearingMode.MEDIA_ASSIST, true))
    }

    @Test fun mediaEnableUsesClinicalEnrollmentAndPreservesOpaquePmeEnrollmentAndEveryCurve() {
        assertNull(fixture(enrollment = 2).plan(HearingMode.MEDIA_ASSIST, true))
        val original = fixture(pmeEnrollment = 0)
        val before = original.pmeReport!!.copyOf()
        val plan = original.plan(HearingMode.MEDIA_ASSIST, true)!!
        val pme = plan.packets.single { it[4] == 0x53.toByte() }
        for (index in before.indices) if (index !in 10..11) assertEquals("offset $index", before[index], pme[index])
        assertEquals(1.toByte(), pme[10]); assertEquals(1.toByte(), pme[11])
        assertArrayEquals(before, original.pmeReport)
        assertFalse(plan.packets.any { it[6] == 0x0d.toByte() })
        assertFalse(before.sliceArray(12..43).contentEquals(before.sliceArray(44..75)))
        assertTrue(freshReport(plan).confirms(plan))
        assertEquals(true, freshReport(plan).mediaAssistEnabled)
        assertEquals(0.toByte(), pme[9])
    }

    @Test fun replacementSessionNeedsAllThreeFreshReportsAndExactUnchangedProfileBytes() {
        val original = fixture(pmeEnrollment = 0).copy(hearingRevision = 20, assistRevision = 30, pmeRevision = 40)
        val plan = original.plan(HearingMode.MEDIA_ASSIST, true)!!
        val matching = freshReport(plan)
        var replacement = HearingModes(pending = HearingMode.MEDIA_ASSIST, uncertain = HearingMode.MEDIA_ASSIST)
            .withReport(matching.hearingReport!!).withReport(matching.assistReport!!)
        assertFalse(replacement.confirmsFreshSession(plan))
        replacement = replacement.withReport(matching.pmeReport!!)
        assertTrue(replacement.confirmsFreshSession(plan))
        assertFalse(replacement.confirms(plan))
        for (offset in intArrayOf(9, 12, 139))
            assertFalse(replacement.withReport(matching.pmeReport!!.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }).confirmsFreshSession(plan))
    }

    @Test fun disablingEitherModePreservesTheOtherAndTheSharedGate() {
        val both = fixture(hearing = true, voice = true, media = true)
        val hearingOff = both.plan(HearingMode.HEARING_AID, false)!!
        assertTrue(hearingOff.gate); assertTrue(hearingOff.voice); assertTrue(hearingOff.media)
        assertFalse(hearingOff.packets.any { it[6] == 0x33.toByte() || it[4] == 0x53.toByte() })
        assertEquals(1.toByte(), hearingOff.packets.single()[7])
        assertEquals(2.toByte(), hearingOff.packets.single()[8])
        val mediaOff = both.plan(HearingMode.MEDIA_ASSIST, false)!!
        assertTrue(mediaOff.gate); assertTrue(mediaOff.hearing)
        assertFalse(mediaOff.packets.any { it[6] == 0x33.toByte() || it[6] == 0x2c.toByte() })
        assertEquals(2.toByte(), mediaOff.packets.single()[10])
        assertEquals(2.toByte(), mediaOff.packets.single()[11])
    }

    @Test fun hearingEnableUsesTransparencyAndMediaEnableKeepsTheListeningMode() {
        val hearing = fixture().plan(HearingMode.HEARING_AID, true)!!
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 3, 0, 0, 0), hearing.packets.last())
        assertTrue(hearing.packets.all { it[4] == 9.toByte() })
        val media = fixture().plan(HearingMode.MEDIA_ASSIST, true)!!
        assertFalse(media.packets.any { it[6] == 0x0d.toByte() })
        assertTrue(media.packets.all { it[4] in byteArrayOf(9, 0x53) })
    }

    @Test fun malformedRecognizedReportsInvalidateStateWhileTheSeparateRoutePropertyIsIgnored() {
        val original = fixture()
        val packet = original.pmeReport!!
        for (length in 0 until 9) assertSame(original, original.withReport(packet.copyOf(length)))
        for (length in 9 until packet.size) assertNull(original.withReport(packet.copyOf(length)).pmeReport)
        assertNull(original.withReport(packet + 0).pmeReport)
        for (offset in intArrayOf(0, 1, 4, 5))
            assertSame(original, original.withReport(packet.copyOf().also { it[offset] = 0x7f }))
        for (offset in intArrayOf(6, 7, 8))
            assertNull(original.withReport(packet.copyOf().also { it[offset] = 0x7f }).pmeReport)
        assertSame(original, original.withReport(byteArrayOf(4, 0, 4, 0, 0x53, 0, 2, 0, 1, 1)))
        assertSame(original, original.withReport(byteArrayOf(4, 0, 4, 0, 9, 1, 0x33, 1, 0, 0, 0)))
        assertNull(original.withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 1, 0, 0)).assistReport)
        assertNull(original.withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c)).hearingReport)
    }

    @Test fun completeUnknownPmeFlagsInvalidateKnownModesWithoutSynthesizingDefaults() {
        val modes = fixture().withReport(fixture().pmeReport!!.copyOf().also { it[10] = 0 })
        assertNull(modes.mediaAssistEnabled)
        assertFalse(modes.customTransparencyAllowed)
        assertNull(modes.plan(HearingMode.HEARING_AID, true))
        assertNull(modes.plan(HearingMode.MEDIA_ASSIST, true))
    }

    @Test fun nonFinitePmeCurvesInvalidateModesAndCannotAuthorizeOrConfirmAnEnable() {
        val original = fixture(pmeEnrollment = 0)
        val plan = original.plan(HearingMode.MEDIA_ASSIST, true)!!
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val packet = freshReport(plan).pmeReport!!.copyOf()
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putFloat(12, value)
            val malformed = original.withReport(packet)
            assertNull(malformed.pmeReport)
            assertNull(malformed.mediaAssistEnabled)
            assertNull(malformed.plan(HearingMode.MEDIA_ASSIST, true))
            assertFalse(malformed.confirmsFreshSession(plan))
        }
    }

    @Test fun successRequiresFreshChangedReportsAndUnchangedEnrollmentAndEveryCurveByte() {
        val original = fixture()
        val plan = original.plan(HearingMode.MEDIA_ASSIST, true)!!
        assertFalse(original.confirms(plan))
        val fresh = freshReport(plan)
        assertTrue(fresh.confirms(plan))
        assertTrue(fresh.copy(hearingRevision = original.hearingRevision).confirms(plan))
        assertFalse(fresh.copy(assistRevision = original.assistRevision).confirms(plan))
        assertFalse(fresh.copy(pmeRevision = original.pmeRevision).confirms(plan))
        assertFalse(fresh.withReport(fresh.hearingReport!!.copyOf().also { it[7] = 2 }).confirms(plan))
        for (offset in 9 until 140) if (offset !in 10..11)
            assertFalse("offset $offset", fresh.withReport(fresh.pmeReport!!.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }).confirms(plan))
    }

    @Test fun explicitOffRecoversAnUncertainEnableWithoutTurningTheOtherModeOff() {
        val uncertain = fixture(voice = true, media = true).copy(uncertain = HearingMode.HEARING_AID)
        assertNull(uncertain.hearingAidEnabled)
        assertNull(uncertain.mediaAssistEnabled)
        assertFalse(uncertain.customTransparencyAllowed)
        assertNull(uncertain.plan(HearingMode.MEDIA_ASSIST, false))
        assertNull(uncertain.plan(HearingMode.HEARING_AID, true))
        val off = uncertain.plan(HearingMode.HEARING_AID, false)!!
        assertTrue(off.gate)
        assertEquals(1.toByte(), off.packets.first()[7])
        assertEquals(2.toByte(), off.packets.last()[8])
        assertTrue(freshReport(off).confirms(off))
        val partialGateEnable = fixture().copy(uncertain = HearingMode.MEDIA_ASSIST)
        val recover = partialGateEnable.plan(HearingMode.MEDIA_ASSIST, false)!!
        assertEquals(0x33.toByte(), recover.packets.first()[6])
        assertEquals(2.toByte(), recover.packets.first()[7])
    }

    @Test fun customTransparencyRequiresBothModesAndGateConfirmedOffWithNoPendingTransaction() {
        assertTrue(fixture().customTransparencyAllowed)
        assertFalse(fixture(hearing = true).customTransparencyAllowed)
        assertFalse(fixture(media = true).customTransparencyAllowed)
        assertFalse(fixture().copy(pending = HearingMode.MEDIA_ASSIST).customTransparencyAllowed)
        assertFalse(fixture().withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0)).customTransparencyAllowed)
    }

    @Test fun reportSnapshotOwnsItsBytesAndDetectsAnExternalChangeBeforeModeWrites() {
        val original = fixture()
        val packet = original.pmeReport!!.copyOf()
        val state = original.withReport(packet)
        packet[12] = (packet[12].toInt() xor 1).toByte()
        assertTrue(original.sameConfiguration(state))
        assertFalse(original.sameConfiguration(state.withReport(packet)))
        assertFalse(state.copy(pending = HearingMode.HEARING_AID).canChange(HearingMode.MEDIA_ASSIST, true))
    }

    private fun flag(value: Boolean): Byte = if (value) 1 else 2
}
