package me.kavishdevar.librepods.core.utils

import me.kavishdevar.librepods.core.data.apple.MotionReport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HeadTrackingMotionSampleTest {
    private val report = MotionReport(123, listOf(100, -200, 300), listOf(1, 2, 3),
        listOf(4, 5, 6), listOf(7, 8, 9), 4)

    @Before fun clearBefore() = HeadTracking.reset()
    @After fun clearAfter() = HeadTracking.reset()

    @Test fun latestTimedSampleKeepsTheExactSourceArrivalEvenForAnEqualReport() {
        HeadTracking.addMotion(report, 3f, 2f, 100)
        assertEquals(MotionSample(report, 100), HeadTracking.motionSample.value)
        HeadTracking.addMotion(report, 3f, 2f, 200)
        assertEquals(MotionSample(report, 200), HeadTracking.motionSample.value)
        assertSame(report, HeadTracking.motion.value)
        val next = report.copy(timestampNs = 456, counter = 8)
        HeadTracking.addMotion(next, 5f, 4f, 300)
        assertEquals(MotionSample(next, 300), HeadTracking.motionSample.value)
        assertSame(next, HeadTracking.motion.value)
    }

    @Test fun legacyMotionAndGyroPublishersKeepTheirBehaviorWithoutInventingArrivalTimes() {
        HeadTracking.addMotion(report, 3f, 2f, 100)
        HeadTracking.addMotion(report, 6f, 5f)
        assertNull(HeadTracking.motionSample.value)
        assertSame(report, HeadTracking.motion.value)
        assertEquals(Acceleration(6f, 5f), HeadTracking.acceleration.value)
        assertSame(HeadTracking.acceleration, HeadTracking.angularVelocity)
        HeadTracking.addMotion(report, 3f, 2f, 200)
        HeadTracking.addAccel(8f, 7f)
        assertEquals(MotionSample(report, 200), HeadTracking.motionSample.value)
        assertEquals(Acceleration(8f, 7f), HeadTracking.angularVelocity.value)
    }

    @Test fun resetAndNullReportsClearTimedMotion() {
        HeadTracking.addMotion(report, 3f, 2f, 100)
        HeadTracking.addMotion(null, 6f, 5f, 200)
        assertNull(HeadTracking.motionSample.value); assertNull(HeadTracking.motion.value)
        assertEquals(Acceleration(6f, 5f), HeadTracking.angularVelocity.value)
        HeadTracking.addMotion(report, 3f, 2f, 300)
        HeadTracking.reset()
        assertNull(HeadTracking.motionSample.value); assertNull(HeadTracking.motion.value)
        assertEquals(Acceleration(), HeadTracking.angularVelocity.value)
    }

    @Test fun aTimedSampleRequiresAPositiveHostArrival() {
        for (arrival in listOf(0L, -1L)) assertThrows(IllegalArgumentException::class.java) {
            MotionSample(report, arrival)
        }
    }
}
