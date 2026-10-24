package me.kavishdevar.librepods.core.devices

import org.junit.Assert.*
import org.junit.Test

class BatteryDisplayTest {
    private val left = Battery(BatteryComponent.LEFT, 80, BatteryStatus.CHARGING, 1000)
    private val right = Battery(BatteryComponent.RIGHT, 60, BatteryStatus.NOT_CHARGING, 1100)

    @Test fun repeatedObservationsAndComponentOrderDoNotChangeTheDisplay() {
        val first = setOf(left, right)
        val fresh = setOf(right.copy(observedAt = 80_000), left.copy(observedAt = 80_100))
        assertTrue(first.hasSameDisplayValues(fresh))
        assertNotEquals(first, fresh)
        assertTrue(emptySet<Battery>().hasSameDisplayValues(emptySet()))
    }

    @Test fun everyVisibleChangeAndComponentAdditionOrRemovalRequiresAnUpdate() {
        val first = setOf(left, right)
        for (changed in listOf(left.copy(level = 79), left.copy(level = 0),
            left.copy(status = BatteryStatus.OPTIMIZED_CHARGING), left.copy(present = false),
            left.copy(status = BatteryStatus.DISCONNECTED, present = false),
            left.copy(observedAt = null), left.copy(component = BatteryComponent.CASE))) {
            assertFalse(first.hasSameDisplayValues(setOf(changed, right)))
        }
        assertFalse(first.hasSameDisplayValues(setOf(left)))
        assertFalse(first.hasSameDisplayValues(first + left.copy(component = BatteryComponent.CASE)))
    }
}
