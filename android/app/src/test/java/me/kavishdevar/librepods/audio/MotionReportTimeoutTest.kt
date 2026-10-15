package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class MotionReportTimeoutTest {
    @Test fun unknownFirmwareGetsBoundedGraceInsteadOfUsingDeviceUptime() {
        val timeout = MotionReportTimeout()
        assertFalse(timeout.expired(true, 800_000, 0))
        assertFalse(timeout.expired(true, 804_999, 0))
        assertTrue(timeout.expired(true, 805_000, 0))
    }

    @Test fun lateServiceDiscoveryStartsFreshReportDeadline() {
        val timeout = MotionReportTimeout()
        assertFalse(timeout.expired(true, 800_000, 0))
        timeout.restart(804_000)
        assertFalse(timeout.expired(true, 805_000, 0))
        assertTrue(timeout.expired(true, 809_000, 0))
    }

    @Test fun reportsExtendDeadlineAndOffOrSuspensionClearsIt() {
        val timeout = MotionReportTimeout()
        assertFalse(timeout.expired(true, 100_000, 0))
        assertFalse(timeout.expired(true, 106_000, 104_000))
        assertTrue(timeout.expired(true, 109_000, 104_000))
        assertFalse(timeout.expired(false, 120_000, 104_000))
        assertFalse(timeout.expired(true, 500_000, 104_000))
        assertTrue(timeout.expired(true, 505_000, 104_000))
    }
}
