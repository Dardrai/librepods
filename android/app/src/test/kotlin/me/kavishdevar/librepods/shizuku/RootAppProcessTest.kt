package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConfiguration
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RootAppProcessTest {
    class MediaManager
    class MediaInitializer {
        companion object {
            var current: MediaManager? = null
            var writes = 0
            @JvmStatic fun getMediaServiceManager(): MediaManager? = current
            @JvmStatic fun setMediaServiceManager(value: MediaManager) { writes++; current = value }
        }
    }
    class IgnoredMediaInitializer {
        companion object {
            @JvmStatic fun getMediaServiceManager(): MediaManager? = null
            @JvmStatic fun setMediaServiceManager(value: MediaManager) = Unit
        }
    }
    class WrongMediaInitializer {
        companion object {
            @JvmStatic fun getMediaServiceManager(): String = "wrong layout"
        }
    }

    @Test fun rootAndAuthorizedShellInitializeOnlyTheMissingMediaManager() {
        try {
            for (uid in listOf(0, 2000)) {
                MediaInitializer.current = null; MediaInitializer.writes = 0
                RootAppProcess.prepareMedia(uid, MediaInitializer::class.java, MediaManager::class.java)
                val initialized = MediaInitializer.current
                assertNotNull(initialized); assertEquals(1, MediaInitializer.writes)
                RootAppProcess.prepareMedia(uid, MediaInitializer::class.java, MediaManager::class.java)
                assertSame(initialized, MediaInitializer.current); assertEquals(1, MediaInitializer.writes)
            }
        } finally { MediaInitializer.current = null; MediaInitializer.writes = 0 }
    }

    @Test fun anExistingMediaManagerIsNeverReplaced() {
        val initialized = MediaManager()
        try {
            MediaInitializer.current = initialized; MediaInitializer.writes = 0
            RootAppProcess.prepareMedia(0, MediaInitializer::class.java, MediaManager::class.java)
            assertSame(initialized, MediaInitializer.current); assertEquals(0, MediaInitializer.writes)
        } finally { MediaInitializer.current = null; MediaInitializer.writes = 0 }
    }

    @Test fun missingReadbackAndWrongMediaLayoutsFailAtTheFixedCompatibilityBoundary() {
        for (type in listOf(IgnoredMediaInitializer::class.java, WrongMediaInitializer::class.java)) {
            val failure = assertThrows(MediaRuntimeUnavailableException::class.java) {
                RootAppProcess.prepareMedia(0, type, MediaManager::class.java)
            }
            assertEquals("Media session runtime is unavailable", failure.message)
            assertEquals(MediaInspectionReason.MEDIA_RUNTIME_UNAVAILABLE, CompanionMediaInspection.failureReason(failure))
        }
    }

    @Test fun anOrdinaryAppOrSystemServerCannotInitializeTheMediaRuntime() {
        try {
            MediaInitializer.current = null; MediaInitializer.writes = 0
            for (uid in listOf(1000, 10412)) assertThrows(SecurityException::class.java) {
                RootAppProcess.prepareMedia(uid, MediaInitializer::class.java, MediaManager::class.java)
            }
            assertNull(MediaInitializer.current); assertEquals(0, MediaInitializer.writes)
        } finally { MediaInitializer.current = null; MediaInitializer.writes = 0 }
    }

    // Robolectric's ActivityThread shadow always reports false; this uses the exact reflected
    // production layout so the correction and readback branches really execute on the JVM.
    class ActivityThreadLayout {
        var mSystemThread = true
        companion object {
            private val current = ActivityThreadLayout()
            @JvmStatic fun currentActivityThread() = current
            @JvmStatic fun isSystem() = current.mSystemThread
        }
    }

    @Test fun rootClientCanUnparcelTheSystemServersRemoteUsbSerialReader() {
        val thread = ActivityThreadLayout.currentActivityThread()
        val before = thread.mSystemThread
        try {
            thread.mSystemThread = true
            assertTrue(ActivityThreadLayout.isSystem())
            RootAppProcess.prepare(0, ActivityThreadLayout::class.java)
            assertFalse(ActivityThreadLayout.isSystem())
            RootAppProcess.prepare(0, ActivityThreadLayout::class.java)
            assertFalse(ActivityThreadLayout.isSystem())
            // The actual framework creator still accepts the remote reader in client mode.
            RootAppProcess.prepare(0)
            val device = remoteUsbDevice()
            assertEquals(0xcafe, device.vendorId); assertEquals(0x4015, device.productId)
        } finally { thread.mSystemThread = before }
    }

    @Test fun nonRootAndTheActualSystemServerCannotChangeClassification() {
        val thread = ActivityThreadLayout.currentActivityThread()
        val before = thread.mSystemThread
        try {
            thread.mSystemThread = true
            for (uid in listOf(1000, 2000, 10412)) {
                assertThrows(IllegalStateException::class.java) { RootAppProcess.prepare(uid, ActivityThreadLayout::class.java) }
                assertTrue(ActivityThreadLayout.isSystem())
            }
        } finally { thread.mSystemThread = before }
    }

    @Test fun unsupportedRuntimeFailsAtTheCompatibilityStage() {
        val failure = assertThrows(IllegalStateException::class.java) { RootAppProcess.prepare(0, String::class.java) }
        assertEquals("Root USB runtime compatibility is unavailable", failure.message)
        assertTrue(failure.cause is NoSuchMethodException)
    }

    private fun remoteUsbDevice(): UsbDevice {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeString("/dev/bus/usb/001/002")
            for (value in listOf(0xcafe, 0x4015, 0, 0, 0)) parcel.writeInt(value)
            parcel.writeString("LibrePods"); parcel.writeString("AirPods Dongle"); parcel.writeString("1.0")
            // A remote serial-reader binder is legitimate for a client, but not system_server.
            parcel.writeStrongBinder(mock(IBinder::class.java))
            parcel.writeParcelableArray(emptyArray<UsbConfiguration>(), 0)
            repeat(5) { parcel.writeInt(0) }
            parcel.setDataPosition(0)
            UsbDevice.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
