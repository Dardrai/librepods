/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Array as ReflectArray

/** Runs the carrier checks against the SDK 37 framework, without opening a Bluetooth profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class EldCodecExtendedPlatformTest {
    private val codec = Class.forName("android.bluetooth.BluetoothCodecConfig")
    private val type = Class.forName("android.bluetooth.BluetoothCodecType")
    private val objects = EldCodecObjects(codec, type) { 8 }

    @Test fun platformExtendedCarrierPreservesVendorIdentityAndAllConfigFields() {
        val empty = ReflectArray.newInstance(codec, 0)
        assertSame(empty, objects.priorities(empty, false))
        val catalog = objects.priorities(empty, true)!!
        val own = checkNotNull(ReflectArray.get(catalog, 0))
        val before = objects.fields(own)
        assertEquals(8, before.type)
        assertEquals(0x8001004cffL, before.extendedCodecId)
        val replacement = before.copy(priority = -1, sampleRate = 2, bitsPerSample = 1,
            channelMode = 2, specific1 = Long.MIN_VALUE, specific2 = Long.MAX_VALUE,
            specific3 = -1, specific4 = 0x123456789abcdefL)
        val updated = objects.map(own) { replacement }!!
        assertEquals(replacement, objects.fields(updated))
        assertSame(codec.getMethod("getExtendedCodecType").invoke(own),
            codec.getMethod("getExtendedCodecType").invoke(updated))
        assertEquals("Apple AAC-ELD", type.getMethod("getCodecName")
            .invoke(codec.getMethod("getExtendedCodecType").invoke(updated)))
    }

    @Test fun platformStockAacFallbackIsARealExtendedCodecWithoutPrivateRelabeling() {
        val value = checkNotNull(ReflectArray.get(objects.aacFallback(), 0))
        assertEquals(EldCodecIdentity.Fields(1, 1_000_000, 0, 0, 0, 0, 0, 0, 0, 2), objects.fields(value))
        assertFalse(objects.isEld(value))
    }

    @Test fun platformInitialArraysRejectForeignSlotFiveBeforeNativeReadiness() {
        val boundary = EldCodecBoundary(EldCodecObjects(codec, type) { null })
        val empty = ReflectArray.newInstance(codec, 0)
        fun input(priority: Any? = empty, offload: Any? = empty) =
            arrayOf<Any?>(2, priority, offload, emptyList<Any>())
        assertTrue(boundary.initialCodecSlotFree(input()))
        for ((id, name) in listOf(6L to "LC3", 0x4c35053affL to "LHDCV5")) {
            val foreign = carrier(5, id, name)
            val values = ReflectArray.newInstance(codec, 1).also { ReflectArray.set(it, 0, foreign) }
            assertFalse(boundary.initialCodecSlotFree(input(priority = values)))
            assertFalse(boundary.initialCodecSlotFree(input(offload = values)))
            assertSame(foreign, ReflectArray.get(values, 0))
        }
        assertFalse(boundary.initialCodecSlotFree(input(priority = null)))
        assertFalse(boundary.initialCodecSlotFree(input(offload = ReflectArray.newInstance(codec, 1))))
    }

    @Test fun platformOwnAppleSlotFiveAndOtherCarriersArePreservedExactly() {
        val boundary = EldCodecBoundary(EldCodecObjects(codec, type) { null })
        val own = carrier(5, EldCodecIdentity.APPLE_CODEC_ID, "Apple AAC-ELD")
        val lhdc = carrier(7, 0x4c35053affL, "LHDCV5")
        val values = ReflectArray.newInstance(codec, 2).also {
            ReflectArray.set(it, 0, own); ReflectArray.set(it, 1, lhdc)
        }
        val before = objects.fields(own)
        val args = arrayOf<Any?>(2, values, ReflectArray.newInstance(codec, 0), emptyList<Any>())
        assertTrue(boundary.initialCodecSlotFree(args))
        assertSame(values, args[1]); assertSame(own, ReflectArray.get(values, 0)); assertSame(lhdc, ReflectArray.get(values, 1))
        assertEquals(before, objects.fields(own))
        assertEquals(-1, before.priority)
        assertEquals(Long.MIN_VALUE, before.specific1)
        assertEquals(Long.MAX_VALUE, before.specific2)
    }

    private fun carrier(index: Int, id: Long, name: String): Any {
        val int = Int::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        val extended = type.getDeclaredConstructor(int, long, String::class.java).apply { isAccessible = true }
            .newInstance(index, id, name)
        return codec.getDeclaredConstructor(type, int, int, int, int, long, long, long, long)
            .apply { isAccessible = true }
            .newInstance(extended, -1, 2, 1, 2, Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0x123456789abcdefL)
    }
}
