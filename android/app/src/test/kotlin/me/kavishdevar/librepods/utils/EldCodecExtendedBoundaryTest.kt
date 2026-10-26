/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class EldCodecExtendedBoundaryTest {
    private var nativeIndex: Int? = 8
    private val objects = EldCodecObjects(Codec::class.java, Type::class.java) { nativeIndex }
    private val boundary = EldCodecBoundary(objects)
    private val aac = Type(1, 2, "AAC")
    private val lhdc = Type(7, 0x4c35053affL, "LHDCV5")
    private fun type(index: Int = 8) = Type(index, EldCodecIdentity.APPLE_CODEC_ID, "Apple AAC-ELD")
    private fun codec(type: Type, priority: Int = 0) = Codec(type, priority, 0x22, 7, 3,
        Long.MIN_VALUE, Long.MAX_VALUE, -1, 0x123456789abcdefL)

    @Test fun android17UsesTheSeparateCallbackOwnerAndAnUnchangedDeviceList() {
        val plan = EldCodecHookPlan(Native::class.java, Config::class.java, Codec::class.java,
            Device::class.java, Callback::class.java, Type::class.java) { nativeIndex }
        assertEquals(4, plan.init.parameterCount)
        assertEquals(List::class.java, plan.init.parameterTypes.last())
        assertEquals(Callback::class.java, plan.outbound.declaringClass)
        assertEquals(Callback::class.java, plan.connection.declaringClass)
        assertEquals(3, plan.connection.parameterCount)
        assertEquals(2, plan.audio.parameterCount)
        assertNull(plan.bufferSwitch)
        assertNull(plan.name)
        assertFalse(boundary.suppressBufferSwitch(EldCodecIdentity.MODULE_CODEC_ID, true))
        val devices = listOf(Device(), Device())
        val stock = arrayOf(codec(aac), codec(lhdc))
        val args = arrayOf<Any?>(2, stock, emptyArray<Codec>(), devices)
        val mapped = boundary.init(args, true)
        assertSame(devices, mapped[3])
        assertEquals(2, devices.size)
        assertSame(stock[0], (mapped[1] as Array<*>)[0])
        assertSame(stock[1], (mapped[1] as Array<*>)[1])
        assertEquals(EldCodecIdentity.APPLE_CODEC_ID, ((mapped[1] as Array<*>).last() as Codec)
            .getExtendedCodecType()!!.getCodecId())
    }

    @Test fun unknownSdkAndOldCallbackShapeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EldCodecHookPlan.discover(checkNotNull(javaClass.classLoader), 38)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EldCodecHookPlan(Native::class.java, Config::class.java, Codec::class.java,
                Device::class.java, OldCallback::class.java, Type::class.java) { nativeIndex }
        }
    }

    @Test fun nativeIndexAndRealVendorIdArePreservedWithoutPrivateIntegerRelabeling() {
        assertEquals(0x8001004cffL, EldCodecIdentity.APPLE_CODEC_ID)
        val eld = codec(type(), -1)
        val unknown = codec(Type(5, Long.MIN_VALUE, "Other codec"))
        val local = arrayOf(unknown, codec(lhdc), eld)
        val selectable = arrayOf<Codec?>(null, eld)
        val address = byteArrayOf(1, 2, 3, 4, 5, 6)
        val args = arrayOf<Any?>(address, eld, local, selectable)
        val mapped = boundary.outbound(args, true)
        assertSame(address, mapped[0])
        assertSame(eld, mapped[1])
        assertSame(local, mapped[2])
        assertSame(selectable, mapped[3])
        assertEquals(8, (mapped[1] as Codec).getCodecType())
        assertEquals(objects.fields(eld), objects.fields(mapped[1]!!))
        assertSame(args, boundary.outbound(args, false))
    }

    @Test fun reconstructingUnrelatedConfigRetainsItsOriginalExtendedTypeAndAllLongs() {
        val original = codec(lhdc)
        val changed = objects.map(original) { it.copy(priority = -1) } as Codec
        assertNotSame(original, changed)
        assertSame(lhdc, changed.getExtendedCodecType())
        assertEquals(objects.fields(original).copy(priority = -1), objects.fields(changed))
        assertEquals(Long.MIN_VALUE, changed.getCodecSpecific1())
        assertEquals(Long.MAX_VALUE, changed.getCodecSpecific2())
        assertEquals(-1L, changed.getCodecSpecific3())
        assertEquals(0x123456789abcdefL, changed.getCodecSpecific4())
    }

    @Test fun inactiveBoundaryPublishesNothingAndRejectsOnlyItsStaleOwnPreferences() {
        val stock = arrayOf(codec(aac), codec(lhdc), codec(Type(5, 0x123456789L, "Unknown")))
        val devices = listOf(Device())
        val args = arrayOf<Any?>(2, stock, stock, devices)
        val init = boundary.init(args, false)
        assertSame(stock, init[1])
        assertSame(stock, init[2])
        assertSame(devices, init[3])
        val own = codec(type())
        val preferences = arrayOf<Codec?>(stock[0], own, null, stock[2])
        val mapped = boundary.preference(arrayOf(Device(), preferences), false)!![1] as Array<*>
        assertSame(stock[0], mapped[0])
        assertNull(mapped[1])
        assertNull(mapped[2])
        assertSame(stock[2], mapped[3])
        assertNull(boundary.preference(arrayOf(Device(), arrayOf(own)), false))
    }

    @Test fun onlyTheValidatedNativeIndexCanCarryOurVendorCodecInPreferences() {
        val correct = codec(type(), -1)
        val stale = codec(type(5))
        val unrelated = codec(Type(5, 123L, "Other codec"))
        val preferences = arrayOf(correct, stale, unrelated)
        val mapped = boundary.preference(arrayOf(Device(), preferences), true)!![1] as Array<*>
        assertSame(correct, mapped[0])
        assertNull(mapped[1])
        assertSame(unrelated, mapped[2])
        assertEquals(-1, (mapped[0] as Codec).getCodecPriority())
        assertThrows(IllegalArgumentException::class.java) {
            boundary.outbound(arrayOf(byteArrayOf(), stale, preferences, preferences), true)
        }
    }

    @Test fun indexIsNeverAssumedAndSinkOrUnknownIndicesCannotAddPriorities() {
        for (index in listOf(null, -1, 12, 18, Int.MAX_VALUE)) {
            nativeIndex = index
            assertNull(objects.nativeIndex())
            val stock = arrayOf(codec(aac), codec(lhdc))
            assertSame(stock, objects.priorities(stock, true))
            assertNull(boundary.preference(arrayOf(Device(), arrayOf(codec(type()))), true))
        }
        nativeIndex = 11
        val mapped = objects.priorities(emptyArray<Codec>(), true) as Array<*>
        assertEquals(11, (mapped.single() as Codec).getCodecType())
    }

    @Test fun softwareCodecIsNeverAddedToOffloadButOtherExtendedCodecsAreUntouched() {
        val other = codec(lhdc)
        val own = codec(type())
        val offload = arrayOf(other, own)
        val mapped = boundary.init(arrayOf(2, emptyArray<Codec>(), offload, emptyList<Device>()), true)[2] as Array<*>
        assertSame(other, mapped[0])
        assertNull(mapped[1])
    }

    @Test fun nativeIndexConflictIsRejectedAndAnExplicitDisabledPriorityIsPreserved() {
        val conflict = codec(Type(8, 123L, "Existing provider"))
        assertThrows(IllegalArgumentException::class.java) { objects.priorities(arrayOf(conflict), true) }
        val explicit = codec(type(), -1)
        val entries = arrayOf(codec(lhdc), explicit)
        assertSame(entries, objects.priorities(entries, true))
        assertSame(explicit, (boundary.init(arrayOf(2, entries, null, emptyList<Device>()), true)[1] as Array<*>)[1])
    }

    @Test fun aacFallbackUsesTheRealStockCarrierAndNoEldCodecSpecificData() {
        val fallback = (objects.aacFallback() as Array<*>).single() as Codec
        assertEquals(EldCodecIdentity.Fields(1, 1_000_000, 0, 0, 0, 0, 0, 0, 0, 2), objects.fields(fallback))
        assertEquals("AAC", fallback.getExtendedCodecType()!!.getCodecName())
    }

    class Device
    @Suppress("UNUSED_PARAMETER")
    class Native {
        fun init(count: Int, priority: Array<Codec?>, offload: Array<Codec?>, devices: List<Device>) = Unit
        fun setCodecConfigPreference(device: Device?, codecs: Array<Codec?>): Boolean = true
        fun setActiveDevice(device: Device?): Boolean = true
        fun getByteAddress(device: Device?): ByteArray = ByteArray(6)
        fun cleanup() = Unit
    }
    @Suppress("UNUSED_PARAMETER")
    class Callback {
        fun onCodecConfigChanged(address: ByteArray, codec: Codec, local: Array<Codec?>, selectable: Array<Codec?>) = Unit
        fun onAudioStateChanged(address: ByteArray, state: Int) = Unit
        fun onConnectionStateChanged(address: ByteArray, state: Int, status: Int) = Unit
    }
    @Suppress("UNUSED_PARAMETER")
    class OldCallback {
        fun onCodecConfigChanged(address: ByteArray, codec: Codec, local: Array<Codec?>, selectable: Array<Codec?>) = Unit
        fun onAudioStateChanged(address: ByteArray, state: Int) = Unit
        fun onConnectionStateChanged(address: ByteArray, state: Int) = Unit
    }
    class Config { fun assignCodecConfigPriorities(): Array<Codec?> = emptyArray() }
    class Type(private val index: Int, private val id: Long, private val name: String) {
        fun getNativeCodecType() = index
        fun getCodecId() = id
        fun getCodecName() = name
        companion object {
            @JvmStatic fun createFromType(index: Int): Type? = when (index) {
                0 -> Type(0, 0, "SBC")
                1 -> Type(1, 2, "AAC")
                else -> null
            }
        }
    }
    class Codec(private val type: Type?, private val priority: Int, private val rate: Int,
        private val bits: Int, private val channels: Int, private val one: Long,
        private val two: Long, private val three: Long, private val four: Long) {
        constructor(index: Int, priority: Int, rate: Int, bits: Int, channels: Int, one: Long,
            two: Long, three: Long, four: Long) : this(Type.createFromType(index), priority, rate,
            bits, channels, one, two, three, four)
        fun getCodecType() = type?.getNativeCodecType() ?: 1_000_000
        fun getExtendedCodecType() = type
        fun getCodecPriority() = priority
        fun getSampleRate() = rate
        fun getBitsPerSample() = bits
        fun getChannelMode() = channels
        fun getCodecSpecific1() = one
        fun getCodecSpecific2() = two
        fun getCodecSpecific3() = three
        fun getCodecSpecific4() = four
    }
}
