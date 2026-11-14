package me.kavishdevar.librepods.core.bluetooth.aacp

/** Shared only while an explicit control refresh replaces its sensor transport. */
internal class MotionOwners {
    private val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    @Synchronized fun set(owner: Any, enabled: Boolean) {
        if (enabled) owners.add(owner) else owners.remove(owner)
    }

    @Synchronized fun wanted() = owners.isNotEmpty()
    @Synchronized fun clear() = owners.clear()
}
