package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.utils.BluetoothCryptography
import org.junit.Assert.*
import org.junit.Test

class BluetoothCryptographyTest {
    // Synthetic CAPod fixture also used by ntpods; no user's proximity keys.
    private val irk = byteArrayOf(0x79, 0x04, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
        0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)

    @Test fun verifiesIndependentOwnedAddressFixtureAndRejectsChangedHash() {
        assertTrue(BluetoothCryptography.verifyRPA("5A:16:2B:91:D1:CD", irk))
        assertFalse(BluetoothCryptography.verifyRPA("5A:16:2B:91:D1:CC", irk))
        assertFalse(BluetoothCryptography.verifyRPA("5A:16:2B:91:D1:CD", irk.copyOf().also { it[15] = 0xaa.toByte() }))
    }

    @Test fun invalidKeysAddressesAndNonRpaAddressesCannotBecomeTrusted() {
        for (address in listOf("", "5A:16:2B", "ZZ:16:2B:91:D1:CD", "00:16:2B:91:D1:CD", "DA:16:2B:91:D1:CD")) {
            assertFalse(BluetoothCryptography.verifyRPA(address, irk))
        }
        for (size in listOf(0, 15, 17, 32)) {
            assertFalse(BluetoothCryptography.verifyRPA("5A:16:2B:91:D1:CD", ByteArray(size)))
        }
    }
}
