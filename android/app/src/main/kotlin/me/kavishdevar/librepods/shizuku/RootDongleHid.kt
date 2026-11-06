package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.dongle.DongleHidNative
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleUsb
import java.io.Closeable
import java.io.File

/** Share feature reports with usbhid; never detach its media-key input driver. */
internal class RootDongleHid(private val access: Access, private val caller: () -> Unit) : Closeable {
    interface Handle : Closeable {
        fun descriptor(): ByteArray
        fun exchange(operation: Int, report: Int, payload: ByteArray): ByteArray
    }
    interface Access {
        fun current(target: DongleHidIdentity.Target, callerPid: Int): Boolean
        fun open(target: DongleHidIdentity.Target): Handle
    }
    private val connections = mutableSetOf<Connection>()
    @Volatile private var closed = false

    constructor(context: Context, expectedUid: Int, caller: () -> Unit) : this(AndroidAccess(context, expectedUid), caller)

    fun open(target: DongleHidIdentity.Target, callerPid: Int, owner: IPlaybackRecoveryOwner): IUsbHidConnection {
        check(owner.isCurrent && access.current(target, callerPid)) { "USB dongle permission or selection changed" }
        val handle = access.open(target)
        val connection = Connection(target, callerPid, owner, handle)
        try {
            check(DongleUsb.vendorReports(handle.descriptor())) { "Unsupported dongle HID descriptor" }
            synchronized(connections) { check(!closed); connections += connection }
            owner.asBinder().linkToDeath(connection, 0)
            connection.requireCurrent()
            return connection
        } catch (error: Throwable) { connection.closeInternal(); throw error }
    }

    private inner class Connection(val target: DongleHidIdentity.Target, val pid: Int,
        val owner: IPlaybackRecoveryOwner, val handle: Handle) : IUsbHidConnection.Stub(), IBinder.DeathRecipient {
        @Volatile private var ended = false
        fun requireCurrent() {
            check(!ended && !closed && owner.isCurrent && access.current(target, pid)) {
                "USB dongle permission or selection changed"
            }
        }
        private fun <T> useHandle(action: () -> T): T {
            caller()
            val identity = Binder.clearCallingIdentity()
            return try { synchronized(this) {
                try { requireCurrent(); action().also { requireCurrent() } }
                catch (error: Throwable) {
                    closeInternal()
                    // Binder marshals RuntimeException, not a JNI-thrown checked IOException.
                    throw if (error is Exception && error !is RuntimeException)
                        IllegalStateException("USB HID control failed: ${error.message}", error) else error
                }
            } } finally { Binder.restoreCallingIdentity(identity) }
        }
        override fun descriptor(): ByteArray = useHandle {
            handle.descriptor().also { check(DongleUsb.vendorReports(it)) { "Unsupported dongle HID descriptor" } }
        }
        override fun exchange(operation: Int, report: Int, payload: ByteArray): ByteArray = useHandle {
            require(operation in 1..2 && report in 2..3 && payload.size == if (operation == 1) 0 else 64)
            if (operation == 2) DongleProtocol.checkReport(payload, report)
            handle.exchange(operation, report, payload).also { DongleProtocol.checkReport(it, report) }
        }
        override fun close() { caller(); closeInternal() }
        override fun binderDied() = closeInternal()
        @Synchronized fun closeInternal() {
            if (ended) return
            ended = true
            runCatching { handle.close() }
            runCatching { owner.asBinder().unlinkToDeath(this, 0) }
            synchronized(connections) { connections -= this }
        }
    }

    override fun close() {
        val retired = synchronized(connections) { closed = true; connections.toList().also { connections.clear() } }
        retired.forEach { it.closeInternal() }
    }

    private class AndroidAccess(private val context: Context, private val appUid: Int) : Access {
        private val manager = context.getSystemService(UsbManager::class.java)
        override fun current(target: DongleHidIdentity.Target, callerPid: Int): Boolean {
            val device = manager.deviceList[target.deviceName] ?: return false
            if (!DongleUsb.candidate(device) || device.productId != target.product ||
                (0 until device.interfaceCount).none {
                    val intf = device.getInterface(it)
                    intf.id == target.interfaceId && intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
                        intf.interfaceSubclass == 0 && intf.interfaceProtocol == 0
                }) return false
            // hasPermission(device) here would inspect UID 0, not the app that granted USB consent.
            // UsbSerialReader rejects the APK package under UID 0. Serial comes from sysfs/RAWUNIQ;
            // the app owner also checks its own permitted UsbDevice serial on every request.
            return ownUsbPermission(manager, device, callerPid, appUid) && runCatching {
                DongleHidIdentity.resolve(target); true
            }.getOrDefault(false)
        }

        override fun open(target: DongleHidIdentity.Target): Handle {
            DongleHidNative.load(context)
            val entry = DongleHidIdentity.resolve(target)
            val fd = ParcelFileDescriptor.open(File("/dev/${entry.fileName}"), ParcelFileDescriptor.MODE_READ_WRITE)
            try {
                check(OsConstants.S_ISCHR(Os.fstat(fd.fileDescriptor).st_mode))
                check(DongleHidIdentity.matches(entry, target)) { "USB HID identity changed" }
                check(DongleHidNative.identity(fd.fd, target.product, target.serial)) { "USB HID identity changed" }
                return object : Handle {
                    override fun descriptor() = DongleHidNative.descriptor(fd.fd)
                    override fun exchange(operation: Int, report: Int, payload: ByteArray) =
                        DongleHidNative.exchange(fd.fd, operation, report, payload)
                    override fun close() = fd.close()
                }
            } catch (error: Throwable) { fd.close(); throw error }
        }
    }

    companion object {
        internal fun ownUsbPermission(manager: Any, device: UsbDevice, pid: Int, uid: Int): Boolean {
            require(pid > 0 && uid >= 10000)
            val method = manager.javaClass.getMethod("hasPermission", UsbDevice::class.java,
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            return method.invoke(manager, device, BuildConfig.APPLICATION_ID, pid, uid) == true
        }
    }
}
