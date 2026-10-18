package me.kavishdevar.librepods.core.utils

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GestureDetectorTest {
    private fun field(name: String) = GestureDetector::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun session(detector: GestureDetector) = field("activeSession").get(detector) as Job?

    @Test fun scoringAndOrientationUpdatesCannotReadAndModifyTheSameBuffersConcurrently() {
        val detector = GestureDetector()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val values = object : AbstractMutableList<Double>() {
            private val data = MutableList(8) { 1.0 }
            override val size get() = data.size
            override fun get(index: Int): Double {
                if (pauseOnce.compareAndSet(true, false)) {
                    reading.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
                return data[index]
            }
            override fun set(index: Int, element: Double) = data.set(index, element)
            override fun add(index: Int, element: Double) = data.add(index, element)
            override fun removeAt(index: Int) = data.removeAt(index)
        }
        val active = Job()
        field("activeSession").set(detector, active)
        field("horizontalBuffer").set(detector, values)
        @Suppress("UNCHECKED_CAST")
        val peaks = field("verticalPeaks").get(detector) as MutableList<Triple<Int, Double, Long>>
        peaks.addAll(listOf(Triple(0, 900.0, 0L), Triple(1, -900.0, 400L), Triple(2, 900.0, 800L)))
        val score = GestureDetector::class.java.getDeclaredMethod("detectGestures").apply { isAccessible = true }
        val reader = FutureTask { score.invoke(detector) }
        val writer = FutureTask { detector.processHeadOrientation(10, 10) }
        val readerThread = Thread(reader, "gesture-score-test")
        val writerThread = Thread(writer, "gesture-update-test")
        try {
            readerThread.start()
            assertTrue("Scoring must reach its shared-buffer read", reading.await(2, TimeUnit.SECONDS))
            writerThread.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (writerThread.state != Thread.State.BLOCKED && !writer.isDone && System.nanoTime() < deadline) Thread.yield()
            assertEquals("An orientation update must wait until scoring finishes", Thread.State.BLOCKED, writerThread.state)
            release.countDown()
            assertEquals(true, reader.get(2, TimeUnit.SECONDS))
            writer.get(2, TimeUnit.SECONDS)
            assertEquals(9, values.size)
        } finally {
            release.countDown()
            detector.stopDetection()
            readerThread.join(2_000)
            writerThread.join(2_000)
        }
    }

    @Test fun aFailedWorkerRetiresItsSessionAndCancelsItsPeerSoDetectionCanRestart() {
        val detector = GestureDetector()
        val collecting = CountDownLatch(1)
        val triggerFailure = CountDownLatch(1)
        val source = object : StateFlow<Acceleration> {
            override val value = Acceleration()
            override val replayCache = listOf(value)
            @OptIn(InternalCoroutinesApi::class)
            override suspend fun collect(collector: FlowCollector<Acceleration>): Nothing {
                collecting.countDown()
                check(triggerFailure.await(2, TimeUnit.SECONDS))
                throw IllegalStateException("Test sensor failure")
            }
        }
        try {
            assertTrue(detector.startDetection(source) { fail("No gesture was supplied") })
            assertTrue(collecting.await(2, TimeUnit.SECONDS))
            val failedSession = requireNotNull(session(detector))
            val workers = failedSession.children.toList()
            assertEquals(2, workers.size)
            triggerFailure.countDown()
            runBlocking { withTimeout(2_000) { failedSession.join() } }
            assertNull(session(detector))
            assertTrue(workers.all { it.isCompleted && !it.isActive })
            assertTrue(detector.startDetection(MutableStateFlow(Acceleration())) {})
        } finally {
            triggerFailure.countDown()
            detector.stopDetection()
        }
    }

    @Test fun completionFromARetiredSessionCannotStopTheNextDetection() {
        val detector = GestureDetector()
        val acceleration = MutableStateFlow(Acceleration())
        try {
            assertTrue(detector.startDetection(acceleration) {})
            val retired = requireNotNull(session(detector))
            assertFalse(detector.startDetection(acceleration) {})
            detector.stopDetection()
            assertTrue(detector.startDetection(acceleration) {})
            val current = requireNotNull(session(detector))
            GestureDetector::class.java.getDeclaredMethod("stopDetection", Job::class.java).apply { isAccessible = true }
                .invoke(detector, retired)
            assertSame(current, session(detector))
            assertTrue(current.isActive)
        } finally { detector.stopDetection() }
    }
}
