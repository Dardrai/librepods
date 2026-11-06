package me.kavishdevar.librepods.shizuku

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Resolve only the currently selected USB bus/device/interface; hidraw numbers can be reused. */
internal object DongleHidIdentity {
    data class Target(val deviceName: String, val serial: String, val product: Int, val interfaceId: Int) {
        init {
            require(Regex("/dev/bus/usb/[0-9]{3}/[0-9]{3}").matches(deviceName))
            require(serial.isNotBlank() && serial.length <= 128 && serial.all { it.code in 33..126 })
            require(product in setOf(0x4005, 0x4015) && interfaceId in 0..255)
        }
        val bus get() = deviceName.split('/')[4].toInt()
        val device get() = deviceName.split('/')[5].toInt()
    }

    fun resolve(target: Target, classes: Path = Paths.get("/sys/class/hidraw"),
        devices: Path = Paths.get("/sys/devices")): Path {
        val matches = Files.newDirectoryStream(classes).use { entries ->
            entries.take(129).also { check(it.size <= 128) }.filter { entry ->
                Regex("hidraw[0-9]+").matches(entry.fileName.toString()) && matches(entry, target, devices)
            }
        }
        check(matches.size == 1) { "Selected dongle has no unique shared HID interface" }
        return matches.single()
    }

    fun matches(entry: Path, target: Target, devices: Path = Paths.get("/sys/devices")): Boolean = runCatching {
        val hid = entry.resolve("device").toRealPath()
        val root = devices.toRealPath()
        if (!hid.startsWith(root)) return false
        val fields = read(hid.resolve("uevent")).lineSequence().mapNotNull {
            val at = it.indexOf('='); if (at < 0) null else it.substring(0, at) to it.substring(at + 1)
        }.toMap()
        val id = fields["HID_ID"]?.split(':') ?: return false
        if (id.size != 3 || id[0].toIntOrNull(16) != 3 || id[1].toIntOrNull(16) != 0xcafe ||
            id[2].toIntOrNull(16) != target.product || fields["HID_UNIQ"] != target.serial) return false
        var current: Path? = hid.parent
        var intf = false
        repeat(8) {
            val path = current ?: return false
            if (!path.startsWith(root)) return false
            if (Files.isRegularFile(path.resolve("bInterfaceNumber"))) {
                if (intf || read(path.resolve("bInterfaceNumber")).toIntOrNull(16) != target.interfaceId ||
                    read(path.resolve("bInterfaceClass")).toIntOrNull(16) != 3 ||
                    read(path.resolve("bInterfaceSubClass")).toIntOrNull(16) != 0 ||
                    read(path.resolve("bInterfaceProtocol")).toIntOrNull(16) != 0) return false
                intf = true
            }
            if (Files.isRegularFile(path.resolve("idVendor"))) return intf &&
                read(path.resolve("idVendor")).toIntOrNull(16) == 0xcafe &&
                read(path.resolve("idProduct")).toIntOrNull(16) == target.product &&
                read(path.resolve("serial")) == target.serial &&
                read(path.resolve("busnum")).toIntOrNull() == target.bus &&
                read(path.resolve("devnum")).toIntOrNull() == target.device
            current = path.parent
        }
        false
    }.getOrDefault(false)

    private fun read(path: Path): String {
        require(Files.size(path) <= 8192)
        return String(Files.readAllBytes(path), Charsets.US_ASCII).trim()
    }
}
