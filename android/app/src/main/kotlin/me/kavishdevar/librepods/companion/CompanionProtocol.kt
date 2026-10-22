package me.kavishdevar.librepods.companion

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object CompanionProtocol {
    val SERVICE: UUID = UUID.fromString("bbc1e9f2-6f73-4c61-ae15-6e74706f6473")
    const val MAX_FRAME = 8192
    const val HANDSHAKE_TX = "0000000000000000"
    fun address(value: String): String {
        require(value.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) &&
            value != "00:00:00:00:00:00" && value != "FF:FF:FF:FF:FF:FF") { "Invalid Bluetooth device" }
        return value
    }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun hexBytes(value: String, size: Int): ByteArray {
        require(value.length == size * 2 && value.matches(Regex("[0-9a-f]+"))) { "Invalid companion token" }
        return ByteArray(size) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun nonce(size: Int = 32): String = hex(ByteArray(size).also { SecureRandom().nextBytes(it) })
    const val CAPS = "03"
    fun transcript(device: String, clientNonce: String, serverNonce: String,
        clientCaps: String = CAPS, serverCaps: String = CAPS): ByteArray {
        address(device); hexBytes(clientNonce, 32); hexBytes(serverNonce, 32)
        capabilities(clientCaps); capabilities(serverCaps)
        return ("NTPODS-COMPANION/2\nservice=$SERVICE\nclient=android\nserver=pc\ndevice=$device\n" +
            "client_nonce=$clientNonce\nserver_nonce=$serverNonce\nclient_caps=$clientCaps\nserver_caps=$serverCaps\n").toByteArray(Charsets.US_ASCII)
    }
    fun sid(transcript: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(transcript))
    fun proof(key: ByteArray, role: String, transcript: ByteArray): String {
        require(key.size == 32 && role in setOf("client", "server"))
        return hex(Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update("$role\n".toByteArray(Charsets.US_ASCII)); doFinal(transcript)
        })
    }
    fun verifyProof(expected: String, received: String): Boolean =
        MessageDigest.isEqual(hexBytes(expected, 32), hexBytes(received, 32))

    fun frame(type: String, device: String, tx: String = HANDSHAKE_TX, sid: String? = null): JSONObject =
        JSONObject().put("v", 2).put("type", type).put("tx", tx).put("device", device).apply {
            if (sid != null) put("sid", sid)
        }

    fun validate(frame: JSONObject, device: String, sid: String? = null) {
        require(frame.get("v") == 2 && frame.get("device") == device) { "Incompatible companion session" }
        require(frame.getString("tx").matches(Regex("[0-9a-f]{16}"))) { "Invalid transaction" }
        if (sid != null) require(frame.getString("sid") == sid) { "Retired companion session" }
        val extras = when (frame.getString("type")) {
            "hello" -> setOf("nonce", "caps")
            "challenge" -> setOf("nonce", "proof", "caps")
            "auth" -> setOf("proof")
            "authenticated", "ping", "release", "acquire" -> emptySet()
            "status" -> setOf("connected", "busy", "can_release", "can_acquire")
            "switch" -> setOf("target")
            "prepare" -> setOf("target", "purpose", "episode", "call_gen", "origin_token")
            "pref" -> setOf("key", "rev", "origin") + CompanionPreferences.payloadKeys(frame.getString("key"))
            "pref_ack" -> setOf("key", "rev", "origin", "code")
            "call_origin" -> setOf("origin_token", "ready")
            "call_begin" -> setOf("episode", "call_gen", "origin_token")
            "call_state" -> setOf("episode", "call_gen", "state", "hfp_ready", "sco", "all_idle")
            "call_end" -> setOf("episode", "call_gen", "origin_token", "reason")
            "result" -> setOf("phase", "ok", "code")
            "cancel" -> setOf("reason")
            else -> error("Unknown companion message")
        }
        val expected = setOf("v", "type", "tx", "device") + extras +
            if (frame.getString("type") == "hello") emptySet() else setOf("sid")
        require(frame.keys().asSequence().toSet() == expected) { "Unexpected companion fields" }
        frame.keys().forEach { key ->
            if (key in setOf("connected", "busy", "can_release", "can_acquire", "ok", "ready", "hfp_ready", "all_idle", "pause_media") ||
                key == "value" && frame.optString("key") in CompanionPreferences.boolKeys) require(frame.get(key) is Boolean)
            else if (key != "v") require(frame.get(key) is String)
        }
        if (frame.has("sid")) hexBytes(frame.getString("sid"), 32)
        if (frame.has("nonce")) hexBytes(frame.getString("nonce"), 32)
        if (frame.has("proof")) hexBytes(frame.getString("proof"), 32)
        if (frame.has("caps")) capabilities(frame.getString("caps"))
        val type = frame.getString("type")
        if (type == "pref") CompanionPreferences.decode(frame)
        if (type == "pref_ack") {
            require(frame.getString("key") in CompanionPreferences.keys)
            hexBytes(frame.getString("rev"), 8); require(frame.getString("rev") != HANDSHAKE_TX)
            require(frame.getString("origin") in setOf("pc", "android") && frame.getString("code") in
                setOf("applied", "unchanged", "unsupported", "failed", "stale"))
        }
        if (frame.has("episode")) {
            hexBytes(frame.getString("episode"), 16); hexBytes(frame.getString("call_gen"), 8)
            val manual = type == "prepare" && frame.getString("purpose") == "manual"
            require((frame.getString("episode") == "0".repeat(32)) == manual)
            require((frame.getString("call_gen") == HANDSHAKE_TX) == manual)
        }
        if (frame.has("origin_token")) {
            hexBytes(frame.getString("origin_token"), 16)
            val zeroToken = frame.getString("origin_token") == "0".repeat(32)
            require(zeroToken == (type == "call_origin" && !frame.getBoolean("ready") ||
                type == "prepare" && frame.getString("purpose") == "manual"))
        }
        if (type == "prepare") {
            val purpose = frame.getString("purpose")
            require(purpose in setOf("manual", "call_takeover", "call_return"))
            require(purpose != "call_takeover" || frame.getString("target") == "android")
            require(purpose != "call_return" || frame.getString("target") == "pc")
        }
        if (type == "call_state") {
            require(frame.getString("state") in setOf("ringing", "active", "idle", "unknown"))
            require(frame.getString("sco") in setOf("idle", "connected", "unknown"))
            require(!frame.getBoolean("all_idle") || frame.getString("state") == "idle")
        }
        if (frame.has("target")) require(frame.getString("target") in setOf("pc", "android"))
        if (frame.has("reason")) require(frame.getString("reason") in if (type == "call_end") setOf("ended", "invalidated")
            else setOf("user", "timeout", "lost", "superseded", "failed"))
        if (frame.has("phase")) require(frame.getString("phase") in setOf("prepare", "release", "acquire", "cancel", "complete"))
        if (frame.has("code") && type != "pref_ack") require(frame.getString("code") in setOf("ready", "released", "acquired", "hfp_ready", "cancelled",
            "busy", "unavailable", "invalid_state", "superseded", "timeout", "failed"))
        if (frame.getString("type") == "result") {
            val success = mapOf("prepare" to "ready", "release" to "released", "acquire" to "acquired",
                "cancel" to "cancelled", "complete" to "acquired")
            if (frame.getBoolean("ok")) require(frame.getString("code") == success[frame.getString("phase")] ||
                frame.getString("phase") in setOf("acquire", "complete") && frame.getString("code") == "hfp_ready")
            else require(frame.getString("code") in setOf("busy", "unavailable", "invalid_state", "superseded", "timeout", "failed"))
        }
        val zero = frame.getString("tx") == HANDSHAKE_TX
        require(zero == (type in setOf("hello", "challenge", "auth", "authenticated", "ping", "status", "pref", "pref_ack", "call_origin", "call_state", "call_end")))
    }

    fun capabilities(value: String): Int = hexBytes(value, 1)[0].toInt().also { require(it in 0..3) }

    fun read(input: InputStream): JSONObject {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) throw EOFException("Companion disconnected")
            if (next == 10) break
            require(next != 0 && next != 13 && bytes.size() < MAX_FRAME) { "Invalid companion frame" }
            bytes.write(next)
        }
        require(bytes.size() > 0) { "Empty companion frame" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return parse(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString())
    }

    // The protocol is deliberately flat. Parsing each primitive prevents duplicate-key overwrite and JSON extensions.
    internal fun parse(text: String): JSONObject {
        val input = text.trim(' ', '\t')
        require(input.startsWith('{') && input.endsWith('}')) { "Invalid companion JSON" }
        val property = Pattern.compile("[ \\t]*\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\"[ \\t]*:[ \\t]*(?:\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\"|true|false|2)[ \\t]*")
        val result = JSONObject()
        var position = 1
        while (position < input.length - 1) {
            val matcher = property.matcher(input).region(position, input.length - 1)
            require(matcher.lookingAt()) { "Invalid companion JSON value" }
            val part = JSONObject("{${matcher.group()}}")
            val key = part.keys().next()
            require(!result.has(key)) { "Duplicate companion field" }
            result.put(key, part.get(key))
            position = matcher.end()
            if (position == input.length - 1) break
            require(input[position++] == ',' && position < input.length - 1) { "Invalid companion JSON separator" }
        }
        return result
    }

    fun write(output: OutputStream, frame: JSONObject) {
        val bytes = frame.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME && bytes.none { it == 10.toByte() || it == 13.toByte() })
        output.write(bytes); output.write(10); output.flush()
    }
}
