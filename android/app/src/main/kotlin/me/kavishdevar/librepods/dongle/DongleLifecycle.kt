package me.kavishdevar.librepods.dongle

import kotlinx.coroutines.CancellationException

/** Only short publication/cleanup blocks belong here; never socket I/O. */
internal class DongleLifecycle {
    private var generation = 0L
    @Synchronized fun current() = generation
    @Synchronized fun retire(cleanup: () -> Unit): Long {
        generation++
        cleanup()
        return generation
    }
    @Synchronized fun <T> publish(expected: Long, block: () -> T): T {
        if (generation != expected) throw CancellationException("Dongle connection changed")
        return block()
    }
}
