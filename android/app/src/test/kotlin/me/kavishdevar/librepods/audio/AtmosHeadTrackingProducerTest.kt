/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.bluetooth.BluetoothSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.shizuku.AtmosCanonicalFrame
import me.kavishdevar.librepods.shizuku.AtmosPoseOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosHeadTrackingProducerTest {
    private val identity = requireNotNull(SpatialPoseProfile.fromBasis(listOf(
        listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))))

    private class Output : AtmosPoseOutput {
        val frames = CopyOnWriteArrayList<ByteArray>()
        var closes = 0
        var fail = false
        override fun submit(pose: SpatialPose, arrivalNs: Long) {
            check(closes == 0 && !fail) { "test_output_retired" }
            frames += AtmosCanonicalFrame.encode(pose, arrivalNs)
        }
        override fun close() { closes++ }
    }

    private inner class Target {
        val started = CopyOnWriteArrayList<Any>()
        val released = CopyOnWriteArrayList<Any>()
        val device = mock(AppleDevice::class.java, withSettings().defaultAnswer { call ->
            if (call.method.name.startsWith("startHeadTracking") && call.arguments.isNotEmpty()) {
                started += call.getArgument<Any>(0); true
            } else RETURNS_DEFAULTS.answer(call)
        })
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val states = MutableStateFlow(AppleState(budRoleRaw = listOf(1, 0, 1, 0)))
        val metadata = MutableStateFlow(AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3064", version1 = "fixture"))
        val connection = MutableStateFlow(ConnectionState.CONNECTED)
        val motion = MutableSharedFlow<MotionSample?>()
        val clock = AtomicLong(1_000_000_000)
        var currentProfile: SpatialPoseProfile? = identity
        var allowed = true
        var tryFast = true
        var fallbacks = 0
        init {
            `when`(device.macAddress).thenReturn(MacAddress("02:11:22:33:44:55"))
            `when`(device.aacp).thenReturn(aacp); `when`(device.state).thenReturn(states)
            `when`(device.metadata).thenReturn(metadata); `when`(device.connectionState).thenReturn(connection)
            `when`(aacp.captureAudioSocket()).thenReturn(socket); `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
            doAnswer { released += it.getArgument<Any>(0); true }.`when`(device).stopHeadTracking(any<Any>() ?: Any())
        }
        fun producer(output: Output, discontinuity: Int = 9, recovery: HeadMotionGapRecovery? = null,
            recoveryActive: () -> Boolean = { true }) =
            AtmosHeadTrackingProducer(requireNotNull(AtmosMotionSource.capture(device, identity)),
                motion, discontinuity, output, { allowed }, { currentProfile }, { tryFast }, { fallbacks++ },
                now = clock::get, recovery = recovery, recoveryActive = recoveryActive)
        suspend fun emit(tick: Int, arrival: Long = clock.addAndGet(10_000_000)) {
            motion.emit(MotionSample(MotionReport(tick * 10_000_000L, listOf(1, 0, 0), listOf(0, 0, 0),
                listOf(0, 0, 0), listOf(0, 0, -1000), tick * 2L), arrival))
        }
    }

    @Test fun closingOneConsumerPreservesOtherOwnerAndOriginalTimestamps() = runBlocking {
        val f = Target(); val a = Output(); val b = Output()
        val first = f.producer(a); val second = f.producer(b)
        val ja = async(start = CoroutineStart.UNDISPATCHED) { first.run() }
        val jb = async(start = CoroutineStart.UNDISPATCHED) { second.run() }
        try {
            waitUntil { f.motion.subscriptionCount.value == 2 }
            f.emit(1); f.emit(2)
            waitUntil { a.frames.size == 1 && b.frames.size == 1 }
            val value = requireNotNull(AtmosCanonicalFrame.decode(a.frames.single()))
            assertEquals(20_000_000L, value.sensorNs); assertEquals(1_020_000_000L, value.arrivalNs)
            assertEquals(9, value.discontinuity)
            assertEquals(2, f.started.size); assertNotSame(f.started[0], f.started[1])
            first.close(); withTimeout(2_000) { ja.join() }
            assertEquals(listOf(f.started[0]), f.released.toList()); assertEquals(0, b.closes)
            first.close() // Delayed cleanup keeps its captured output; it cannot close second.
            f.emit(3); waitUntil { b.frames.size == 2 }
            assertEquals(1, a.frames.size); assertEquals(1, a.closes)
        } finally { first.close(); second.close(); ja.cancelAndJoin(); jb.cancelAndJoin() }
        assertEquals(f.started.toSet(), f.released.toSet())
        verify(f.device, never()).stopHeadTracking()
    }

    @Test fun validatedSharedRuntimeFencesCapturedStartupZerosAndPreservesOriginalTimestamps() = runBlocking {
        val f = Target(); val output = Output(); val producer = f.producer(output)
        val job = async(start = CoroutineStart.UNDISPATCHED) { producer.run() }
        val captured = listOf(
            Triple(9921037129000L, listOf(-18261, 22093, 169), 24909L),
            Triple(9921106420000L, listOf(0, 0, 0), 24916L),
            Triple(9921128720000L, listOf(0, 0, 0), 24921L),
            Triple(9921158646000L, listOf(0, 0, 0), 24927L),
            Triple(9921193681000L, listOf(0, 0, 0), 24934L),
            Triple(9921235013000L, listOf(-13479, 22145, 129), 24942L)
        ).map { (time, attitude, counter) -> MotionReport(time, attitude, listOf(0, 0, 0),
            listOf(0, 0, 0), listOf(0, 0, -1000), counter) }
        try {
            waitUntil { f.motion.subscriptionCount.value == 1 }
            captured.forEach { f.motion.emit(MotionSample(it, f.clock.addAndGet(10_000_000))) }
            assertTrue(output.frames.isEmpty()); assertTrue(job.isActive)
            val last = captured.last()
            f.motion.emit(MotionSample(last.copy(timestampNs = last.timestampNs!! + 33_333_333,
                counter = last.counter!! + 6), f.clock.addAndGet(10_000_000)))
            waitUntil { output.frames.size == 1 }
            val frame = requireNotNull(AtmosCanonicalFrame.decode(output.frames.single()))
            assertEquals(last.timestampNs!! + 33_333_333, frame.sensorNs); assertEquals(1_070_000_000L, frame.arrivalNs)
            assertEquals(9, frame.discontinuity); assertEquals(identity.headFromRaw, f.currentProfile?.headFromRaw)
        } finally { producer.close(); job.cancelAndJoin() }
        assertEquals(f.started.toSet(), f.released.toSet())
        verify(f.device, never()).stopHeadTracking()
    }

    @Test fun sameKeyRecalibrationAndSocketOrRoleChangesInvalidateCapturedSource() {
        val f = Target(); val source = requireNotNull(AtmosMotionSource.capture(f.device, identity))
        assertTrue(source.current(identity))
        val changed = requireNotNull(SpatialPoseProfile.fromBasis(listOf(
            listOf(0.0, 1.0, 0.0), listOf(-1.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0))))
        assertFalse(source.current(changed)); assertFalse(source.current(null))
        f.states.value = f.states.value.copy(budRoleRaw = listOf(1, 0, 1, 1))
        assertFalse(source.current(identity))
        f.states.value = f.states.value.copy(budRoleRaw = listOf(1, 0, 1, 0))
        `when`(f.aacp.ownsAudioSocket(f.socket)).thenReturn(false)
        assertFalse(source.current(identity))
    }

    @Test fun verifiedGapClosesOldAtmosOutputAndNewEpochCannotReuseItsPoseOrRetryBudget() = runBlocking {
        val f = Target(); val recovery = HeadMotionGapRecovery(); val oldOutput = Output()
        val old = f.producer(oldOutput, recovery = recovery)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { old.run() }.exceptionOrNull() }
        suspend fun emit(time: Long, counter: Long) = f.motion.emit(MotionSample(MotionReport(time,
            listOf(1, 0, 0), listOf(0, 0, 0), listOf(0, 0, 0), listOf(0, 0, -1000), counter),
            f.clock.addAndGet(30_000_000)))
        try {
            waitUntil { f.motion.subscriptionCount.value == 1 }
            emit(20_000_000, 4); emit(40_000_000, 8)
            waitUntil { oldOutput.frames.size == 1 }
            emit(328_887_000, 66)
            val error = withTimeout(2_000) { first.await() } as CalibratedHeadMotionFault
            assertTrue(error.verifiedSensorGap); assertTrue(recovery.failed(error.verifiedSensorGap))
            assertEquals(1, oldOutput.closes); assertEquals(f.started.toSet(), f.released.toSet())

            val freshOutput = Output(); val fresh = f.producer(freshOutput, 10, recovery)
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fresh.run() }.exceptionOrNull() }
            try {
                waitUntil { f.motion.subscriptionCount.value == 1 }
                emit(360_000_000, 72)
                assertTrue("New root/source witness needs a fresh pair", freshOutput.frames.isEmpty())
                emit(393_333_333, 78)
                waitUntil { freshOutput.frames.size == 1 }
                val pose = requireNotNull(AtmosCanonicalFrame.decode(freshOutput.frames.single()))
                assertEquals(10, pose.discontinuity); assertEquals(393_333_333L, pose.sensorNs)
                assertEquals(1, oldOutput.frames.size); assertNotSame(f.started[0], f.started[1])
                emit(682_220_333, 136)
                val repeated = withTimeout(2_000) { second.await() } as CalibratedHeadMotionFault
                assertTrue(repeated.verifiedSensorGap); assertFalse(recovery.failed(repeated.verifiedSensorGap))
                assertTrue(recovery.latched); assertEquals(1, freshOutput.closes)
            } finally { fresh.close(); second.cancelAndJoin() }
        } finally { old.close(); first.cancelAndJoin() }
        assertEquals(f.started.toSet(), f.released.toSet())
        verify(f.device, never()).stopHeadTracking()
    }

    @Test fun staleAndPreStartSamplesDoNotBecomeNewlyTimestampedOutput() = runBlocking {
        val f = Target(); val out = Output(); val producer = f.producer(out)
        val job = async(start = CoroutineStart.UNDISPATCHED) { producer.run() }
        try {
            waitUntil { f.motion.subscriptionCount.value == 1 }
            f.emit(90, 999_999_999)
            f.clock.addAndGet(300_000_000)
            f.emit(91, 1_000_000_001)
            f.emit(1); f.emit(2)
            f.emit(3, f.clock.get()) // A second report from the same Bluetooth read.
            val receipt = f.clock.addAndGet(10_000_000)
            f.clock.addAndGet(15_000_000) // Parsing took time on this read.
            f.emit(4, receipt)
            waitUntil { out.frames.size >= 2 }
            val frames = out.frames.map { requireNotNull(AtmosCanonicalFrame.decode(it)) }
            assertEquals(listOf(20_000_000L, 40_000_000L), frames.map { it.sensorNs })
            assertEquals(listOf(1_320_000_000L, receipt), frames.map { it.arrivalNs })
        } finally { producer.close(); job.cancelAndJoin() }
    }

    @Test fun outputFailureReleasesOnlyItsMotionRequestAndClosesCapturedOutput() = runBlocking {
        val f = Target(); val out = Output(); val producer = f.producer(out)
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { producer.run() }.exceptionOrNull() }
        waitUntil { f.motion.subscriptionCount.value == 1 }
        f.emit(1); out.fail = true; f.emit(2)
        assertEquals("test_output_retired", withTimeout(2_000) { job.await() }?.message)
        assertEquals(f.started.toList(), f.released.toList()); assertEquals(1, out.closes)
        verify(f.device, never()).stopHeadTracking()
    }

    @Test fun referenceOnlyAtmosFramesCannotReplenishTheRecoveryBudgetWhilePaused() = runBlocking {
        val f = Target(); f.tryFast = false
        val recovery = HeadMotionGapRecovery(); assertTrue(recovery.failed(true))
        val output = Output(); val producer = f.producer(output, recovery = recovery, recoveryActive = { false })
        val job = async(start = CoroutineStart.UNDISPATCHED) { producer.run() }
        try {
            waitUntil { f.motion.subscriptionCount.value == 1 }
            for (tick in 1..310) f.emit(tick, f.clock.addAndGet(100_000_000))
            waitUntil { output.frames.size == 309 }
            assertFalse("Over30s of reference transport is not30s of active Atmos publication", recovery.failed(true))
            assertTrue(recovery.latched)
        } finally { producer.close(); job.cancelAndJoin() }
        assertEquals(f.started.toSet(), f.released.toSet())
    }

    @Test fun producerUsesCurrentSharedFallbackDecisionAtStartRatherThanRetryingFast() = runBlocking {
        val f = Target(); val out = Output(); val producer = f.producer(out)
        f.tryFast = false // Another consumer fell back after this producer was created.
        val job = async(start = CoroutineStart.UNDISPATCHED) { producer.run() }
        try {
            waitUntil { f.motion.subscriptionCount.value == 1 }
            assertEquals(1, f.started.size)
            assertEquals(1, f.fallbacks)
            f.emit(1); f.emit(2); waitUntil { out.frames.size == 1 }
            assertEquals(1, f.started.size)
        } finally { producer.close(); job.cancelAndJoin() }
    }

    private suspend fun waitUntil(test: () -> Boolean) = withTimeout(2_000) { while (!test()) delay(1) }
}
