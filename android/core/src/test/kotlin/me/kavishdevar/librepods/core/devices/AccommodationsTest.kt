package me.kavishdevar.librepods.core.devices

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class AccommodationsTest {
    @Test fun ownedFollowupsPreserveTheLastRequestedCurvesAndUseTheirOwnNoChangeBaseline() {
        val original = report()!!
        val preset = AccommodationsPreset(AccommodationsTuning.BRIGHTNESS, AccommodationsLevel.STRONG)
        val first = original.plan(false, true, preset)
        val second = first.followup(null, false, null)
        assertEquals(AccommodationsSelection(false, false, preset), second.selection)
        assertTrue(second.changesRequired)
        assertArrayEquals(first.packet().copyOfRange(12, 140), second.packet().copyOfRange(12, 140))
        assertFalse(second.followup(false, false, null).changesRequired)
        assertTrue(second.canStart(original))
        assertEquals(AccommodationsResult.UNCONFIRMED, second.result(original, AccommodationsSendOutcome.COMPLETE))
        assertArrayEquals(packet(), original.packet())
    }

    private fun packet(phone: Boolean = true, media: Boolean = false): ByteArray = ByteArray(140).also {
        byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 2,
            if (phone) 1 else 2, if (media) 1 else 2).copyInto(it)
        val values = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 32) values.putFloat(12 + index * 4, index / 4f)
        values.putInt(12, Int.MIN_VALUE)
    }

    private fun report(packet: ByteArray = packet(), session: Long = 7, revision: Long = 10,
        hearing: Boolean? = false, gate: Boolean? = false): AccommodationsReport? =
        AccommodationsReport.parse(packet, session, revision, hearing, gate)

    @Test fun receivedZeroEnrollmentStaysOpaqueAndRequiresExplicitClinicalOffForEditing() {
        for (phone in listOf(false, true)) for (media in listOf(false, true)) {
            val bytes = packet(phone, media).also { it[9] = 0 }
            val preset = AccommodationsPreset(AccommodationsTuning.VOCAL_RANGE, AccommodationsLevel.MODERATE)
            preset.curves().copyInto(bytes, 12)
            val received = ReceivedAccommodations.parse(bytes, 7, 10)!!
            assertEquals(0, received.rawEnrollment)
            assertEquals(phone, received.phoneEnabled)
            assertEquals(media, received.mediaEnabled)
            assertEquals(preset, received.preset)
            assertEquals(7L, received.session)
            assertEquals(10L, received.receptionRevision)
            assertNotNull(report(bytes))
            assertNull(report(bytes, hearing = null))
            assertNull(report(bytes, gate = true))
            assertTrue(AccommodationsRequestState().editable(report(bytes)))
            bytes[9] = 2; bytes[10] = 2; bytes[11] = 2
            assertEquals(0, received.rawEnrollment)
            assertEquals(phone, received.phoneEnabled)
            assertEquals(media, received.mediaEnabled)
        }
        for (enrollment in 0..2) {
            val bytes = packet().also { it[9] = enrollment.toByte() }
            assertEquals(enrollment, ReceivedAccommodations.parse(bytes, 7, 10)!!.rawEnrollment)
            assertEquals(enrollment != 1, report(bytes) != null)
        }
    }

    @Test fun receivedProjectionRejectsMalformedNonfiniteAndUnknownValuesWithoutSupplyingDefaults() {
        val bytes = packet().also { it[9] = 0 }
        for (size in 0 until 140) assertNull(ReceivedAccommodations.parse(bytes.copyOf(size), 7, 10))
        assertNull(ReceivedAccommodations.parse(bytes + 0, 7, 10))
        for (offset in 0..8) assertNull(ReceivedAccommodations.parse(bytes.copyOf().also { it[offset] = 0x7f }, 7, 10))
        for (value in byteArrayOf(3, -1)) assertNull(ReceivedAccommodations.parse(bytes.copyOf().also { it[9] = value }, 7, 10))
        for (offset in 10..11) for (value in byteArrayOf(0, 3, -1))
            assertNull(ReceivedAccommodations.parse(bytes.copyOf().also { it[offset] = value }, 7, 10))
        for (offset in 12 until 140 step 4) for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            val malformed = bytes.copyOf()
            ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
            assertNull(ReceivedAccommodations.parse(malformed, 7, 10))
        }
        for (invalid in listOf(0L, -1L)) {
            assertNull(ReceivedAccommodations.parse(bytes, invalid, 10))
            assertNull(ReceivedAccommodations.parse(bytes, 7, invalid))
        }
        assertNull(ReceivedAccommodations.parse(bytes, 7, 10)!!.preset)
    }

    @Test fun receivedProjectionDoesNotNeedClinicalEligibilityAndMalformedLatestReportsRetireIt() {
        val bytes = packet().also { it[9] = 0 }
        val modes = HearingModes.empty().withReport(bytes)
        assertTrue(modes.hasReceivedPme)
        assertNotNull(modes.receivedAccommodations(7))
        assertNull(modes.receivedAccommodations(0))
        assertNull(modes.accommodationsReport(7))
        assertFalse(modes.customTransparencyAllowed)
        val malformed = modes.withReport(bytes.copyOf(139))
        assertTrue(malformed.hasReceivedPme)
        assertNull(malformed.receivedAccommodations(7))
        assertFalse(HearingModes.empty().hasReceivedPme)
        assertNull(HearingModes.empty().receivedAccommodations(7))
    }

    @Test fun requiresACompleteLegacyRecordAndKnownInactiveClinicalPaths() {
        val packet = packet()
        for (size in 0 until 140) assertNull("size $size", report(packet.copyOf(size)))
        assertNull(report(packet + 0))
        for (offset in 0..9) assertNull("header $offset", report(packet.copyOf().also { it[offset] = 0x7f }))
        assertNull(report(packet.copyOf().also { it[9] = 1 }))
        assertNotNull(report(packet.copyOf().also { it[9] = 0 }))
        for (offset in 10..11) for (flag in byteArrayOf(0, 3, -1))
            assertNull(report(packet.copyOf().also { it[offset] = flag }))
        for (state in listOf(null, true)) {
            assertNull(report(hearing = state)); assertNull(report(gate = state))
        }
        assertNull(report(session = 0)); assertNull(report(session = -1))
        assertNull(report(revision = 0)); assertNull(report(revision = -1))
        assertNotNull(report())
    }

    @Test fun rejectsEveryNonfiniteCurveWithoutChangingTheInput() {
        val packet = packet()
        val original = packet.copyOf()
        for (offset in 12 until 140 step 4) for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val malformed = packet.copyOf()
            ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
            assertNull("offset $offset: $value", report(malformed))
        }
        assertArrayEquals(original, packet)
    }

    @Test fun phoneAndMediaAreIndependentAndPreserveEveryOtherByteIncludingSignedZero() {
        val original = report()!!
        val bytes = original.packet()
        for (phone in listOf(false, true)) for (media in listOf(false, true)) {
            val requested = original.plan(phone, media).packet()
            assertEquals(if (phone) 1.toByte() else 2.toByte(), requested[10])
            assertEquals(if (media) 1.toByte() else 2.toByte(), requested[11])
            for (offset in bytes.indices) if (offset !in 10..11)
                assertEquals("offset $offset", bytes[offset], requested[offset])
        }
        assertArrayEquals(bytes, original.packet())
        assertEquals(true, original.phoneEnabled); assertEquals(false, original.mediaEnabled)
    }

    @Test fun stockPresetTableMatchesTheAnonymousCanonicalFixtureAndOnlyReplacesCurveBytes() {
        val fixture = javaClass.getResourceAsStream("/accommodations-stock-v1.bin")!!.use { it.readBytes() }
        assertEquals(1152, fixture.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        assertEquals("763e3244db48c1e24f36544118a0e73bdf12852574737da2670b38662ce43462", hash)
        val original = report()!!
        val generated = ArrayList<Byte>()
        val unique = HashSet<List<Byte>>()
        var index = 0
        for (tuning in AccommodationsTuning.entries) for (level in AccommodationsLevel.entries) {
            val preset = AccommodationsPreset(tuning, level)
            val requested = original.plan(preset = preset).packet()
            val block = requested.copyOfRange(12, 140)
            assertArrayEquals(fixture.copyOfRange(index * 128, (index + 1) * 128), block)
            index++
            assertArrayEquals(original.packet().copyOfRange(0, 12), requested.copyOfRange(0, 12))
            assertEquals(preset, report(requested)!!.preset)
            val values = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in 0 until 128 step 4) assertTrue(values.getFloat(offset).isFinite())
            assertTrue(unique.add(block.toList()))
            generated.addAll(block.toList())
        }
        assertArrayEquals(fixture, generated.toByteArray())
        assertNull(original.preset)
    }

    @Test fun sendsAndIdenticalCachedDataNeverSubstituteForFreshDeviceReadback() {
        val original = report()!!
        val plan = original.plan(media = true)
        val target = plan.packet()
        val cached = report(target)!!
        val fresh = report(target, revision = 11)!!
        assertEquals(AccommodationsResult.NOT_SENT, plan.result(fresh, AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(null, AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(cached, AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(report(target, revision = 9), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(report(target, session = 8, revision = 11), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(report(revision = 11), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(fresh, AccommodationsSendOutcome.INCOMPLETE))
        assertEquals(AccommodationsResult.CONFIRMED, plan.result(fresh, AccommodationsSendOutcome.COMPLETE))
    }

    @Test fun zeroReportUsesNativeCommandTwoButOnlyTheExactOriginalReportHeaderConfirms() {
        val bytes = packet().also { it[9] = 0 }
        val original = report(bytes)!!
        val plan = original.plan(phone = false, media = true)
        val command = plan.packet()
        assertEquals(2.toByte(), command[9])
        val received = command.copyOf().also { it[9] = 0 }
        for (offset in bytes.indices) if (offset !in 9..11) assertEquals(bytes[offset], command[offset])
        assertEquals(AccommodationsResult.CONFIRMED, plan.result(report(received, revision = 11), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(report(command, revision = 11), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.CONFIRMED, plan.resultAfterRefresh(report(received, session = 8, revision = 1), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.resultAfterRefresh(report(command, session = 8, revision = 1), AccommodationsSendOutcome.COMPLETE))
        val restored = plan.restore(report(received, revision = 11)!!)!!
        assertEquals(2.toByte(), restored.packet()[9])
        assertEquals(AccommodationsResult.CONFIRMED, restored.result(report(bytes, revision = 12), AccommodationsSendOutcome.COMPLETE))
        assertFalse(original.plan().changesRequired)
        assertEquals(AccommodationsResult.NO_CHANGE, original.plan().result(original, AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertArrayEquals(bytes, original.packet())
    }

    @Test fun baselineOwnershipAndEveryDesiredByteAreChecked() {
        val original = report()!!
        val plan = original.plan(media = true)
        assertTrue(plan.canStart(original))
        assertFalse(plan.canStart(report(revision = 9)!!))
        assertFalse(plan.canStart(report(revision = 11)!!))
        assertFalse(plan.canStart(report(session = 8)!!))
        assertFalse(plan.canStart(report(packet().also { it[12] = 1 })!!))
        for (offset in 12 until 140) {
            val changed = plan.packet().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val unexpected = report(changed, revision = 11) ?: continue
            assertEquals("offset $offset", AccommodationsResult.UNCONFIRMED,
                plan.result(unexpected, AccommodationsSendOutcome.COMPLETE))
        }
    }

    @Test fun originalRestorationRequiresFreshExactAppliedStateAndItsOwnNewReadback() {
        val original = report()!!
        val plan = original.plan(phone = false, media = true,
            preset = AccommodationsPreset(AccommodationsTuning.VOCAL_RANGE, AccommodationsLevel.MODERATE))
        assertNull(plan.restore(report(plan.packet())!!))
        assertNull(plan.restore(report(plan.packet(), session = 8, revision = 11)!!))
        assertNull(plan.restore(report(revision = 11)!!))
        val applied = report(plan.packet(), revision = 11)!!
        val restore = plan.restore(applied)!!
        assertTrue(restore.canStart(applied))
        assertArrayEquals(original.packet(), restore.packet())
        assertEquals(AccommodationsResult.UNCONFIRMED, restore.result(report(original.packet(), revision = 11), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.CONFIRMED, restore.result(report(original.packet(), revision = 12), AccommodationsSendOutcome.COMPLETE))
        assertEquals(AccommodationsResult.UNCONFIRMED, restore.result(report(original.packet(), revision = 12), AccommodationsSendOutcome.INCOMPLETE))
    }

    @Test fun unchangedPlansAreExplicitAndStillRequireTheSameReportedConfiguration() {
        val original = report()!!
        val plan = original.plan()
        assertFalse(plan.changesRequired)
        assertEquals(AccommodationsResult.NO_CHANGE, plan.result(original, AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.NO_CHANGE, plan.result(report(revision = 11), AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.NOT_SENT, plan.result(null, AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.NOT_SENT, plan.result(report(revision = 9), AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.NOT_SENT, plan.result(report(packet(media = true)), AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.NOT_SENT, plan.result(report(session = 8), AccommodationsSendOutcome.NOT_ATTEMPTED))
        assertEquals(AccommodationsResult.UNCONFIRMED, plan.result(report(revision = 11), AccommodationsSendOutcome.COMPLETE))
    }

    @Test fun reportsAndPlansOwnTheirByteArrays() {
        val bytes = packet()
        val original = report(bytes)!!
        bytes[10] = 2
        assertTrue(original.phoneEnabled)
        original.packet()[10] = 2
        assertTrue(original.phoneEnabled)
        val plan = original.plan(media = true)
        val expected = plan.packet()
        plan.packet()[9] = 1
        assertArrayEquals(expected, plan.packet())
        assertArrayEquals(expected, original.plan(media = true).packet())
    }

    @Test fun uncertainRequestsNeedANewerActualReportBeforeAnotherEdit() {
        val old = report()!!
        assertTrue(AccommodationsRequestState().editable(old))
        assertFalse(AccommodationsRequestState(pending = true).editable(old))
        val uncertain = AccommodationsRequestState(result = AccommodationsResult.UNCONFIRMED, baselineRevision = old.receptionRevision)
        assertFalse(uncertain.editable(old))
        assertFalse(uncertain.editable(null))
        assertTrue(uncertain.editable(report(revision = 11)))
    }

    @Test fun legacyPhoneMediaCanCoexistWithTransparencyWithoutEnablingClinicalModes() {
        val full = packet(phone = true, media = true)
        val modes = HearingModes().withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 2, 0, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0)).withReport(full)
        assertNotNull(modes.accommodationsReport(1))
        assertTrue(modes.customTransparencyAllowed)
        assertNull(modes.plan(HearingMode.MEDIA_ASSIST, true))
        assertNull(modes.plan(HearingMode.HEARING_AID, true))
        assertFalse(modes.withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0)).customTransparencyAllowed)
        assertNull(modes.withReport(full.copyOf(139)).accommodationsReport(1))
    }
}
