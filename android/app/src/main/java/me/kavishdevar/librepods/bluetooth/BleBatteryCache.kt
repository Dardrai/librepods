/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

/** One immutable preferences snapshot binds both BLE keys to the selected Classic identity. */
internal data class BleKeyBinding(val address: String?, val owner: String?, val irk: String?, val encryptionKey: String?) {
    val ownsKeys: Boolean get() = !address.isNullOrBlank() && owner == address
    fun isCurrent(preferences: Map<String, *>): Boolean = this == from(preferences)

    companion object {
        fun from(preferences: Map<String, *>): BleKeyBinding = BleKeyBinding(
            preferences["mac_address"] as? String, preferences["proximity_key_device_address"] as? String,
            preferences["IRK"] as? String, preferences["ENC_KEY"] as? String)
    }
}

/** Queued Android scan callbacks cannot outlive the scan that produced them. */
internal class BleScanSession {
    private var generation = 0L
    var active = false
        private set
    fun begin(): Long { active = true; return ++generation }
    fun end() { active = false; generation++ }
    fun isCurrent(token: Long): Boolean = active && token == generation
}

/** Preserve radio observation age when Android delivers a delayed scan batch. */
internal fun bleObservationTime(nowMillis: Long, nowNanos: Long, observedNanos: Long, maxAgeMillis: Long): Long? {
    if (observedNanos < 0 || observedNanos > nowNanos) return null
    val age = (nowNanos - observedNanos) / 1_000_000
    return (nowMillis - age).takeIf { age < maxAgeMillis && it >= 0 }
}

/** One selected pair; absent components retain their last percentage and original age. */
class BleBatteryCache {
    data class Reading(
        val level: Int? = null,
        val updatedAt: Long? = null,
        val present: Boolean = false,
        val charging: Boolean = false
    ) {
        fun live(now: Long): Boolean = present && updatedAt != null && now >= updatedAt && now - updatedAt < FRESH_MS
        fun chargingNow(now: Long): Boolean = charging && live(now)
    }

    data class Snapshot(val left: Reading = Reading(), val right: Reading = Reading(), val case: Reading = Reading())

    var snapshot = Snapshot()
        private set
    private var lastObservationNanos: Long? = null

    fun update(left: Int?, leftCharging: Boolean, right: Int?, rightCharging: Boolean,
               case: Int?, caseCharging: Boolean, now: Long, observationNanos: Long): Snapshot {
        if (lastObservationNanos?.let { observationNanos <= it } == true) return snapshot
        fun merge(old: Reading, level: Int?, charging: Boolean): Reading =
            if (level != null && level in 0..100) Reading(level, now, true, charging)
            else old.copy(present = false, charging = false)
        snapshot = Snapshot(merge(snapshot.left, left, leftCharging), merge(snapshot.right, right, rightCharging),
            merge(snapshot.case, case, caseCharging))
        lastObservationNanos = observationNanos
        return snapshot
    }

    fun clear() { snapshot = Snapshot(); lastObservationNanos = null }

    companion object { const val FRESH_MS = 90_000L }
}
