/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.media.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses SDK37 framework methods only; no Bluetooth profile or audio device is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class EldAudioFormatBridgePlatformTest {
    private val owner = Class.forName("android.media.AudioSystem")
    private val method = checkNotNull(EldAudioFormatBridge.discover(37, "system_server", owner.classLoader!!))

    @Test fun realFrameworkMapperHasTheExpectedSignatureAndNativeEldFormatName() {
        assertEquals(owner, method.declaringClass)
        assertEquals(0, method.invoke(null, EldCodecIdentity.APPLE_CODEC_ID))
        val native = EldAudioFormatBridge.map(EldCodecIdentity.APPLE_CODEC_ID,
            method.invoke(null, EldCodecIdentity.APPLE_CODEC_ID) as Int, true)
        assertEquals(0x04000200, native)
        assertEquals("AUDIO_FORMAT_AAC_ELD", owner.getDeclaredMethod("audioFormatToString", Int::class.javaPrimitiveType)
            .invoke(null, native))
        assertNotEquals(AudioFormat.ENCODING_AAC_ELD, native)
    }

    @Test fun realStockMappingsAndForeignLongIdsKeepTheirOriginalResults() {
        for ((id, expected) in listOf(0L to 0x1f000000, 2L to 0x04000000,
            0x01004fffL to 0x20000000, 0x2400d7ffL to 0x21000000,
            0x0100e0ffL to 0x08000000, 0x4c35053affL to 0x28000000)) {
            val original = method.invoke(null, id) as Int
            assertEquals(expected, original)
            assertEquals(original, EldAudioFormatBridge.map(id, original, true))
        }
        for (id in listOf(0x18001004cffL, 0x01004cffL, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val original = method.invoke(null, id) as Int
            assertEquals(original, EldAudioFormatBridge.map(id, original, true))
        }
        assertEquals(0, EldAudioFormatBridge.map(EldCodecIdentity.APPLE_CODEC_ID, 0, false))
    }
}
