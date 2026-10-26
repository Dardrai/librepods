/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class EldCodecBoundaryTest {
    private val objects = EldCodecObjects(Codec::class.java)
    private val boundary = EldCodecBoundary(objects)
    private fun codec(type: Int, priority: Int = 0) = Codec(type, priority, 0x22, 7, 3,
        Long.MIN_VALUE, Long.MAX_VALUE, -1, 0x123456789abcdefL)

    @Test fun exactNote8ShapeIsAcceptedAndUnknownOverloadOrReturnTypeIsRejected() {
        EldCodecHookPlan(Native::class.java, Config::class.java, Codec::class.java, Device::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            exactEldMethod(Overloaded::class.java, "callback", Void.TYPE, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            exactEldMethod(Native::class.java, "init", Boolean::class.javaPrimitiveType!!,
                listOf(Int::class.javaPrimitiveType!!, objects.arrayClass, objects.arrayClass))
        }
    }

    @Test fun currentLocalAndSelectableAreMappedTogetherWithoutMutatingOriginalObjects() {
        val eld = codec(5)
        val aac = codec(1, 1_000_000)
        val opus = codec(6)
        val local = arrayOf<Codec?>(aac, eld, opus)
        val selectable = arrayOf<Codec?>(null, eld)
        val address = byteArrayOf(1, 2, 3, 4, 5, 6)
        val args = arrayOf<Any?>(address, eld, local, selectable)
        assertSame(args, boundary.outbound(args, false))
        val mapped = boundary.outbound(args, true)
        assertSame(address, mapped[0])
        assertEquals(objects.fields(eld).copy(type = 0x4c8001), objects.fields(mapped[1]!!))
        val mappedLocal = mapped[2] as Array<*>
        assertSame(aac, mappedLocal[0]); assertSame(opus, mappedLocal[2])
        assertEquals(0x4c8001, (mappedLocal[1] as Codec).getCodecType())
        assertNull((mapped[3] as Array<*>)[0])
        assertEquals(5, eld.getCodecType()); assertSame(eld, local[1])
    }

    @Test fun incomingPreferencesRemoveLc3ButPreserveExplicitPrivateDisabledPriorityAndOtherCodecs() {
        val aac = codec(1, 1_000_000)
        val eld = codec(0x4c8001, -1)
        val configs = arrayOf<Codec?>(codec(5), aac, null, eld)
        val mapped = boundary.preference(arrayOf(Device(), configs), true)!![1] as Array<*>
        assertNull(mapped[0]); assertSame(aac, mapped[1]); assertNull(mapped[2])
        assertEquals(objects.fields(eld).copy(type = 5), objects.fields(mapped[3]!!))
        assertEquals(0x4c8001, eld.getCodecType())
        assertNull(boundary.preference(arrayOf(Device(), arrayOf(codec(5))), true))
    }

    @Test fun inactivePreferencesKeepLc3AndRejectOnlyTheStalePrivateIdentity() {
        val lc3 = codec(5)
        val aac = codec(1)
        val configs = arrayOf<Codec?>(lc3, codec(0x4c8001), aac)
        val mapped = boundary.preference(arrayOf(Device(), configs), false)!![1] as Array<*>
        assertSame(lc3, mapped[0]); assertNull(mapped[1]); assertSame(aac, mapped[2])
        assertNull(boundary.preference(arrayOf(Device(), arrayOf(codec(0x4c8001))), false))
    }

    @Test fun initializationAddsMissingPriorityAndNeverMarksTheSoftwareAdapterOffloaded() {
        val stock = listOf(0, 1, 2, 3, 4, 6).map { codec(it) }.toTypedArray()
        val aac = codec(1)
        val offloading = arrayOf<Codec?>(aac, codec(5), codec(0x4c8001))
        val mapped = boundary.init(arrayOf(2, stock, offloading), true)
        assertEquals(2, mapped[0])
        val priorities = mapped[1] as Array<*>
        assertEquals(7, priorities.size)
        stock.forEachIndexed { i, value -> assertSame(value, priorities[i]) }
        assertEquals(EldCodecIdentity.Fields(5, 10_001, 0, 0, 0, 0, 0, 0, 0), objects.fields(priorities[6]!!))
        val actualOffload = mapped[2] as Array<*>
        assertSame(aac, actualOffload[0]); assertNull(actualOffload[1]); assertNull(actualOffload[2])
    }

    @Test fun anExplicitPrivatePriorityIsNotOverwrittenByInitialization() {
        val disabled = codec(0x4c8001, -1)
        val input = arrayOf<Codec?>(codec(0), disabled)
        val priorities = boundary.init(arrayOf(2, input, null), true)[1] as Array<*>
        assertEquals(2, priorities.size)
        assertEquals(objects.fields(disabled).copy(type = 5), objects.fields(priorities[1]!!))
    }

    @Test fun stockBufferSwitchBehaviorIncludingRealOpusIsPreserved() {
        for (type in listOf(0, 1, 2, 3, 4, 5, 6, 255)) assertFalse(boundary.suppressBufferSwitch(type, true))
        assertTrue(boundary.suppressBufferSwitch(0x4c8001, true))
        assertFalse(boundary.suppressBufferSwitch(0x4c8001, false))
    }

    @Test fun nullAndEmptyStockPreferenceRequestsKeepTheirOriginalMeaning() {
        val empty = emptyArray<Codec?>()
        assertSame(empty, boundary.preference(arrayOf(Device(), empty), true)!![1])
        assertNull(boundary.preference(arrayOf(Device(), null), false)!![1])
        assertThrows(IllegalArgumentException::class.java) {
            objects.array(arrayOf("wrong carrier")) { it }
        }
    }

    class Device
    @Suppress("UNUSED_PARAMETER")
    class Native {
        fun init(count: Int, priority: Array<Codec?>?, offload: Array<Codec?>?) = Unit
        fun onCodecConfigChanged(address: ByteArray, current: Codec?, local: Array<Codec?>?, selectable: Array<Codec?>?) = Unit
        fun setCodecConfigPreference(device: Device?, value: Array<Codec?>?): Boolean = true
        fun setActiveDevice(device: Device?): Boolean = true
        fun getByteAddress(device: Device?): ByteArray = ByteArray(6)
        fun onAudioStateChanged(address: ByteArray, state: Int) = Unit
        fun onConnectionStateChanged(address: ByteArray, state: Int) = Unit
        fun cleanup() = Unit
    }
    @Suppress("UNUSED_PARAMETER")
    class Config {
        fun assignCodecConfigPriorities(): Array<Codec?> = emptyArray()
        fun switchCodecByBufferSize(device: Device?, lowLatency: Boolean, current: Int) = Unit
    }
    class Overloaded { fun callback() = Unit; fun callback(value: Int) = Unit }
    class Codec(private val kind: Int, private val priority: Int, private val rate: Int,
        private val bits: Int, private val channels: Int, private val one: Long,
        private val two: Long, private val three: Long, private val four: Long) {
        fun getCodecType() = kind
        fun getCodecPriority() = priority
        fun getSampleRate() = rate
        fun getBitsPerSample() = bits
        fun getChannelMode() = channels
        fun getCodecSpecific1() = one
        fun getCodecSpecific2() = two
        fun getCodecSpecific3() = three
        fun getCodecSpecific4() = four
        companion object { @JvmStatic fun getCodecName(type: Int) = "Codec $type" }
    }
}
