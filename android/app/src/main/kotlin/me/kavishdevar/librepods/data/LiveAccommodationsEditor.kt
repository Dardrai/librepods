package me.kavishdevar.librepods.data

import kotlinx.coroutines.CoroutineScope
import me.kavishdevar.librepods.core.devices.AccommodationsAudiogram
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AccommodationsSelection
import me.kavishdevar.librepods.core.devices.ReceivedAccommodations

internal data class LiveAccommodationsSource(val owner: Any?, val report: AccommodationsReport?,
    val received: ReceivedAccommodations?, val request: AccommodationsRequestState,
    val writable: Boolean, val audiogram: AccommodationsAudiogram? = null) {
    val selection: AccommodationsSelection? get() = request.requested ?: received?.let {
        AccommodationsSelection(it.phoneEnabled, it.mediaEnabled, it.preset, it.audiogram)
    }
}

/** An editable target, not a received report. Equality binds every intended wire byte. */
internal class LiveAccommodationsValue(val selection: AccommodationsSelection, val packet: List<Byte>, val curveStamp: Long) {
    override fun equals(other: Any?): Boolean = other is LiveAccommodationsValue && packet == other.packet && selection == other.selection
    override fun hashCode(): Int = 31 * packet.hashCode() + selection.hashCode()
}

internal class LiveAccommodationsEditor(scope: CoroutineScope, private val read: () -> LiveAccommodationsSource,
    private val write: suspend (Any, AccommodationsReport, Long, AccommodationsSelection, Boolean) -> AccommodationsResult) {
    private val lock = Any()
    private var lastOwner: Any? = null
    private var lastRevision = -1L
    private var lastReported: List<Byte>? = null
    private var sourceEpoch = 0L
    private var lastExpected: List<Byte>? = null
    private var curveStamp = 0L
    private var appliedCurveStamp = 0L
    @Volatile private var executing = false
    private data class Owner(val target: Any, val epoch: Long)

    private fun target(source: LiveAccommodationsSource, choice: AccommodationsSelection, stamp: Long): LiveAccommodationsValue? {
        val report = source.report ?: return null
        if (choice.audiogram && choice.preset != null) return null
        var plan = report.plan(choice.phone, choice.media, choice.preset)
        if (choice.audiogram) plan = plan.withAudiogram(source.audiogram ?: return null)
        val expected = plan.packet().also { it[9] = report.packet()[9] }
        return LiveAccommodationsValue(choice, expected.toList(), stamp)
    }

    private fun source(): LiveValueSource<LiveAccommodationsValue> = synchronized(lock) {
        val current = read()
        val report = current.report
        val packet = report?.packet()?.toList()
        if (current.owner != lastOwner) {
            lastOwner = current.owner; lastExpected = null; lastReported = null; lastRevision = -1; sourceEpoch++
        }
        if (report?.receptionRevision != lastRevision) {
            if (lastRevision >= 0 && packet != lastReported && packet != lastExpected) sourceEpoch++
            lastRevision = report?.receptionRevision ?: -1
        }
        lastReported = packet
        val value = current.selection?.let { target(current, it, appliedCurveStamp) }
        val owner = current.owner?.takeIf { report != null && value != null }?.let { Owner(it, sourceEpoch) }
        LiveValueSource(owner, value, current.writable && (!current.request.pending || executing))
    }

    private val editor = LiveValueEditor(scope, ::source, valid = { it.packet.size == 140 }, equivalent = { a, b -> a.packet == b.packet }, write = { captured, value ->
        val owner = captured as Owner
        val current = read()
        if (source().owner != captured || current.report == null) false else {
            val applyCurves = synchronized(lock) {
                val changed = value.curveStamp > appliedCurveStamp
                appliedCurveStamp = maxOf(appliedCurveStamp, value.curveStamp)
                lastExpected = value.packet
                changed
            }
            executing = true
            try {
                val result = if (applyCurves && value.selection.audiogram && read().audiogram == null)
                    AccommodationsResult.NOT_SENT
                else write(owner.target, current.report, current.request.editRevision, value.selection, applyCurves)
                val accepted = result in listOf(AccommodationsResult.SENT, AccommodationsResult.CONFIRMED, AccommodationsResult.NO_CHANGE)
                if (!accepted) synchronized(lock) { lastExpected = null }
                accepted
            } finally { executing = false }
        }
    })
    val state get() = editor.state
    fun observe() = editor.observe()
    fun flush() = editor.flush()
    fun cancel(revision: Long) = editor.cancel(revision)

    fun edit(revision: Long, changeCurves: Boolean = false,
        change: (AccommodationsSelection) -> AccommodationsSelection): Boolean {
        val base = editor.state.value.requested?.selection ?: read().selection ?: return false
        val choice = change(base)
        val stamp = synchronized(lock) { if (changeCurves) ++curveStamp else editor.state.value.displayed?.curveStamp ?: appliedCurveStamp }
        val value = target(read(), choice, stamp) ?: return false
        return editor.edit(revision, value, force = changeCurves)
    }
}
