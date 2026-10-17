package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit connects join an existing profile; microphone recovery can cycle only the selected pair. */
internal class A2dpPlaybackRecovery(
    private val context: Context,
    address: String,
    private val owner: IPlaybackRecoveryOwner,
    private val connectOnly: Boolean = false,
) : IBinder.DeathRecipient {
    private val guard = A2dpRecoveryGuard(address)
    private var connectIssued = false
    fun cancel() = guard.cancel()
    override fun binderDied() = guard.ownerDied()

    private fun refreshOwner() {
        try {
            if (!owner.isCurrent) guard.cancel()
        } catch (_: Exception) {
            guard.ownerDied()
        }
    }

    @SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
    fun run(): Boolean {
        for (permission in arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_PRIVILEGED")) {
            check(context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED) {
                "Shizuku lacks $permission for selected-device playback recovery"
            }
        }
        val deadline = SystemClock.elapsedRealtime() + if (connectOnly) 18_000 else 5500
        val adapter = ShellBluetooth.adapter(context)
        check(adapter.isEnabled) { "Bluetooth is off" }
        val device = adapter.bondedDevices.singleOrNull { it.address == guard.address }
            ?: error("Selected AirPods are no longer bonded")
        check(A2dpRecoveryGuard.isAirPodsCandidate(device.bondState == BluetoothDevice.BOND_BONDED,
            device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
            device.uuids?.map { it.uuid.toString() }.orEmpty())) { "Selected bonded device does not advertise the AirPods AACP service" }
        val latch = CountDownLatch(1)
        val proxyLock = Any()
        var closed = false
        var proxy: BluetoothA2dp? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                val closeLate = synchronized(proxyLock) {
                    if (closed || profile != BluetoothProfile.A2DP || service !is BluetoothA2dp) true
                    else { proxy = service; false }
                }
                if (closeLate) adapter.closeProfileProxy(profile, service) else latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) { cancel(); latch.countDown() }
        }
        fun competing(profile: BluetoothA2dp) = profile.connectedDevices.any { it.address != guard.address }
        fun bonded() = device.bondState == BluetoothDevice.BOND_BONDED
        owner.asBinder().linkToDeath(this, 0)
        try {
            refreshOwner()
            if (!guard.liveOwner()) return false
            check(adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) { "A2DP proxy request was rejected" }
            if (!latch.await(minOf(1000L, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)) return false
            val profile = synchronized(proxyLock) { proxy } ?: return false
            return connectProfile(profile, device, deadline)
        } finally {
            // An owner that died after our disconnect gets only its exact prior pair's best-effort rollback.
            val profile = synchronized(proxyLock) { closed = true; proxy.also { proxy = null } }
            try {
                if (profile != null && !connectIssued) {
                    refreshOwner()
                    if (guard.mayRollback(competing(profile)) && bonded() &&
                        profile.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                        runCatching { invokeProfile(profile, "connect", device) }
                    }
                }
            } finally {
                if (profile != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, profile)
                runCatching { owner.asBinder().unlinkToDeath(this, 0) }
            }
        }
    }

    private fun invokeProfile(profile: BluetoothA2dp, method: String, device: BluetoothDevice): Boolean =
        BluetoothA2dp::class.java.getDeclaredMethod(method, BluetoothDevice::class.java)
            .apply { isAccessible = true }.invoke(profile, device) == true

    /** Injectable clock keeps the real profile transitions covered without sleeping in tests. */
    @SuppressLint("MissingPermission")
    internal fun connectProfile(profile: BluetoothA2dp, device: BluetoothDevice, deadline: Long,
        now: () -> Long = { SystemClock.elapsedRealtime() }, pause: () -> Unit = { Thread.sleep(40) }): Boolean {
        val started = now()
        fun eligible(): Boolean {
            refreshOwner()
            return guard.mayReconnect(profile.connectedDevices.any { it.address != guard.address }) &&
                device.bondState == BluetoothDevice.BOND_BONDED
        }
        fun awaitState(state: Int, until: Long): Boolean {
            while (now() < until) {
                if (!eligible()) return false
                if (profile.getConnectionState(device) == state) return true
                pause()
            }
            return false
        }
        fun awaitConnection(alreadyConnecting: Boolean): Boolean {
            var progressing = alreadyConnecting
            while (now() < deadline) {
                if (!eligible()) return false
                when (profile.getConnectionState(device)) {
                    BluetoothProfile.STATE_CONNECTED -> return true
                    BluetoothProfile.STATE_CONNECTING -> progressing = true
                    BluetoothProfile.STATE_DISCONNECTED -> if (progressing) return false
                    BluetoothProfile.STATE_DISCONNECTING -> return false
                }
                pause()
            }
            return false
        }
        var initial = profile.getConnectionState(device)
        while (initial == BluetoothProfile.STATE_DISCONNECTING && now() < deadline) {
            if (!eligible()) return false
            pause()
            initial = profile.getConnectionState(device)
        }
        guard.begin(initial == BluetoothProfile.STATE_CONNECTED)
        if (!eligible() || now() >= deadline) return false
        Log.i("NearbyTakeover", "A2DP start: connectOnly=$connectOnly state=$initial")
        val result = when (initial) {
            BluetoothProfile.STATE_CONNECTED -> if (connectOnly) true else {
                if (!guard.mayDisconnect(profile.connectedDevices.any { it.address != guard.address }) ||
                    !invokeProfile(profile, "disconnect", device)) false else {
                    guard.disconnectedByUs()
                    if (!awaitState(BluetoothProfile.STATE_DISCONNECTED, minOf(deadline, now() + 1500)) || !eligible()) false
                    else {
                        connectIssued = invokeProfile(profile, "connect", device)
                        connectIssued && awaitConnection(false)
                    }
                }
            }
            BluetoothProfile.STATE_CONNECTING -> awaitConnection(true)
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (!eligible() || now() >= deadline) false else {
                    connectIssued = invokeProfile(profile, "connect", device)
                    if (connectIssued) awaitConnection(false)
                    else when (profile.getConnectionState(device)) {
                        BluetoothProfile.STATE_CONNECTED -> eligible()
                        BluetoothProfile.STATE_CONNECTING -> awaitConnection(true)
                        else -> false
                    }
                }
            }
            else -> false
        }
        Log.i("NearbyTakeover", "A2DP complete: connected=$result elapsedMs=${now() - started}")
        return result
    }
}
