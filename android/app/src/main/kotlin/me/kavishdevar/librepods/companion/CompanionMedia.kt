package me.kavishdevar.librepods.companion

internal data class CompanionMediaSample(val known: Boolean = false, val playing: Boolean = false,
    val connected: Boolean = false, val available: Boolean = false, val player: String = "", val eligible: Boolean = true)
internal data class CompanionMediaState(val generation: Long, val event: Long, val sample: CompanionMediaSample)

/** Generations describe media intent, not the route change caused by our own transfer. */
internal class CompanionMedia {
    private var previous: CompanionMediaSample? = null
    private var generation = 0L
    private var event = 0L
    private var pendingStart: Pair<String, Long>? = null
    @Volatile var latest: CompanionMediaState? = null
        private set
    @Synchronized fun observe(next: CompanionMediaSample, now: Long = System.nanoTime() / 1_000_000): CompanionMediaState {
        val old = previous
        if (old == null || old.known != next.known || old.playing != next.playing || old.player != next.player) {
            generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
        }
        if (!next.known || !next.playing || !next.available || old?.player != next.player ||
            pendingStart?.let { now < it.second || now - it.second > 2000 } == true) pendingStart = null
        val start = old?.known == true && next.known && next.playing && next.available && next.player.isNotEmpty() &&
            (!old.playing || old.player != next.player)
        if (start && !next.eligible) pendingStart = next.player to now
        if (next.eligible && (start || pendingStart?.first == next.player)) {
            event = if (event == Long.MAX_VALUE) 1 else event + 1
            pendingStart = null
        }
        previous = next
        return CompanionMediaState(generation, event, next).also { latest = it }
    }
}
