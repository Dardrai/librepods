/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.core.data.apple

import kotlin.math.abs

/** Preserve raw counters; recognize only a short 16-bit crossing corroborated by the sensor clock. */
fun motionCounterStep(before: Long, after: Long, beforeTimeNs: Long?, afterTimeNs: Long?): Long {
    if (before in 0..0xffffL && after in 0..0xffffL && after < before &&
        beforeTimeNs != null && beforeTimeNs > 0 && afterTimeNs != null && afterTimeNs > beforeTimeNs) {
        val elapsed = afterTimeNs - beforeTimeNs
        val wrapped = (after - before) and 0xffffL
        // Captured Pro 3 counter ticks are approximately 5 ms; allow one tick of timing error.
        if (elapsed < 250_000_000L && wrapped > 0 && abs(elapsed - wrapped * 5_000_000L) <= 5_000_000L)
            return wrapped
    }
    return (after - before) and 0xffffffffL
}
