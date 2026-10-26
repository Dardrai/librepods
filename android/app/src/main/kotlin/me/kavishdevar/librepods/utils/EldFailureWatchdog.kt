/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class EldFailureWatchdog(private val plan: EldCodecHookPlan,
    private val read: () -> EldEncoderHealth?, private val report: (String) -> Unit) {
    private data class Target(val native: Any, val device: Any)
    private val policy = EldFailurePolicy(read)
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled: EldFailurePolicy.Candidate? = null
    private var task: Runnable? = null
    private val streaming = java.util.Collections.synchronizedSet(mutableSetOf<List<Int>>())

    fun selected(native: Any, device: Any?) {
        val peer = runCatching { plan.address.invoke(native, device) as ByteArray }.getOrNull()
        policy.selected(peer?.map { it.toInt() and 255 }, device?.let { Target(native, it) })
        schedule()
    }
    fun codec(peer: ByteArray, type: Int) { policy.codec(peer.map { it.toInt() and 255 }, type); schedule() }
    fun connected(peer: ByteArray) { policy.connected(peer.map { it.toInt() and 255 }); schedule() }
    fun audioChanged(peer: ByteArray, started: Boolean) {
        val key = peer.map { it.toInt() and 255 }
        if (started) { streaming.add(key); policy.resumed(key) } else streaming.remove(key)
        policy.changed(); schedule()
    }
    fun disconnected(peer: ByteArray) {
        val key = peer.map { it.toInt() and 255 }; streaming.remove(key); policy.disconnected(key); schedule()
    }
    fun clear() { streaming.clear(); policy.clear(); schedule() }

    private fun schedule() { handler.post { reconcile() } }
    private fun reconcile() {
        val owner = policy.candidate()
        if (owner == scheduled) return
        task?.let { handler.removeCallbacks(it) }
        task = null; scheduled = owner
        if (owner == null) return
        val pendingDeadline = SystemClock.elapsedRealtime() + 15_000
        val next = object : Runnable {
            override fun run() {
                if (scheduled != owner) return
                policy.changed()
                val confirmed = policy.owner
                val tick = confirmed?.let { policy.tick(it) } ?: EldFailurePolicy.Tick(false)
                tick.fallback?.let { expected ->
                    if (policy.current(expected)) {
                        val target = expected.target as Target
                        val accepted = runCatching {
                            plan.preference.invoke(target.native, target.device, plan.objects.aacFallback()) == true
                        }.getOrDefault(false)
                        report("ELD encoder failed; standard AAC fallback request accepted=$accepted")
                    }
                }
                if (policy.candidate() == owner &&
                    (tick.keepPolling && streaming.contains(owner.peer) || SystemClock.elapsedRealtime() < pendingDeadline))
                    handler.postDelayed(this, 500)
                else { policy.retire(owner); scheduled = null; task = null; reconcile() }
            }
        }
        task = next
        handler.postDelayed(next, 500)
    }
}
