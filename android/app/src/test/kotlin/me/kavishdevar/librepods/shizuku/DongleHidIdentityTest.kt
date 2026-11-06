package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class DongleHidIdentityTest {
    @get:Rule val temp = TemporaryFolder()
    private val target = DongleHidIdentity.Target("/dev/bus/usb/001/002", "board", 0x4015, 5)
    private fun node(root: Path, name: String = "hidraw0", serial: String = "board", intf: Int = 5): Pair<Path, Path> {
        val usb = Files.createDirectories(root.resolve("usb1/1-1"))
        fun write(path: Path, value: String) = Files.write(path, value.toByteArray())
        mapOf("idVendor" to "cafe", "idProduct" to "4015", "serial" to serial,
            "busnum" to "1", "devnum" to "2").forEach { (key, value) -> write(usb.resolve(key), value) }
        val face = Files.createDirectories(usb.resolve("interface-$intf"))
        mapOf("bInterfaceNumber" to intf.toString(16), "bInterfaceClass" to "03",
            "bInterfaceSubClass" to "00", "bInterfaceProtocol" to "00").forEach { (key, value) -> write(face.resolve(key), value) }
        val classes = Files.createDirectories(face.resolve("hidraw"))
        val entry = Files.createDirectories(classes.resolve("$name/device"))
        write(entry.resolve("uevent"), "HID_ID=0003:0000CAFE:00004015\nHID_UNIQ=$serial\n")
        return classes to classes.resolve(name)
    }

    @Test fun currentBusDeviceInterfaceAndSerialMustAllMatch() {
        val root = temp.newFolder("devices").toPath()
        val (classes, entry) = node(root)
        assertEquals(entry, DongleHidIdentity.resolve(target, classes, root))
        for (other in listOf(target.copy(serial = "different"), target.copy(interfaceId = 2),
            target.copy(product = 0x4005), target.copy(deviceName = "/dev/bus/usb/001/003")))
            assertFalse(DongleHidIdentity.matches(entry, other, root))
        assertFalse(DongleHidIdentity.matches(entry, target, temp.newFolder("outside").toPath()))
    }

    @Test fun ambiguousOrRecycledNodesAreRejected() {
        val root = temp.newFolder("devices").toPath()
        val (classes, entry) = node(root)
        node(root, "hidraw1")
        assertThrows(IllegalStateException::class.java) { DongleHidIdentity.resolve(target, classes, root) }
        Files.write(entry.resolve("device/uevent"), "HID_ID=0003:00001234:00004015\nHID_UNIQ=board".toByteArray())
        assertFalse(DongleHidIdentity.matches(entry, target, root))
    }

    @Test fun arbitraryDevicePathsAndSerialInjectionAreNotAccepted() {
        assertThrows(IllegalArgumentException::class.java) { target.copy(deviceName = "/dev/hidraw0") }
        assertThrows(IllegalArgumentException::class.java) { target.copy(deviceName = "/dev/bus/usb/001/../../hidraw0") }
        assertThrows(IllegalArgumentException::class.java) { target.copy(serial = "board\nHID_ID=oops") }
    }
}
