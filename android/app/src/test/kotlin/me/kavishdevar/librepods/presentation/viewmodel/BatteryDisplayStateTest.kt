package me.kavishdevar.librepods.presentation.viewmodel

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test

class BatteryDisplayStateTest {
    @Test fun onlyTimestampRefreshesAreSuppressedAndOtherStateStillPropagates() = runBlocking {
        val initial = AppleState(battery = setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.CHARGING, 1000)))
        val fresh = initial.copy(battery = initial.battery.map { it.copy(observedAt = 80_000) }.toSet())
        val role = fresh.copy(leftIsPrimary = false)
        val worn = role.copy(componentState = setOf(DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_EAR)))
        val requested = worn.copy(requestedCustomEq = worn.customEq.copy())
        val absent = worn.copy(battery = fresh.battery.map { it.copy(status = BatteryStatus.DISCONNECTED, present = false) }.toSet())
        val returned = absent.copy(battery = fresh.battery.map { it.copy(observedAt = 170_000) }.toSet())
        assertFalse(sameDisplayedAppleState(worn, requested))
        val shown = listOf(initial, fresh, role, worn, absent, returned).asFlow()
            .distinctUntilChanged(::sameDisplayedAppleState).toList()
        assertEquals(listOf(initial, role, worn, absent, returned), shown)
        assertEquals(80_000L, role.battery.single().observedAt)
    }
}
