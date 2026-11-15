package me.kavishdevar.librepods.core.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun aTwoReportBurstReachesEachLiveSubscriberInOrderWhileStateRemainsLatest() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            HeadTracking.motionSamples.filterNotNull().take(2).toList()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            HeadTracking.motionSamples.filterNotNull().take(2).toList()
        }
        val state = async(start = CoroutineStart.UNDISPATCHED) {
            HeadTracking.motionSample.filterNotNull().first()
        }
        HeadTracking.addMotion(report, 0f, 0f, 100)
        val one = HeadTracking.motionSample.value!!
        HeadTracking.addMotion(report.copy(counter = 6), 0f, 0f, 200)
        val two = HeadTracking.motionSample.value!!
        withTimeout(2_000) {
            assertEquals(listOf(one, two), first.await())
            assertEquals(listOf(one, two), second.await())
            assertSame(two, state.await())
        }
        // Both handoffs share the same immutable sample, not separately allocated wrappers.
        assertSame(two, HeadTracking.motionSamples.replayCache.single())
    }

    @Test fun aBlockedSubscriberCannotBlockTheProducerOrRetainMoreThanTheNewestTwoReports() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<Long>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            HeadTracking.motionSamples.filterNotNull().take(3).collect {
                received += it.arrivalNs
                if (received.size == 1) { entered.complete(Unit); release.await() }
            }
        }
        try {
            HeadTracking.addMotion(report, 0f, 0f, 1)
            withTimeout(2_000) { entered.await() }
            // No receiver progress is possible until publication of the whole burst completes.
            for (arrival in 2L..100L) HeadTracking.addMotion(report, 0f, 0f, arrival)
            assertEquals(listOf(1L), received)
            release.complete(Unit)
            withTimeout(2_000) { reader.join() }
            assertEquals(listOf(1L, 99L, 100L), received)
        } finally { release.complete(Unit); reader.cancel() }
    }

    @Test fun aNewSubscriberGetsOnlyTheLatestReportAndResetOrLegacyPublicationClearsReplay() = runBlocking {
        HeadTracking.addMotion(report, 0f, 0f, 100)
        HeadTracking.addMotion(report.copy(counter = 6), 0f, 0f, 200)
        assertSame(HeadTracking.motionSample.value, HeadTracking.motionSamples.first())
        HeadTracking.reset()
        assertNull(HeadTracking.motionSamples.first())
        HeadTracking.addMotion(report, 0f, 0f, 300)
        HeadTracking.addMotion(report, 0f, 0f)
        assertNull(HeadTracking.motionSamples.first())
        HeadTracking.addMotion(report, 0f, 0f, 400)
        HeadTracking.addMotion(null, 0f, 0f, 500)
        assertNull(HeadTracking.motionSamples.first())
    }
}
