/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.core.devices

import java.util.concurrent.atomic.AtomicReference

/** System queries must never hold the monitor that publishes motion samples. */
internal class SettingsAudioIdle(private val monitor: Any, private val nowNs: () -> Long,
    private val readIdle: () -> Boolean) {
    private data class Snapshot(val startedNs: Long, val idle: Boolean)
    private val snapshot = AtomicReference<Snapshot?>()

    fun isIdle(): Boolean {
        if (!Thread.holdsLock(monitor)) {
            val pending = Snapshot(nowNs(), false)
            snapshot.set(pending)
            val result = pending.copy(idle = runCatching(readIdle).getOrDefault(false))
            if (!result.idle) {
                snapshot.set(result)
                return false
            }
            // A slower, older query cannot replace a newer busy result.
            snapshot.compareAndSet(pending, result)
        }
        val observed = snapshot.get() ?: return false
        return observed.idle && nowNs() - observed.startedNs in 0..250_000_000L
    }
}
