package me.kavishdevar.librepods.core.devices

import org.junit.Assert.*
import org.junit.Test

class HearingModeConfirmationTest {
    private fun control(id: Int, value: Int, second: Int = 0) = byteArrayOf(4, 0, 4, 0, 9, 0, id.toByte(), value.toByte(), second.toByte(), 0, 0)
    private fun pme() = ByteArray(140) { (it * 3).toByte() }.apply {
        byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 2, 2).copyInto(this)
    }
    private fun baseline() = HearingModes().withReport(control(0x2c, 1, 2)).withReport(control(0x33, 2)).withReport(pme())

    @Test fun changedReportsConfirmWithoutRequiringUnchangedCurvesToBeReportedAgain() {
        val original = baseline()
        val plan = original.plan(HearingMode.HEARING_AID, true)!!
        val confirmed = original.withReport(control(0x33, 1)).withReport(control(0x2c, 1, 1))
        assertTrue(confirmed.confirms(plan))
        assertFalse(original.withReport(control(0x33, 1)).confirms(plan))
        assertFalse(original.confirms(plan))
    }

    @Test fun changedMediaReportIsRequiredAndItsCurvesAndEnrollmentArePreserved() {
        val original = baseline()
        val plan = original.plan(HearingMode.MEDIA_ASSIST, true)!!
        val media = plan.packets.single { it[4] == 0x53.toByte() }
        assertArrayEquals(pme().copyOfRange(12, 140), media.copyOfRange(12, 140))
        assertEquals(1.toByte(), media[9])
        assertFalse(original.withReport(control(0x33, 1)).confirms(plan))
        assertTrue(original.withReport(control(0x33, 1)).withReport(media).confirms(plan))
        media[24] = (media[24].toInt() xor 1).toByte()
        assertFalse(original.withReport(control(0x33, 1)).withReport(media).confirms(plan))
    }
}
