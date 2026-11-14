package me.kavishdevar.librepods.core.bluetooth.aacp

/** Shared only while an explicit control refresh replaces its sensor transport. */
internal class MotionOwners {
    data class Request(val intervalUs: Int?)
    private val owners = java.util.IdentityHashMap<Any, Request>()

    @Synchronized fun set(owner: Any, enabled: Boolean, intervalUs: Int? = null): Request? {
        val request = if (enabled) Request(intervalUs) else null
        if (request != null) owners[owner] = request else owners.remove(owner)
        return request
    }

    @Synchronized fun request(owner: Any): Request? = owners[owner]
    @Synchronized fun restore(owner: Any, expected: Request, previous: Request?) {
        // A release during a blocked transport write or refresh must never be replayed.
        if (owners[owner] !== expected) return
        if (previous != null) owners[owner] = previous else owners.remove(owner)
    }
    @Synchronized fun fastestIntervalUs(): Int? = owners.values.mapNotNull { it.intervalUs }.minOrNull()
    @Synchronized fun wanted() = owners.isNotEmpty()
    @Synchronized fun clear() = owners.clear()
}
