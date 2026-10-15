/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

/** Includes the bounded wait for firmware/service discovery before reports can start. */
internal class MotionReportTimeout {
    private var started: Long? = null

    fun restart(now: Long) { started = now }

    fun expired(wanted: Boolean, now: Long, lastReport: Long): Boolean {
        if (!wanted) { started = null; return false }
        val first = started ?: now.also { started = it }
        return now - maxOf(first, lastReport) >= 5_000
    }
}
