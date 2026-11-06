package me.kavishdevar.librepods.dongle

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import me.kavishdevar.librepods.shizuku.IUsbHidConnection

internal class DongleUsbControlsUnavailable(cause: Throwable? = null) : IOException(
    if (cause == null) "Root Bluetooth access is required to share USB controls held by Android's media-key driver."
    else "The shared USB helper could not open controls while Android's media-key driver is attached.", cause)

/** Control-only and audio personalities share a board serial, but not interface numbers. */
internal object DongleUsb {
    fun candidate(vendor: Int, product: Int) = vendor == 0xcafe && product in listOf(0x4005, 0x4015)
    fun candidate(device: UsbDevice) = candidate(device.vendorId, device.productId)
    fun serial(device: UsbDevice): String? = runCatching { device.serialNumber?.takeIf(String::isNotBlank) }.getOrNull()
    fun key(device: UsbDevice) = serial(device)?.let { "usb:serial:$it" } ?: "usb:path:${device.deviceName}"
    fun devices(manager: UsbManager) = manager.deviceList.values.filter(::candidate)
    fun resolve(devices: List<UsbDevice>, selected: String): UsbDevice? = devices.firstOrNull { key(it) == selected }
        ?: devices.singleOrNull()?.takeIf { serial(it) == null && selected.startsWith("usb:serial:") }
    fun audioAvailable(device: UsbDevice) = (0 until device.interfaceCount).any {
        device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
    }

    // Parse HID items rather than accepting the consumer collection on the same interface.
    fun vendorReports(descriptor: ByteArray): Boolean {
        var page = 0; var usage = 0; var size = 0; var count = 0; var report = 0
        var vendorDepth = -1; var depth = 0
        val reports = mutableSetOf<Int>()
        var at = 0
        while (at < descriptor.size) {
            val prefix = descriptor[at++].toInt() and 255
            if (prefix == 0xfe) return false
            val length = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            if (at + length > descriptor.size) return false
            var value = 0L
            repeat(length) { value = value or ((descriptor[at++].toLong() and 255) shl (8 * it)) }
            when (prefix and 0xfc) {
                0x04 -> page = value.toInt()
                0x08 -> usage = value.toInt()
                0x74 -> size = value.toInt()
                0x94 -> count = value.toInt()
                0x84 -> report = value.toInt()
                0xa0 -> { depth++; if (page == 0xff31 && usage == 1 && value == 1L) vendorDepth = depth; usage = 0 }
                0xc0 -> { if (vendorDepth == depth) vendorDepth = -1; depth--; if (depth < 0) return false }
                0xb0 -> if (vendorDepth > 0 && report in 2..3 && size == 8 && count == 63) reports += report
                // Push/pop globals are not used by this version; reject rather than misidentify them.
                0xa4, 0xb4 -> return false
            }
        }
        return depth == 0 && reports.containsAll(listOf(2, 3))
    }

    suspend fun open(manager: UsbManager, device: UsbDevice,
        shared: suspend (UsbInterface) -> IUsbHidConnection? = { null }): Link {
        check(candidate(device) && manager.hasPermission(device)) { "Allow USB access to connect the dongle" }
        val interfaces = (0 until device.interfaceCount).map(device::getInterface).filter {
            it.interfaceClass == UsbConstants.USB_CLASS_HID && it.interfaceSubclass == 0 && it.interfaceProtocol == 0
        }
        if (interfaces.isEmpty()) throw IOException("USB device does not expose the supported dongle controls")
        val connection = manager.openDevice(device) ?: throw IOException("USB dongle is unavailable")
        var retained = false
        try {
            var descriptorFailed = false
            val vendor = interfaces.filter { intf ->
                val descriptor = ByteArray(1024)
                val count = connection.controlTransfer(0x81, 6, 0x2200, intf.id, descriptor, descriptor.size, 1000)
                if (count !in 1..descriptor.size) { descriptorFailed = true; false }
                else vendorReports(descriptor.copyOf(count))
            }
            if (vendor.size > 1 || vendor.isEmpty() && !descriptorFailed)
                throw IOException("USB device does not expose unique supported dongle controls")
            if (vendor.size == 1) {
                val link = Link(connection, vendor.single())
                try {
                    DongleProtocol.status(link.exchange(1, 2, byteArrayOf()))
                    retained = true
                    return link
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        } finally { if (!retained) connection.close() }
        // Only startup reads can select a different transport. Never replay a write.
        var problem: Exception? = null
        var selected: Link? = null
        try {
            for (intf in interfaces) {
                val handle = try { shared(intf) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { problem = error; null } ?: continue
                val link = try {
                    if (!vendorReports(handle.descriptor())) throw IOException("Unsupported shared USB descriptor")
                    Link(handle).also { DongleProtocol.status(it.exchange(1, 2, byteArrayOf())) }
                } catch (error: Exception) {
                    runCatching { handle.close() }
                    if (error is CancellationException) throw error
                    problem = error
                    continue
                }
                if (selected != null) {
                    link.close()
                    throw IOException("USB device exposes ambiguous shared dongle controls")
                }
                selected = link
            }
            return selected ?: throw DongleUsbControlsUnavailable(problem)
        } catch (error: Exception) { runCatching { selected?.close() }; throw error }
    }

    class Link private constructor(private val connection: UsbDeviceConnection?, private val intf: UsbInterface?,
        private val shared: IUsbHidConnection?) : Closeable {
        constructor(connection: UsbDeviceConnection, intf: UsbInterface) : this(connection, intf, null)
        constructor(shared: IUsbHidConnection) : this(null, null, shared)
        private val closed = AtomicBoolean(false)
        fun exchange(operation: Int, report: Int, payload: ByteArray): ByteArray {
            require(operation in 1..2 && report in 2..3 && payload.size == if (operation == 1) 0 else 64)
            if (operation == 2) DongleProtocol.checkReport(payload, report)
            if (closed.get()) throw IOException("USB dongle controls are closed")
            if (shared != null) {
                val bytes = try { shared.exchange(operation, report, payload.copyOf()) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { throw IOException("Shared USB control transfer failed", error) }
                if (closed.get()) throw IOException("USB dongle controls were retired")
                DongleProtocol.checkReport(bytes, report)
                return bytes
            }
            val bytes = if (operation == 1) ByteArray(64) else payload.copyOf()
            // Only unbound interfaces use USBFS; bound consumer HID uses the root shared path.
            val count = checkNotNull(connection).controlTransfer(if (operation == 1) 0xa1 else 0x21,
                if (operation == 1) 1 else 9, 0x300 or report, checkNotNull(intf).id, bytes, bytes.size, 1000)
            if (count != 64 || closed.get()) throw IOException("USB dongle control transfer ended")
            if (operation == 1) return bytes.also { DongleProtocol.checkReport(it, report) }
            return DongleProtocol.header(report)
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (shared != null) shared.close() else connection?.close()
        }
    }
}
