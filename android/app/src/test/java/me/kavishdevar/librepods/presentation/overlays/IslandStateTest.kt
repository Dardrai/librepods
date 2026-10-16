/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import org.junit.Assert.*
import org.junit.Test

class IslandStateTest {
    private fun snapshot(address: String = "pair-a", model: String? = "A3063", batteries: List<Battery> = emptyList()) =
        IslandSnapshot(address, "AirPods", model, batteries)
    private fun battery(component: Int = BatteryComponent.CASE, level: Int = 60, at: Long? = 100,
                        status: Int = BatteryStatus.CHARGING, live: Boolean = true) = Battery(component, level, status, at, live)

    @Test fun routineUpdatesCannotKeepTheIslandAliveOrShowItAfterDismissal() {
        val state = IslandState()
        state.show(snapshot(batteries = listOf(battery())), 100)
        val deadline = state.dismissAt
        assertTrue(state.update(snapshot(batteries = listOf(battery(level = 59, at = 500)))))
        assertEquals(deadline, state.dismissAt)
        state.hide()
        assertFalse(state.update(snapshot()))
        assertEquals(0L, state.dismissAt)
        state.show(snapshot(), 900)
        assertEquals(5400L, state.dismissAt)
    }

    @Test fun cachedArtworkAndBatteriesBelongOnlyToTheirOriginalPair() {
        val state = IslandState()
        state.show(snapshot(batteries = listOf(battery())), 100)
        assertFalse(state.update(snapshot(address = "pair-b")))
        assertEquals("pair-a", state.snapshot!!.address)
        state.show(snapshot(address = "pair-b", model = null), 200)
        assertNull(state.snapshot!!.modelNumber)
        assertTrue(state.snapshot!!.batteries.isEmpty())
    }

    @Test fun missingModelAndUnavailableBatteryRetainTheirOriginalValueAndAge() {
        val state = IslandState()
        state.show(snapshot(batteries = listOf(battery(), battery(BatteryComponent.LEFT, 80))), 100)
        state.update(snapshot(model = null, batteries = listOf(battery(at = null, level = 0, status = BatteryStatus.DISCONNECTED, live = false))))
        assertEquals("A3063", state.snapshot!!.modelNumber)
        val case = state.snapshot!!.batteries.first { it.component == BatteryComponent.CASE }
        assertEquals(60, case.level)
        assertEquals(100L, case.updatedAt)
        assertFalse(islandBatteryReading(case, 200).live)
        assertFalse(islandBatteryReading(case, 200).charging)
        assertEquals(80, state.snapshot!!.batteries.first { it.component == BatteryComponent.LEFT }.level)
    }

    @Test fun aSystemClockCorrectionDoesNotBlockTheNextServiceSnapshot() {
        val state = IslandState()
        state.show(snapshot(batteries = listOf(battery(at = 500))), 100)
        state.update(snapshot(batteries = listOf(battery(level = 10, at = 400))))
        assertEquals(10, state.snapshot!!.batteries.single().level)
        assertEquals(400L, state.snapshot!!.batteries.single().updatedAt)
    }

    @Test fun zeroIsKnownWhileUnknownValuesHaveNoGaugeOrChargingIndicator() {
        val zero = islandBatteryReading(battery(level = 0), 200)
        assertEquals(0, zero.level)
        assertTrue(zero.live)
        assertTrue(zero.charging)
        val unknown = islandBatteryReading(battery(level = 255), 200)
        assertNull(unknown.level)
        assertFalse(unknown.charging)
        assertNull(islandBatteryReading(battery(at = null), 200).level)
        assertNull(islandBatteryReading(null, 200).level)
    }

    @Test fun staleUnavailableAndClockReversedReadingsNeverShowCharging() {
        assertFalse(islandBatteryReading(battery(), 90_100).charging)
        assertFalse(islandBatteryReading(battery(), 99).charging)
        assertFalse(islandBatteryReading(battery(status = BatteryStatus.DISCONNECTED), 200).charging)
        assertFalse(islandBatteryReading(battery(live = false), 200).charging)
        assertEquals(60, islandBatteryReading(battery(), 90_100).level)
    }

    @Test fun headerRevealsBeforeBatteriesAndTheEndpointsAreStable() {
        assertEquals(0f, islandStageOpacity(0f, 0.08f), 0f)
        assertEquals(0f, islandStageOpacity(0.3f, 0.32f), 0f)
        assertTrue(islandStageOpacity(0.5f, 0.08f) > islandStageOpacity(0.5f, 0.32f))
        assertEquals(1f, islandStageOpacity(1f, 0.08f), 0f)
        assertEquals(1f, islandStageOpacity(1f, 0.32f), 0f)
    }
}
