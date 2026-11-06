package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A handoff releases only its selected pair. It never changes policy, bonds, or reconnects. */
internal class NativePlaybackRelease(
    private val context: Context,
    address: String,
    private val owner: IPlaybackRecoveryOwner,
) : IBinder.DeathRecipient {
    private val address = A2dpRecoveryGuard(address).address
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    override fun binderDied() = cancel()

    private fun current(): Boolean = !cancelled &&
        context.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL &&
        runCatching { owner.isCurrent }.getOrDefault(false) && !cancelled

    @SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
    fun run(inspectOnly: Boolean = false): Boolean {
        for (permission in arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_PRIVILEGED")) {
            check(context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED) {
                "Shizuku lacks $permission for selected-device release"
            }
        }
        val adapter = ShellBluetooth.adapter(context)
        check(adapter.isEnabled) { "Bluetooth is off" }
        val device = adapter.bondedDevices.singleOrNull { it.address == address }
            ?: error("Selected AirPods are no longer bonded")
        check(A2dpRecoveryGuard.isAirPodsCandidate(true,
            device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
            device.uuids?.map { it.uuid.toString() }.orEmpty())) { "Selected device does not advertise AirPods AACP" }
        val deadline = SystemClock.elapsedRealtime() + 8_000
        val latch = CountDownLatch(2)
        val lock = Any()
        val proxies = mutableMapOf<Int, BluetoothProfile>()
        var closed = false
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val keep = synchronized(lock) {
                    if (closed || profile !in setOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) false
                    else { proxies[profile] = proxy; true }
                }
                if (keep) latch.countDown() else adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) { cancel(); latch.countDown() }
        }
        owner.asBinder().linkToDeath(this, 0)
        try {
            if (!current()) return false
            for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                if (!adapter.getProfileProxy(context, listener, profile)) return false
            }
            if (!latch.await(1_500, TimeUnit.MILLISECONDS)) return false
            val media = synchronized(lock) { proxies[BluetoothProfile.A2DP] as? BluetoothA2dp } ?: return false
            val headset = synchronized(lock) { proxies[BluetoothProfile.HEADSET] as? BluetoothHeadset } ?: return false
            val source = context.attributionSource
            fun profile(proxy: BluetoothProfile, kind: Int, serviceName: String): Profile {
                val proxyType = if (kind == BluetoothProfile.A2DP) BluetoothA2dp::class.java else BluetoothHeadset::class.java
                val getter = proxyType.getDeclaredMethod("getService").apply { isAccessible = true }
                val service = getter.invoke(proxy) as? IInterface ?: error("Native profile service unavailable")
                val binder = checkNotNull(service.asBinder())
                val type = Class.forName(serviceName)
                check(type.isInstance(service)) { "Native profile service type is unavailable" }
                val policy = type.getMethod("getConnectionPolicy", BluetoothDevice::class.java, AttributionSource::class.java)
                val state = type.getMethod("getConnectionState", BluetoothDevice::class.java, AttributionSource::class.java)
                val disconnect = type.getMethod("disconnect", BluetoothDevice::class.java, AttributionSource::class.java)
                fun available() = beforeDeadline(deadline, { SystemClock.elapsedRealtime() }) {
                    current() && adapter.isEnabled && device.bondState == BluetoothDevice.BOND_BONDED &&
                        synchronized(lock) { !closed && proxies[kind] === proxy } && getter.invoke(proxy) === service &&
                        binder.isBinderAlive && binder.pingBinder() && policy.invoke(service, device, source) in setOf(0, 100) &&
                        synchronized(lock) { !closed && proxies[kind] === proxy } && getter.invoke(proxy) === service &&
                        binder.isBinderAlive && current()
                }
                val audio = if (kind == BluetoothProfile.HEADSET)
                    type.getMethod("getAudioState", BluetoothDevice::class.java, AttributionSource::class.java) else null
                return Profile(
                    state = { verifiedProfileState(::available) { state.invoke(service, device, source) as Int } },
                    available = ::available,
                    audioState = { if (audio != null && available()) (audio.invoke(service, device, source) as Int).takeIf { available() } ?: -1 else -1 },
                    disconnect = { available() && disconnect.invoke(service, device, source) == true && available() })
            }
            val mediaProfile = profile(media, BluetoothProfile.A2DP, "android.bluetooth.IBluetoothA2dp")
            val headsetProfile = profile(headset, BluetoothProfile.HEADSET, "android.bluetooth.IBluetoothHeadset")
            fun scoReady() = scoAudioIdle({ mediaProfile.available() && headsetProfile.available() }) { headsetProfile.audioState() }
            if (inspectOnly) return scoReady() && listOf(mediaProfile.state(), headsetProfile.state())
                .all { it in BluetoothProfile.STATE_DISCONNECTED..BluetoothProfile.STATE_DISCONNECTING } && scoReady()
            var mediaState = -1
            var headsetState = -1
            Log.i("CompanionHandoff", "phase=release event=native_start")
            val profiles = listOf(
                Profile({ mediaProfile.state().also { mediaState = it } }) { mediaProfile.disconnect() },
                Profile({ headsetProfile.state().also { headsetState = it } }) { headsetProfile.disconnect() },
            )
            val released = releaseProfiles(profiles, deadline, { scoReady() && device.bondState == BluetoothDevice.BOND_BONDED })
            Log.i("CompanionHandoff", "phase=release event=native_result released=$released a2dp=$mediaState hfp=$headsetState")
            return released
        } finally {
            val captured = synchronized(lock) { closed = true; proxies.toMap().also { proxies.clear() } }
            captured.forEach { (profile, proxy) -> adapter.closeProfileProxy(profile, proxy) }
            runCatching { owner.asBinder().unlinkToDeath(this, 0) }
        }
    }

    internal class Profile(val state: () -> Int, val available: () -> Boolean = { true },
        val audioState: () -> Int = { -1 }, val disconnect: () -> Boolean)

    companion object {
        internal fun beforeDeadline(deadline: Long, now: () -> Long, current: () -> Boolean): Boolean =
            now() < deadline && current() && now() < deadline

        // Public profile wrappers return DISCONNECTED on errors; only a verified raw reply is evidence.
        internal fun verifiedProfileState(available: () -> Boolean, state: () -> Int): Int {
            check(available()) { "Native profile service unavailable" }
            val value = state()
            check(value in BluetoothProfile.STATE_DISCONNECTED..BluetoothProfile.STATE_DISCONNECTING && available()) {
                "Native profile state is unavailable"
            }
            return value
        }
        // Public SCO queries default to idle on errors; the raw service's policy
        // query instead reports unknown when its profile service is unavailable.
        internal fun scoAudioIdle(available: () -> Boolean, state: () -> Int): Boolean = runCatching {
            available() && state() == BluetoothHeadset.STATE_AUDIO_DISCONNECTED && available()
        }.getOrDefault(false)

        internal fun releaseProfiles(profiles: List<Profile>, deadline: Long, current: () -> Boolean,
            now: () -> Long = { SystemClock.elapsedRealtime() }, pause: () -> Unit = { Thread.sleep(40) }): Boolean {
            fun live() = beforeDeadline(deadline, now, current)
            for (profile in profiles) {
                if (!live()) return false
                val state = profile.state()
                if (!live()) return false
                if (state != BluetoothProfile.STATE_DISCONNECTED && state != BluetoothProfile.STATE_DISCONNECTING) {
                    val accepted = profile.disconnect()
                    if (!live()) return false
                    if (!accepted) {
                        val after = profile.state()
                        if (!live() || after !in setOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING)) return false
                    }
                }
            }
            while (now() < deadline) {
                if (!live()) return false
                if (profiles.all {
                        if (!live()) return false
                        val state = it.state()
                        if (!live()) return false
                        state == BluetoothProfile.STATE_DISCONNECTED
                    }) return live()
                pause()
            }
            return false
        }
    }
}
