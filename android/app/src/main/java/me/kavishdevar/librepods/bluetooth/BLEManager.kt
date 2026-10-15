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

package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.kavishdevar.librepods.utils.BluetoothCryptography
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.iterator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager for Bluetooth Low Energy scanning operations specifically for AirPods
 */
@OptIn(ExperimentalEncodingApi::class)
class BLEManager(private val context: Context) {

    data class AirPodsStatus(
        val address: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val paired: Boolean = false,
        val model: String = "Unknown",
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val isLeftInEar: Boolean = false,
        val isRightInEar: Boolean = false,
        val isLeftCharging: Boolean = false,
        val isRightCharging: Boolean = false,
        val isCaseCharging: Boolean = false,
        val lidOpen: Boolean = false,
        val color: String = "Unknown",
        val connectionState: String = "Unknown",
        val batterySnapshot: BleBatteryCache.Snapshot? = null
    )

    @Synchronized
    fun getMostRecentStatus(): AirPodsStatus? {
        val observed = lastPairedObservationNanos ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        if (!scanSession.active || now < observed || (now - observed) / 1_000_000 >= STALE_DEVICE_TIMEOUT_MS) return null
        return currentBatteryStatus()
    }

    /** Battery UI may retain percentages after radio silence, with their original observation age. */
    @Synchronized
    fun getMostRecentBatteryStatus(): AirPodsStatus? = currentBatteryStatus()

    private fun currentBatteryStatus(): AirPodsStatus? {
        val binding = refreshKeyBinding()
        val status = latestPairedStatus?.let { withBattery(it, batteryCache.snapshot) }
        if (!binding.isCurrent(sharedPreferences.all)) {
            refreshKeyBinding()
            return null
        }
        return status
    }

    interface AirPodsStatusListener {
        fun onDeviceStatusChanged(device: AirPodsStatus, previousStatus: AirPodsStatus?)
        fun onBroadcastFromNewAddress(device: AirPodsStatus)
        fun onLidStateChanged(lidOpen: Boolean)
        fun onEarStateChanged(device: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)
        fun onBatteryChanged(device: AirPodsStatus)
        fun onDeviceDisappeared()
    }

    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    private var mScanCallback: ScanCallback? = null
    private var airPodsStatusListener: AirPodsStatusListener? = null
    private val deviceStatusMap = mutableMapOf<String, AirPodsStatus>()
    private val verifiedAddresses = mutableSetOf<String>()
    private var keyBinding: BleKeyBinding? = null
    private val batteryCache = BleBatteryCache()
    private var latestPairedStatus: AirPodsStatus? = null
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var currentGlobalLidState: Boolean? = null
    private var lastBroadcastTime: Long = 0
    private var lastPairedObservationNanos: Long? = null
    private val scanSession = BleScanSession()
    private val nearbyVisits = NearbyAirPods.Visits()
    private var nearbyDeviceListener: ((NearbyAirPods.Event) -> Unit)? = null

    fun setNearbyDeviceListener(listener: (NearbyAirPods.Event) -> Unit) {
        nearbyDeviceListener = listener
    }

    private val modelNames = mapOf(
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x2720 to "AirPods Pro 3",
        0x3020 to "AirPods 5 (Wireless Charging)",
        0x3620 to "AirPods 5",
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)"
    )

    val colorNames = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow"
    )

    val connStates = mapOf(
        0x00 to "Disconnected", 0x04 to "Idle", 0x05 to "Music",
        0x06 to "Call", 0x07 to "Ringing", 0x09 to "Hanging Up", 0xFF to "Unknown"
    )

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            synchronized(this@BLEManager) {
                if (!scanSession.active) return
                cleanupStaleDevices()
                checkLidStateTimeout()
                cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }
    }

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScanning() {
        stopScanning()
        try {
            Log.d(TAG, "Starting BLE scanner")

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null) {
                Log.d(TAG, "No Bluetooth adapter available")
                return
            }

            if (!btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth is disabled")
                return
            }

            mBluetoothLeScanner = btAdapter.bluetoothLeScanner
            if (mBluetoothLeScanner == null) return
            val scanToken = scanSession.begin()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(500L)
                .build()

            val manufacturerData = ByteArray(27)
            val manufacturerDataMask = ByteArray(27)

            manufacturerData[0] = 7
            manufacturerData[1] = 25

            manufacturerDataMask[0] = -1
            manufacturerDataMask[1] = -1

            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(76, manufacturerData, manufacturerDataMask)
                .build()

            mScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    processScanResult(result, scanToken)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    for (result in results) {
                        processScanResult(result, scanToken)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    synchronized(this@BLEManager) {
                        if (!scanSession.isCurrent(scanToken)) return
                        Log.e(TAG, "BLE scan failed with error code: $errorCode")
                        stopScanning()
                    }
                }
            }

            mBluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, mScanCallback)
            Log.d(TAG, "BLE scanner started successfully")

            cleanupHandler.removeCallbacks(cleanupRunnable)
            cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        } catch (t: Throwable) {
            stopScanning()
            Log.e(TAG, "Error starting BLE scanner", t)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScanning() {
        val scanner = mBluetoothLeScanner
        val callback = mScanCallback
        scanSession.end()
        mScanCallback = null
        mBluetoothLeScanner = null
        lastPairedObservationNanos = null
        cleanupHandler.removeCallbacks(cleanupRunnable)
        val closedInvitations = nearbyVisits.clear()
        try {
            if (scanner != null && callback != null) {
                Log.d(TAG, "Stopping BLE scanner")
                scanner.stopScan(callback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scanner", t)
        }
        closedInvitations.forEach { nearbyDeviceListener?.invoke(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeProximityKey(binding: BleKeyBinding, keyBase64: String?): ByteArray? {
        if (!binding.ownsKeys) return null
        return if (keyBase64 != null) {
            try {
                Base64.decode(keyBase64).takeIf { it.size == 16 }
            } catch (e: Exception) {
                Log.w(TAG, "Stored proximity key could not be decoded")
                null
            }
        } else {
            null
        }
    }

    @SuppressLint("GetInstance")
    private fun decryptLastBytes(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (data.size < 16) {
                return null
            }

            val block = data.copyOfRange(data.size - 16, data.size)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(block)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            null
        }
    }

    private fun formatBattery(byteVal: Int): Pair<Boolean, Int?> {
        val level = (byteVal and 0x7F).takeIf { it <= 100 }
        val charging = level != null && (byteVal and 0x80) != 0
        return Pair(charging, level)
    }

    @Synchronized
    private fun processScanResult(result: ScanResult, scanToken: Long) {
        if (!scanSession.isCurrent(scanToken)) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address

            val manufacturer = scanRecord.getManufacturerSpecificData(76) ?: return
            val manufacturerData = NearbyAirPods.proximityRecord(manufacturer) ?: return
            if (manufacturerData[2].toInt() != 1) return
            val observationNanos = result.timestampNanos
            val observedAt = bleObservationTime(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
                observationNanos, STALE_DEVICE_TIMEOUT_MS) ?: return
            val binding = refreshKeyBinding()

            val irk = decodeProximityKey(binding, binding.irk)
            val trusted = verifiedAddresses.contains(address) ||
                (irk != null && BluetoothCryptography.verifyRPA(address, irk))
            val savedAddress = binding.address
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val pairedHere = trusted && adapter?.bondedDevices?.any { it.address == savedAddress } == true
            NearbyAirPods.lidEvent(manufacturer)?.let { event ->
                nearbyVisits.observe(event, observationNanos / 1_000_000, pairedHere)?.let {
                    nearbyDeviceListener?.invoke(it)
                }
            }
            if (!pairedHere) return
            if (lastPairedObservationNanos?.let { observationNanos <= it } == true) return

            if (!verifiedAddresses.contains(address)) {
                if (!trusted) {
                    return
                }
                verifiedAddresses.add(address)
                Log.d(TAG, "RPA verified and added to trusted list: $address")
            }

            val encryptionKey = decodeProximityKey(binding, binding.encryptionKey)
            val decryptedData = if (encryptionKey != null) decryptLastBytes(manufacturerData, encryptionKey) else null
            val decodedStatus = (if (decryptedData != null && decryptedData.size == 16) {
                parseProximityMessageWithDecryption(address, manufacturerData, decryptedData)
            } else {
                parseProximityMessage(address, manufacturerData)
            }).copy(lastSeen = observedAt)
            if (!binding.isCurrent(sharedPreferences.all)) {
                refreshKeyBinding()
                return
            }
            val snapshot = batteryCache.update(decodedStatus.leftBattery, decodedStatus.isLeftCharging,
                decodedStatus.rightBattery, decodedStatus.isRightCharging, decodedStatus.caseBattery,
                decodedStatus.isCaseCharging, decodedStatus.lastSeen, observationNanos)
            val parsedStatus = withBattery(decodedStatus, snapshot)
            latestPairedStatus = parsedStatus
            lastPairedObservationNanos = observationNanos
            lastBroadcastTime = observationNanos / 1_000_000

            val previousStatus = deviceStatusMap[address]
            deviceStatusMap[address] = parsedStatus

            airPodsStatusListener?.let { listener ->
                if (previousStatus == null) {
                    listener.onBroadcastFromNewAddress(parsedStatus)
                    listener.onBatteryChanged(parsedStatus)
                    Log.d(TAG, "New AirPods device detected: $address")

                    if (currentGlobalLidState == null || currentGlobalLidState != parsedStatus.lidOpen) {
                        currentGlobalLidState = parsedStatus.lidOpen
                        listener.onLidStateChanged(parsedStatus.lidOpen)
                        Log.d(TAG, "Lid state ${if (parsedStatus.lidOpen) "opened" else "closed"} (detected from new device)")
                    }
                } else {
                    if (parsedStatus != previousStatus) {
                        listener.onDeviceStatusChanged(parsedStatus, previousStatus)
                    }

                    if (parsedStatus.lidOpen != previousStatus.lidOpen) {
                        val previousGlobalState = currentGlobalLidState
                        currentGlobalLidState = parsedStatus.lidOpen

                        if (previousGlobalState != parsedStatus.lidOpen) {
                            listener.onLidStateChanged(parsedStatus.lidOpen)
                            Log.d(TAG, "Lid state changed from $previousGlobalState to ${parsedStatus.lidOpen}")
                        }
                    }

                    if (parsedStatus.isLeftInEar != previousStatus.isLeftInEar ||
                        parsedStatus.isRightInEar != previousStatus.isRightInEar) {
                        listener.onEarStateChanged(
                            parsedStatus,
                            parsedStatus.isLeftInEar,
                            parsedStatus.isRightInEar
                        )
                        Log.d(TAG, "Ear state changed - Left: ${parsedStatus.isLeftInEar}, Right: ${parsedStatus.isRightInEar}")
                    }

                    if (parsedStatus.leftBattery != previousStatus.leftBattery ||
                        parsedStatus.rightBattery != previousStatus.rightBattery ||
                        parsedStatus.caseBattery != previousStatus.caseBattery) {
                        listener.onBatteryChanged(parsedStatus)
                        Log.d(TAG, "Battery changed - Left: ${parsedStatus.leftBattery}, Right: ${parsedStatus.rightBattery}, Case: ${parsedStatus.caseBattery}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }

    private fun parseProximityMessageWithDecryption(address: String, data: ByteArray, decrypted: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
//        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftByteIndex = if (isFlipped) 2 else 1
        val rightByteIndex = if (isFlipped) 1 else 2

        val (isLeftCharging, leftBattery) = formatBattery(decrypted[leftByteIndex].toInt() and 0xFF)
        val (isRightCharging, rightBattery) = formatBattery(decrypted[rightByteIndex].toInt() and 0xFF)

        val (isCaseCharging, caseBattery) = formatBattery(decrypted[3].toInt() and 0xFF)

        val lidOpen = ((lid shr 3) and 0x01) == 0

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    @Synchronized
    private fun cleanupStaleDevices() {
        refreshKeyBinding()
        val now = System.currentTimeMillis()
        nearbyVisits.expire(SystemClock.elapsedRealtime()).forEach { nearbyDeviceListener?.invoke(it) }
        latestPairedStatus?.let { status ->
            val aged = withBattery(status, batteryCache.snapshot)
            if (aged != status) {
                latestPairedStatus = aged
                airPodsStatusListener?.onBatteryChanged(aged)
            }
        }
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS
        val hadDevices = deviceStatusMap.isNotEmpty()

        val staleDevices = deviceStatusMap.filter { it.value.lastSeen < staleCutoff }

        for (device in staleDevices) {
            deviceStatusMap.remove(device.key)
            Log.d(TAG, "Removed stale device from tracking: ${device.key}")
        }

        if (hadDevices && deviceStatusMap.isEmpty()) {
            airPodsStatusListener?.onDeviceDisappeared()
        }
    }

    private fun checkLidStateTimeout() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastBroadcastTime > LID_CLOSE_TIMEOUT_MS && currentGlobalLidState == true) {
            Log.d(TAG, "No broadcasts for ${LID_CLOSE_TIMEOUT_MS}ms, forcing lid state to closed")
            currentGlobalLidState = false
            airPodsStatusListener?.onLidStateChanged(false)
        }
    }

    private fun refreshKeyBinding(): BleKeyBinding {
        val current = BleKeyBinding.from(sharedPreferences.all)
        if (current != keyBinding) {
            keyBinding = current
            verifiedAddresses.clear()
            deviceStatusMap.clear()
            batteryCache.clear()
            latestPairedStatus = null
            lastPairedObservationNanos = null
            lastBroadcastTime = 0
            currentGlobalLidState = null
        }
        return current
    }

    private fun withBattery(status: AirPodsStatus, snapshot: BleBatteryCache.Snapshot): AirPodsStatus {
        val now = System.currentTimeMillis()
        return status.copy(leftBattery = snapshot.left.level, rightBattery = snapshot.right.level,
            caseBattery = snapshot.case.level, isLeftCharging = snapshot.left.chargingNow(now),
            isRightCharging = snapshot.right.chargingNow(now), isCaseCharging = snapshot.case.chargingNow(now),
            batterySnapshot = snapshot)
    }

    private fun parseProximityMessage(address: String, data: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBattery = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        val lidOpen = ((lid shr 3) and 0x01) == 0

        fun decodeBattery(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBattery),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val CLEANUP_INTERVAL_MS = 10000L
        private const val STALE_DEVICE_TIMEOUT_MS = 15000L
        private const val LID_CLOSE_TIMEOUT_MS = 2500L
    }
}
