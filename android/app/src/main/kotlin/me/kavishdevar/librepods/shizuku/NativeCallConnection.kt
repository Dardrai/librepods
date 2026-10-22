package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Connects the selected native HFP profile; it never answers, intercepts, or disconnects a call. */
internal class NativeCallConnection(private val context: Context, address: String,
    private val owner: IPlaybackRecoveryOwner) : IBinder.DeathRecipient {
    private val address = A2dpRecoveryGuard(address).address
    @Volatile private var cancelled = false
    override fun binderDied() { cancelled = true }
    private fun current() = !cancelled && runCatching { owner.isCurrent }.getOrDefault(false)

    @SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
    fun run(connect: Boolean): Int {
        for (permission in listOf("BLUETOOTH_CONNECT", "BLUETOOTH_PRIVILEGED", "READ_PHONE_STATE")) {
            check(context.checkPermission("android.permission.$permission", Process.myPid(), Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED) { "Native call access is unavailable" }
        }
        val adapter = ShellBluetooth.adapter(context)
        if (!adapter.isEnabled || !current()) return 0
        val device = adapter.bondedDevices.singleOrNull { it.address == address } ?: return 0
        if (!A2dpRecoveryGuard.isAirPodsCandidate(device.bondState == BluetoothDevice.BOND_BONDED,
                device.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL),
                device.uuids?.map { it.uuid.toString() }.orEmpty())) return 0
        val ready = CountDownLatch(1)
        val gate = Any()
        var closed = false
        var headset: BluetoothHeadset? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val keep = synchronized(gate) {
                    if (closed || profile != BluetoothProfile.HEADSET || proxy !is BluetoothHeadset) false
                    else { headset = proxy; true }
                }
                if (keep) ready.countDown() else adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) { cancelled = true; ready.countDown() }
        }
        owner.asBinder().linkToDeath(this, 0)
        try {
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) ||
                !ready.await(1500, TimeUnit.MILLISECONDS)) return 0
            val proxy = synchronized(gate) { headset } ?: return 0
            val serviceGetter = BluetoothHeadset::class.java.getDeclaredMethod("getService").apply { isAccessible = true }
            val service = serviceGetter.invoke(proxy) as? IInterface ?: return 0
            val binder = service.asBinder()
            val serviceType = Class.forName("android.bluetooth.IBluetoothHeadset")
            val source = context.attributionSource
            val policy = serviceType.getMethod("getConnectionPolicy", BluetoothDevice::class.java, AttributionSource::class.java)
            val audio = serviceType.getMethod("getAudioState", BluetoothDevice::class.java, AttributionSource::class.java)
            val active = serviceType.getMethod("getActiveDevice", AttributionSource::class.java)
            fun available() = current() && adapter.isEnabled && device.bondState == BluetoothDevice.BOND_BONDED &&
                synchronized(gate) { !closed && headset === proxy } && serviceGetter.invoke(proxy) === service &&
                binder.isBinderAlive && binder.pingBinder() && policy.invoke(service, device, source) in setOf(0, 100)
            fun selectedActive() = active.invoke(service, source) as? BluetoothDevice
            fun snapshot(): Int {
                if (!available()) return 0
                val state = proxy.getConnectionState(device)
                val selected = selectedActive()
                val sco = audio.invoke(service, device, source) as Int
                if (!available()) return 0
                return receipt(state, selected?.address == address, sco)
            }
            if (proxy.connectedDevices.any { it.address != address } || selectedActive()?.let { it.address != address } == true) return 0
            if (!connect) return snapshot()
            fun mayConnect() = available() && context.getSystemService(TelecomManager::class.java).isInCall &&
                proxy.connectedDevices.none { it.address != address } && selectedActive()?.let { it.address == address } != false
            val connected = connectSelected({ proxy.getConnectionState(device) }, ::mayConnect,
                { BluetoothHeadset::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
                    .apply { isAccessible = true }.invoke(proxy, device) == true })
            if (!connected || !mayConnect()) return 0
            if (selectedActive() == null) {
                if (!mayConnect() || BluetoothHeadset::class.java.getDeclaredMethod("setActiveDevice", BluetoothDevice::class.java)
                        .apply { isAccessible = true }.invoke(proxy, device) != true) return 0
            }
            val deadline = SystemClock.elapsedRealtime() + 1500
            while (mayConnect() && SystemClock.elapsedRealtime() < deadline) {
                val value = snapshot()
                if (value and READY == READY) return value
                Thread.sleep(40)
            }
            return 0
        } finally {
            val captured = synchronized(gate) { closed = true; headset.also { headset = null } }
            captured?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            runCatching { owner.asBinder().unlinkToDeath(this, 0) }
        }
    }

    companion object {
        const val HFP_CONNECTED = 1
        const val SELECTED_ACTIVE = 2
        const val SCO_CONNECTED = 4
        const val SCO_IDLE = 8
        const val READY = HFP_CONNECTED or SELECTED_ACTIVE

        internal fun receipt(profile: Int, selected: Boolean, audio: Int): Int {
            if (profile !in 0..3 || audio !in setOf(10, 11, 12)) return 0
            return (if (profile == BluetoothProfile.STATE_CONNECTED) HFP_CONNECTED else 0) or
                (if (selected) SELECTED_ACTIVE else 0) or
                (if (audio == BluetoothHeadset.STATE_AUDIO_CONNECTED) SCO_CONNECTED else 0) or
                (if (audio == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) SCO_IDLE else 0)
        }

        internal fun connectSelected(state: () -> Int, current: () -> Boolean, connect: () -> Boolean,
            now: () -> Long = { SystemClock.elapsedRealtime() }, pause: () -> Unit = { Thread.sleep(40) }): Boolean {
            val deadline = now() + 18_000
            var issued = false
            var progressing = false
            while (now() < deadline && current()) {
                when (state()) {
                    BluetoothProfile.STATE_CONNECTED -> return current()
                    BluetoothProfile.STATE_CONNECTING -> progressing = true
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (progressing || issued || !current()) return false
                        issued = true
                        if (!connect() && state() !in setOf(BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED)) return false
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> if (issued || progressing) return false
                    else -> return false
                }
                pause()
            }
            return false
        }
    }
}
