/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class EldAudioFormatBridgeTest {
    @Test fun onlyTheExactFullAppleIdMapsToTheExistingNativeFormatWhenEnabled() {
        assertEquals(0x04000200, EldAudioFormatBridge.map(0x8001004cffL, 0, true))
        for (id in listOf(0L, 2L, 5L, 6L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
            0x004c8001L, 0x01004cffL, 0x18001004cffL, 0x8001004cfeL, 0x4c35053affL)) {
            for (result in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x04000000, 0x1f000000))
                assertEquals(result, EldAudioFormatBridge.map(id, result, true))
        }
        for (result in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x04000000))
            assertEquals(result, EldAudioFormatBridge.map(0x8001004cffL, result, false))
    }

    @Test fun otherProcessesAndSdksDoNotEvenResolveTheFrameworkClass() {
        var loads = 0
        val loader = object : ClassLoader(null) {
            override fun loadClass(name: String): Class<*> { loads++; throw ClassNotFoundException(name) }
        }
        for (sdk in listOf(33, 36, 38)) assertNull(EldAudioFormatBridge.discover(sdk, "system_server", loader))
        for (process in listOf("android", "com.android.bluetooth", "com.google.android.bluetooth", "system_server:other"))
            assertNull(EldAudioFormatBridge.discover(37, process, loader))
        assertEquals(0, loads)
        assertNull(EldAudioFormatBridge.discover(37, "system_server", loader))
        assertEquals(1, loads)
    }

    @Test fun exactStaticLongToIntShapeAcceptsMissingOrMatchingOptionalConstant() {
        for (owner in listOf(Mapper::class.java, MatchingConstant::class.java)) {
            val method = EldAudioFormatBridge.platformMethod(owner)
            assertTrue(Modifier.isStatic(method.modifiers))
            assertEquals(Int::class.javaPrimitiveType, method.returnType)
            assertArrayEquals(arrayOf(Long::class.javaPrimitiveType), method.parameterTypes)
        }
    }

    @Test fun changedArgumentsResultStaticnessOrOverloadsFailClosed() {
        for (owner in listOf(IntArgument::class.java, BoxedArgument::class.java, LongResult::class.java,
            InstanceMethod::class.java, Overloaded::class.java, MissingMethod::class.java))
            assertThrows(IllegalArgumentException::class.java) { EldAudioFormatBridge.platformMethod(owner) }
    }

    @Test fun changedOptionalNativeConstantCannotBeUsedSilently() {
        for (owner in listOf(WrongConstant::class.java, WrongConstantType::class.java, InstanceConstant::class.java))
            assertThrows(IllegalArgumentException::class.java) { EldAudioFormatBridge.platformMethod(owner) }
    }

    class Mapper { companion object { @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt() } }
    class MatchingConstant { companion object {
        const val AUDIO_FORMAT_AAC_ELD = 0x04000200
        @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt()
    } }
    class WrongConstant { companion object {
        const val AUDIO_FORMAT_AAC_ELD = 0x04000000
        @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt()
    } }
    class WrongConstantType { companion object {
        const val AUDIO_FORMAT_AAC_ELD = 0x04000200L
        @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt()
    } }
    class InstanceConstant {
        @JvmField val AUDIO_FORMAT_AAC_ELD = 0x04000200
        companion object { @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt() }
    }
    class IntArgument { companion object { @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Int): Int = id } }
    class BoxedArgument { companion object { @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long?): Int = id?.toInt() ?: 0 } }
    class LongResult { companion object { @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Long = id } }
    class InstanceMethod { fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt() }
    class Overloaded { companion object {
        @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Long): Int = id.toInt()
        @JvmStatic fun bluetoothA2dpCodecToAudioFormat(id: Int): Int = id
    } }
    class MissingMethod
}
