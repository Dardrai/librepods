package me.kavishdevar.librepods.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Mobile-call state only. No number, contact, subscription identity or call history leaves this object. */
internal class CompanionCallMonitor(private val context: Context, private val scope: CoroutineScope,
    private val changed: (String, Boolean) -> Unit) : AutoCloseable {
    private data class Registration(val manager: TelephonyManager, val callback: TelephonyCallback)
    private val registrations = mutableMapOf<Int, Registration>()
    private val states = mutableMapOf<Int, Int>()
    private var previous: String? = null
    private var enabled = false
    private var job: Job? = null
    @Volatile var state = "unknown"; private set

    fun enable(value: Boolean) {
        if (value == enabled) return
        enabled = value
        closeRegistrations()
        job?.cancel(); job = null
        if (!value) { publish("unknown", false); return }
        job = scope.launch {
            while (isActive && enabled) {
                refresh()
                delay(1000)
            }
        }
    }

    private fun refresh() {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            closeRegistrations(); publish("unknown", false); return
        }
        val ids = runCatching {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.map { it.subscriptionId }?.toSet()
        }.getOrNull()
        if (ids.isNullOrEmpty()) { closeRegistrations(); publish("unknown", false); return }
        if (ids == registrations.keys) return
        closeRegistrations()
        publish("unknown", false)
        try {
            for (id in ids) {
                val manager = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(id)
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(value: Int) {
                        if (!enabled || registrations[id]?.callback !== this) return
                        states[id] = value
                        if (states.keys != registrations.keys) { publish("unknown", false); return }
                        val next = aggregate(states.values)
                        val fresh = previous == "idle" && next == "ringing"
                        publish(next, fresh)
                    }
                }
                registrations[id] = Registration(manager, callback)
                manager.registerTelephonyCallback(context.mainExecutor, callback)
            }
        } catch (_: Exception) { closeRegistrations(); publish("unknown", false) }
    }

    private fun publish(value: String, fresh: Boolean) {
        state = value
        if (value != previous || fresh) changed(value, fresh)
        previous = value
    }

    private fun closeRegistrations() {
        registrations.values.forEach { runCatching { it.manager.unregisterTelephonyCallback(it.callback) } }
        registrations.clear(); states.clear(); previous = null
    }

    override fun close() { enabled = false; job?.cancel(); closeRegistrations(); state = "unknown" }

    companion object {
        internal fun aggregate(states: Collection<Int>): String {
            if (states.isEmpty() || states.any { it !in 0..2 } || states.count { it != TelephonyManager.CALL_STATE_IDLE } > 1) return "unknown"
            return when {
                states.any { it == TelephonyManager.CALL_STATE_RINGING } -> "ringing"
                states.any { it == TelephonyManager.CALL_STATE_OFFHOOK } -> "active"
                else -> "idle"
            }
        }
    }
}
