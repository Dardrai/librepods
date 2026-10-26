package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Repairs a previously working pair only after Android loses sync on its sole media route. */
internal class BluetoothVolumeSync(
    context: Context,
    private val audio: Audio = ShizukuVolumeSyncAudio(context),
    private val granted: () -> Boolean = {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val pause: (Long) -> Unit = Thread::sleep,
) : AutoCloseable {
    internal data class Route(val address: String, val role: Int, val type: Int, val behavior: Int, val token: Any)
    internal data class Snapshot(val mode: Int, val volume: Int, val maximum: Int, val routes: List<Route>)
    internal interface Audio {
        fun syncEnabled(address: String): Boolean
        fun snapshot(): Snapshot
        fun setVolume(index: Int)
        fun setAbsolute(route: Route)
    }
    private class Observation(val owner: Any, @Volatile var healthyIndex: Int? = null,
        var variableSince: Long? = null, var lastAttempt: Long? = null,
        var pendingCeiling: Int? = null, var pendingMaximum: Int? = null, var expectedVolume: Int? = null) {
        fun abandon() { pendingCeiling = null; pendingMaximum = null; expectedVolume = null }
    }
    private val observations = mutableMapOf<String, Observation>()
    private var closed = false
    private var checking = false
    private var selectedRoute: String? = null

    /** Route changes retire work and restoration intent, while connected owners keep their capability. */
    @Synchronized fun selectRoute(address: String?) {
        if (closed || selectedRoute.equals(address, ignoreCase = true)) return
        selectedRoute = address
        observations.replaceAll { _, previous -> Observation(previous.owner, previous.healthyIndex) }
    }

    /** The caller holds its audio-operation reservation and excludes conversation volume changes. */
    fun check(address: String, owner: Any, stillCurrent: () -> Boolean): Boolean {
        val deadline = now() + 2_000
        val selected = runCatching { A2dpRecoveryGuard(address).address }.getOrNull() ?: return false
        if (selected.take(2).toInt(16) and 1 != 0) return false
        val observation = synchronized(this) {
            if (closed || checking || !selected.equals(selectedRoute, true)) return false
            checking = true
            observations[selected]?.takeIf { it.owner === owner }
                ?: Observation(owner).also { observations[selected] = it }
        }
        fun eligible(): Boolean = now() < deadline && runCatching {
            stillCurrent() && granted()
        }.getOrDefault(false) && synchronized(this) {
            !closed && observations[selected] === observation
        }
        fun read(): Snapshot? {
            if (!eligible()) return null
            val value = audio.snapshot()
            val route = value.routes.singleOrNull()
            if (value.maximum <= 0 || value.volume !in 0..value.maximum || route?.role != 2 ||
                route.type != 8 || !route.address.equals(selected, true)) {
                observation.abandon()
                return null
            }
            return value.takeIf { eligible() && it.mode == AudioManager.MODE_NORMAL }
        }
        try {
            val initial = read() ?: run { observation.variableSince = null; return false }
            if (observation.pendingCeiling != null && (initial.volume != observation.expectedVolume ||
                    initial.maximum != observation.pendingMaximum)) observation.abandon()
            when (initial.routes.single().behavior) {
                3 -> {
                    if (observation.pendingCeiling == null) {
                        if (observation.healthyIndex == null)
                            Log.i("BluetoothVolumeSync", "Observed healthy AirPods volume sync for the current connection")
                        observation.healthyIndex = initial.volume
                        observation.variableSince = null
                        observation.lastAttempt = null
                        return false
                    }
                }
                0 -> Unit
                else -> { observation.abandon(); observation.variableSince = null; return false }
            }
            val healthy = observation.healthyIndex ?: return false
            val since = observation.variableSince ?: now().also { observation.variableSince = it }
            if (now() - since < 500 || observation.lastAttempt?.let { now() - it < 30_000 } == true) return false
            if (!audio.syncEnabled(selected)) return false
            val ceiling = minOf(observation.pendingCeiling ?: initial.volume, healthy,
                (initial.maximum.toLong() * 44 / 100).toInt())
            val before = read() ?: return false
            if (before.volume != initial.volume || before.maximum != initial.maximum ||
                before.routes.single().behavior != initial.routes.single().behavior) { observation.abandon(); return false }
            observation.lastAttempt = now()
            observation.pendingCeiling = ceiling
            observation.pendingMaximum = initial.maximum
            observation.expectedVolume = 0
            // Muting before restoring host gain avoids a burst from the earbuds' independent volume.
            if (before.volume != 0) {
                if (!eligible()) return false
                audio.setVolume(0)
            }
            fun awaitVolume(expected: Int, previous: Int, behavior: Int, allowAbsolute: Boolean = false): Snapshot? {
                val deadline = now() + 500
                while (true) {
                    val current = read() ?: return null
                    val currentBehavior = current.routes.single().behavior
                    if ((currentBehavior != behavior && !(allowAbsolute && currentBehavior == 3)) ||
                        current.maximum != initial.maximum) {
                        observation.abandon(); return null
                    }
                    if (current.volume == expected) return current
                    if (current.volume != previous) { observation.abandon(); return null }
                    if (now() >= deadline) return null
                    pause(25)
                }
            }
            val muted = awaitVolume(0, before.volume, before.routes.single().behavior, allowAbsolute = true) ?: return false
            val ready = read() ?: return false
            if (ready.volume != 0 || ready.maximum != muted.maximum ||
                ready.routes.single().behavior !in setOf(before.routes.single().behavior, 3)) {
                observation.abandon()
                return false
            }
            if (!eligible()) return false
            if (ready.routes.single().behavior == 0) {
                if (!audio.syncEnabled(selected)) return false
                val confirmed = read() ?: return false
                if (confirmed.volume != 0 || confirmed.maximum != initial.maximum ||
                    confirmed.routes.single().behavior !in setOf(0, 3)) { observation.abandon(); return false }
                if (!eligible()) return false
                if (confirmed.routes.single().behavior == 0) audio.setAbsolute(confirmed.routes.single())
            }
            val behaviorDeadline = now() + 500
            while (true) {
                val current = read() ?: return false
                if (current.volume != 0 || current.maximum != initial.maximum) { observation.abandon(); return false }
                when (current.routes.single().behavior) {
                    3 -> break
                    0 -> if (now() >= behaviorDeadline) return false else pause(25)
                    else -> { observation.abandon(); return false }
                }
            }
            for (index in 1..ceiling) {
                pause(40)
                val current = read() ?: return false
                if (current.volume != index - 1 || current.maximum != initial.maximum ||
                    current.routes.single().behavior != 3) { observation.abandon(); return false }
                if (!eligible()) return false
                audio.setVolume(index)
                observation.expectedVolume = index
                if (awaitVolume(index, index - 1, 3) == null) return false
            }
            observation.healthyIndex = ceiling
            observation.abandon()
            observation.variableSince = null
            observation.lastAttempt = null
            Log.i("BluetoothVolumeSync", "Restored previously working AirPods volume sync at a capped level")
            return true
        } catch (error: Exception) {
            observation.variableSince = null
            Log.w("BluetoothVolumeSync", "Volume sync check stopped: ${error.javaClass.simpleName}")
            return false
        } finally {
            synchronized(this) { checking = false }
        }
    }

    @Synchronized fun forget(owner: Any) { observations.entries.removeAll { it.value.owner === owner } }
    @Synchronized fun forget(address: String) {
        observations.keys.removeAll { it.equals(address, true) }
    }
    @Synchronized override fun close() { closed = true; observations.clear() }
}

/** Only the bounded audio-service calls run as shell; the app still owns route and lifecycle checks. */
@android.annotation.SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal class ShizukuVolumeSyncAudio(private val context: Context,
    private val binder: (String) -> IBinder = {
        ShizukuBinderWrapper(checkNotNull(SystemServiceHelper.getSystemService(it)))
    }, private val packageName: String = "com.android.shell",
) : BluetoothVolumeSync.Audio {
    private val serviceType = Class.forName("android.media.IAudioService")
    private val deviceType = Class.forName("android.media.AudioDeviceAttributes")
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private fun service(): Any {
        return checkNotNull(Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder(Context.AUDIO_SERVICE)))
    }
    private val integer = Int::class.javaPrimitiveType!!
    override fun syncEnabled(address: String): Boolean = BluetoothVolumeSupport { binder("bluetooth_manager") }.isEnabled()
    override fun snapshot(): BluetoothVolumeSync.Snapshot {
        val remote = service()
        val routes = serviceType.getMethod("getDevicesForAttributes", AudioAttributes::class.java)
            .invoke(remote, attributes) as List<*>
        return BluetoothVolumeSync.Snapshot(
            serviceType.getMethod("getMode").invoke(remote) as Int,
            serviceType.getMethod("getStreamVolume", integer).invoke(remote, AudioManager.STREAM_MUSIC) as Int,
            serviceType.getMethod("getStreamMaxVolume", integer).invoke(remote, AudioManager.STREAM_MUSIC) as Int,
            routes.map { token ->
                checkNotNull(token)
                BluetoothVolumeSync.Route(deviceType.getMethod("getAddress").invoke(token) as String,
                    deviceType.getMethod("getRole").invoke(token) as Int,
                    deviceType.getMethod("getType").invoke(token) as Int,
                    serviceType.getMethod("getDeviceVolumeBehavior", deviceType).invoke(remote, token) as Int, token)
            })
    }
    override fun setVolume(index: Int) {
        serviceType.getMethod("setStreamVolume", integer, integer, integer, String::class.java)
            .invoke(service(), AudioManager.STREAM_MUSIC, index, 0, packageName)
    }
    override fun setAbsolute(route: BluetoothVolumeSync.Route) {
        serviceType.getMethod("setDeviceVolumeBehavior", deviceType, integer, String::class.java)
            .invoke(service(), route.token, 3, packageName)
    }
}
