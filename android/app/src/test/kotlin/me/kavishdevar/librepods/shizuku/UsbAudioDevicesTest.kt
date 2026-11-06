package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
class UsbAudioDevicesTest {
    private fun device(id: Int, type: Int = AudioDeviceInfo.TYPE_USB_HEADSET, sink: Boolean = true) =
        mock(AudioDeviceInfo::class.java).also {
            `when`(it.id).thenReturn(id); `when`(it.type).thenReturn(type); `when`(it.isSink).thenReturn(sink)
        }
    @Test fun freshMetadataFindsUsbWhileThePublicCacheStaysEmpty() {
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val previous = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        shadowOf(audio).setOutputDevices(emptyList())
        try {
            val usb = device(88)
            val fresh = UsbAudioDevices.outputs({ UsbAudioDevices.Snapshot(0, 116,
                listOf(device(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), usb, device(89, sink = false))) }, uid = 0)
            assertEquals(listOf(usb), fresh)
            assertTrue(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isEmpty())
        } finally { shadowOf(audio).setOutputDevices(previous) }
    }
    @Test fun unknownLayoutsFailedReadsAndInvalidGenerationsFailClosed() {
        for (snapshot in listOf(UsbAudioDevices.Snapshot(-1, 10, emptyList()),
            UsbAudioDevices.Snapshot(0, 0, emptyList()), UsbAudioDevices.Snapshot(0, 10, emptyList(), 1025),
            UsbAudioDevices.Snapshot(0, 10, listOf(device(0))),
            UsbAudioDevices.Snapshot(0, 10, listOf(device(5), device(5))))) {
            assertThrows(IllegalStateException::class.java) { UsbAudioDevices.outputs({ snapshot }, uid = 1000) }
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            UsbAudioDevices.outputs({ throw NoSuchMethodException("Changed framework metadata API") }, uid = 0)
        }
        assertEquals("Fresh USB audio metadata is unavailable", failure.message)
    }
    @Test fun ordinaryAppAndUnknownSdkCannotUseThePrivateMetadataPath() {
        var called = false
        val source = { called = true; UsbAudioDevices.Snapshot(0, 1, emptyList()) }
        assertThrows(IllegalStateException::class.java) { UsbAudioDevices.outputs(source, uid = 10412) }
        assertThrows(IllegalStateException::class.java) { UsbAudioDevices.outputs(source, sdk = 36, uid = 0) }
        assertFalse(called)
    }
    @Test fun multipleFreshUsbOutputsRemainAmbiguousForTheCallerToReject() {
        val outputs = UsbAudioDevices.outputs({ UsbAudioDevices.Snapshot(0, 120, listOf(device(7), device(8))) }, uid = 0)
        assertEquals(2, outputs.size)
        assertNull(outputs.singleOrNull())
    }
}
