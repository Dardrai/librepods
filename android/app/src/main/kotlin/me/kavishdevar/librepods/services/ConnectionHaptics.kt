package me.kavishdevar.librepods.services

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import java.util.Locale

internal class ConnectionHaptics(private val context: Context) {
    private var outputs: Set<String>? = null
    private val pending = mutableSetOf<String>()

    fun update(outputAddresses: Set<String>, pairedAppleAddresses: Set<String>) {
        val current = outputAddresses.mapTo(mutableSetOf()) { it.uppercase(Locale.ROOT) }
        val previous = outputs
        outputs = current
        // Android reports the existing inventory when registering the callback, too.
        if (previous == null) return
        pending.retainAll(current)
        pending += current - previous
        val paired = pairedAppleAddresses.mapTo(mutableSetOf()) { it.uppercase(Locale.ROOT) }
        val connected = pending.intersect(paired)
        pending.removeAll(connected)
        connected.forEach { tick() }
    }

    fun clear() {
        outputs = emptySet()
        pending.clear()
    }

    private fun tick() {
        try {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (vibrator.hasVibrator()) vibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
        } catch (error: RuntimeException) {
            Log.w("ConnectionHaptics", "Connection feedback unavailable", error)
        }
    }
}
