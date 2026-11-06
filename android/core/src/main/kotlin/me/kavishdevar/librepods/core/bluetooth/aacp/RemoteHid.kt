package me.kavishdevar.librepods.core.bluetooth.aacp

import java.util.Base64

/** RemoteHID evidence: MousePods by Matteo Gaudioso (starman-tech), commit 79736c79. */
internal object RemoteHid {
    private const val DOCTYPE = "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
    private val PREFIX = byteArrayOf(4,0,4,0,0x17,0,0,8,0,0)
    data class Packet(val kind: Int, val device: Int, val response: Boolean, val status: Boolean,
                      val timestampNs: Long?, val payload: ByteArray)
    private fun word(b: ByteArray, at: Int) = (0..3).fold(0L) { n,i -> n or ((b[at+i].toLong() and 255) shl (8*i)) }

    fun packets(body: ByteArray): List<Packet>? = runCatching {
        require(body.size in 8..4096 && body[0] == 0.toByte() && body[3].toInt() and 0xfd == 0)
        val result = mutableListOf<Packet>()
        var at = 4
        while (at < body.size) {
            require(result.size < 32 && at + 4 <= body.size)
            val header = word(body,at)
            val size = ((header ushr 7) and 1023).toInt()
            val kind = ((header ushr 17) and 7).toInt()
            require(size >= 4 && size <= body.size-at && kind <= 5 && header and 0xff800000L == 0L)
            var payload = body.copyOfRange(at+4,at+size)
            val stamped = header and (1L shl 20) != 0L
            var timestamp: Long? = null
            if (stamped) {
                require(kind == 2 && payload.size >= 9 && payload[0] == 0.toByte())
                timestamp = (0..7).fold(0L) { n,i -> n or ((payload[1+i].toLong() and 255) shl (8*i)) }
                require(timestamp >= 0)
                payload = payload.copyOfRange(9,payload.size)
            } else if (kind == 2) {
                require(payload.isNotEmpty() && payload[0] == 0.toByte())
                payload = payload.copyOfRange(1,payload.size)
            }
            result += Packet(kind,(header and 127).toInt(),header and (1L shl 21) != 0L,
                header and (1L shl 22) != 0L,timestamp,payload)
            at += size
        }
        result
    }.getOrNull()

    private fun frame(sequence: Int, device: Int, kind: Int, payload: ByteArray): ByteArray {
        require(device in 0..127 && kind in 0..5 && payload.size <= 1019)
        val h = device or ((4+payload.size) shl 7) or (kind shl 17)
        val size = 8+payload.size
        return PREFIX + byteArrayOf(size.toByte(),(size ushr 8).toByte(),0,sequence.toByte(),0,((device ushr 6)*2).toByte()) +
            ByteArray(4) { (h ushr (8*it)).toByte() } + payload
    }
    fun connect(sequence: Int) = frame(sequence,0,0,byteArrayOf())
    fun feature(sequence: Int,device: Int,report: Int,value: Int): ByteArray {
        require(value >= 0 && report in 1..255)
        return frame(sequence,device,3,byteArrayOf(2,report.toByte()) + ByteArray(4) { (value ushr (8*it)).toByte() })
    }

    private class Plist(var text: String) {
        private var nodes = 0
        fun take(literal: String) {
            text = text.trimStart(' ', '\r', '\n', '\t')
            require(text.startsWith(literal))
            text = text.substring(literal.length)
        }
        fun atom(tag: String): String {
            take("<$tag>")
            val end = text.indexOf('<'); require(end >= 0)
            val value = text.substring(0, end); require('&' !in value)
            text = text.substring(end)
            take("</$tag>")
            return value
        }
        fun value(depth: Int): Pair<String, String> {
            require(++nodes <= 96 && depth <= 4)
            text = text.trimStart(' ', '\r', '\n', '\t')
            for (tag in listOf("string", "integer", "data")) {
                if (text.startsWith("<$tag>")) return atom(tag) to tag
            }
            for (tag in listOf("<true/>", "<false/>")) {
                if (text.startsWith(tag)) { take(tag); return "" to "bool" }
            }
            take("<array>")
            while (!text.trimStart().startsWith("</array>")) value(depth + 1)
            take("</array>")
            return "" to "array"
        }
    }

    fun motionDescriptor(bytes: ByteArray): Boolean = runCatching {
        require(bytes.size <= 1019)
        var text = bytes.toString(Charsets.UTF_8).trim()
        require(bytes.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(bytes))
        if (text.startsWith("<?xml ")) {
            val end = text.indexOf("?>"); require(end in 0..100)
            text = text.substring(end + 2).trimStart()
        }
        if (text.startsWith("<!DOCTYPE")) {
            require(text.startsWith(DOCTYPE)); text = text.substring(DOCTYPE.length).trimStart()
        }
        val p = Plist(text)
        p.take("<plist version=\"1.0\">"); p.take("<dict>")
        val values = mutableMapOf<String, Pair<String, String>>()
        while (!p.text.trimStart().startsWith("</dict>")) {
            val key = p.atom("key")
            require(key.toByteArray().size <= 128 && key !in values && values.size < 24)
            values[key] = p.value(0)
        }
        p.take("</dict>"); p.take("</plist>"); require(p.text.isBlank())
        fun scalar(key: String, kind: String): String {
            val pair = checkNotNull(values[key]); require(pair.second == kind); return pair.first
        }
        val encoded = scalar("ReportDescriptor", "data").filterNot { it in " \r\n\t" }
        require(encoded.isNotEmpty() && encoded.length <= 512 && encoded.length % 4 == 0)
        val descriptor = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(descriptor) == encoded)
        scalar("AccessoryService", "string") == "devmotion6" && scalar("VendorID", "integer").toLong() == 1452L &&
            scalar("MaxReportSize", "integer").toLong() == 182L && descriptor.contentEquals(SensorDescriptorLayout.MOTION_DESCRIPTOR)
    }.getOrDefault(false)
}
