/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later.
 * Descriptor and report layouts follow NTPods and LibrePods' Android rewrite. */
package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.data.AirPodsFirmware

/** Bounded RTBuddy framing and exact device-supplied sensor descriptions. */
class RtBuddySensors(private var legacyMotionService: Int? = null) {
    data class HeartRate(val bpm: Int, val confidence: Int)
    /** Signed sensor units, not calibrated angles or a spatial-audio pose. */
    data class Motion(val horizontal: Int, val vertical: Int)
    data class Batch(
        val mappingChanged: Boolean,
        val generation: Long,
        val motionService: Int?,
        val heartRateService: Int?,
        val heartRate: HeartRate?,
        val motion: Motion?,
        val legacyMotionAllowed: Boolean = true,
    ) {
        internal fun sameMapping(other: Batch) = generation == other.generation &&
            motionService == other.motionService && heartRateService == other.heartRateService &&
            legacyMotionAllowed == other.legacyMotionAllowed
    }
    private data class Field(val id: Int, val number: Long? = null, val bytes: ByteArray? = null)
    private enum class Sensor { MOTION, HEART_RATE }

    private var carry = byteArrayOf()
    private var lastSequence: Long? = null
    private var generation = 0L
    private var motionService: Int? = null
    private var heartRateService: Int? = null
    private var motionDescriptorSeen = false
    private var heartDescriptorSeen = false

    @Synchronized
    internal fun setLegacyMotionService(service: Int?) { legacyMotionService = service }

    @Synchronized
    fun feed(chunk: ByteArray): Batch {
        var changed = false
        var heart: HeartRate? = null
        var motion: Motion? = null
        if (chunk.size > 65_535 || carry.size + chunk.size > 81_920) {
            carry = byteArrayOf()
            return batch(false, null, null)
        }
        val input = carry + chunk
        var cursor = 0
        while (input.size - cursor >= PREFIX.size) {
            if (!PREFIX.indices.all { input[cursor + it] == PREFIX[it] }) { cursor++; continue }
            if (input.size - cursor < 12) break
            val size = unsigned(input[cursor + 10]) or (unsigned(input[cursor + 11]) shl 8)
            if (size > MAX_PAYLOAD) { cursor += PREFIX.size; continue }
            val end = cursor + 12 + size
            if (end > input.size) break
            val payload = input.copyOfRange(cursor + 12, end)
            val fields = sensorFields(payload)
            if (fields != null) {
                if (discover(fields)) {
                    // Samples preceding a remap belong to the old service table.
                    heart = null
                    motion = null
                    changed = true
                }
                heart = heartRate(fields) ?: heart
                motion = motion(fields) ?: motion
            }
            cursor = end
        }
        carry = input.copyOfRange(cursor, input.size)
        return batch(changed, heart, motion)
    }

    private fun batch(changed: Boolean, heart: HeartRate?, motion: Motion?) =
        Batch(changed, generation, motionService, heartRateService, heart, motion, !motionDescriptorSeen)

    private fun discover(top: List<Field>): Boolean {
        if (unique(top, 2)?.number !in listOf(1L, 3L)) return false
        val announced = mutableListOf<Pair<Int, Sensor>>()
        for (field in top.filter { it.id == 5 }) {
            val nested = fields(field.bytes ?: return false) ?: return false
            val service = unique(nested, 1)?.number?.takeIf { it in 1L..127L }?.toInt() ?: return false
            val descriptor = unique(nested, 2)?.bytes ?: return false
            val sensor = description(descriptor) ?: continue
            if (announced.any { (id, kind) -> (id == service) != (kind == sensor) }) return false
            announced += service to sensor
        }
        if (announced.isEmpty()) return false
        val sequence = unique(top, 1)?.number?.takeIf { it >= 0 } ?: return false
        val restart = sequence == 1L && (lastSequence ?: 0) > 1
        var nextMotion = if (restart) null else motionService
        var nextHeart = if (restart) null else heartRateService
        for ((service, sensor) in announced) when (sensor) {
            Sensor.MOTION -> { if (nextHeart == service) return false; nextMotion = service }
            Sensor.HEART_RATE -> { if (nextMotion == service) return false; nextHeart = service }
        }
        val changed = restart || nextMotion != motionService || nextHeart != heartRateService
        if (restart) generation++
        lastSequence = sequence
        motionService = nextMotion
        heartRateService = nextHeart
        motionDescriptorSeen = motionDescriptorSeen || nextMotion != null
        heartDescriptorSeen = heartDescriptorSeen || nextHeart != null
        return changed
    }

    private fun heartRate(top: List<Field>): HeartRate? {
        if (unique(top, 2)?.number !in listOf(1L, 3L)) return null
        if (heartRateService == null && heartDescriptorSeen) return null
        val reports = mutableListOf<ByteArray>()
        fun payloads(bytes: ByteArray, depth: Int) {
            if (reports.size >= 12) return
            reports += bytes
            if (depth < 3) fields(bytes)?.filter { it.bytes != null }?.forEach {
                payloads(checkNotNull(it.bytes), depth + 1)
            }
        }
        var commands = 0
        fun collect(bytes: ByteArray, depth: Int) {
            if (depth > 3 || commands >= 16) return
            val nested = fields(bytes) ?: return
            val service = unique(nested, 1)?.number
            if (if (heartRateService == null) service in listOf(19L, 20L, 84L) && service != motionService?.toLong()
                else service == heartRateService?.toLong()) {
                commands++
                nested.filter { it.id == 3 && it.bytes != null }.forEach {
                    payloads(checkNotNull(it.bytes), 0)
                }
            }
            if (depth < 3) nested.filter { it.bytes != null }.forEach {
                collect(checkNotNull(it.bytes), depth + 1)
            }
        }
        top.filter { it.id in listOf(5, 7, 8, 9, 12) && it.bytes != null }.forEach {
            collect(checkNotNull(it.bytes), 0)
        }
        val record = reports.firstOrNull { data ->
            data.size == 18 && unsigned(data[1]) in 30..220 && unsigned(data[2]) >= 0x80 &&
                HEART_TAILS.any { tail -> tail.indices.all { data[15 + it] == tail[it] } }
        } ?: return null
        return HeartRate(unsigned(record[1]), unsigned(record[2]))
    }

    private fun motion(top: List<Field>): Motion? {
        val log = unique(top, 2)?.number?.takeIf { it in listOf(1L, 3L, 5L) } ?: return null
        if (top.count { it.id == 3 || it.id == 7 } != 1) return null
        val service = motionService ?: legacyMotionService?.takeUnless { motionDescriptorSeen || it == heartRateService }
            ?: return null
        val packed = unique(top, 3)?.bytes
        val command = unique(top, 7)?.bytes
        val report = if (packed != null && motionService != null) {
            if (!((log in listOf(1L, 3L) && packed.size in listOf(58, 60)) ||
                    (log == 5L && packed.size == 58)) ||
                unsigned(packed[0]) != service || packed[1] != 0.toByte()) return null
            packed.copyOfRange(2, packed.size)
        } else if (command != null && log in listOf(1L, 3L)) {
            val nested = fields(command) ?: return null
            if (unique(nested, 1)?.number != service.toLong()) return null
            unique(nested, 3)?.bytes ?: return null
        } else return null
        if (report.size !in listOf(56, 58) ||
            (report.size == 56 && (motionService == null || report[0] != 1.toByte()))) return null
        fun signed(offset: Int) = (unsigned(report[offset]) or (unsigned(report[offset + 1]) shl 8)).toShort().toInt()
        return Motion(signed(28), signed(30))
    }

    companion object {
        private const val MAX_PAYLOAD = 16_384
        private val PREFIX = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0)
        internal val MOTION_DESCRIPTOR = hex("06 0c ff 09 11 a1 01 06 00 ff 09 23 a1 00 85 01 06 15 ff 09 04 95 01 75 40 81 02 06 0c ff 09 0d 76 b0 05 81 02 85 02 09 0e 81 02 c0 85 01 05 20 0a 0e 03 75 20 b1 02 85 02 0a 1b 03 b1 02 06 15 ff 0a 01 00 85 03 b1 02 85 04 05 20 0a 1a 03 b1 02 85 ff 06 15 ff 0a 20 00 75 08 95 e0 b1 02 c0")
        internal val HEART_DESCRIPTOR = hex("05 20 09 16 a1 01 85 01 0a 0e 03 14 27 ff ff ff 7f 75 20 95 01 b1 02 0a b8 04 26 ff 00 75 08 95 01 81 02 06 15 ff 0a 20 01 95 01 75 08 81 02 26 ff 7f 06 15 ff 0a 21 01 95 01 75 10 81 02 75 08 95 01 15 01 25 02 1a 04 01 2a 05 01 81 00 06 00 ff 09 23 a1 00 15 00 24 06 15 ff 09 04 95 08 75 08 81 02 06 0a ff 09 12 95 04 75 08 81 02 85 02 06 0a ff 09 13 96 58 02 75 08 81 02 c0 c0")
        private val HEART_TAILS = listOf("10 00 00", "10 00 80", "20 00 00", "20 80 00", "20 02 80", "20 82 80").map(::hex)
        private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        private fun unsigned(value: Byte) = value.toInt() and 0xff
        private fun unique(fields: List<Field>, id: Int) = fields.filter { it.id == id }.singleOrNull()

        private fun fields(data: ByteArray): List<Field>? = try {
            require(data.size <= MAX_PAYLOAD)
            var offset = 0
            fun varint(): Long {
                var value = 0L
                for (index in 0..9) {
                    require(offset < data.size)
                    val byte = unsigned(data[offset++])
                    require(index != 9 || byte <= 1)
                    value = value or ((byte and 0x7f).toLong() shl (index * 7))
                    if (byte and 0x80 == 0) return value
                }
                error("Invalid varint")
            }
            val result = mutableListOf<Field>()
            while (offset < data.size) {
                require(result.size < 96)
                val key = varint()
                require(key ushr 3 in 1L..4096L)
                val id = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> result += Field(id, number = varint())
                    2 -> {
                        val length = varint()
                        require(length >= 0 && length <= data.size - offset)
                        val end = offset + length.toInt()
                        result += Field(id, bytes = data.copyOfRange(offset, end))
                        offset = end
                    }
                    1, 5 -> {
                        offset += if (key and 7 == 1L) 8 else 4
                        require(offset <= data.size)
                        result += Field(id)
                    }
                    else -> error("Unsupported protobuf wire type")
                }
            }
            result
        } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

        private fun sensorFields(data: ByteArray): List<Field>? {
            fields(data)?.let { return it.takeUnless { unique(it, 2)?.number == 5L } }
            if (data.size < 2 || data[data.size - 1] != 0xff.toByte() || data[data.size - 2] != 0xff.toByte()) return null
            return fields(data.copyOf(data.size - 2))?.takeIf { unique(it, 2)?.number == 5L }
        }

        private fun description(data: ByteArray): Sensor? = try {
            require(data.size <= MAX_PAYLOAD && data.size >= 8 && unsigned(data[0]) == 0xd3 &&
                data.sliceArray(1..3).all { it == 0.toByte() })
            var offset = 4
            fun take(size: Int): ByteArray {
                require(size >= 0 && size <= data.size - offset)
                val result = data.copyOfRange(offset, offset + size)
                offset = (offset + size + 3) and -4
                require(offset <= data.size)
                return result
            }
            fun word(): Int = take(4).let { bytes ->
                unsigned(bytes[0]) or (unsigned(bytes[1]) shl 8) or (unsigned(bytes[2]) shl 16) or (unsigned(bytes[3]) shl 24)
            }
            fun item(): Pair<Int, ByteArray> {
                val header = word()
                val kind = (header ushr 24) and 0x7f
                val count = header and 0xffffff
                val length = when {
                    kind == 4 && count <= 64 -> 8
                    kind in 8..10 -> count
                    kind == 11 && count <= 1 -> 0
                    else -> error("Unsupported descriptor item")
                }
                return kind to take(length)
            }
            val header = word()
            val count = header and 0xffffff
            require(header and 0x7f000000 == 0x01000000 && count <= 96)
            val values = mutableMapOf<String, Pair<Int, ByteArray>>()
            repeat(count) {
                val (kind, bytes) = item()
                require(kind in 8..9 && bytes.size <= 128)
                val key = bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
                require(!values.containsKey(key))
                values[key] = item()
            }
            require(offset == data.size)
            fun number(key: String): Long? = values[key]?.takeIf { it.first == 4 && it.second.size == 8 }?.second?.let { bytes ->
                var value = 0L
                for (index in bytes.indices) value = value or (unsigned(bytes[index]).toLong() shl (index * 8))
                value
            }
            require(number("VendorID") == 1452L)
            val names = listOfNotNull(values["AccessoryService"], values["HeartRateService"])
            require(names.size == 1 && names[0].first in 8..9)
            val name = names[0].second.toString(Charsets.US_ASCII).trimEnd('\u0000')
            val descriptor = values["ReportDescriptor"]?.takeIf { it.first == 10 }?.second
            when {
                name == "devmotion6" && number("MaxReportSize") == 191L && descriptor.contentEquals(MOTION_DESCRIPTOR) -> Sensor.MOTION
                name == "HeartRate" && number("MaxReportSize") == 601L && descriptor.contentEquals(HEART_DESCRIPTOR) -> Sensor.HEART_RATE
                else -> null
            }
        } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

        private fun sequenceField(sequence: Int): MutableList<Byte> {
            require(sequence in 0..16383)
            var seq = sequence
            val payload = mutableListOf<Byte>(8)
            do {
                val byte = seq and 0x7f
                seq = seq ushr 7
                payload += (byte or if (seq != 0) 0x80 else 0).toByte()
            } while (seq != 0)
            return payload
        }

        fun descriptorRequest(sequence: Int): ByteArray {
            val payload = sequenceField(sequence).apply { add(0x22); add(0) }
            return PREFIX + byteArrayOf(payload.size.toByte(), 0) + payload.toByteArray()
        }

        fun streamCommand(sequence: Int, service: Int, periodUs: Int, report: Int = 1, heartRate: Boolean = false): ByteArray {
            require(service in 1..127 && periodUs >= 0 && report in 1..4)
            val payload = sequenceField(sequence)
            if (heartRate) payload += listOf(0x10, 2).map(Int::toByte)
            payload += listOf(0x42, 11, 8, service, 0x10, 2, 0x1a, 5, report).map(Int::toByte)
            repeat(4) { payload += (periodUs ushr (8 * it)).toByte() }
            return PREFIX + byteArrayOf(payload.size.toByte(), 0) + payload.toByteArray()
        }

        fun publicFirmwareMajor(firmware: String): Int? = AirPodsFirmware.major(firmware)
    }
}
