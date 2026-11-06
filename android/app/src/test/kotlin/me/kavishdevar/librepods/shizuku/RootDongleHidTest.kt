package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.hardware.usb.UsbDevice
import android.os.IBinder
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.dongle.DongleProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RootDongleHidTest {
    private val target = DongleHidIdentity.Target("/dev/bus/usb/001/002", "board", 0x4015, 5)
    private val vendorDescriptor = intArrayOf(6, 0x31, 0xff, 9, 1, 0xa1, 1, 0x85, 2,
        0x75, 8, 0x95, 63, 9, 1, 0xb1, 2, 0x85, 3, 9, 2, 0xb1, 2, 0xc0).map(Int::toByte).toByteArray()
    private inner class Access : RootDongleHid.Access {
        var allowed = true; var opens = 0; var closes = 0; var exchanges = 0
        var descriptor = vendorDescriptor
        var afterDescriptor: () -> Unit = {}
        var failure: Exception? = null
        override fun current(target: DongleHidIdentity.Target, callerPid: Int): Boolean {
            assertEquals(this@RootDongleHidTest.target, target); assertEquals(1234, callerPid)
            return allowed
        }
        override fun open(target: DongleHidIdentity.Target): RootDongleHid.Handle {
            opens++
            return object : RootDongleHid.Handle {
                override fun descriptor() = descriptor.also { afterDescriptor() }
                override fun exchange(operation: Int, report: Int, payload: ByteArray): ByteArray {
                    exchanges++; failure?.let { throw it }; return DongleProtocol.header(report)
                }
                override fun close() { closes++ }
            }
        }
    }
    private class Owner {
        var current = true
        lateinit var death: IBinder.DeathRecipient
        val binder = mock(IBinder::class.java).also {
            doAnswer { call -> death = call.getArgument(0); null }.`when`(it).linkToDeath(any(), eq(0))
        }
        val remote = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent() = current
            override fun asBinder() = binder
        }
    }

    @Test fun consentAndOwnerAreRequiredBeforeOpeningAnyNode() {
        val access = Access(); val owner = Owner()
        RootDongleHid(access) {}.use { broker ->
            access.allowed = false
            assertThrows(IllegalStateException::class.java) { broker.open(target, 1234, owner.remote) }
            access.allowed = true; owner.current = false
            assertThrows(IllegalStateException::class.java) { broker.open(target, 1234, owner.remote) }
            assertEquals(0, access.opens)
        }
    }

    @Test fun descriptorMustMatchBeforeExposingAFeatureWriter() {
        val access = Access().also { it.descriptor = byteArrayOf(0) }
        RootDongleHid(access) {}.use { broker ->
            assertThrows(IllegalStateException::class.java) { broker.open(target, 1234, Owner().remote) }
            assertEquals(1, access.closes); assertEquals(0, access.exchanges)
        }
    }

    @Test fun retirementDuringOpenClosesTheLateHandle() {
        val access = Access(); val owner = Owner()
        access.afterDescriptor = { owner.current = false }
        RootDongleHid(access) {}.use { broker ->
            assertThrows(IllegalStateException::class.java) { broker.open(target, 1234, owner.remote) }
            assertEquals(1, access.closes)
        }
    }

    @Test fun ownerDeathClosesOnlyItsHandleAndAStaleOwnerCannotWrite() {
        val access = Access(); val old = Owner(); val current = Owner()
        RootDongleHid(access) {}.use { broker ->
            val first = broker.open(target, 1234, old.remote)
            val second = broker.open(target, 1234, current.remote)
            old.death.binderDied()
            assertThrows(IllegalStateException::class.java) { first.exchange(2, 2, DongleProtocol.header(2)) }
            second.exchange(1, 2, byteArrayOf())
            assertEquals(1, access.exchanges); assertEquals(1, access.closes)
            current.current = false
            assertThrows(IllegalStateException::class.java) { second.exchange(2, 3, DongleProtocol.header(3)) }
            assertEquals(1, access.exchanges); assertEquals(2, access.closes)
        }
        assertEquals(2, access.closes)
    }

    @Test fun revokedConsentAndInvalidReportsNeverReachTheDriver() {
        val access = Access(); val owner = Owner()
        RootDongleHid(access) {}.use { broker ->
            val connection = broker.open(target, 1234, owner.remote)
            assertThrows(IllegalArgumentException::class.java) { connection.exchange(2, 1, DongleProtocol.header(2)) }
            assertEquals(0, access.exchanges); assertEquals(1, access.closes)
            val replacement = broker.open(target, 1234, owner.remote)
            access.allowed = false
            assertThrows(IllegalStateException::class.java) { replacement.exchange(1, 2, byteArrayOf()) }
            assertEquals(0, access.exchanges); assertEquals(2, access.closes)
        }
    }

    @Test fun everyReturnedBinderAuthenticatesTheAppUid() {
        val access = Access(); var uid = 10123
        RootDongleHid(access) { AudioBridgeRules.requireCaller(10123, uid) }.use { broker ->
            val connection = broker.open(target, 1234, Owner().remote)
            uid = 10124
            assertThrows(IllegalArgumentException::class.java) { connection.descriptor() }
            assertThrows(IllegalArgumentException::class.java) { connection.exchange(2, 2, DongleProtocol.header(2)) }
            assertThrows(IllegalArgumentException::class.java) { connection.close() }
            assertEquals(0, access.exchanges); assertEquals(0, access.closes)
        }
        assertEquals(1, access.closes)
    }

    @Test fun checkedNativeIoFailuresAreClosedAndMarshaledAsBinderRuntimeErrors() {
        val access = Access()
        RootDongleHid(access) {}.use { broker ->
            val connection = broker.open(target, 1234, Owner().remote)
            access.failure = java.io.IOException("USB HID get feature failed (errno 19)")
            val failure = assertThrows(IllegalStateException::class.java) { connection.exchange(1, 2, byteArrayOf()) }
            assertTrue(failure.message!!.contains("errno 19")); assertEquals(1, access.closes)
            assertThrows(IllegalStateException::class.java) { connection.exchange(2, 2, DongleProtocol.header(2)) }
            assertEquals(1, access.exchanges)
        }
    }

    class PermissionManager {
        var allowed = false
        var identity: List<Any>? = null
        @Suppress("UNUSED_PARAMETER") fun hasPermission(device: UsbDevice): Boolean = error("UID 0 permission is irrelevant")
        fun hasPermission(device: UsbDevice, pkg: String, pid: Int, uid: Int): Boolean {
            identity = listOf(device, pkg, pid, uid); return allowed
        }
    }
    @Test fun permissionCheckUsesTheCallingAppIdentityInsteadOfRoot() {
        val manager = PermissionManager(); val device = mock(UsbDevice::class.java)
        assertFalse(RootDongleHid.ownUsbPermission(manager, device, 1234, 10123))
        assertEquals(listOf(device, BuildConfig.APPLICATION_ID, 1234, 10123), manager.identity)
        manager.allowed = true
        assertTrue(RootDongleHid.ownUsbPermission(manager, device, 1234, 10123))
        assertThrows(IllegalArgumentException::class.java) { RootDongleHid.ownUsbPermission(manager, device, 1234, 0) }
    }
}
