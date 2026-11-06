package me.kavishdevar.librepods.dongle

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleAudioTopologyTest {
    @Test fun controllerOwnsAudioListenerAndTopologyInvalidationDoesNotOpenNativeAirPods() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(AudioManager::class.java)
        val controller = DongleController(context)
        fun field(name: String) = DongleController::class.java.getDeclaredField(name).apply { isAccessible = true }
        val callback = field("audioDevices").get(controller) as AudioDeviceCallback
        val callbacks = shadowOf(audio).javaClass.getDeclaredField("audioDeviceCallbacks").apply { isAccessible = true }
        val bridge = mock(ShizukuAudioClient::class.java)
        val volume = mock(DongleUsbVolume::class.java)
        (field("scope").get(controller) as CoroutineScope).cancel()
        field("scope").set(controller, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        field("bridge\$delegate").set(controller, lazyOf(bridge))
        field("usbVolume").set(controller, lazyOf(volume))
        try {
            assertTrue((callbacks.get(shadowOf(audio)) as Set<*>).contains(callback))
            val usb = mock(AudioDeviceInfo::class.java)
            `when`(usb.type).thenReturn(AudioDeviceInfo.TYPE_USB_HEADSET)
            shadowOf(audio).addOutputDevice(usb, true)
            shadowOf(audio).removeOutputDevice(usb, true)
            verify(volume, times(2)).topologyChanged()
            verifyNoInteractions(bridge)
            assertNull(field("connection").get(controller))
        } finally { controller.close() }
        assertFalse((callbacks.get(shadowOf(audio)) as Set<*>).contains(callback))
        callback.onAudioDevicesAdded(emptyArray()) // A queued delivery cannot revive a retired controller.
        verify(volume, times(2)).topologyChanged()
    }
}
