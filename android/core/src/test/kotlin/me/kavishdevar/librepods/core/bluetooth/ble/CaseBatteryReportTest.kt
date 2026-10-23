package me.kavishdevar.librepods.core.bluetooth.ble

import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CaseBatteryReportTest {
    private val key = ByteArray(16) { it.toByte() }
    private val plain = byteArrayOf(0x29, 0x20, 0x0a, 0x3d, 0xde.toByte(), 0xdd.toByte(),
        0x51, 0x0b, 0, 0, 0, 0, 0xf7.toByte(), 0x82.toByte(), 0x16, 0)

    @Test fun sharedSyntheticVectorPreservesComponentOrderChargingAndAge() {
        val raw = hex("071106aea248933e2159872f2c5ae5c8bf0f31")
        assertArrayEquals(raw, advertisement())
        val report = decode(raw)!!
        assertFalse(report.lidOpen)
        assertEquals(setOf(Battery(BatteryComponent.CASE, 61, BatteryStatus.NOT_CHARGING, 1234),
            Battery(BatteryComponent.LEFT, 94, BatteryStatus.CHARGING, 1234),
            Battery(BatteryComponent.RIGHT, 93, BatteryStatus.CHARGING, 1234)), report.batteries)
        assertNull("Cases must never become public lid invitations", NearbyAirPods.lidEvent(raw))
        assertNull("Cases must never become bud proximity records", NearbyAirPods.proximityRecord(raw))
    }

    @Test fun acceptsOpaqueFirmwareVariantsTransientAndCounterChangesIncludingResets() {
        for (variant in listOf(0x510b, 0x5a86, 0xabcd)) for (counter in listOf(0, 1, -1, 0x0016ffff)) {
            val changed = plain.copyOf().apply {
                this[2] = 7
                this[6] = (variant shr 8).toByte(); this[7] = variant.toByte(); this[9] = 0xff.toByte()
                for (i in 0..3) this[12 + i] = (counter ushr (8 * i)).toByte()
            }
            assertTrue(decode(advertisement(changed))!!.lidOpen)
        }
    }

    @Test fun onlyFFIsAbsentAndAllValidPercentagesHaveCorrectCharging() {
        for (level in 0..100) for (charging in listOf(0, 128)) {
            val changed = plain.copyOf().apply { this[3] = (level or charging).toByte(); this[4] = -1; this[5] = -1 }
            val batteries = decode(advertisement(changed))!!.batteries.associateBy { it.component }
            val case = batteries.getValue(BatteryComponent.CASE)
            assertEquals(level, case.level)
            assertEquals(if (charging == 0) BatteryStatus.NOT_CHARGING else BatteryStatus.CHARGING, case.status)
            for (component in listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT)) {
                val absent = batteries.getValue(component)
                assertFalse(absent.present); assertNull(absent.observedAt); assertEquals(BatteryStatus.DISCONNECTED, absent.status)
            }
        }
        for (slot in 3..5) for (invalid in (101..127) + (229..254)) {
            assertNull("slot=$slot raw=$invalid", decode(advertisement(plain.copyOf().apply { this[slot] = invalid.toByte() })))
        }
    }

    @Test fun rejectsEveryFixedByteViolationAndUpperLidBits() {
        for (slot in listOf(0, 1, 8, 10, 11)) {
            assertNull(decode(advertisement(plain.copyOf().apply { this[slot] = (this[slot].toInt() xor 1).toByte() })))
        }
        for (lid in listOf(0x10, 0x20, 0x40, 0x80, 0xff))
            assertNull(decode(advertisement(plain.copyOf().apply { this[2] = lid.toByte() })))
        assertNull(decode(advertisement().apply { this[2] = 1 }))
    }

    @Test fun requiresOneCompleteCaseRecordWithNoDuplicateOrBudRecord() {
        val raw = advertisement()
        assertNotNull(decode(byteArrayOf(1, 1, 42) + raw + byteArrayOf(9, 0)))
        for (invalid in listOf(raw.copyOf(18), raw + 0, raw + byteArrayOf(1, 4), raw + raw,
            raw + ByteArray(27).apply { this[0] = 7; this[1] = 25 },
            raw.copyOf().apply { this[1] = 16 }, ByteArray(255))) assertNull(decode(invalid))
        assertNull(NearbyAirPods.proximityRecord(raw + ByteArray(27).apply { this[0] = 7; this[1] = 25 }))
    }

    @Test fun failsClosedWithoutEligibleModelValidKeyAndNonnegativeObservationTime() {
        val raw = advertisement()
        for (model in AirPodsModel.entries.filter { it != AirPodsModel.AIRPODS_PRO_3 })
            assertNull(CaseBatteryReport.decode(raw, key, model, 1234))
        for (invalid in listOf(ByteArray(0), ByteArray(15), ByteArray(17), ByteArray(16), ByteArray(16) { 42 }))
            assertNull(CaseBatteryReport.decode(raw, invalid, AirPodsModel.AIRPODS_PRO_3, 1234))
        assertNull(CaseBatteryReport.decode(raw, key, AirPodsModel.AIRPODS_PRO_3, -1))
    }

    private fun decode(raw: ByteArray) = CaseBatteryReport.decode(raw, key, AirPodsModel.AIRPODS_PRO_3, 1234)
    private fun advertisement(value: ByteArray = plain): ByteArray = byteArrayOf(7, 17, 6) +
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(value) }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
