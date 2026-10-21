package me.kavishdevar.librepods.bluetooth.cases

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CaseGattConnectionTest {
    private class Link {
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        lateinit var callback: BluetoothGattCallback
        val characteristic = BluetoothGattCharacteristic(CaseSoundRoute.DULT.characteristic,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
        private val descriptor = BluetoothGattDescriptor(CASE_CCCD, 0)
        var cccd = byteArrayOf(0, 0)
        var callbacks = true
        var writes = 0
        init {
            characteristic.addDescriptor(descriptor)
            val service = BluetoothGattService(CaseSoundRoute.DULT.service, 0).apply { addCharacteristic(characteristic) }
            val pnp = BluetoothGattCharacteristic(CASE_PNP_CHARACTERISTIC, BluetoothGattCharacteristic.PROPERTY_READ, 0)
            val identity = BluetoothGattService(CASE_PNP_SERVICE, 0).apply { addCharacteristic(pnp) }
            `when`(gatt.services).thenReturn(listOf(service, identity))
            `when`(device.connectGatt(any(Context::class.java), eq(false), any(BluetoothGattCallback::class.java), eq(BluetoothDevice.TRANSPORT_LE)))
                .thenAnswer { invocation ->
                    callback = invocation.getArgument(2)
                    callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
                    gatt
                }
            `when`(gatt.discoverServices()).thenAnswer { callback.onServicesDiscovered(gatt, 0); true }
            `when`(gatt.readCharacteristic(pnp)).thenAnswer {
                callback.onCharacteristicRead(gatt, pnp, byteArrayOf(1, 0x4c, 0, 0x29, 0x20, 1, 0), 0); true
            }
            `when`(gatt.readDescriptor(descriptor)).thenAnswer { callback.onDescriptorRead(gatt, descriptor, 0, cccd.copyOf()); true }
            `when`(gatt.setCharacteristicNotification(characteristic, true)).thenReturn(true)
            `when`(gatt.writeDescriptor(eq(descriptor), any(ByteArray::class.java))).thenAnswer {
                cccd = it.getArgument<ByteArray>(1).copyOf(); callback.onDescriptorWrite(gatt, descriptor, 0); 0
            }
            `when`(gatt.writeCharacteristic(eq(characteristic), any(ByteArray::class.java), anyInt())).thenAnswer {
                writes++; if (callbacks) callback.onCharacteristicWrite(gatt, characteristic, 0); 0
            }
        }
    }

    @Test fun subscriptionRestoresTheExactOriginalValueAndClosesEvenAfterCancellation() = runBlocking<Unit> {
        val link = Link()
        val connection = CaseGattConnection { }
        connection.connect(RuntimeEnvironment.getApplication(), link.device)
        connection.verifyIdentity()
        connection.subscribe(link.characteristic)
        assertArrayEquals(byteArrayOf(2, 0), link.cccd)
        assertTrue(connection.write(link.characteristic, CaseSoundRoute.DULT.command(false)))
        `when`(link.gatt.writeCharacteristic(eq(link.characteristic), any(ByteArray::class.java), anyInt()))
            .thenReturn(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)
        val failure = runCatching { connection.write(link.characteristic, CaseSoundRoute.DULT.command(true)) }.exceptionOrNull() as CaseGattFailure
        assertEquals(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, failure.status)
        assertFalse("A local queue error must not be called an authentication failure", failure.isGattStatus)
        assertTrue(connection.close())
        assertArrayEquals(byteArrayOf(0, 0), link.cccd)
        verify(link.gatt).disconnect(); verify(link.gatt).close()
        verify(link.device, never()).createBond()
    }

    @Test fun lateStartCallbacksCannotConfirmASafetyStopOnAPoisonedSession() = runBlocking<Unit> {
        val link = Link()
        val connection = CaseGattConnection { }
        connection.connect(RuntimeEnvironment.getApplication(), link.device)
        connection.subscribe(link.characteristic)
        link.callbacks = false
        val pending = async { connection.write(link.characteristic, CaseSoundRoute.DULT.command(false)) }
        yield()
        pending.cancelAndJoin()
        link.callback.onCharacteristicWrite(mock(BluetoothGatt::class.java), link.characteristic, 0)
        assertFalse(connection.stop(link.characteristic, CaseSoundRoute.DULT.command(true)))
        link.callback.onCharacteristicWrite(link.gatt, link.characteristic, 0)
        assertEquals(2, link.writes)
        assertTrue(connection.close())
        assertArrayEquals(byteArrayOf(0, 0), link.cccd)
    }
}
