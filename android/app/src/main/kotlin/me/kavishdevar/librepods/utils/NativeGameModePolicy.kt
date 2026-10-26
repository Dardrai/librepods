/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Pure receiver admission/math; native prerequisite probes do not apply this policy. */
internal object NativeGameModePolicy {
    data class Owner(val peer: List<Int>, val mediaGeneration: Long, val controlSession: Long)
    data class Host(val peer: List<Int>, val mediaGeneration: Long, val startedMs: Long,
        val delaySerial: Long, val delayReceivedMs: Long, val delayTenths: Int,
        val reporting: Boolean, val encoderReady: Boolean, val flushReady: Boolean,
        val clockReady: Boolean, val streaming: Boolean, val scoOrMicrophone: Boolean,
        val delayReceivedNs: Long = delayReceivedMs * 1_000_000)
    data class Clock(val intervalTenths: Int, val driftMs: Int)
    private fun owns(expected: Owner, session: Long, host: Host) = expected.controlSession > 0 &&
        session == expected.controlSession && expected.mediaGeneration > 0 &&
        host.mediaGeneration == expected.mediaGeneration && expected.peer == host.peer &&
        expected.peer.size == 6 && expected.peer.all { it in 0..255 } && expected.peer.any { it != 0 }
    private fun ready(host: Host) = host.reporting && host.encoderReady && host.flushReady &&
        host.clockReady && !host.scoOrMicrophone
    fun canApply(expected: Owner, session: Long, host: Host, nowMs: Long) = owns(expected, session, host) &&
        ready(host) && host.streaming && nowMs >= host.startedMs && nowMs - host.startedMs >= 500 &&
        host.delayTenths in 600..65534
    // Cleanup keeps its original owner even after Stop/SCO makes application inadmissible.
    fun canRestore(expected: Owner, session: Long, host: Host) = owns(expected, session, host)
    fun freshRestoreReport(expected: Owner, session: Long, host: Host, beforeSerial: Long, sentMs: Long, nowMs: Long,
        sentNs: Long = sentMs * 1_000_000) =
        owns(expected, session, host) && host.reporting && host.delaySerial != beforeSerial &&
            host.delayReceivedNs >= sentNs && host.delayReceivedMs >= sentMs && nowMs >= sentMs && nowMs - sentMs <= 2000 &&
            host.delayTenths in 1..65534
    fun freshReport(expected: Owner, session: Long, host: Host, beforeSerial: Long, sentMs: Long, nowMs: Long,
        sentNs: Long = sentMs * 1_000_000) =
        owns(expected, session, host) && ready(host) && host.delaySerial != beforeSerial &&
            host.delayReceivedNs >= sentNs && host.delayReceivedMs >= sentMs && nowMs >= sentMs && nowMs - sentMs <= 2000 &&
            host.delayTenths in 1..65534
    fun clock(reportTenths: Int, targetMs: Int): Clock? {
        if (reportTenths !in 1..65534 || targetMs !in listOf(60, 150)) return null
        val report = reportTenths / 10
        var difference = kotlin.math.abs(report - targetMs)
        if (difference > 10 && targetMs != 150) difference -= 10
        if (difference > 500) return null
        return Clock(if (report > targetMs) 11 else if (report < targetMs) 9 else 10, difference)
    }
}
