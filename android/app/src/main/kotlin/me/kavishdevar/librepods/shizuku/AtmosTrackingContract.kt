/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.Bundle

internal object AtmosTrackingContract {
    const val STATUS_WORDS = 20
    const val DIAGNOSTIC_WORDS = 36
    const val MAX_WINDOW_MS = 15 * 60_000L

    fun validStatus(value: LongArray, rootPid: Int, handoff: LongArray? = null): Boolean = value.size == STATUS_WORDS &&
        value[0] == 1L && (value[1] in 0..6 || value[1] == 7L && handoff != null &&
            AtmosDecoderHandoff.from(handoff)?.phase?.let { it in 1..3 || it in 5..9 } == true) && value[2] in 0..9 && value[19] == rootPid.toLong()

    fun validDiagnostic(value: LongArray, rootPid: Int): Boolean = value.size == DIAGNOSTIC_WORDS &&
        value[0] == 1L && value[1] == 1L && rootPid > 0 && value[19] == rootPid.toLong() && value[35] == 0L

    /** Optional cached first-failure evidence only; never used as authority or polled during healthy playback. */
    fun terminalDiagnostic(live: Boolean, status: LongArray?, rootPid: Int, read: () -> LongArray): LongArray? {
        if (live && (status == null || !validStatus(status, rootPid) || status[1] !in 5L..6L)) return null
        return runCatching { read().takeIf { validDiagnostic(it, rootPid) } }.getOrNull()
    }

    fun validSource(source: Bundle): Boolean = source.getByteArray("full_role")?.let {
        it.size in 1..64 && (it[0].toInt() and 255) in 1..2
    } == true && listOf("model", "firmware").all { source.getString(it)?.length in 1..256 } &&
        source.getInt("profile_schema") == 1 && source.getByteArray("profile_digest")?.let { it.size == 32 && it.any { n -> n != 0.toByte() } } == true &&
        source.getLong("source_epoch") > 0 && source.getLong("reference_epoch") > 0 && source.getInt("discontinuity", -1) in 0..255

    fun validPermit(permit: Bundle, nowMs: Long, boot: Int, address: String): Boolean {
        val issued = permit.getLong("issued_ms")
        val deadline = permit.getLong("deadline_ms")
        return issued > 0 && issued <= nowMs && deadline > nowMs && deadline > issued &&
            deadline - issued <= MAX_WINDOW_MS && deadline <= Long.MAX_VALUE / 1_000_000 &&
            boot >= 0 && permit.getInt("boot", -1) == boot && permit.getString("peer").equals(address, true) &&
            permit.getInt("route_id", -1) > 0 && permit.getInt("directional_mapping", -1) in 1..4
    }
}
