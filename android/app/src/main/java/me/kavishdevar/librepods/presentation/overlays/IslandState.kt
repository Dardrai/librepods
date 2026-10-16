/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryStatus

data class IslandSnapshot(
    val address: String,
    val name: String,
    val modelNumber: String? = null,
    val batteries: List<Battery> = emptyList(),
    val connected: Boolean = true
)

/** Cached art and component percentages belong to exactly one paired address. */
internal class IslandState {
    var snapshot: IslandSnapshot? = null
        private set
    var dismissAt = 0L
        private set

    fun show(next: IslandSnapshot, now: Long, holdMillis: Long = 4500) {
        if (snapshot?.address != next.address) snapshot = null
        merge(next)
        dismissAt = now + holdMillis.coerceAtLeast(0)
    }

    fun update(next: IslandSnapshot): Boolean {
        if (dismissAt == 0L || snapshot?.address != next.address) return false
        merge(next)
        return true
    }

    private fun merge(next: IslandSnapshot) {
        val previous = snapshot
        val batteries = previous?.batteries.orEmpty().associateBy { it.component }.toMutableMap()
        next.batteries.forEach { incoming ->
            val old = batteries[incoming.component]
            batteries[incoming.component] = when {
                incoming.updatedAt == null || incoming.level !in 0..100 -> old?.copy(live = false, status = BatteryStatus.DISCONNECTED) ?: incoming
                else -> incoming
            }
        }
        snapshot = next.copy(modelNumber = next.modelNumber?.takeIf { it.isNotBlank() } ?: previous?.modelNumber,
            batteries = batteries.values.toList())
    }

    fun hide() { dismissAt = 0L }
}

internal data class IslandBatteryReading(val level: Int?, val updatedAt: Long?, val live: Boolean, val charging: Boolean)

internal fun islandBatteryReading(battery: Battery?, now: Long): IslandBatteryReading {
    val level = battery?.level?.takeIf { battery.updatedAt != null && it in 0..100 }
    val live = level != null && battery.status != BatteryStatus.DISCONNECTED && battery.isLive(now)
    return IslandBatteryReading(level, battery?.updatedAt, live, live && battery.status == BatteryStatus.CHARGING)
}

internal fun islandStageOpacity(expansion: Float, start: Float): Float {
    val value = ((expansion - start) / (1 - start)).coerceIn(0f, 1f)
    return value * value * (3 - 2 * value)
}
