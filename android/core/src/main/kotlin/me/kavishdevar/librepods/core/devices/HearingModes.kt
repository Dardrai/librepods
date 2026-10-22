package me.kavishdevar.librepods.core.devices

enum class HearingMode { HEARING_AID, MEDIA_ASSIST }

/** Only complete reports from the current control transport can authorize a mode change. */
data class HearingModes internal constructor(
    internal val hearingReport: ByteArray? = null,
    internal val assistReport: ByteArray? = null,
    internal val pmeReport: ByteArray? = null,
    internal val hearingRevision: Long = 0,
    internal val assistRevision: Long = 0,
    internal val pmeRevision: Long = 0,
    val pending: HearingMode? = null,
    val uncertain: HearingMode? = null,
) {
    companion object { fun empty(): HearingModes = HearingModes() }
    fun withRequestState(pending: HearingMode?, uncertain: HearingMode?): HearingModes = copy(pending = pending, uncertain = uncertain)
    private val reportedHearing: Boolean? get() = hearingReport?.let {
        if (it[7] == 2.toByte() && it[8] == 0.toByte()) false
        else if (it[7] !in byteArrayOf(1, 2) || (it[7] == 2.toByte() && it[8] == 1.toByte())) null
        else flag(it[8])
    }
    private val reportedVoice: Boolean? get() = pmeReport?.let { flag(it[10]) }
    private val reportedMedia: Boolean? get() = pmeReport?.let { flag(it[11]) }
    private val reportedGate: Boolean? get() = assistReport?.let { flag(it[7]) }
    val enrolled: Boolean? get() = hearingReport?.let { flag(it[7]) }
    val hearingAidEnabled: Boolean? get() = if (uncertain != null) null else
        reportedHearing?.let { enabled -> if (!enabled) false else true.takeIf { reportedGate == true } }
    val mediaAssistEnabled: Boolean? get() = if (uncertain != null) null else
        reportedVoice?.let { voice -> reportedMedia?.let { media ->
            if (!voice && !media) false else true.takeIf { reportedGate == true && pmeReport?.get(9) == 1.toByte() }
        } }
    val customTransparencyAllowed: Boolean get() = pending == null && reportedGate == false &&
        hearingAidEnabled == false && mediaAssistEnabled == false

    fun canChange(mode: HearingMode, enabled: Boolean): Boolean = pending == null && plan(mode, enabled) != null

    fun withReport(packet: ByteArray): HearingModes {
        if (packet.size == 11 && packet.take(6) == listOf<Byte>(4, 0, 4, 0, 9, 0) &&
            packet.sliceArray(9..10).all { it == 0.toByte() }) {
            if (packet[6] == 0x2c.toByte() && (packet[7].toInt() and 255) in 0..2 &&
                (packet[8].toInt() and 255) in 0..2)
                return copy(hearingReport = packet.copyOf(), hearingRevision = hearingRevision + 1)
            if (packet[6] == 0x33.toByte() && (packet[7].toInt() and 255) in 0..2 && packet[8] == 0.toByte())
                return copy(assistReport = packet.copyOf(), assistRevision = assistRevision + 1)
        }
        if (packet.size == 140 && packet.take(9) == listOf<Byte>(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2) &&
            packet.sliceArray(9..11).all { (it.toInt() and 255) in 0..2 })
            return copy(pmeReport = packet.copyOf(), pmeRevision = pmeRevision + 1)
        return this
    }

    fun plan(mode: HearingMode, enabled: Boolean): HearingModePlan? {
        if (uncertain != null && (enabled || mode != uncertain)) return null
        val hearing = reportedHearing ?: return null
        val voice = reportedVoice ?: return null
        val media = reportedMedia ?: return null
        val gate = reportedGate ?: return null
        if (enabled && (enrolled != true || gate != (hearing || voice || media))) return null
        if (enabled && mode == HearingMode.MEDIA_ASSIST && pmeReport?.get(9) != 1.toByte()) return null
        val desiredHearing = if (mode == HearingMode.HEARING_AID) enabled else hearing
        val desiredVoice = if (mode == HearingMode.MEDIA_ASSIST) enabled else voice
        val desiredMedia = if (mode == HearingMode.MEDIA_ASSIST) enabled else media
        val desiredGate = desiredHearing || desiredVoice || desiredMedia
        val packets = buildList {
            if (gate != desiredGate || uncertain != null) add(assistReport!!.copyOf().also { it[7] = wireFlag(desiredGate) })
            if (mode == HearingMode.HEARING_AID && (hearing != enabled || uncertain == mode)) {
                add(hearingReport!!.copyOf().also { it[8] = wireFlag(enabled) })
                if (enabled) add(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 3, 0, 0, 0))
            }
            if (mode == HearingMode.MEDIA_ASSIST && (voice != enabled || media != enabled || uncertain == mode))
                add(pmeReport!!.copyOf().also { it[10] = wireFlag(enabled); it[11] = wireFlag(enabled) })
        }
        return HearingModePlan(this, desiredHearing, desiredVoice, desiredMedia, desiredGate, packets)
    }

    fun confirms(plan: HearingModePlan): Boolean =
        (!plan.packets.any { it[6] == 0x2c.toByte() } || hearingRevision > plan.original.hearingRevision) &&
            (!plan.packets.any { it[6] == 0x33.toByte() } || assistRevision > plan.original.assistRevision) &&
            (!plan.packets.any { it[4] == 0x53.toByte() } || pmeRevision > plan.original.pmeRevision) && matches(plan)

    internal fun sameConfiguration(other: HearingModes): Boolean =
        hearingReport.contentEquals(other.hearingReport) && assistReport.contentEquals(other.assistReport) &&
            pmeReport.contentEquals(other.pmeReport)

    internal fun matches(plan: HearingModePlan): Boolean =
        reportedHearing == plan.hearing && reportedVoice == plan.voice && reportedMedia == plan.media &&
            reportedGate == plan.gate && hearingReport?.get(7) == plan.original.hearingReport?.get(7) &&
            pmeReport?.copyOfRange(8, 10).contentEquals(plan.original.pmeReport?.copyOfRange(8, 10)) &&
            pmeReport?.copyOfRange(12, 140).contentEquals(plan.original.pmeReport?.copyOfRange(12, 140))
}

data class HearingModePlan(val original: HearingModes, val hearing: Boolean, val voice: Boolean,
    val media: Boolean, val gate: Boolean, val packets: List<ByteArray>)

private fun flag(value: Byte): Boolean? = when (value.toInt()) { 1 -> true; 2 -> false; else -> null }
private fun wireFlag(value: Boolean): Byte = if (value) 1 else 2
