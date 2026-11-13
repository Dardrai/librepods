package me.kavishdevar.librepods.core.bluetooth.aacp.packet

/**
 * Strict decoder for Apple's OPACK encoding, as carried in smart-routing relays (0x0010/0x0011).
 *
 * Only the value kinds seen on the wire are supported. Anything else, truncation, or
 * unconsumed bytes rejects the whole object. Back references (0xA0..0xBF) are kept as
 * [Reference] because their target table is not needed by any caller.
 */
internal object Opack {
    data class Reference(val index: Int)

    private const val MAX_DEPTH = 4
    private const val MAX_ITEMS = 64

    /**
     * Decodes one top-level dictionary. Up to [maxPadding] trailing zero bytes are tolerated:
     * upstream LibrePods allocates one byte more than it writes for its ShowNearbyUI relay.
     */
    fun decodeDictionary(bytes: ByteArray, maxPadding: Int = 0): Map<String, Any?>? = try {
        val reader = Reader(bytes)
        val value = reader.value(0)
        val rest = bytes.size - reader.offset
        if (value !is Map<*, *> || rest > maxPadding || (reader.offset until bytes.size).any { bytes[it] != 0.toByte() }) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            value as Map<String, Any?>
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Reader(private val bytes: ByteArray) {
        var offset = 0

        private fun byte(): Int {
            require(offset < bytes.size)
            return bytes[offset++].toInt() and 0xff
        }

        private fun take(size: Int): ByteArray {
            require(size >= 0 && size <= bytes.size - offset)
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        private fun littleEndian(size: Int): Long =
            take(size).foldIndexed(0L) { index, acc, b -> acc or ((b.toLong() and 0xff) shl (8 * index)) }

        private fun string(size: Int): String {
            val raw = take(size)
            val text = raw.toString(Charsets.UTF_8)
            require(text.toByteArray(Charsets.UTF_8).contentEquals(raw) && text.none { it.isISOControl() })
            return text
        }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH)
            return when (val tag = byte()) {
                0x01 -> true
                0x02 -> false
                0x04 -> null
                in 0x08..0x2f -> (tag - 8).toLong()
                0x30 -> littleEndian(1).toByte().toLong()
                0x31 -> littleEndian(2).toShort().toLong()
                0x32 -> littleEndian(4).toInt().toLong()
                0x33 -> littleEndian(8)
                0x35 -> Float.fromBits(littleEndian(4).toInt()).toDouble()
                0x36 -> Double.fromBits(littleEndian(8))
                in 0x40..0x60 -> string(tag - 0x40)
                0x61 -> string(byte())
                0x62 -> string(littleEndian(2).toInt())
                in 0x70..0x90 -> take(tag - 0x70)
                0x91 -> take(byte())
                in 0xa0..0xbf -> Reference(tag - 0xa0)
                in 0xd0..0xde -> List(tag - 0xd0) { value(depth + 1) }
                in 0xe0..0xee -> dictionary(tag - 0xe0, depth)
                else -> throw IllegalArgumentException("Unsupported OPACK tag $tag")
            }
        }

        private fun dictionary(count: Int, depth: Int): Map<String, Any?> {
            require(count <= MAX_ITEMS)
            val result = LinkedHashMap<String, Any?>()
            repeat(count) {
                val key = value(depth + 1)
                require(key is String && key !in result)
                result[key] = value(depth + 1)
            }
            return result
        }
    }
}
