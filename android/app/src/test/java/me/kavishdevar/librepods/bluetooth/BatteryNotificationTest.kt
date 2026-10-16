package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import org.junit.Assert.*
import org.junit.Test

class BatteryNotificationTest {
    private fun counted(vararg records: ByteArray): ByteArray = byteArrayOf(4, 0, 4, 0, 4, 0, records.size.toByte()) +
        records.fold(byteArrayOf()) { bytes, record -> bytes + record }
    private fun record(component: Int, level: Int, status: Int = 2): ByteArray =
        byteArrayOf(component.toByte(), 1, level.toByte(), status.toByte(), 1)
    private fun packet(): ByteArray = ByteArray(22).apply {
        this[0] = 4; this[2] = 4; this[4] = 4; this[6] = 3
        this[7] = 4; this[9] = 80; this[10] = 1
        this[12] = 2; this[14] = 70; this[15] = 2
        this[17] = 8; this[19] = 60; this[20] = 1
    }

    @Test fun unknownAacpComponentsRetainTheirOriginalPercentageAndAge() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(packet())
        val before = notification.getBattery()
        notification.setBattery(packet().also { it[9] = 0xff.toByte(); it[19] = 0xff.toByte() })
        val after = notification.getBattery()
        assertEquals(before[0].level, after[0].level)
        assertEquals(before[0].updatedAt, after[0].updatedAt)
        assertFalse(after[0].live)
        assertEquals(BatteryStatus.DISCONNECTED, after[0].status)
        assertEquals(before[2].level, after[2].level)
        assertEquals(before[2].updatedAt, after[2].updatedAt)
        assertFalse(after[2].isLive())
        assertTrue(after[1].isLive())
    }

    @Test fun swappedSlotsKeepComponentIdentityAndMalformedPacketsDoNotChangeIt() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(packet().also { it[7] = 2; it[12] = 4 })
        val before = notification.getBattery()
        assertEquals(BatteryComponent.LEFT, before[0].component)
        assertEquals(70, before[0].level)
        assertEquals(BatteryComponent.RIGHT, before[1].component)
        assertEquals(80, before[1].level)
        notification.setBattery(packet().copyOf(21))
        assertEquals(before, notification.getBattery())
        notification.setBattery(packet().also { it[7] = 2; it[12] = 2 })
        assertEquals(before, notification.getBattery())
    }

    @Test fun noObservationIsNotAFreshZeroPercentReading() {
        val initial = AirPodsNotifications.BatteryNotification().getBattery()
        assertTrue(initial.all { it.updatedAt == null && !it.live && it.status == BatteryStatus.DISCONNECTED })
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(packet().also { it[9] = 0 })
        assertEquals(0, notification.getBattery()[0].level)
        assertTrue(notification.getBattery()[0].isLive())
    }

    @Test fun countedPartialReportsUpdateOnlyTheirOwnComponentsInAnyOrder() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(packet())
        val before = notification.getBattery()
        notification.setBattery(counted(record(BatteryComponent.RIGHT, 42)))
        val partial = notification.getBattery()
        assertEquals(before[0], partial[0])
        assertEquals(42, partial[1].level)
        assertTrue(partial[1].isLive())
        assertEquals(before[2], partial[2])
        notification.setBattery(counted(record(BatteryComponent.CASE, 0, 4), record(BatteryComponent.LEFT, 81, 5)))
        val absentCase = notification.getBattery()
        assertEquals(81, absentCase[0].level)
        assertEquals(BatteryStatus.OPTIMIZED_CHARGING, absentCase[0].status)
        assertEquals(partial[1], absentCase[1])
        assertEquals(before[2].level, absentCase[2].level)
        assertEquals(before[2].updatedAt, absentCase[2].updatedAt)
        assertFalse(absentCase[2].live)
        notification.setBattery(counted(record(1, 255), record(BatteryComponent.CASE, 61),
            record(BatteryComponent.RIGHT, 43), record(BatteryComponent.LEFT, 82)))
        assertEquals(listOf(82, 43, 61), notification.getBattery().map { it.level })
    }

    @Test fun invalidCountHeaderOrPercentageRejectsTheWholeBatteryUpdate() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(packet())
        val before = notification.getBattery()
        val good = counted(record(BatteryComponent.LEFT, 81), record(BatteryComponent.RIGHT, 71))
        for (invalid in listOf(good.copyOf().also { it[0] = 0 }, good.copyOf().also { it[5] = 1 },
            good.copyOf().also { it[6] = 3 }, good.copyOf().also { it[6] = 1 },
            good.copyOf().also { it[14] = 101.toByte() }, good.copyOf(16), good + byteArrayOf(0),
            counted(), counted(record(BatteryComponent.LEFT, 81), record(BatteryComponent.LEFT, 82)))) {
            assertFalse(notification.isBatteryData(invalid))
            notification.setBattery(invalid)
            assertEquals(before, notification.getBattery())
        }
        assertTrue(notification.isBatteryData(good))
    }
}
