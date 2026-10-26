/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Private-build lab entry only. No exported receiver, sound command or receiver-buffer write. */
class NativeGameProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operation = intent.getStringExtra("operation") ?: "query"
        if (operation !in setOf("query", "read", "roundtrip")) return
        val expected = intent.getStringExtra("peer")?.takeIf {
            it.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))
        }?.split(':')?.map { it.toInt(16).toByte() }?.toByteArray()
        if (operation != "query" && expected == null) return
        // Finish this broadcast immediately: Android17 queues our RPC reply behind
        // an unfinished broadcast to the same app. The lab keeps the app foreground.
        CoroutineScope(Dispatchers.Default).launch {
            var client: NativeGameTransportClient? = null
            var submitted = false
            try {
                val bridge = NativeGameTransportClient.get(context).also { client = it }
                withTimeout(8_000) {
                    suspend fun query(): NativeGameSnapshot {
                        val reply = bridge.query()
                        check(reply.successful) { "Transport status ${reply.status}" }
                        return checkNotNull(NativeGameSnapshot.parse(reply.values)) { "Invalid native snapshot" }
                    }
                    query()
                    // QUERY posts a refresh; allow its completion before taking the owned tuple.
                    delay(150)
                    var snapshot = query()
                    Log.i(TAG, "$operation before ${snapshot.summary()}")
                    if (operation == "query") return@withTimeout
                    check(snapshot.supported && !snapshot.busy && snapshot.sameHost(expected!!)) {
                        "Selected native route unavailable"
                    }
                    val reply = if (operation == "read") bridge.readFlush(expected,
                        snapshot.mediaGeneration, snapshot.encoderGeneration, snapshot.aclHandle)
                    else bridge.probeFlush(expected, snapshot.mediaGeneration,
                        snapshot.encoderGeneration, snapshot.aclHandle)
                    check(reply.successful) { "Transport status ${reply.status}" }
                    snapshot = checkNotNull(NativeGameSnapshot.parse(reply.values))
                    val serial = snapshot.submittedSerial
                    check(serial > 0) { "Native operation refused" }
                    submitted = true
                    repeat(65) {
                        delay(100)
                        val update = bridge.poll(serial)
                        check(update.successful) { "Poll transport status ${update.status}" }
                        snapshot = checkNotNull(NativeGameSnapshot.parse(update.values))
                        if (!snapshot.busy) {
                            Log.i(TAG, "$operation finished ${snapshot.summary()}")
                            return@withTimeout
                        }
                    }
                    error("Native operation did not finish")
                }
            } catch (failure: Exception) {
                Log.w(TAG, "$operation failed: ${failure.message}")
            } finally {
                if (submitted) runCatching { withTimeout(500) { client?.cancel() } }
            }
        }
    }

    companion object { private const val TAG = "LibrePodsGameProbe" }
}
