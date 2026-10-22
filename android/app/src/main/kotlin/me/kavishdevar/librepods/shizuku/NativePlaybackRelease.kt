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

    private fun current(): Boolean = !cancelled && runCatching { owner.isCurrent }.getOrDefault(false) &&
        context.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL

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
            val getService = BluetoothHeadset::class.java.getDeclaredMethod("getService").apply { isAccessible = true }
            val service = getService.invoke(headset) as? IInterface ?: return false
            val binder = service.asBinder() ?: return false
            val source = context.attributionSource
            val serviceType = Class.forName("android.bluetooth.IBluetoothHeadset")
            val audioState = serviceType.getMethod("getAudioState", BluetoothDevice::class.java, AttributionSource::class.java)
            val connectionPolicy = serviceType.getMethod("getConnectionPolicy", BluetoothDevice::class.java, AttributionSource::class.java)
            fun scoReady() = scoAudioIdle({
                current() && adapter.isEnabled && synchronized(lock) { !closed && proxies[BluetoothProfile.HEADSET] === headset } &&
                    getService.invoke(headset) === service && binder.isBinderAlive && binder.pingBinder() &&
                    connectionPolicy.invoke(service, device, source) in setOf(0, 100)
            }) { audioState.invoke(service, device, source) as Int }
            if (inspectOnly) return scoReady() && listOf(media.getConnectionState(device), headset.getConnectionState(device))
                .all { it in BluetoothProfile.STATE_DISCONNECTED..BluetoothProfile.STATE_DISCONNECTING }
            var mediaState = -1
            var headsetState = -1
            Log.i("CompanionHandoff", "phase=release event=native_start")
            val profiles = listOf(
                Profile({ media.getConnectionState(device).also { mediaState = it } }) {
                    BluetoothA2dp::class.java.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }.invoke(media, device) == true
                },
                Profile({ headset.getConnectionState(device).also { headsetState = it } }) {
                    BluetoothHeadset::class.java.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }.invoke(headset, device) == true
                },
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

    internal class Profile(val state: () -> Int, val disconnect: () -> Boolean)

    companion object {
        // Public SCO queries default to idle on errors; the raw service's policy
        // query instead reports unknown when its profile service is unavailable.
        internal fun scoAudioIdle(available: () -> Boolean, state: () -> Int): Boolean = runCatching {
            available() && state() == BluetoothHeadset.STATE_AUDIO_DISCONNECTED && available()
        }.getOrDefault(false)

        internal fun releaseProfiles(profiles: List<Profile>, deadline: Long, current: () -> Boolean,
            now: () -> Long = { SystemClock.elapsedRealtime() }, pause: () -> Unit = { Thread.sleep(40) }): Boolean {
            for (profile in profiles) {
                if (!current() || now() >= deadline) return false
                val state = profile.state()
                if (state != BluetoothProfile.STATE_DISCONNECTED && state != BluetoothProfile.STATE_DISCONNECTING) {
                    if (!current()) return false
                    if (!profile.disconnect() && profile.state() !in setOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING)) return false
                }
            }
            while (now() < deadline) {
                if (!current()) return false
                if (profiles.all { it.state() == BluetoothProfile.STATE_DISCONNECTED }) return true
                pause()
            }
            return false
        }
    }
}
