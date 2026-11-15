package me.kavishdevar.librepods.core.data.apple

import org.junit.Assert.*
import org.junit.Test

class MotionCounterTest {
    @Test fun shortSixteenBitCrossingsNeedMatchingSensorTime() {
        for ((before, after, step) in listOf(Triple(65534L, 0L, 2L), Triple(65535L, 1L, 2L),
            Triple(65532L, 0L, 4L), Triple(65524L, 12L, 24L), Triple(65510L, 24L, 50L))) {
            val elapsed = if (step == 50L) 249_999_999L else step * 5_000_000L
            assertEquals(step, motionCounterStep(before, after, 1_000_000_000L, 1_000_000_000L + elapsed))
        }
        assertEquals(2L, motionCounterStep(65534, 0, 100, 5_000_100))
        assertEquals(2L, motionCounterStep(65534, 0, 100, 15_000_100))
        assertEquals(2L, motionCounterStep(65534, 0, Long.MAX_VALUE - 10_000_000, Long.MAX_VALUE))
    }

    @Test fun ambiguousResetsAndMissingOrInconsistentTimeKeepTheirOriginalUnsignedStep() {
        fun unchanged(before: Long, after: Long, start: Long?, end: Long?) =
            assertEquals((after - before) and 0xffffffffL, motionCounterStep(before, after, start, end))
        for ((before, after) in listOf(65534L to 65534L, 30000L to 0L, 100L to 98L,
            65530L to 65528L, 0x1fffeL to 0x10000L, 0x1fffeL to 0L))
            unchanged(before, after, 100, 10_000_100)
        for ((start, end) in listOf(null to 10_000_100L, 100L to null, 0L to 10_000_000L,
            -1L to 9_999_999L, 100L to 100L, 100L to 99L, 100L to 250_000_100L,
            100L to 4_999_999L, 100L to 15_000_101L, 100L to Long.MAX_VALUE))
            unchanged(65534, 0, start, end)
    }

    @Test fun ordinaryForwardAndThirtyTwoBitWrapBehaviorIsUnchanged() {
        assertEquals(4L, motionCounterStep(100, 104, null, null))
        assertEquals(0L, motionCounterStep(100, 100, 100, 200))
        assertEquals(2L, motionCounterStep(0xfffffffeL, 0, 100, 200))
        assertEquals(0xffffffffL, motionCounterStep(0, 0xffffffffL, 100, 200))
    }
}
