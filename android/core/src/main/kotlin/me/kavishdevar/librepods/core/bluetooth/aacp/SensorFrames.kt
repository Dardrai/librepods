package me.kavishdevar.librepods.core.bluetooth.aacp

/** Reassemble bounded RTBuddy frames before handing their payloads to generated protobuf types. */
internal class SensorFrames {
    private var carry = byteArrayOf()
    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.size > 16_384 || carry.size + chunk.size > 32_768) {
            carry = byteArrayOf()
            return emptyList()
        }
        val input = carry + chunk
        val frames = mutableListOf<ByteArray>()
        var cursor = 0
        while (input.size - cursor >= PREFIX.size) {
            if (!PREFIX.indices.all { input[cursor + it] == PREFIX[it] }) { cursor++; continue }
            if (input.size - cursor < 12) break
            val length = (input[cursor + 10].toInt() and 255) or ((input[cursor + 11].toInt() and 255) shl 8)
            val descriptor = input.copyOfRange(cursor + 6, cursor + 10)
            val remote = descriptor.contentEquals(byteArrayOf(0, 8, 0, 0))
            if (length > if (remote) 4096 else 16_384) { cursor += PREFIX.size; continue }
            val end = cursor + 12 + length
            if (end > input.size) break
            if (remote || descriptor.contentEquals(byteArrayOf(0, 0, 0x10, 0))) {
                frames += input.copyOfRange(cursor, end)
            }
            cursor = end
        }
        carry = input.copyOfRange(cursor, input.size)
        return frames
    }
    companion object { private val PREFIX = byteArrayOf(4, 0, 4, 0, 0x17, 0) }
}
