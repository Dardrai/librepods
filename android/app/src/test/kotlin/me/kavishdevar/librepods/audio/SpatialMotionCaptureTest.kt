package me.kavishdevar.librepods.audio

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.json.JSONObject
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SpatialMotionCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun requestsRequireAnExactMacAllowedPhaseAndAtMostTenSeconds() {
        for (phase in listOf("neutral", "yaw", "pitch", "roll")) {
            assertEquals(phase, SpatialMotionCaptureRequest(MAC, phase, 1).phase)
            assertEquals(10, SpatialMotionCaptureRequest(MAC, phase).durationSeconds)
        }
        for (mac in listOf("", "00:11:22:33:44", "00:11:22:33:44:55/other")) {
            assertThrows(IllegalArgumentException::class.java) { SpatialMotionCaptureRequest(mac, "yaw") }
        }
        for (phase in listOf("", "Yaw", "../yaw", "anything")) {
            assertThrows(IllegalArgumentException::class.java) { SpatialMotionCaptureRequest(MAC, phase) }
        }
        for (seconds in listOf(0, 11, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SpatialMotionCaptureRequest(MAC, "yaw", seconds) }
        }
    }

    @Test fun captureDropsCachedMotionLimitsFreshSamplesAndPreservesRawFieldsAndArrivalClock() = runBlocking {
        val target = Target()
        val otherOwner = Any()
        target.device.startHeadTracking(otherOwner)
        val directory = temporary.newFolder("raw")
        val ticks = AtomicLong(1_000)
        val source = flow<MotionReport?> {
            emit(report(999_999))
            repeat(600) { emit(report(it.toLong())) }
        }
        val result = SpatialMotionCapture(directory, source, ticks::getAndIncrement)
            .capture(SpatialMotionCaptureRequest(MAC.lowercase(), "yaw"), target.device, { true })
        assertEquals(500, result.samples)
        assertEquals("Sample limit reached.", result.reason)
        val file = requireNotNull(result.file)
        assertEquals(directory.canonicalFile, file.parentFile.canonicalFile)
        val lines = file.readLines().map { JSONObject(it) }
        assertEquals(501, lines.size)
        assertEquals("capture", lines.first().getString("type"))
        assertEquals(MAC, lines.first().getString("expected_mac"))
        assertEquals("yaw", lines.first().getString("phase"))
        assertEquals("A3063", lines.first().getString("model_number"))
        assertEquals(1_000L, lines.first().getLong("started_arrival_ns"))
        for ((index, line) in lines.drop(1).withIndex()) {
            assertEquals("sample", line.getString("type"))
            assertEquals(1_001L + index, line.getLong("arrival_ns"))
            val raw = line.getJSONObject("report")
            val expected = report(index.toLong())
            assertEquals(12_000_000L + index, raw.getLong("timestamp_ns"))
            assertEquals(index.toLong(), raw.getLong("counter"))
            for ((key, vector) in listOf(
                "attitude_q15" to expected.attitudeQ15,
                "gyro_raw" to expected.gyroRaw,
                "user_acceleration_raw" to expected.userAccelerationRaw,
                "gravity_raw" to expected.gravityRaw,
            )) {
                val encoded = raw.getJSONArray(key)
                assertEquals(3, encoded.length())
                assertEquals(vector, (0 until encoded.length()).map(encoded::getInt))
            }
        }
        assertEquals(1, target.stopped.size)
        assertSame(target.started.last(), target.stopped.single())
        assertNotSame(otherOwner, target.stopped.single())
        verify(target.device, never()).stopHeadTracking()
    }

    @Test fun disconnectOrMotionOffClosesTheFileAndReleasesOnlyTheCaptureOwner() = runBlocking {
        for (disconnect in listOf(true, false)) {
            val target = Target()
            val current = AtomicBoolean(true)
            val arrivals = AtomicLong(123)
            val source = flow<MotionReport?> {
                emit(null)
                emit(report(1))
                if (disconnect) current.set(false)
                else target.states.value = AppleState(headTrackingState = BuddyState.INACTIVE)
                emit(report(2))
            }
            val result = SpatialMotionCapture(temporary.newFolder(), source, arrivals::getAndIncrement)
                .capture(SpatialMotionCaptureRequest(MAC, "pitch"), target.device, current::get)
            assertEquals(1, result.samples)
            assertTrue(result.reason.startsWith("Stopped:"))
            assertEquals(2, requireNotNull(result.file).readLines().size)
            assertSame(target.started.single(), target.stopped.single())
            verify(target.device, never()).stopHeadTracking()
        }
    }

    @Test fun anOverlappingCaptureIsRejectedAndCancellationReleasesItsOwnerAndTheGate() = runBlocking {
        val target = Target()
        val collecting = CompletableDeferred<Unit>()
        val arrivals = AtomicLong(123)
        val blocked = SpatialMotionCapture(temporary.newFolder(), flow<MotionReport?> {
            emit(null)
            collecting.complete(Unit)
            awaitCancellation()
        }, arrivals::getAndIncrement)
        val first = async { blocked.capture(SpatialMotionCaptureRequest(MAC, "neutral"), target.device, { true }) }
        try {
            withTimeout(2_000) { collecting.await() }
            val second = SpatialMotionCapture(temporary.newFolder(), flowOf(null, report(1)), arrivals::getAndIncrement)
                .capture(SpatialMotionCaptureRequest(MAC, "roll"), target.device, { true })
            assertNull(second.file)
            assertEquals(0, second.samples)
            assertEquals("Another motion capture is running.", second.reason)
            assertEquals(1, target.started.size)
            assertTrue(target.stopped.isEmpty())
            first.cancelAndJoin()
            assertSame(target.started.single(), target.stopped.single())

            val retry = SpatialMotionCapture(temporary.newFolder(), flowOf(null, report(2)), arrivals::getAndIncrement)
                .capture(SpatialMotionCaptureRequest(MAC, "roll"), target.device, { true })
            assertEquals(1, retry.samples)
            assertEquals(2, target.started.size)
            assertEquals(2, target.stopped.size)
            assertSame(target.started.last(), target.stopped.last())
        } finally { first.cancelAndJoin() }
    }

    @Test fun duplicateCountersAreSkippedAndUint32WrapIsAcceptedBeforeBackwardCountersStopCapture() = runBlocking {
        val target = Target()
        val arrivals = AtomicLong(100)
        val counters = listOf(0xfffffffeL, 0xfffffffeL, 0xffffffffL, 0L, 0L, 0xffffffffL)
        val source = flow<MotionReport?> {
            emit(null)
            for (counter in counters) emit(report(counter))
        }
        val result = SpatialMotionCapture(temporary.newFolder(), source, arrivals::getAndIncrement)
            .capture(SpatialMotionCaptureRequest(MAC, "roll"), target.device, { true })
        assertEquals(3, result.samples)
        assertTrue(result.reason.contains("counter moved backward"))
        val capturedCounters = requireNotNull(result.file).readLines().drop(1).map {
            JSONObject(it).getJSONObject("report").getLong("counter")
        }
        assertEquals(listOf(0xfffffffeL, 0xffffffffL, 0L), capturedCounters)
        assertSame(target.started.single(), target.stopped.single())
    }

    @Test fun unknownSensorTimestampAndCounterRemainExplicitJsonNull() = runBlocking {
        val target = Target()
        val arrivals = AtomicLong(100)
        val unknown = report(1).copy(timestampNs = null, counter = null)
        val result = SpatialMotionCapture(temporary.newFolder(), flowOf(null, unknown), arrivals::getAndIncrement)
            .capture(SpatialMotionCaptureRequest(MAC, "neutral"), target.device, { true })
        assertEquals(1, result.samples)
        val raw = JSONObject(requireNotNull(result.file).readLines()[1]).getJSONObject("report")
        assertTrue(raw.has("timestamp_ns"))
        assertTrue(raw.isNull("timestamp_ns"))
        assertTrue(raw.has("counter"))
        assertTrue(raw.isNull("counter"))
    }

    @Test fun aCaptureWithoutMotionEndsAtItsExplicitOneSecondDeadline() = runBlocking {
        val target = Target()
        val arrivals = AtomicLong(100)
        val source = flow<MotionReport?> { emit(null); awaitCancellation() }
        val result = withTimeout(3_000) {
            SpatialMotionCapture(temporary.newFolder(), source, arrivals::getAndIncrement)
                .capture(SpatialMotionCaptureRequest(MAC, "neutral", 1), target.device, { true })
        }
        assertEquals(0, result.samples)
        assertEquals("Capture duration complete.", result.reason)
        assertEquals(1, requireNotNull(result.file).readLines().size)
        assertSame(target.started.single(), target.stopped.single())
    }

    private class Target {
        val device = mock(AppleDevice::class.java)
        val states = MutableStateFlow(AppleState(headTrackingState = BuddyState.WAITING))
        val started = CopyOnWriteArrayList<Any>()
        val stopped = CopyOnWriteArrayList<Any>()
        init {
            `when`(device.macAddress).thenReturn(MacAddress(MAC))
            `when`(device.state).thenReturn(states)
            `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(modelNumber = "A3063", version1 = "9A348")))
            doAnswer { started += it.getArgument<Any>(0); true }.`when`(device).startHeadTracking(any<Any>() ?: Any())
            doAnswer { stopped += it.getArgument<Any>(0); true }.`when`(device).stopHeadTracking(any<Any>() ?: Any())
        }
    }

    companion object {
        private const val MAC = "00:AA:22:33:44:55"
        private fun report(counter: Long) = MotionReport(
            timestampNs = 12_000_000 + counter,
            attitudeQ15 = listOf(-32768, 123, 32767),
            gyroRaw = listOf(-1200, 1400, 250),
            userAccelerationRaw = listOf(12, -34, 56),
            gravityRaw = listOf(-8192, 16384, 321),
            counter = counter,
        )
    }
}
