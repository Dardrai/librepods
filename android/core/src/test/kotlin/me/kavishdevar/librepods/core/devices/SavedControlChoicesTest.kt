package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.*
import org.junit.Test

class SavedControlChoicesTest {
    @Test fun savedIntentCanReplaceACacheButNeverAFreshAccessoryReport() {
        val id = ControlCommandIdentifier.MIC_MODE
        val settings = AppleSettings(savedUnconfirmedChoices = mapOf("desired_microphone_side" to "left"))
        val cached = AppleState(controlStates = mapOf(id to byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(2), cached.effectiveControl(id, settings))
        assertTrue(cached.hasSavedControlFallback(settings))
        val reported = cached.withControlReport(id, byteArrayOf(0))
        assertArrayEquals(byteArrayOf(0), reported.effectiveControl(id, settings))
        assertFalse(reported.hasSavedControlFallback(settings))
        assertTrue(reported.requestedControls.isEmpty())
        assertArrayEquals(byteArrayOf(0), reported.controlStates[id])
    }

    @Test fun sharedSwipeChoicesAdaptTheExistingAndroidOneBasedWireValues() {
        val id = ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL
        for (index in 0..2) {
            assertEquals(index.toString(), savedControlChoice(id, byteArrayOf((index + 1).toByte())))
            assertArrayEquals(byteArrayOf((index + 1).toByte()), savedControlValue(id,
                AppleSettings(savedUnconfirmedChoices = mapOf("desired_swipe_duration" to index.toString()))))
        }
        assertNull(savedControlChoice(id, byteArrayOf(0)))
    }

    @Test fun disablingTheCacheDoesNotKeepAnOmittedUnavailableComponent() {
        val old = Battery(BatteryComponent.CASE, 61, BatteryStatus.DISCONNECTED, observedAt = 1, present = false)
        assertTrue(mergeBatteryReports(setOf(old), emptySet(), false).isEmpty())
        assertEquals(setOf(old), mergeBatteryReports(setOf(old), emptySet(), true))
    }
}
