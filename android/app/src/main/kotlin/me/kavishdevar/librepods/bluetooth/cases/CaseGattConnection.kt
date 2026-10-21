@file:Suppress("MissingPermission")

package me.kavishdevar.librepods.bluetooth.cases

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

internal class CaseGattFailure(val status: Int, val isGattStatus: Boolean = true): Exception()

/** One explicit experiment owns one GATT connection; no bond or platform cache modifications. */
internal class CaseGattConnection(private val notification: (ByteArray) -> Unit) {
    private data class Request(val kind: Int, val uuid: UUID?, val result: CompletableDeferred<ByteArray>)
    private val lock = Any()
    private val operations = Mutex()
    private val connected = CompletableDeferred<BluetoothGatt>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var poisoned = false
    private var request: Request? = null
    private var subscribed: BluetoothGattCharacteristic? = null
    private var originalCccd: ByteArray? = null

    private fun accept(value: BluetoothGatt): Boolean = !closed && (gatt == null || gatt === value)
    private fun complete(value: BluetoothGatt, kind: Int, uuid: UUID?, status: Int, bytes: ByteArray = byteArrayOf()) {
        if (!accept(value)) return
        synchronized(lock) {
            val pending = request ?: return
            if (pending.kind != kind || pending.uuid != uuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.result.complete(bytes.copyOf())
            else pending.result.completeExceptionally(CaseGattFailure(status))
        }
    }

    private val callback = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(value: BluetoothGatt, status: Int, state: Int) {
            if (!accept(value)) return
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) connected.complete(value)
            else if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                connected.completeExceptionally(CaseGattFailure(status))
                synchronized(lock) { request?.result?.completeExceptionally(CaseGattFailure(status)) }
            }
        }
        override fun onServicesDiscovered(value: BluetoothGatt, status: Int) = complete(value, 0, null, status)
        override fun onCharacteristicRead(value: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) =
            complete(value, 1, characteristic.uuid, status, bytes)
        override fun onCharacteristicWrite(value: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            complete(value, 2, characteristic.uuid, status)
        override fun onDescriptorRead(value: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, bytes: ByteArray) =
            complete(value, 3, descriptor.uuid, status, bytes)
        override fun onDescriptorWrite(value: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            complete(value, 4, descriptor.uuid, status)
        override fun onCharacteristicChanged(value: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
            if (accept(value) && characteristic === subscribed) notification(bytes.copyOf())
        }
    }

    suspend fun connect(context: Context, device: BluetoothDevice) {
        check(!closed && gatt == null)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: error("connection")
        withTimeout(8_000) { connected.await() }
        operation(0, null) { it.discoverServices() }
    }

    private suspend fun operation(kind: Int, uuid: UUID?, timeout: Long = 2_000, allowPoisoned: Boolean = false,
                                  issue: (BluetoothGatt) -> Boolean): ByteArray = operations.withLock {
        check(!closed && (!poisoned || allowPoisoned))
        val pending = Request(kind, uuid, CompletableDeferred())
        synchronized(lock) { request = pending }
        try {
            check(issue(checkNotNull(gatt)))
            withTimeout(timeout) { pending.result.await() }
        } catch (failure: Exception) {
            // After cancellation/timeout a late reply cannot be attributed to a new write on this characteristic.
            if (!pending.result.isCompleted) poisoned = true
            throw failure
        } finally {
            synchronized(lock) { if (request === pending) request = null }
        }
    }

    fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.filter { it.uuid == service }?.singleOrNull()?.characteristics?.filter { it.uuid == uuid }?.singleOrNull()

    suspend fun verifyIdentity() {
        val pnp = characteristic(CASE_PNP_SERVICE, CASE_PNP_CHARACTERISTIC) ?: error("identity")
        check(verifiedPro3Case(read(pnp)))
    }

    private suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray =
        operation(1, characteristic.uuid) { it.readCharacteristic(characteristic) }

    private fun accepted(status: Int): Boolean {
        if (status != BluetoothStatusCodes.SUCCESS) throw CaseGattFailure(status, isGattStatus = false)
        return true
    }

    suspend fun subscribe(characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(CASE_CCCD) ?: error("subscription")
        val original = operation(3, descriptor.uuid) { it.readDescriptor(descriptor) }
        check(validCaseCccd(original))
        val value = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> byteArrayOf(2, 0)
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> byteArrayOf(1, 0)
            else -> error("subscription")
        }
        subscribed = characteristic
        originalCccd = original.copyOf()
        check(checkNotNull(gatt).setCharacteristicNotification(characteristic, true))
        operation(4, descriptor.uuid) { accepted(it.writeDescriptor(descriptor, value)) }
    }

    private fun writeType(characteristic: BluetoothGattCharacteristic): Int = when {
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else -> error("read-only")
    }

    /** False means locally queued without an ATT response, never a remote acknowledgement. */
    suspend fun write(characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = writeType(characteristic)
        operation(2, characteristic.uuid, 900) { accepted(it.writeCharacteristic(characteristic, bytes, type)) }
        return type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    }

    suspend fun stop(characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        if (!poisoned) return write(characteristic, bytes)
        // A safety Stop may still be queued, but a late Start callback must never be called a Stop acknowledgement.
        accepted(checkNotNull(gatt).writeCharacteristic(characteristic, bytes, writeType(characteristic)))
        return false
    }

    suspend fun close(): Boolean = withContext(NonCancellable) {
        var restored = originalCccd == null
        try {
            val characteristic = subscribed
            val original = originalCccd
            if (characteristic != null && original != null) {
                val descriptor = characteristic.getDescriptor(CASE_CCCD) ?: error("subscription")
                var written = false
                try { operation(4, descriptor.uuid, allowPoisoned = true) { accepted(it.writeDescriptor(descriptor, original)) }; written = true }
                finally {
                    val readback = operation(3, descriptor.uuid, allowPoisoned = true) { it.readDescriptor(descriptor) }
                    restored = written && readback.contentEquals(original)
                }
            }
        } catch (_: Exception) { restored = false }
        finally {
            closed = true
            subscribed?.let { runCatching { gatt?.setCharacteristicNotification(it, false) } }
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }
        restored
    }
}
