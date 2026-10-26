package me.kavishdevar.librepods.core.devices

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AccommodationsTuning { BALANCED_TONE, VOCAL_RANGE, BRIGHTNESS }
enum class AccommodationsLevel { SLIGHT, MODERATE, STRONG }

data class AccommodationsPreset(val tuning: AccommodationsTuning, val level: AccommodationsLevel) {
    internal fun curves(): ByteArray = stockCurves[tuning.ordinal * 3 + level.ordinal].copyOf()
}

enum class AccommodationsSendOutcome { NOT_ATTEMPTED, COMPLETE, INCOMPLETE }
enum class AccommodationsResult { NO_CHANGE, NOT_SENT, UNCONFIRMED, CONFIRMED, SENT }

data class AccommodationsSelection(val phone: Boolean, val media: Boolean, val preset: AccommodationsPreset?,
    val audiogram: Boolean = false)

data class AccommodationsRequestState(
    val pending: Boolean = false,
    val result: AccommodationsResult? = null,
    val baselineRevision: Long = 0,
    val requested: AccommodationsSelection? = null,
    val editRevision: Long = 0,
) {
    fun editable(report: AccommodationsReport?): Boolean = !pending && report != null &&
        (result != AccommodationsResult.UNCONFIRMED || report.receptionRevision > baselineRevision)
}

/** Received values do not establish enrollment semantics or permission to write. */
class ReceivedAccommodations private constructor(
    val session: Long,
    val receptionRevision: Long,
    val rawEnrollment: Int,
    val phoneEnabled: Boolean,
    val mediaEnabled: Boolean,
    val preset: AccommodationsPreset?,
    val audiogram: Boolean,
) {
    companion object {
        fun parse(packet: ByteArray, session: Long, receptionRevision: Long,
            source: AccommodationsAudiogram? = null): ReceivedAccommodations? {
            if (session <= 0 || receptionRevision <= 0 || !validReceivedAccommodationsPacket(packet)) return null
            val personal = source?.let { it.session == session && it.matchesPme(packet) } == true
            return ReceivedAccommodations(session, receptionRevision, packet[9].toInt(),
                packet[10] == 1.toByte(), packet[11] == 1.toByte(), if (personal) null else stockPreset(packet), personal)
        }
    }
}

/** A complete received legacy PME record, with clinical hearing paths confirmed off. */
class AccommodationsReport private constructor(
    val session: Long,
    val receptionRevision: Long,
    private val record: ByteArray,
) {
    val phoneEnabled: Boolean get() = record[10] == 1.toByte()
    val mediaEnabled: Boolean get() = record[11] == 1.toByte()
    val preset: AccommodationsPreset? get() = stockPreset(record)

    fun packet(): ByteArray = record.copyOf()

    fun plan(phone: Boolean = phoneEnabled, media: Boolean = mediaEnabled,
        preset: AccommodationsPreset? = null): AccommodationsPlan = AccommodationsPlan(this, phone, media, preset)

    internal fun matches(packet: ByteArray): Boolean = record.contentEquals(packet)

    companion object {
        fun parse(packet: ByteArray, session: Long, receptionRevision: Long,
            hearingAidEnabled: Boolean?, sharedGateEnabled: Boolean?): AccommodationsReport? {
            if (session <= 0 || receptionRevision <= 0 || hearingAidEnabled != false || sharedGateEnabled != false ||
                !validLegacyAccommodationsPacket(packet)) return null
            return AccommodationsReport(session, receptionRevision, packet.copyOf())
        }
    }
}

/** Sending is separate from confirmation: only a newer received exact record can confirm. */
class AccommodationsPlan private constructor(
    private val original: AccommodationsReport,
    private val requested: ByteArray,
    private val baseline: ByteArray = original.packet(),
) {
    internal constructor(original: AccommodationsReport, phone: Boolean, media: Boolean,
        preset: AccommodationsPreset?) : this(original, original.packet().also {
            it[10] = if (phone) 1 else 2
            it[11] = if (media) 1 else 2
            preset?.curves()?.copyInto(it, 12)
        })

    val changesRequired: Boolean get() = !baseline.contentEquals(requested)
    val selection: AccommodationsSelection get() = AccommodationsSelection(requested[10] == 1.toByte(),
        requested[11] == 1.toByte(), stockPreset(requested))
    fun packet(): ByteArray = requested.copyOf().also { it[9] = 2 }

    internal fun followup(phone: Boolean?, media: Boolean?, preset: AccommodationsPreset?): AccommodationsPlan =
        AccommodationsPlan(original, requested.copyOf().also {
            phone?.let { value -> it[10] = if (value) 1 else 2 }
            media?.let { value -> it[11] = if (value) 1 else 2 }
            preset?.curves()?.copyInto(it, 12)
        }, requested.copyOf())

    fun withAudiogram(source: AccommodationsAudiogram): AccommodationsPlan {
        require(source.session == original.session)
        return AccommodationsPlan(original, requested.copyOf().also { source.curves().copyInto(it, 12) }, baseline.copyOf())
    }

    internal fun matchesAudiogram(source: AccommodationsAudiogram): Boolean = source.matchesPme(requested)

    internal fun acceptsReported(current: AccommodationsReport): Boolean =
        current.matches(original.packet()) || current.matches(requested)

    fun canStart(current: AccommodationsReport): Boolean = current.session == original.session &&
        current.receptionRevision == original.receptionRevision && current.matches(original.packet())

    fun result(current: AccommodationsReport?, send: AccommodationsSendOutcome): AccommodationsResult {
        val sameSession = current?.session == original.session
        if (!changesRequired && send == AccommodationsSendOutcome.NOT_ATTEMPTED)
            return if (sameSession && current.receptionRevision >= original.receptionRevision && current.matches(requested))
                AccommodationsResult.NO_CHANGE else AccommodationsResult.NOT_SENT
        if (send == AccommodationsSendOutcome.NOT_ATTEMPTED) return AccommodationsResult.NOT_SENT
        return if (changesRequired && send == AccommodationsSendOutcome.COMPLETE && sameSession &&
            current.receptionRevision > original.receptionRevision && current.matches(requested))
            AccommodationsResult.CONFIRMED else AccommodationsResult.UNCONFIRMED
    }

    internal fun resultAfterRefresh(current: AccommodationsReport?, send: AccommodationsSendOutcome): AccommodationsResult =
        if (changesRequired && send == AccommodationsSendOutcome.COMPLETE && current != null &&
            current.session != original.session && current.receptionRevision > 0 && current.matches(requested))
            AccommodationsResult.CONFIRMED else AccommodationsResult.UNCONFIRMED

    fun restore(current: AccommodationsReport): AccommodationsPlan? =
        if (current.session == original.session && current.receptionRevision > original.receptionRevision && current.matches(requested))
            AccommodationsPlan(current, original.packet()) else null
}

internal fun validLegacyAccommodationsPacket(packet: ByteArray): Boolean =
    validReceivedAccommodationsPacket(packet) && packet[9] in byteArrayOf(0, 2)

private fun validReceivedAccommodationsPacket(packet: ByteArray): Boolean {
    if (packet.size != 140 || !packet.copyOfRange(0, 9).contentEquals(pmeHeader) ||
        packet[9] !in byteArrayOf(0, 1, 2) ||
        packet[10] !in byteArrayOf(1, 2) || packet[11] !in byteArrayOf(1, 2)) return false
    val values = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    return (12 until 140 step 4).all { values.getFloat(it).isFinite() }
}

private fun stockPreset(packet: ByteArray): AccommodationsPreset? {
    val curves = packet.copyOfRange(12, 140)
    val index = stockCurves.indexOfFirst { it.contentEquals(curves) }
    return if (index < 0) null else AccommodationsPreset(
        AccommodationsTuning.entries[index / 3], AccommodationsLevel.entries[index % 3])
}

private val pmeHeader = byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2)

// Anonymous captured stock blocks, ordered tuning then level; no personal hearing records.
private val stockCurves = arrayOf<String>(
    "08ac8e41d9ce8f411b2f8f41ba499c41ba49a841b6f3d041c976f641c976f64108ac8e41d9ce8f411b2f8f41ba499c41ba49a841b6f3d041c976f641c976f64108ac8e41d9ce8f411b2f8f41ba499c41ba49a841b6f3d041c976f641c976f64108ac8e41d9ce8f411b2f8f41ba499c41ba49a841b6f3d041c976f641c976f641",
    "6210da413d0af04160e50c42ac9c1e421b2f29429e6f334293184642931846426210da413d0af04160e50c42ac9c1e421b2f29429e6f334293184642931846426210da413d0af04160e50c42ac9c1e421b2f29429e6f334293184642931846426210da413d0af04160e50c42ac9c1e421b2f29429e6f33429318464293184642",
    "ac9c3b4204563e42751343422731604252b86b4208ac6b42d1226142d1226142ac9c3b4204563e42751343422731604252b86b4208ac6b42d1226142d1226142ac9c3b4204563e42751343422731604252b86b4208ac6b42d1226142d1226142ac9c3b4204563e42751343422731604252b86b4208ac6b42d1226142d1226142",
    "426013411b2f2b4139b454419eefea41b81e1e42e17a0842cff7d941cff7d941426013411b2f2b4139b454419eefea41b81e1e42e17a0842cff7d941cff7d941426013411b2f2b4139b454419eefea41b81e1e42e17a0842cff7d941cff7d941426013411b2f2b4139b454419eefea41b81e1e42e17a0842cff7d941cff7d941",
    "25063d41ba498641022be741c1ca534206816a42986e7042aec77642aec7764225063d41ba498641022be741c1ca534206816a42986e7042aec77642aec7764225063d41ba498641022be741c1ca534206816a42986e7042aec77642aec7764225063d41ba498641022be741c1ca534206816a42986e7042aec77642aec77642",
    "b81ece4139b40c42df4f5b429398864231c88c42a2858e42b21d9842b21d9842b81ece4139b40c42df4f5b429398864231c88c42a2858e42b21d9842b21d9842b81ece4139b40c42df4f5b429398864231c88c42a2858e42b21d9842b21d9842b81ece4139b40c42df4f5b429398864231c88c42a2858e42b21d9842b21d9842",
    "39b4144179e91c41d1223b4196438f410000b541b6f31c42500d5542500d554239b4144179e91c41d1223b4196438f410000b541b6f31c42500d5542500d554239b4144179e91c41d1223b4196438f410000b541b6f31c42500d5542500d554239b4144179e91c41d1223b4196438f410000b541b6f31c42500d5542500d5542",
    "17d9af4114aeba4191edd2410ad70b4210d82f425e3a6542c10a8b42c10a8b4217d9af4114aeba4191edd2410ad70b4210d82f425e3a6542c10a8b42c10a8b4217d9af4114aeba4191edd2410ad70b4210d82f425e3a6542c10a8b42c10a8b4217d9af4114aeba4191edd2410ad70b4210d82f425e3a6542c10a8b42c10a8b42",
    "10d825424ee22a421f8535425c8f4f42c9766742b8de8742b21d9442b21d944210d825424ee22a421f8535425c8f4f42c9766742b8de8742b21d9442b21d944210d825424ee22a421f8535425c8f4f42c9766742b8de8742b21d9442b21d944210d825424ee22a421f8535425c8f4f42c9766742b8de8742b21d9442b21d9442",
).map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
