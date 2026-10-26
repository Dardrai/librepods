package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AirPodsMicrophoneLiveTest {
    private class Sink(private val failOnPcm: Boolean = false) : LivePcmConsumer {
        val timestamps = CopyOnWriteArrayList<Long>()
        val samples = CopyOnWriteArrayList<ByteArray>()
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        @Volatile var format: PcmFormat? = null
        override fun onFormat(format: PcmFormat) { this.format = format }
        override fun onPcm(bytes: ByteArray, presentationTimeUs: Long) {
            if (failOnPcm) error("consumer failed")
            samples += bytes
            timestamps += presentationTimeUs
        }
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }

    private class Fixture(realTime: Boolean = true) : AutoCloseable {
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val current = AtomicBoolean(true)
        val time = AtomicLong(1_000_000_000)
        private val startedNs = System.nanoTime()
        private val advancingClock = AtomicBoolean(realTime)
        val callback = AtomicReference<((BluetoothSocket, ByteArray) -> Unit)?>()
        val starts = CountDownLatch(1)
        val stops = AtomicInteger()
        val stopStarted = CountDownLatch(1)
        val keepPlayback = CopyOnWriteArrayList<Boolean>()
        val recoveryGenerations = CopyOnWriteArrayList<Long>()
        val decoderCloses = AtomicInteger()
        val decoderSetupEntered = CountDownLatch(1)
        @Volatile var decoderSetupGate: CountDownLatch? = null
        @Volatile var decoderSetupFails = false
        val decoderEntered = CountDownLatch(1)
        @Volatile var decoderGate: CountDownLatch? = null
        val samples = ByteArray(960) { (it * 17).toByte() }
        val directory = Files.createTempDirectory("librepods-live-test").toFile()
        val recordings = directory.resolve("recordings")
        val microphone: AirPodsMicrophone
        val microphoneRecovery = AtomicReference<(Long) -> Unit>({})
        init {
            `when`(socket.isConnected).thenReturn(true)
            `when`(aacp.captureAudioSocket()).thenReturn(socket)
            `when`(aacp.ownsAudioSocket(socket)).thenAnswer { current.get() }
            `when`(aacp.requestMicrophoneStream(socket)).thenAnswer { starts.countDown(); true }
            `when`(aacp.endMicrophoneStream(socket)).thenAnswer {
                stops.incrementAndGet(); stopStarted.countDown(); true
            }
            doAnswer { callback.set(it.getArgument(0)); null }.`when`(aacp).microphonePacketCallback = any()
            `when`(aacp.microphonePacketCallback).thenAnswer { callback.get() }
            microphone = AirPodsMicrophone(aacp, recordings, { generation ->
                microphoneRecovery.get().invoke(generation)
            }, { onFormat, onPcm ->
                decoderSetupEntered.countDown()
                decoderSetupGate?.let { check(it.await(2, TimeUnit.SECONDS)) }
                check(!decoderSetupFails) { "decoder setup failed" }
                object : MicrophoneDecoder {
                override var decodedFrames = 0L
                override fun decode(unit: ByteArray, shouldStop: () -> Boolean) {
                    decoderEntered.countDown()
                    decoderGate?.let { check(it.await(2, TimeUnit.SECONDS)) }
                    if (shouldStop()) return
                    if (decodedFrames == 0L) onFormat(PcmFormat(64_000, 1))
                    val timestamp = AirPodsMicrophoneClock.presentationTimeUs(decodedFrames)
                    decodedFrames += 480
                    onPcm(samples.copyOf(), timestamp)
                }
                override fun drain() = Unit
                override fun close() { decoderCloses.incrementAndGet() }
            } }, { time.get() + if (advancingClock.get()) System.nanoTime() - startedNs else 0L })
            microphoneRecovery.set { generation ->
                recoveryGenerations += generation
                keepPlayback += microphone.canKeepPlayback(generation)
                microphone.completeRecovery(generation, true)
            }
        }
        fun packet(secondsLater: Long = 0, source: BluetoothSocket = socket, accessUnits: Int = 1) {
            time.addAndGet(secondsLater * 1_000_000_000)
            val bytes = ByteArray(22 + accessUnits * 6)
            byteArrayOf(4, 0, 4, 0, 0x58, 0, 1, 0).copyInto(bytes)
            repeat(accessUnits) {
                bytes[26 + it * 6] = 1
                bytes[27 + it * 6] = 42
            }
            callback.get()!!.invoke(source, bytes)
        }
        fun awaitFinished() = await { microphone.state.value.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR) &&
            microphone.transportFor(microphone.state.value.generation) == null }
        override fun close() {
            decoderSetupGate?.countDown()
            decoderGate?.countDown()
            advancingClock.set(true)
            microphone.close()
            time.addAndGet(10_000_000_000)
            awaitFinished()
            directory.deleteRecursively()
        }
    }

    @Test fun liveCaptureHasNoFileOrSixtySecondLimitAndKeepsPhysicalClockAndGain() {
        Fixture().use { fixture ->
            val sink = Sink()
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            repeat(62) { index ->
                fixture.packet(1)
                await { sink.timestamps.size == index + 1 }
            }
            assertEquals(MicrophonePhase.CAPTURING, fixture.microphone.state.value.phase)
            assertEquals(PcmFormat(64_000, 1), sink.format)
            assertEquals((0 until 62).map { it * 7_500L }, sink.timestamps.toList())
            sink.samples.forEach { assertArrayEquals(fixture.samples, it) }
            assertFalse(fixture.recordings.exists())
            assertNull(fixture.microphone.state.value.file)
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertTrue(sink.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, sink.closes.get())
            assertEquals(1, fixture.stops.get())
            assertEquals(1, fixture.decoderCloses.get())
            assertEquals(MicrophonePhase.IDLE, fixture.microphone.state.value.phase)
            assertEquals(listOf(true), fixture.keepPlayback.toList())
            assertFalse(fixture.microphone.canKeepPlayback(fixture.recoveryGenerations.single()))
        }
    }

    @Test fun recorderStillCreatesPcmWaveAndStopsAfterSixtySeconds() {
        Fixture().use { fixture ->
            assertTrue(fixture.microphone.start())
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            repeat(60) { index ->
                fixture.packet(1)
                await { fixture.microphone.state.value.decodedFrames == (index + 1) * 480L }
            }
            fixture.awaitFinished()
            assertEquals(MicrophonePhase.IDLE, fixture.microphone.state.value.phase)
            val file = checkNotNull(fixture.microphone.state.value.file)
            assertTrue(file.exists())
            val bytes = file.readBytes()
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(64_000, ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(44 + 60 * 960, bytes.size)
            assertArrayEquals(fixture.samples, bytes.copyOfRange(44, 44 + 960))
            assertEquals(1, fixture.stops.get())
            assertEquals(listOf(false), fixture.keepPlayback.toList())
        }
    }

    @Test fun sinkFailureStopsUplinkRecoversPlaybackAndDoesNotCreateRecording() {
        Fixture().use { fixture ->
            val sink = Sink(failOnPcm = true)
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.packet()
            fixture.awaitFinished()
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertTrue(fixture.microphone.state.value.message.contains("consumer failed"))
            assertEquals(1, fixture.stops.get())
            assertEquals(1, fixture.decoderCloses.get())
            assertTrue(sink.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, sink.closes.get())
            assertFalse(fixture.recordings.exists())
            assertEquals(listOf(false), fixture.keepPlayback.toList())
        }
    }

    @Test fun stopWithoutPcmClosesSinkAndRejectedStartsRetainCallerOwnership() {
        Fixture().use { fixture ->
            val sink = Sink()
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            val rejected = Sink()
            assertFalse(fixture.microphone.startLive(rejected))
            assertEquals(0, rejected.closes.get())
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertTrue(sink.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, sink.closes.get())
            assertFalse(fixture.recordings.exists())
            rejected.close()
        }
    }

    @Test fun completedFailedConsumerCanRetryWithFreshSessionAndSinkOwnership() {
        Fixture().use { fixture ->
            val failed = Sink(failOnPcm = true)
            assertTrue(fixture.microphone.startLive(failed))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.packet()
            fixture.awaitFinished()
            val previous = fixture.microphone.state.value.generation
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertNull(fixture.microphone.transportFor(previous))
            val replacement = Sink()
            assertTrue(fixture.microphone.startLive(replacement))
            fixture.packet()
            await { replacement.samples.size == 1 }
            assertEquals(previous + 1, fixture.microphone.state.value.generation)
            assertFalse(fixture.microphone.ownsLiveConsumer(failed))
            assertTrue(fixture.microphone.ownsLiveConsumer(replacement))
            assertEquals(1, failed.closes.get())
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertEquals(1, replacement.closes.get())
            assertFalse(fixture.recordings.exists())
        }
    }

    @Test fun staleSocketCannotFeedCurrentCaptureAndRevocationStopsDelivery() {
        Fixture().use { fixture ->
            val sink = Sink()
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.packet(source = mock(BluetoothSocket::class.java))
            fixture.packet()
            await { sink.timestamps.size == 1 }
            fixture.microphone.onPermissionRevoked()
            fixture.packet()
            fixture.awaitFinished()
            assertEquals(1, sink.timestamps.size)
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertTrue(fixture.microphone.state.value.message.contains("access"))
            assertFalse(fixture.recordings.exists())
            assertTrue(sink.closed.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(false), fixture.keepPlayback.toList())
        }
    }

    @Test fun liveStopNeedsQuietAfterTheStopWriteEvenIfLastPacketIsOld() {
        Fixture(realTime = false).use { fixture ->
            assertTrue(fixture.microphone.startLive(Sink()))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.time.addAndGet(600_000_000)
            fixture.microphone.stop()
            assertTrue(fixture.stopStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(60)
            assertEquals(MicrophonePhase.STOPPING, fixture.microphone.state.value.phase)
            assertTrue(fixture.keepPlayback.isEmpty())
            assertFalse(fixture.microphone.canKeepPlayback(fixture.microphone.state.value.generation))
            fixture.time.addAndGet(499_000_000)
            Thread.sleep(60)
            assertEquals(MicrophonePhase.STOPPING, fixture.microphone.state.value.phase)
            assertTrue(fixture.keepPlayback.isEmpty())
            fixture.time.addAndGet(1_000_000)
            fixture.awaitFinished()
            assertEquals(listOf(true), fixture.keepPlayback.toList())
        }
    }

    @Test fun failedStopRetainsConservativeRecovery() {
        Fixture().use { fixture ->
            `when`(fixture.aacp.endMicrophoneStream(fixture.socket)).thenReturn(false)
            val sink = Sink()
            fixture.microphoneRecovery.set { generation ->
                assertFalse(fixture.microphone.completeCallStopRecovery(generation, sink))
                fixture.keepPlayback += fixture.microphone.canKeepPlayback(generation)
                fixture.microphone.completeRecovery(generation, true)
            }
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.microphone.stop()
            fixture.awaitFinished()
            verify(fixture.aacp, times(2)).endMicrophoneStream(fixture.socket)
            assertEquals(listOf(false), fixture.keepPlayback.toList())
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertFalse(fixture.microphone.takeQuietLiveStop(sink))
        }
    }

    @Test fun quietCallStopNeedsExactConsumerAndGenerationAndProducesOneReceipt() {
        Fixture().use { fixture ->
            val sink = Sink()
            val other = Sink()
            fixture.microphoneRecovery.set { generation ->
                assertFalse(fixture.microphone.completeCallStopRecovery(generation + 1, sink))
                assertFalse(fixture.microphone.completeCallStopRecovery(generation, other))
                assertTrue(fixture.microphone.completeCallStopRecovery(generation, sink))
            }
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            assertFalse(fixture.microphone.completeCallStopRecovery(fixture.microphone.state.value.generation, sink))
            assertFalse(fixture.microphone.takeQuietLiveStop(sink))
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertEquals(MicrophonePhase.IDLE, fixture.microphone.state.value.phase)
            assertFalse(fixture.microphone.takeQuietLiveStop(other))
            assertTrue(fixture.microphone.takeQuietLiveStop(sink))
            assertFalse(fixture.microphone.takeQuietLiveStop(sink))
        }
    }

    @Test fun captureFailureStillAllowsVerifiedCallStopWithoutHidingTheError() {
        Fixture().use { fixture ->
            val sink = Sink(failOnPcm = true)
            fixture.microphoneRecovery.set { generation ->
                assertFalse(fixture.microphone.canKeepPlayback(generation))
                assertTrue(fixture.microphone.completeCallStopRecovery(generation, sink))
            }
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.packet()
            fixture.awaitFinished()
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertTrue(fixture.microphone.state.value.message.contains("consumer failed"))
            assertTrue(fixture.microphone.takeQuietLiveStop(sink))
        }
    }

    @Test fun quietStopReceiptDoesNotClaimNativePlaybackRecoverySucceeded() {
        Fixture().use { fixture ->
            val sink = Sink()
            fixture.microphoneRecovery.set { generation -> fixture.microphone.completeRecovery(generation, false) }
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertTrue(fixture.microphone.state.value.message.contains("recovery"))
            assertTrue(fixture.microphone.takeQuietLiveStop(sink))
        }
    }

    @Test fun lateAudioReplacedSocketAndDisposalInvalidateRetiredQuietProof() {
        for (invalidate in listOf<(Fixture) -> Unit>(
            { it.packet() }, { it.current.set(false) }, { it.microphone.close() }, { it.microphone.onDisconnected() })) {
            Fixture().use { fixture ->
                val sink = Sink()
                assertTrue(fixture.microphone.startLive(sink))
                assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
                fixture.microphone.stop()
                fixture.awaitFinished()
                invalidate(fixture)
                assertFalse(fixture.microphone.takeQuietLiveStop(sink))
            }
        }
    }

    @Test fun aNewAcceptedSessionCannotInheritOrExposeAnOlderQuietReceipt() {
        Fixture().use { fixture ->
            val old = Sink()
            val replacement = Sink()
            assertTrue(fixture.microphone.startLive(old))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertTrue(fixture.microphone.startLive(replacement))
            assertFalse(fixture.microphone.takeQuietLiveStop(old))
            assertFalse(fixture.microphone.takeQuietLiveStop(replacement))
            fixture.packet()
            await { replacement.samples.size == 1 }
            fixture.microphone.stop()
            fixture.awaitFinished()
            assertFalse(fixture.microphone.takeQuietLiveStop(old))
            assertTrue(fixture.microphone.takeQuietLiveStop(replacement))
        }
    }

    @Test fun cancelledDecoderSetupCanProveThatItsExactSessionNeverStartedTheUplink() {
        Fixture().use { fixture ->
            val sink = Sink()
            val gate = CountDownLatch(1)
            fixture.decoderSetupGate = gate
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.decoderSetupEntered.await(1, TimeUnit.SECONDS))
            fixture.microphone.stop()
            gate.countDown()
            fixture.awaitFinished()
            verify(fixture.aacp, never()).requestMicrophoneStream(fixture.socket)
            verify(fixture.aacp, never()).endMicrophoneStream(fixture.socket)
            assertTrue(fixture.microphone.takeQuietLiveStop(sink))
            assertFalse(fixture.microphone.takeQuietLiveStop(sink))
        }
    }

    @Test fun failedDecoderSetupProvesNoStartOnlyWhenNoMicrophoneAudioWasObserved() {
        for (audioArrived in listOf(false, true)) {
            Fixture().use { fixture ->
                val sink = Sink()
                val gate = CountDownLatch(1)
                fixture.decoderSetupGate = gate
                fixture.decoderSetupFails = true
                assertTrue(fixture.microphone.startLive(sink))
                assertTrue(fixture.decoderSetupEntered.await(1, TimeUnit.SECONDS))
                if (audioArrived) fixture.packet()
                gate.countDown()
                fixture.awaitFinished()
                assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
                verify(fixture.aacp, never()).requestMicrophoneStream(fixture.socket)
                verify(fixture.aacp, never()).endMicrophoneStream(fixture.socket)
                assertEquals(!audioArrived, fixture.microphone.takeQuietLiveStop(sink))
            }
        }
    }

    @Test fun latePacketAndReplacedSocketRevokeLiveKeepPermission() {
        Fixture().use { fixture ->
            val verified = CountDownLatch(1)
            val checks = CopyOnWriteArrayList<Boolean>()
            fixture.microphoneRecovery.set { generation ->
                fixture.recoveryGenerations += generation
                checks += fixture.microphone.canKeepPlayback(generation)
                checks += fixture.microphone.canKeepPlayback(generation + 1)
                fixture.packet()
                checks += fixture.microphone.canKeepPlayback(generation)
                fixture.time.addAndGet(600_000_000)
                checks += fixture.microphone.canKeepPlayback(generation)
                fixture.current.set(false)
                checks += fixture.microphone.canKeepPlayback(generation)
                fixture.microphone.completeRecovery(generation, true)
                verified.countDown()
            }
            assertTrue(fixture.microphone.startLive(Sink()))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            fixture.microphone.stop()
            assertTrue(verified.await(2, TimeUnit.SECONDS))
            fixture.awaitFinished()
            assertEquals(listOf(true, false, false, true, false), checks.toList())
            assertFalse(fixture.microphone.canKeepPlayback(fixture.recoveryGenerations.single()))
        }
    }

    @Test fun receivedFrameFloorIncludesUndecodedAndQueuedAccessUnitsForOnlyTheCurrentConsumer() {
        Fixture().use { fixture ->
            val sink = Sink()
            val other = Sink()
            val gate = CountDownLatch(1)
            fixture.decoderGate = gate
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            assertEquals(0L, fixture.microphone.receivedFrameFloor(sink))
            assertNull(fixture.microphone.receivedFrameFloor(other))
            fixture.packet(accessUnits = 3)
            assertTrue(fixture.decoderEntered.await(1, TimeUnit.SECONDS))
            fixture.packet(accessUnits = 2)
            fixture.packet(source = mock(BluetoothSocket::class.java), accessUnits = 4)
            assertEquals(0L, fixture.microphone.state.value.decodedFrames)
            assertTrue(sink.samples.isEmpty())
            assertEquals(2_400L, fixture.microphone.receivedFrameFloor(sink))
            gate.countDown()
            await { sink.samples.size == 5 }
            assertEquals((0 until 5).map { it * 7_500L }, sink.timestamps.toList())
            assertEquals(2_400L, fixture.microphone.receivedFrameFloor(sink))
            fixture.microphone.stop()
            assertNull(fixture.microphone.receivedFrameFloor(sink))
            fixture.awaitFinished()
            assertTrue(fixture.microphone.startLive(other))
            assertNull(fixture.microphone.receivedFrameFloor(sink))
            assertEquals(0L, fixture.microphone.receivedFrameFloor(other))
            fixture.current.set(false)
            assertNull(fixture.microphone.receivedFrameFloor(other))
            fixture.microphone.onDisconnected()
            fixture.awaitFinished()
        }
    }

    @Test fun invalidAudioCannotAdvanceOrExposeTheLiveFrameFloor() {
        Fixture().use { fixture ->
            val sink = Sink()
            assertTrue(fixture.microphone.startLive(sink))
            assertTrue(fixture.starts.await(1, TimeUnit.SECONDS))
            val malformed = ByteArray(28)
            byteArrayOf(4, 0, 4, 0, 0x58, 0, 1, 0).copyInto(malformed)
            malformed[26] = 2
            fixture.callback.get()!!.invoke(fixture.socket, malformed)
            assertNull(fixture.microphone.receivedFrameFloor(sink))
            fixture.awaitFinished()
            assertEquals(MicrophonePhase.ERROR, fixture.microphone.state.value.phase)
            assertTrue(sink.samples.isEmpty())
        }
    }

    companion object {
        private fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("Timed out waiting for capture state", predicate())
        }
    }
}
