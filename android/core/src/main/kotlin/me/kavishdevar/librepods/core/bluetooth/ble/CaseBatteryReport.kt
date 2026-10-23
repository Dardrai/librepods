/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.core.bluetooth.ble

import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Key-and-layout association with a saved pair; this is not an IRK-verified bud identity. */
data class CaseBatteryReport(val batteries: Set<Battery>, val lidOpen: Boolean) {
    companion object {
        fun encryptedBlock(manufacturer: ByteArray): ByteArray? =
            NearbyAirPods.proximityRecord(manufacturer, 19)?.takeIf { it[2] == 6.toByte() }?.copyOfRange(3, 19)

        fun decode(manufacturer: ByteArray, encryption: ByteArray, model: AirPodsModel,
                   observedAt: Long): CaseBatteryReport? {
            if (model != AirPodsModel.AIRPODS_PRO_3 || observedAt < 0 || encryption.size != 16 ||
                encryption.all { it == 0.toByte() }) return null
            val encrypted = encryptedBlock(manufacturer) ?: return null
            val plain = try {
                Cipher.getInstance("AES/ECB/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(encryption, "AES"))
                    doFinal(encrypted)
                }
            } catch (_: Exception) { return null }
            if (plain[0] != 0x29.toByte() || plain[1] != 0x20.toByte() ||
                listOf(8, 10, 11).any { plain[it] != 0.toByte() } || plain[2].toInt() and 0xf0 != 0 ||
                (3..5).any { (plain[it].toInt() and 255) != 255 && (plain[it].toInt() and 127) > 100 }) return null
            fun battery(component: BatteryComponent, index: Int): Battery {
                val raw = plain[index].toInt() and 255
                return if (raw == 255) Battery(component, 0, BatteryStatus.DISCONNECTED)
                else Battery(component, raw and 127,
                    if (raw and 128 != 0) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING, observedAt)
            }
            // Variant 6/7, transient 9 and counter 12..15 are intentionally opaque.
            return CaseBatteryReport(setOf(battery(BatteryComponent.CASE, 3),
                battery(BatteryComponent.LEFT, 4), battery(BatteryComponent.RIGHT, 5)), plain[2].toInt() and 8 == 0)
        }
    }
}
