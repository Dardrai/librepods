package me.kavishdevar.librepods.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionCallMonitorTest {
    @Test fun everySubscriptionMustBeKnownAndOnlyOneCallMayBeReserved() {
        assertEquals("unknown", CompanionCallMonitor.aggregate(emptyList()))
        assertEquals("unknown", CompanionCallMonitor.aggregate(listOf(0, -1)))
        assertEquals("idle", CompanionCallMonitor.aggregate(listOf(0, 0)))
        assertEquals("ringing", CompanionCallMonitor.aggregate(listOf(0, 1)))
        assertEquals("active", CompanionCallMonitor.aggregate(listOf(2, 0)))
        assertEquals("unknown", CompanionCallMonitor.aggregate(listOf(1, 2)))
    }
}
