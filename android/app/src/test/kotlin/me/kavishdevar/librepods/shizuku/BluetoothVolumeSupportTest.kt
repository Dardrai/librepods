package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.FileDescriptor
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [BluetoothVolumeDumpOsShadow::class])
class BluetoothVolumeSupportTest {
    private val descriptors = mutableListOf<ParcelFileDescriptor>()

    @Before fun setUp() { BluetoothVolumeDumpOsShadow.reset() }
    @After fun tearDown() { descriptors.forEach { it.close() } }

    @Test fun theNativeGlobalFlagIsIndependentOfMaskedPerDeviceCapabilityReports() {
        assertTrue(BluetoothVolumeSupport.parse(fixture))
        assertFalse(BluetoothVolumeSupport.parse(fixture.replace("Enabled: true", "Enabled: false")))
    }

    @Test fun unknownMissingAndMalformedNativeReportsCannotEnableRecovery() {
        listOf("", "Permission Denial: can't dump BluetoothManager\n", "Absolute Volume Enabled: true\n",
            fixture.replace("Enabled: true", "Enabled: unknown"),
            fixture.replace("Enabled: true", "Enabled: True"),
            fixture.replace("1 devices", "unknown devices"),
            fixture.replace("Absolute Volume Enabled: true", "Absolute Volume Enabled: true (cached)"),
            fixture + '\u0000',
        ).forEach { assertFalse(BluetoothVolumeSupport.parse(it)) }
    }

    @Test fun duplicateSectionsOrFlagsAreAmbiguousEvenIfTheyAgree() {
        listOf(fixture + fixture,
            fixture.replace("Absolute Volume Enabled: true", "Absolute Volume Enabled: true\n  Absolute Volume Enabled: true"),
            fixture + "Absolute Volume Enabled: true\n",
            fixture + "AVRCP Target Native Service: unsupported\n",
        ).forEach { assertFalse(BluetoothVolumeSupport.parse(it)) }
    }

    @Test fun aFlagOutsideTheNativeSectionOrAnIncompleteSectionIsRejected() {
        assertFalse(BluetoothVolumeSupport.parse(fixture.replace("  Absolute Volume Enabled: true\n", "") +
            "Absolute Volume Enabled: true\n"))
        assertFalse(BluetoothVolumeSupport.parse("AVRCP Target Native Service: 1 devices\n  Absolute Volume Enabled: true\n"))
        assertFalse(BluetoothVolumeSupport.parse(fixture.replace("  Absolute Volume Enabled: true",
            "Other native service:\n  Absolute Volume Enabled: true")))
    }

    @Test fun nativeReadinessRequiresExactlyOneActiveSessionAndAValidAvrcpVolume() {
        listOf(fixture.replace("1 devices", "0 devices"),
            fixture.replace("1 devices", "2 devices"),
            fixture.replace(" <Active>", ""),
            fixture.replace(" <Active>", " <Active>\n    xx:xx:xx:xx:66:77 <Active>"),
            fixture.replace("Current Volume: 61", "Current Volume: -1"),
            fixture.replace("Current Volume: 61", "Current Volume: 128"),
            fixture.replace("Current Volume: 61", "Current Volume: unknown"),
            fixture.replace("Current Volume: 61", "Current Volume: 61\n    Current Volume: 61"),
        ).forEach { assertFalse(BluetoothVolumeSupport.parse(it)) }
        assertTrue(BluetoothVolumeSupport.parse(fixture.replace("Current Volume: 61", "Current Volume: 0")))
        assertTrue(BluetoothVolumeSupport.parse(fixture.replace("Current Volume: 61", "Current Volume: 127")))
    }

    @Test fun theShizukuAdapterUsesOnlyAnAsynchronousBluetoothDumpWithAnFdAndEmptyArguments() {
        BluetoothVolumeDumpOsShadow.payload = fixture.toByteArray()
        val native = mock(IBinder::class.java)
        mockStatic(SystemServiceHelper::class.java).use { services ->
            services.`when`<IBinder> { SystemServiceHelper.getSystemService("bluetooth_manager") }.thenReturn(native)
            mockConstruction(ShizukuBinderWrapper::class.java) { wrapper, construction ->
                assertEquals(listOf(native), construction.arguments())
                respond(wrapper)
            }.use { wrappers ->
                assertTrue(BluetoothVolumeSupport().isEnabled())
                assertEquals(1, wrappers.constructed().size)
            }
        }
        assertTrue(BluetoothVolumeDumpOsShadow.eofSeen)
        assertTrue(BluetoothVolumeDumpOsShadow.polls > 0)
    }

    @Test fun aCompleteLookingPrefixDoesNotSucceedWithoutEof() {
        BluetoothVolumeDumpOsShadow.payload = fixture.toByteArray()
        BluetoothVolumeDumpOsShadow.holdOpen = true
        val binder = mock(IBinder::class.java)
        respond(binder)
        assertFalse(BluetoothVolumeSupport { binder }.isEnabled())
        assertFalse(BluetoothVolumeDumpOsShadow.eofSeen)
        assertTrue(BluetoothVolumeDumpOsShadow.timeoutSeen)
    }

    @Test fun anOversizedDumpIsRejectedBeforeItCanBeParsedAsSuccess() {
        BluetoothVolumeDumpOsShadow.payload = (fixture + "x".repeat(512 * 1_024)).toByteArray()
        val binder = mock(IBinder::class.java)
        respond(binder)
        assertFalse(BluetoothVolumeSupport { binder }.isEnabled())
        assertFalse(BluetoothVolumeDumpOsShadow.eofSeen)
        assertTrue(BluetoothVolumeDumpOsShadow.offset <= 512 * 1_024 + 8_192)
    }

    @Test fun transactionRefusalOrFailureDoesNotReadOrEnableAnything() {
        val binder = mock(IBinder::class.java)
        respond(binder, accepted = false)
        assertFalse(BluetoothVolumeSupport { binder }.isEnabled())
        assertEquals(0, BluetoothVolumeDumpOsShadow.polls)
        assertFalse(BluetoothVolumeSupport { throw SecurityException("Shizuku denied") }.isEnabled())
        assertEquals(0, BluetoothVolumeDumpOsShadow.polls)
    }

    @Test fun invalidPollEventsAndMalformedUtf8FailClosed() {
        val binder = mock(IBinder::class.java)
        respond(binder)
        BluetoothVolumeDumpOsShadow.invalidEvents = true
        assertFalse(BluetoothVolumeSupport { binder }.isEnabled())
        BluetoothVolumeDumpOsShadow.reset()
        BluetoothVolumeDumpOsShadow.payload = fixture.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        assertFalse(BluetoothVolumeSupport { binder }.isEnabled())
        assertTrue(BluetoothVolumeDumpOsShadow.eofSeen)
    }

    private fun respond(binder: IBinder, accepted: Boolean = true) {
        doAnswer {
            assertEquals(IBinder.DUMP_TRANSACTION, it.getArgument<Int>(0))
            assertNull(it.getArgument<Parcel?>(2))
            assertEquals(IBinder.FLAG_ONEWAY, it.getArgument<Int>(3))
            val request = it.getArgument<Parcel>(1)
            request.setDataPosition(0)
            val descriptor = checkNotNull(request.readFileDescriptor()).also(descriptors::add)
            assertNotNull(descriptor.fileDescriptor)
            assertArrayEquals(emptyArray<String>(), request.createStringArray())
            assertEquals(0, request.dataAvail())
            accepted
        }.`when`(binder).transact(anyInt(), any(Parcel::class.java), isNull(), anyInt())
    }

    private companion object {
        // Same native section shape as the Pixel dump; nearby masked maps are deliberately irrelevant.
        val fixture = """
              AvrcpVolumeManager:
                mCurrentDevice: XX:XX:XX:XX:44:55
                Device Absolute Volume Map:
              XX:XX:XX:XX:44:55: false
            Socket Events:
              no active socket events
            AVRCP Target Native Service: 1 devices
              Absolute Volume Enabled: true

                xx:xx:xx:xx:44:55 <Active>
                Current Volume: 61
                Current Browsed Player ID: -1
                Registered Notifications:
                    Play Status
                Last Play State: 1
                Last Song Sent ID: ""
                Current Folder: ""
                MTU Sizes: CTRL=512 BROWSE=332

            stack::gatt    last 0 tcb state transitions:
            TCB (GATT_MAX_PHY_CHANNEL: 8) in_use: 0
        """.trimIndent() + "\n"
    }
}

// Robolectric does not implement pipe polling; retain real Parcel/fd marshalling and model only I/O.
@Implements(Os::class)
class BluetoothVolumeDumpOsShadow {
    companion object {
        var payload = byteArrayOf()
        var offset = 0
        var polls = 0
        var eofSeen = false
        var holdOpen = false
        var timeoutSeen = false
        var invalidEvents = false
        private var polled: FileDescriptor? = null

        fun reset() {
            payload = byteArrayOf(); offset = 0; polls = 0; eofSeen = false
            holdOpen = false; timeoutSeen = false; invalidEvents = false; polled = null
        }

        @JvmStatic @Implementation
        fun poll(fds: Array<StructPollfd>, timeoutMs: Int): Int {
            assertEquals(1, fds.size)
            assertEquals(OsConstants.POLLIN.toShort(), fds.single().events)
            assertTrue(timeoutMs in 1..2_000)
            polls++
            polled = fds.single().fd
            if (holdOpen && offset == payload.size) {
                timeoutSeen = true
                ShadowSystemClock.advanceBy(Duration.ofMillis(timeoutMs.toLong()))
                return 0
            }
            fds.single().revents = (if (invalidEvents) OsConstants.POLLNVAL else OsConstants.POLLIN).toShort()
            return 1
        }

        @JvmStatic @Implementation
        fun read(fd: FileDescriptor, buffer: ByteArray, bufferOffset: Int, count: Int): Int {
            assertSame(polled, fd)
            if (offset == payload.size) { eofSeen = true; return 0 }
            val available = minOf(count, payload.size - offset)
            payload.copyInto(buffer, bufferOffset, offset, offset + available)
            offset += available
            return available
        }
    }
}
