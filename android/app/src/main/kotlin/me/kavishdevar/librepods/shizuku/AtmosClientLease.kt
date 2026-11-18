/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.util.concurrent.atomic.AtomicBoolean

/** The Binder itself identifies this lease; old cleanup never resolves a mutable current lease. */
internal class AtmosClientLease(private val remote: AtmosTrackingLease, private val invalidate: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    fun state() = remote.state()
    fun preparation() = remote.preparation()
    fun authorizeHandoff(marker: android.os.Bundle) = !closed.get() && remote.authorizeHandoff(marker)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            invalidate()
            runCatching { remote.retire() } // One-way, no wait for native Codec2.
        }
    }
}
