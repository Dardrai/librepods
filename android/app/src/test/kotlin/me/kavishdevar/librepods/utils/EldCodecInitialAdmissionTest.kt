/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class EldCodecInitialAdmissionTest {
    private var nativeIndexReads = 0
    private val objects = EldCodecObjects(
        EldCodecExtendedBoundaryTest.Codec::class.java, EldCodecExtendedBoundaryTest.Type::class.java
    ) { nativeIndexReads++; null }
    private val boundary = EldCodecBoundary(objects)
    private fun codec(index: Int, id: Long, priority: Int = -1) = EldCodecExtendedBoundaryTest.Codec(
        EldCodecExtendedBoundaryTest.Type(index, id, "Codec"), priority, 2, 1, 2,
        Long.MIN_VALUE, Long.MAX_VALUE, -1, 0x123456789abcdefL)
    private fun args(priority: Any? = emptyArray<EldCodecExtendedBoundaryTest.Codec>(),
        offload: Any? = emptyArray<EldCodecExtendedBoundaryTest.Codec>()) =
        arrayOf<Any?>(2, priority, offload, emptyList<Any>())

    @Test fun emptyAndStockArraysAreCheckedBeforeNativeReadinessWithoutChangingAnyValue() {
        assertTrue(boundary.initialCodecSlotFree(args()))
        val aac = codec(1, 2)
        val lhdc = codec(7, 0x4c35053affL)
        val priority = arrayOf(aac, lhdc)
        val offload = arrayOf(lhdc)
        val original = args(priority, offload)
        val before = priority.map(objects::fields)
        assertTrue(boundary.initialCodecSlotFree(original))
        assertSame(priority, original[1]); assertSame(offload, original[2])
        assertSame(aac, priority[0]); assertSame(lhdc, priority[1])
        assertEquals(before, priority.map(objects::fields))
        assertEquals(0, nativeIndexReads)
    }

    @Test fun aForeignSlotFiveInEitherInitialArrayRejectsLc3LhdcAndUnknownIds() {
        for (id in listOf(6L, 0x4c35053affL, Long.MIN_VALUE, Long.MAX_VALUE, -1L)) {
            val foreign = codec(5, id)
            val values = arrayOf(foreign)
            assertFalse(boundary.initialCodecSlotFree(args(priority = values)))
            assertFalse(boundary.initialCodecSlotFree(args(offload = values)))
            assertSame(foreign, values.single())
        }
        assertEquals(0, nativeIndexReads)
    }

    @Test fun theExactOwnSlotFiveIdentityAndDisabledPriorityCanSurviveReinitialization() {
        val own = codec(5, EldCodecIdentity.APPLE_CODEC_ID)
        val ownFields = objects.fields(own)
        val values = arrayOf(own, codec(1, 2))
        val original = args(values, arrayOf(own))
        repeat(2) { assertTrue(boundary.initialCodecSlotFree(original)) }
        assertSame(values, original[1]); assertSame(own, values[0])
        assertEquals(ownFields, objects.fields(own))
        assertEquals(-1, objects.fields(own).priority)
        assertEquals(Long.MIN_VALUE, own.getCodecSpecific1())
        assertEquals(Long.MAX_VALUE, own.getCodecSpecific2())
        assertEquals(0, nativeIndexReads)
    }

    @Test fun absentAndMalformedInitialSchemasDoNotEstablishSlotOwnership() {
        assertFalse(boundary.initialCodecSlotFree(args(priority = null)))
        assertFalse(boundary.initialCodecSlotFree(args(offload = null)))
        assertFalse(boundary.initialCodecSlotFree(args(priority = arrayOfNulls<EldCodecExtendedBoundaryTest.Codec>(1))))
        assertFalse(boundary.initialCodecSlotFree(args(offload = arrayOfNulls<EldCodecExtendedBoundaryTest.Codec>(1))))
        assertFalse(boundary.initialCodecSlotFree(args(priority = arrayOf("Unknown carrier"))))
        assertFalse(boundary.initialCodecSlotFree(args(offload = intArrayOf(5))))
        val noIdentity = EldCodecExtendedBoundaryTest.Codec(null, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(boundary.initialCodecSlotFree(args(priority = arrayOf(noIdentity))))
        assertFalse(boundary.initialCodecSlotFree(emptyArray()))
        assertFalse(boundary.initialCodecSlotFree(args().also { it[0] = "two" }))
        assertFalse(boundary.initialCodecSlotFree(args().also { it[3] = null }))
        assertFalse(boundary.initialCodecSlotFree(args().also { it[3] = arrayOf<Any>() }))
    }

    @Test fun theLegacyBoundaryDoesNotApplyTheSdk37CatalogGate() {
        val legacy = EldCodecBoundary(EldCodecObjects(EldCodecBoundaryTest.Codec::class.java))
        assertTrue(legacy.initialCodecSlotFree(emptyArray()))
    }
}
