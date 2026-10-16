/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data

import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BleBatteryCache

// TODO: Remove everything but Battery-related stuff

enum class Enums(val value: ByteArray) {
    NOISE_CANCELLATION(byteArrayOf(0x0d)),
    PREFIX(byteArrayOf(0x04, 0x00, 0x04, 0x00)),
    SETTINGS(byteArrayOf(0x09, 0x00)),
    NOISE_CANCELLATION_PREFIX(PREFIX.value + SETTINGS.value + NOISE_CANCELLATION.value),
    CONVERSATION_AWARENESS_RECEIVE_PREFIX(PREFIX.value + byteArrayOf(0x4b, 0x00, 0x02, 0x00)),
}

object BatteryComponent {
    const val LEFT = 4
    const val RIGHT = 2
    const val CASE = 8
}

object BatteryStatus {
    const val CHARGING = 1
    const val NOT_CHARGING = 2
    const val DISCONNECTED = 4
    const val OPTIMIZED_CHARGING = 5
}

@Parcelize
data class Battery(
    val component: Int,
    val level: Int,
    val status: Int,
    val updatedAt: Long? = if (status != BatteryStatus.DISCONNECTED) System.currentTimeMillis() else null,
    val live: Boolean = status != BatteryStatus.DISCONNECTED
) : Parcelable {
    fun isLive(now: Long = System.currentTimeMillis()): Boolean = live && updatedAt != null &&
        now >= updatedAt && now - updatedAt < BleBatteryCache.FRESH_MS

    fun getComponentName(): String? {
        return when (component) {
            BatteryComponent.LEFT -> "LEFT"
            BatteryComponent.RIGHT -> "RIGHT"
            BatteryComponent.CASE -> "CASE"
            else -> null
        }
    }

    fun getStatusName(): String? {
        return when (status) {
            BatteryStatus.CHARGING -> "CHARGING"
            BatteryStatus.NOT_CHARGING -> "NOT_CHARGING"
            BatteryStatus.DISCONNECTED -> "DISCONNECTED"
            BatteryStatus.OPTIMIZED_CHARGING -> "OPTIMIZED_CHARGING"
            else -> null
        }
    }
}

enum class NoiseControlMode {
    OFF,  NOISE_CANCELLATION, TRANSPARENCY, ADAPTIVE
}

class AirPodsNotifications {
    companion object {
        const val AIRPODS_CONNECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTED"
        const val AIRPODS_L2CAP_CONNECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTED"
        const val AIRPODS_DATA = "me.kavishdevar.librepods.AIRPODS_DATA"
        const val EAR_DETECTION_DATA = "me.kavishdevar.librepods.EAR_DETECTION_DATA"
        const val ANC_DATA = "me.kavishdevar.librepods.ANC_DATA"
        const val BATTERY_DATA = "me.kavishdevar.librepods.BATTERY_DATA"
        const val CA_DATA = "me.kavishdevar.librepods.CA_DATA"
        const val AIRPODS_DISCONNECTED = "me.kavishdevar.librepods.AIRPODS_DISCONNECTED"
        const val AIRPODS_CONNECTION_DETECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTION_DETECTED"
        const val DISCONNECT_RECEIVERS = "me.kavishdevar.librepods.DISCONNECT_RECEIVERS"
        const val EQ_DATA = "me.kavishdevar.librepods.HEADPHONE_ACCOMMODATION"
        const val AIRPODS_INFORMATION_UPDATED = "me.kavishdevar.librepods.AIRPODS_INFORMATION_UPDATED"
    }

    class EarDetection {
        private val notificationBit = 6.toByte()
        private val notificationPrefix = Enums.PREFIX.value + notificationBit

        var status: List<Byte> = listOf(0x01, 0x01)

        fun setStatus(data: ByteArray) {
            status = listOf(data[6], data[7])
        }

        fun isEarDetectionData(data: ByteArray): Boolean {
            if (data.size != 8) {
                return false
            }
            val prefixHex = notificationPrefix.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }
    }

    class ANC {
        private val notificationPrefix = Enums.NOISE_CANCELLATION_PREFIX.value

        var status: Int = 1
            private set

        fun isANCData(data: ByteArray): Boolean {
            if (data.size != 11) {
                return false
            }
            val prefixHex = notificationPrefix.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }

        fun setStatus(data: ByteArray) {
            when (data.size) {
                // if the whole packet is given
                11 -> {
                    status = data[7].toInt()
                }
                // if only the data is given
                1 -> {
                    status = data[0].toInt()
                }
                // if the value of control command is given
                4 -> {
                    status = data[0].toInt()
                }
                else -> {
                    Log.d("ANC", "Invalid ANC data size: ${data.size}")
                }
            }
        }

        val name: String =
            when (status) {
                1 -> "OFF"
                2 -> "ON"
                3 -> "TRANSPARENCY"
                4 -> "ADAPTIVE"
                else -> "UNKNOWN"
            }

    }

    class BatteryNotification {
        private var first: Battery = Battery(BatteryComponent.LEFT, 0, BatteryStatus.DISCONNECTED)
        private var second: Battery = Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED)
        private var case: Battery = Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)

        fun clear() {
            first = Battery(BatteryComponent.LEFT, 0, BatteryStatus.DISCONNECTED)
            second = Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED)
            case = Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)
        }

        fun isBatteryData(data: ByteArray): Boolean {
            val prefix = byteArrayOf(4, 0, 4, 0, 4, 0)
            if (data.size < 7 || !prefix.indices.all { data[it] == prefix[it] }) return false
            val count = data[6].toInt() and 0xff
            if (count == 0 || data.size != 7 + count * 5) return false
            val components = mutableSetOf<Int>()
            for (offset in 7 until data.size step 5) {
                val level = data[offset + 2].toInt() and 0xff
                if (level > 100 && level != 255) return false
                val component = data[offset].toInt() and 0xff
                if (component in setOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE) &&
                    !components.add(component)) return false
            }
            return true
        }

        fun setBatteryDirect(
            leftLevel: Int,
            leftCharging: Boolean,
            rightLevel: Int,
            rightCharging: Boolean,
            caseLevel: Int,
            caseCharging: Boolean
        ) {
            first = Battery(BatteryComponent.LEFT, leftLevel, if (leftCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING)
            second = Battery(BatteryComponent.RIGHT, rightLevel, if (rightCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING)
            case = Battery(BatteryComponent.CASE, caseLevel, if (caseCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING)
        }

        fun setBatteryFromBle(status: BLEManager.AirPodsStatus) {
            val snapshot = status.batterySnapshot ?: return
            val now = System.currentTimeMillis()
            fun battery(component: Int, reading: BleBatteryCache.Reading): Battery = Battery(
                component, reading.level ?: 0,
                when {
                    reading.level == null -> BatteryStatus.DISCONNECTED
                    reading.chargingNow(now) -> BatteryStatus.CHARGING
                    else -> BatteryStatus.NOT_CHARGING
                }, reading.updatedAt, reading.live(now)
            )
            first = battery(BatteryComponent.LEFT, snapshot.left)
            second = battery(BatteryComponent.RIGHT, snapshot.right)
            case = battery(BatteryComponent.CASE, snapshot.case)
        }

        fun setBattery(data: ByteArray) {
            if (!isBatteryData(data)) return
            for (offset in 7 until data.size step 5) {
                val component = data[offset].toInt() and 0xff
                val previous = when (component) {
                    BatteryComponent.LEFT -> if (first.component == component) first else second
                    BatteryComponent.RIGHT -> if (second.component == component) second else first
                    BatteryComponent.CASE -> case
                    else -> continue
                }
                val level = data[offset + 2].toInt() and 0xff
                val status = data[offset + 3].toInt() and 0xff
                val next = if (level == 255 || status == BatteryStatus.DISCONNECTED) {
                    previous.copy(live = false, status = BatteryStatus.DISCONNECTED)
                } else {
                    Battery(component, level, if (status == BatteryStatus.CHARGING ||
                        status == BatteryStatus.OPTIMIZED_CHARGING) status else BatteryStatus.NOT_CHARGING)
                }
                when (component) {
                    BatteryComponent.LEFT -> first = next
                    BatteryComponent.RIGHT -> second = next
                    BatteryComponent.CASE -> case = next
                }
            }
        }

        fun getBattery(): List<Battery> {
            val left = if (first.component == BatteryComponent.LEFT) first else second
            val right = if (first.component == BatteryComponent.LEFT) second else first
            val now = System.currentTimeMillis()
            return listOf(left, right, case).map {
                if (it.isLive(now)) it else it.copy(live = false,
                    status = if (it.status == BatteryStatus.CHARGING || it.status == BatteryStatus.OPTIMIZED_CHARGING)
                        BatteryStatus.NOT_CHARGING else it.status)
            }
        }
    }

    class ConversationalAwarenessNotification {
        @Suppress("PrivatePropertyName")
        private val NOTIFICATION_PREFIX = Enums.CONVERSATION_AWARENESS_RECEIVE_PREFIX.value

        var status: Byte = 0
            private set

        fun isConversationalAwarenessData(data: ByteArray): Boolean {
            if (data.size != 10) {
                return false
            }
            val prefixHex = NOTIFICATION_PREFIX.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }

        fun setData(data: ByteArray) {
            status = data[9]
        }
    }
}

fun isHeadTrackingData(data: ByteArray): Boolean {
    if (data.size <= 60) return false

    val prefixPattern = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00,
        0x10, 0x00
    )

    for (i in prefixPattern.indices) {
        if (data[i] != prefixPattern[i]) return false
    }

    if (data[10] != 0x44.toByte() && data[10] != 0x45.toByte()) return false

    if (data[11] != 0x00.toByte()) return false

    return true
}
