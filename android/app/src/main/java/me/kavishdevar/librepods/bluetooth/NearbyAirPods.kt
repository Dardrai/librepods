/* SPDX-License-Identifier: GPL-3.0-or-later
 * Lid invitation validation follows ntpods battery_ble.rs (Ripthulhu/ntpods).
 */
package me.kavishdevar.librepods.bluetooth

/** Public advertisements supply a model hint, never a Classic Bluetooth identity. */
object NearbyAirPods {
    data class Event(val modelId: Int, val lidOpen: Boolean)

    val modelNames = mapOf(
        0x0220 to "AirPods 1", 0x0f20 to "AirPods 2", 0x1320 to "AirPods 3",
        0x1920 to "AirPods 4", 0x1b20 to "AirPods 4 (ANC)",
        0x0e20 to "AirPods Pro", 0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)", 0x2720 to "AirPods Pro 3",
        0x3020 to "AirPods 5 (Wireless Charging)", 0x3620 to "AirPods 5"
    )

    fun proximityRecord(manufacturer: ByteArray): ByteArray? {
        if (manufacturer.size > 254) return null
        var offset = 0
        var record: ByteArray? = null
        while (offset < manufacturer.size) {
            if (manufacturer.size - offset < 2) return null
            val length = (manufacturer[offset + 1].toInt() and 0xff) + 2
            if (length > manufacturer.size - offset) return null
            if (manufacturer[offset].toInt() == 7) {
                if (length != 27 || record != null) return null
                record = manufacturer.copyOfRange(offset, offset + length)
            }
            offset += length
        }
        return record
    }

    fun lidEvent(manufacturer: ByteArray): Event? {
        val data = proximityRecord(manufacturer) ?: return null
        val model = ((data[3].toInt() and 0xff) shl 8) or (data[4].toInt() and 0xff)
        if (data[2].toInt() != 1 || model !in modelNames || data[5].toInt() and 0x44 == 0) return null
        return Event(model, data[8].toInt() and 8 == 0)
    }

    fun matchesName(modelId: Int, name: String?): Boolean {
        val trimmed = name?.trim() ?: return false
        if (!Regex("\\bAirPods\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return false
        val pro = modelNames[modelId]?.contains("Pro") ?: return false
        return trimmed.contains("Pro", ignoreCase = true) == pro &&
            !trimmed.contains("Max", ignoreCase = true)
    }

    class Discovery(private val modelId: Int) {
        private val addresses = linkedSetOf<String>()
        private var soleSince = 0L
        private var complete = false

        fun observe(address: String, name: String?, now: Long): Boolean {
            if (complete || !matchesName(modelId, name)) return false
            if (addresses.add(address) && addresses.size == 1) soleSince = now
            return true
        }

        fun finish() { complete = true }

        fun automatic(now: Long): String? = addresses.singleOrNull()?.takeIf { complete && now - soleSince >= STABLE_MS }

        companion object { const val STABLE_MS = 2_000L }
    }

    class Visits {
        // shortcut: public reports coalesce same-model pairs; split visits when authenticated identity is available.
        private data class Visit(var at: Long, var open: Boolean, var offered: Boolean)
        private val visits = mutableMapOf<Int, Visit>()

        fun observe(event: Event, now: Long, alreadyPaired: Boolean): Event? {
            val visit = visits.getOrPut(event.modelId) { Visit(now, false, false) }
            if (now - visit.at >= ABSENCE_MS) visit.offered = false
            visit.at = now
            if (!event.lidOpen) {
                val wasOpen = visit.open
                visit.open = false
                visit.offered = false
                return event.takeIf { wasOpen }
            }
            visit.open = true
            if (visit.offered) return null
            visit.offered = true
            return event.takeUnless { alreadyPaired }
        }

        fun expire(now: Long): List<Event> = visits.mapNotNull { (model, visit) ->
            if (visit.open && now - visit.at >= ABSENCE_MS) {
                visit.open = false
                visit.offered = false
                Event(model, false)
            } else null
        }

        fun clear() = visits.clear()
    }

    /** A closed/replaced invitation invalidates every discovery and bond callback. */
    class Session {
        var generation: Long = 0
            private set
        var modelId: Int? = null
            private set

        fun open(model: Int): Long {
            generation++
            modelId = model
            return generation
        }

        fun isCurrent(token: Long): Boolean = modelId != null && generation == token

        fun close(model: Int? = null): Boolean {
            if (model != null && modelId != model) return false
            generation++
            modelId = null
            return true
        }
    }

    private const val ABSENCE_MS = 30_000L
}
