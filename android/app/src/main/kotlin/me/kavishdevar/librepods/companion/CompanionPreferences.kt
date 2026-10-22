package me.kavishdevar.librepods.companion

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal

internal class CompanionProjectionSuperseded : Exception()

/** Only explicitly shared values live here. Device reports and local consent never enter this store. */
internal class CompanionPreferences(context: Context,
    private val read: suspend (String) -> Map<String, JSONObject>,
    private val apply: suspend (String, String, JSONObject, () -> Boolean) -> Boolean,
    private val supported: (String, String, JSONObject) -> Boolean = { _, _, _ -> true },
    private val localIntent: (String, String) -> String = { _, _ -> "0".repeat(32) },
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "companion-preferences-v2"))
    private val mutex = Mutex()
    private val records = mutableMapOf<String, MutableMap<String, Record>>()
    private val observed = mutableMapOf<String, MutableMap<String, Record>>()
    private var loaded = false
    private val unapplied = mutableSetOf<Pair<String, String>>()
    private fun scope(device: String, key: String) = if (key == "theme") "global" else device
    private fun record(device: String, key: String) = records[scope(device, key)]?.get(key)
    data class Record(val key: String, val rev: ULong, val origin: String, val payload: JSONObject, val pending: Boolean = false,
        val intent: String = "0".repeat(32)) {
        fun frame(device: String, sid: String) = CompanionProtocol.frame("pref", device, sid = sid)
            .put("key", key).put("rev", rev.toString(16).padStart(16, '0')).put("origin", origin).apply {
                payload.keys().forEach { put(it, payload.get(it)) }
            }
    }

    private fun load() {
        if (loaded) return
        val stagedRecords = mutableMapOf<String, MutableMap<String, Record>>()
        val stagedObserved = mutableMapOf<String, MutableMap<String, Record>>()
        val stagedPending = mutableSetOf<Pair<String, String>>()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val bytes = file.readFully()
            require(bytes.size <= 1_048_576)
            val all = JSONArray(bytes.toString(Charsets.UTF_8))
            require(all.length() <= 64 * 16 * 2)
            for (i in 0 until all.length()) {
                val row = all.getJSONObject(i)
                require(row.get("_pending") is Boolean && row.get("_observed") is Boolean)
                val device = row.getString("device").let { if (it == "global" && row.getString("key") == "theme") it else CompanionProtocol.address(it) }
                val record = decode(row).copy(pending = row.getBoolean("_pending"), intent = row.getString("_intent"))
                CompanionProtocol.hexBytes(record.intent, 16)
                require(row.keys().asSequence().toSet() == setOf("v", "type", "tx", "device", "sid", "key", "rev", "origin", "_pending", "_observed", "_intent") + payloadKeys(record.key))
                require(row.get("v") == 2 && row.getString("type") == "pref" && row.getString("tx") == CompanionProtocol.HANDSHAKE_TX &&
                    row.getString("sid") == "0".repeat(64))
                val target = if (row.getBoolean("_observed")) stagedObserved else stagedRecords
                require(target !== stagedObserved || !record.pending)
                require(target.getOrPut(device) { mutableMapOf() }.put(record.key, record) == null)
                if (target === stagedRecords && record.pending) stagedPending += device to record.key
            }
        }
        for ((device, values) in stagedRecords) for ((key, value) in values) {
            val seen = stagedObserved[device]?.get(key)
            require(seen == null || compare(value, seen) != 0 || same(value.payload, seen.payload))
        }
        records.clear(); records.putAll(stagedRecords)
        observed.clear(); observed.putAll(stagedObserved)
        unapplied.clear(); unapplied.addAll(stagedPending)
        loaded = true
    }

    private fun save(next: Map<String, Map<String, Record>>, seen: Map<String, Map<String, Record>> = observed) {
        val all = JSONArray()
        for ((source, isObserved) in listOf(next to false, seen to true))
            source.toSortedMap().forEach { (device, values) -> values.toSortedMap().values.forEach { value ->
                all.put(value.frame(device, "0".repeat(64)).put("_pending", value.pending).put("_observed", isObserved).put("_intent", value.intent))
            } }
        val bytes = all.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576 && next.size <= 65 && seen.size <= 65)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun commit(device: String, record: Record) {
        val device = scope(device, record.key)
        val next = records.mapValues { it.value.toMutableMap() }.toMutableMap()
        next.getOrPut(device) { mutableMapOf() }[record.key] = record
        save(next)
        records.clear(); records.putAll(next)
    }

    private fun remember(device: String, incoming: Record) {
        val id = scope(device, incoming.key)
        val old = observed[id]?.get(incoming.key)
        if (old != null && compare(incoming, old) <= 0) return
        val next = observed.mapValues { it.value.toMutableMap() }.toMutableMap()
        next.getOrPut(id) { mutableMapOf() }[incoming.key] = incoming.copy(pending = false)
        save(records, next)
        observed.clear(); observed.putAll(next)
    }

    /** Compare actual local values, so replayed remote values do not create a new revision. */
    suspend fun localChanges(device: String): List<Record> = mutex.withLock {
        load()
        project(device)
        val changes = mutableListOf<Record>()
        for ((key, payload) in read(device)) {
            validatePayload(key, payload)
            if (scope(device, key) to key in unapplied) continue
            val old = record(device, key)
            if (old == null || !same(old.payload, payload)) {
                val revision = maxOf(old?.rev ?: 0uL, observed[scope(device, key)]?.get(key)?.rev ?: 0uL)
                check(revision != ULong.MAX_VALUE) { "Shared preference revision exhausted" }
                val next = Record(key, revision + 1uL, "android", payload, intent = localIntent(device, key))
                commit(device, next); changes += next
            }
        }
        changes
    }

    suspend fun snapshot(device: String): List<Record> {
        localChanges(device)
        return mutex.withLock { read(device).keys.mapNotNull { key -> record(device, key)?.takeIf {
            scope(device, key) to key !in unapplied
        } } }
    }

    /** Acknowledgement follows the canonical commit and its durable host projection. */
    suspend fun receive(device: String, incoming: Record): Pair<String, Record?> = mutex.withLock {
        try { load() } catch (_: Exception) { return@withLock "failed" to null }
        val old = record(device, incoming.key)
        val previouslyObserved = observed[scope(device, incoming.key)]?.get(incoming.key)
        for (known in listOfNotNull(old, observed[scope(device, incoming.key)]?.get(incoming.key)))
            if (compare(incoming, known) == 0) require(same(known.payload, incoming.payload)) { "Shared preference revision collision" }
        try { remember(device, incoming) } catch (_: Exception) { return@withLock "failed" to null }
        if (previouslyObserved != null && compare(incoming, previouslyObserved) < 0)
            return@withLock "stale" to old?.takeIf { !it.pending && compare(it, previouslyObserved) >= 0 }
        if (incoming.key !in read(device) || !supported(device, incoming.key, incoming.payload)) return@withLock "unsupported" to null
        val order = if (old == null) 1 else compare(incoming, old)
        if (order == 0) {
            require(same(old!!.payload, incoming.payload)) { "Shared preference revision collision" }
            if (!old.pending) return@withLock "unchanged" to null
            return@withLock (if (projectOne(device, old)) "unchanged" else "failed") to null
        }
        if (order < 0) return@withLock "stale" to old?.takeUnless { it.pending }
        val pending = incoming.copy(pending = true, intent = localIntent(device, incoming.key))
        try { commit(device, pending) } catch (_: Exception) { return@withLock "failed" to null }
        unapplied += scope(device, incoming.key) to incoming.key
        (if (projectOne(device, pending)) "applied" else "failed") to null
    }

    private suspend fun project(device: String) {
        for (key in read(device).keys) {
            val id = scope(device, key) to key
            if (id !in unapplied) continue
            val saved = record(device, key) ?: continue
            projectOne(device, saved)
        }
    }

    private suspend fun projectOne(device: String, saved: Record): Boolean = try {
        if (saved.intent != localIntent(device, saved.key)) throw CompanionProjectionSuperseded()
        if (!supported(device, saved.key, saved.payload) || withTimeoutOrNull(3000) {
                apply(device, saved.key, saved.payload) { saved.intent == localIntent(device, saved.key) }
            } != true) false
        else {
            if (saved.intent != localIntent(device, saved.key)) throw CompanionProjectionSuperseded()
            commit(device, saved.copy(pending = false))
            unapplied -= scope(device, saved.key) to saved.key
            true
        }
    } catch (_: CompanionProjectionSuperseded) {
        // The adapter observed a newer explicit value while its durable projection was pending.
        try { commit(device, saved.copy(pending = false)) }
        catch (_: Exception) { records[scope(device, saved.key)]?.set(saved.key, saved.copy(pending = false)) }
        unapplied -= scope(device, saved.key) to saved.key
        false
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    suspend fun restore(device: String) = mutex.withLock { load(); project(device) }

    companion object {
        val boolKeys = setOf("host_auto_pause", "connection_popup_enabled", "cache_disconnected_battery",
            "call_takeover_enabled", "call_return_enabled")
        val desiredKeys = setOf("desired_microphone_side", "desired_ear_detection", "desired_noise_modes_cycle",
            "desired_swipe_duration", "desired_case_sounds", "desired_case_volume", "desired_allow_off")
        val keys = boolKeys + desiredKeys + setOf("theme", "conversation_policy", "desired_hold_actions")
        fun decode(frame: JSONObject): Record {
            val key = frame.getString("key")
            val rev = frame.getString("rev")
            require(rev.matches(Regex("[0-9a-f]{16}")) && rev != "0".repeat(16))
            val origin = frame.getString("origin"); require(origin in setOf("pc", "android"))
            val payload = JSONObject()
            payloadKeys(key).forEach { payload.put(it, frame.get(it)) }
            validatePayload(key, payload)
            return Record(key, rev.toULong(16), origin, payload)
        }
        fun payloadKeys(key: String): Set<String> = when (key) {
            "conversation_policy" -> setOf("mode", "first", "second", "pause_media")
            "desired_hold_actions" -> setOf("left", "right")
            else -> { require(key in keys); setOf("value") }
        }
        fun validatePayload(key: String, payload: JSONObject) {
            require(payload.keys().asSequence().toSet() == payloadKeys(key))
            if (key in boolKeys) { require(payload.get("value") is Boolean); return }
            if (key == "conversation_policy") {
                require(payload.getString("mode") in setOf("abs_cap", "relative") && payload.get("pause_media") is Boolean)
                val first = decimal(payload.getString("first")); val second = decimal(payload.getString("second"))
                require(second <= first); return
            }
            if (key == "desired_hold_actions") {
                val left = payload.getString("left"); val right = payload.getString("right")
                require(left in setOf("unset", "listening", "assistant") && right in setOf("unset", "listening", "assistant") &&
                    (left == "unset") == (right == "unset")); return
            }
            require(payload.get("value") is String)
            val value = payload.getString("value")
            require(when (key) {
                "theme" -> value in setOf("system", "light", "dark")
                "desired_microphone_side" -> value in setOf("unset", "auto", "right", "left")
                "desired_ear_detection", "desired_case_sounds", "desired_allow_off" -> value in setOf("unset", "on", "off")
                "desired_swipe_duration" -> value in setOf("unset", "0", "1", "2")
                "desired_case_volume" -> value == "unset" || value.matches(Regex("0|[1-9][0-9]?|100"))
                "desired_noise_modes_cycle" -> value == "unset" || value.matches(Regex("0[1-9a-f]")) && value.toInt(16).countOneBits() >= 2
                else -> false
            })
        }
        fun decimal(value: String): BigDecimal {
            require(value.length <= 13 && value.matches(Regex("(?:0|[1-9][0-9]?|100)(?:\\.[0-9]{0,8}[1-9])?")))
            return value.toBigDecimal().also { require(it >= BigDecimal.ZERO && it <= BigDecimal(100)) }
        }
        fun decimal(value: Float): String {
            require(value.isFinite())
            return BigDecimal(value.toString()).stripTrailingZeros().toPlainString().also { decimal(it) }
        }
        private fun same(a: JSONObject, b: JSONObject) = a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
            a.keys().asSequence().all { a.get(it) == b.get(it) }
        internal fun compare(a: Record, b: Record): Int = a.rev.compareTo(b.rev).takeIf { it != 0 }
            ?: (if (a.origin == "pc") 1 else 0).compareTo(if (b.origin == "pc") 1 else 0)
    }
}
