package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import android.os.SystemClock
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.services.LibrePodsService
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleVolumeDumpTest {
    private val services = mutableListOf<LibrePodsService>()
    private val controllers = mutableListOf<DongleController>()

    @After fun close() {
        controllers.forEach {
            field("bridge\$delegate").set(it, lazyOf(mock(ShizukuAudioClient::class.java)))
            it.close()
        }
        services.forEach { (serviceField("serviceScope").get(it) as CoroutineScope).cancel() }
    }

    @Test fun manualDumpNeverInitializesAnUnusedControllerAndOtherArgumentsPrintNothing() {
        val service = service()
        val controller = serviceField("dongleControls\$delegate").get(service) as Lazy<*>
        assertFalse(controller.isInitialized())
        assertEquals("", dump(service, "--other"))
        assertEquals("dongle_volume initialized=false", dump(service, "--dongle-volume").trim())
        assertFalse(controller.isInitialized())
    }

    @Test fun cachedDumpIsScopeFencedAndNeverReadsUsbRootOrPrivatePayloads() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE).edit().clear()
            .putString("address", "usb:serial:PRIVATE_BOARD")
            .putBoolean("hardware_volume:PRIVATE_BOARD", true).commit()
        val service = service()
        val controller = service.dongleControls.also { controllers += it }
        (field("scope").get(controller) as CoroutineScope).cancel()
        val root = field("bridge\$delegate").get(controller) as Lazy<*>
        val volume = field("usbVolume").get(controller) as Lazy<*>
        val ioCalls = AtomicInteger()
        field("session").set(controller, DongleSession({ _, _, _ -> ioCalls.incrementAndGet(); error("Dump performed USB I/O") }))
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(controller) as MutableStateFlow<DongleState>
        val status = DongleProtocol.Status(31, listOf(0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff), 3, 0,
            listOf(null, null, null), 0, 128)
        state.value = state.value.copy(usb = true, connected = true, usbAudioAvailable = true, status = status,
            controlSession = 17, usbVolumeEnabled = true, error = "private error AA:BB:CC:DD:EE:FF",
            name = "PRIVATE_NAME", hearingAidData = "private_hearing_profile".toByteArray())
        val target = DongleController::class.java.getDeclaredMethod("volumeTarget").apply { isAccessible = true }
            .invoke(controller) as DongleUsbVolume.Target
        val observation = DongleUsbVolume.Observation(DongleProtocol.PhysicalVolume(73, null, 111,
            false, false, true, 0x76543210, 4083, 31, true), SystemClock.elapsedRealtime())
        val cache = DongleController::class.java.getDeclaredMethod("cacheVolumeObservation",
            DongleUsbVolume.Target::class.java, DongleUsbVolume.Observation::class.java).apply { isAccessible = true }
        cache.invoke(controller, target, observation)
        cache.invoke(controller, target.copy(generation = 999), observation.copy(value = observation.value.copy(confirmed = 11)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        val output = dump(service, "--dongle-volume")
        assertTrue(output.contains("cached_only=true connected=true busy=false requested=true enabled=true pending=false error=present"))
        assertTrue(output.contains("control_session=17 status_generation=31 fresh_scope=true age_ms=300"))
        assertTrue(output.contains("physical_gain=73 revision=111 usb_generation=31"))
        assertTrue(output.contains("authority_at_snapshot=true token_present=true remaining_ttl_at_snapshot_ms=4083"))
        for (privateValue in listOf("PRIVATE_BOARD", "PRIVATE_NAME", "AA:BB:CC:DD:EE:FF", "private_hearing_profile", "1985229328", "76543210"))
            assertFalse(output.contains(privateValue))
        state.value = state.value.copy(status = status.copy(generation = 32))
        val stale = dump(service, "--dongle-volume")
        assertTrue(stale.contains("fresh_scope=false")); assertTrue(stale.contains("physical=unavailable"))
        assertFalse(stale.contains("physical_gain="))
        assertEquals(0, ioCalls.get()); assertFalse(root.isInitialized()); assertFalse(volume.isInitialized())
    }

    private fun service() = Robolectric.buildService(LibrePodsService::class.java).get().also { services += it }
    private fun dump(service: LibrePodsService, vararg args: String): String {
        val text = StringWriter()
        val writer = PrintWriter(text)
        LibrePodsService::class.java.getDeclaredMethod("dump", FileDescriptor::class.java, PrintWriter::class.java,
            arrayOf<String>().javaClass).apply { isAccessible = true }.invoke(service, FileDescriptor(), writer, args)
        writer.flush()
        return text.toString()
    }
    private fun field(name: String) = DongleController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun serviceField(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
