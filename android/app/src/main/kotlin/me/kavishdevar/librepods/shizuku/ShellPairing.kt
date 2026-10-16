package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock

/** Confirms only the chosen in-progress bond; it never starts pairing or changes another device. */
internal object ShellPairing {
    @SuppressLint("MissingPermission")
    fun confirm(context: Context, address: String, owner: IPlaybackRecoveryOwner): Boolean {
        if (Process.myUid() != 2000 || !hasPermissions(context)) return false
        val guard = PairingConsentGuard(address, SystemClock.elapsedRealtime())
        val death = IBinder.DeathRecipient { guard.cancel() }
        owner.asBinder().linkToDeath(death, 0)
        try {
            fun liveOwner() = runCatching { owner.isCurrent }.getOrDefault(false)
            if (!liveOwner()) return false
            val adapter = ShellBluetooth.adapter(context)
            if (!adapter.isEnabled) return false
            val device = adapter.getRemoteDevice(guard.address)
            val classic = device.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL)
            val bonding = device.bondState == BluetoothDevice.BOND_BONDING
            if (!hasPermissions(context)) return false
            val ownerCurrent = liveOwner()
            // Read time after the Binder queries; a stalled reply must not extend consent's deadline.
            if (!guard.mayConfirm(SystemClock.elapsedRealtime(), device.address, classic, bonding, ownerCurrent)) return false
            // The controller invokes this only for the protected CONSENT pairing-request variant.
            return device.setPairingConfirmation(true)
        } finally {
            guard.cancel()
            runCatching { owner.asBinder().unlinkToDeath(death, 0) }
        }
    }

    private fun hasPermissions(context: Context): Boolean = listOf(
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_PRIVILEGED"
    ).all { context.checkPermission(it, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED }
}

internal class PairingConsentGuard(address: String, private val startedAt: Long) {
    val address = A2dpRecoveryGuard(address).address
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    fun mayConfirm(now: Long, actualAddress: String, classic: Boolean, bonding: Boolean, ownerCurrent: Boolean): Boolean =
        !cancelled && now >= startedAt && now - startedAt < 1_000 && ownerCurrent && classic && bonding &&
            actualAddress.equals(address, ignoreCase = true)
}
