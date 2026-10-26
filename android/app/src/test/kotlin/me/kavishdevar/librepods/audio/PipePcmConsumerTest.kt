/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PipePcmConsumerTest {
    @Test fun preservesPcmExactlyAndClosesOnce() {
        var written: ByteArray? = null
        var closes = 0
        val consumer = PipePcmConsumer({ data, offset, count -> written = data.copyOfRange(offset, offset + count); count }, { closes++ })
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1)
        consumer.onFormat(PcmFormat(64_000, 1))
        consumer.onPcm(pcm, 0)
        assertArrayEquals(pcm, written)
        assertEquals(pcm.size.toLong(), consumer.writtenBytes)
        consumer.close(); consumer.close()
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { consumer.onPcm(pcm, 1) }
    }

    @Test fun rejectsWrongFormatBeforeWriting() {
        var writes = 0
        val consumer = PipePcmConsumer({ _, _, count -> writes++; count }, {})
        assertThrows(IllegalStateException::class.java) { consumer.onFormat(PcmFormat(48_000, 1)) }
        assertThrows(IllegalStateException::class.java) { consumer.onFormat(PcmFormat(64_000, 2)) }
        assertThrows(IllegalStateException::class.java) { consumer.onPcm(byteArrayOf(0, 0), 0) }
        assertEquals(0, writes)
        consumer.close()
    }

    @Test fun partialZeroAndEagainWritesPreserveTheExactSuffixAndSampleOrder() {
        val output = ByteArrayOutputStream()
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1)
        var calls = 0
        var clock = 0L
        val consumer = PipePcmConsumer({ data, offset, count ->
            val accepted = when (calls++) {
                0 -> 3
                1 -> 0
                2 -> throw ErrnoException("write", OsConstants.EAGAIN)
                3 -> 2
                else -> count
            }
            output.write(data, offset, accepted)
            accepted
        }, {}, { wait -> assertTrue(wait in 1..10); clock += wait * 1_000_000L }, { clock })
        consumer.onFormat(PcmFormat(64_000, 1))
        consumer.onPcm(pcm, 0)
        assertArrayEquals(pcm, output.toByteArray())
        assertEquals(8L, consumer.writtenBytes)
        assertEquals(2L, consumer.partialWrites)
        assertEquals(2L, consumer.zeroWrites)
        assertEquals(1L, consumer.wouldBlockWrites)
        assertEquals(20.0, consumer.maxWriteDelayMs, 0.0)
        assertNull(consumer.lastFailure)
        consumer.close()
    }

    @Test fun aFullPipeFailsAtTheBoundedDeadlineWithoutGrowingOrReplayingTheBuffer() {
        var clock = 0L
        var writes = 0
        val consumer = PipePcmConsumer({ _, _, _ -> writes++; 0 }, {},
            { wait -> clock += wait * 1_000_000L }, { clock })
        consumer.onFormat(PcmFormat(64_000, 1))
        val error = assertThrows(IllegalStateException::class.java) { consumer.onPcm(ByteArray(960), 0) }
        assertTrue(error.message.orEmpty().contains("120 ms"))
        assertEquals(12, writes)
        assertEquals(0L, consumer.writtenBytes)
        assertEquals(120.0, consumer.maxWriteDelayMs, 0.0)
        assertTrue(consumer.lastFailure.orEmpty().contains("120 ms"))
        consumer.close()
    }

    @Test fun interruptedWaitStopsWithoutWritingTheRemainder() {
        val waiting = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        var writes = 0
        val consumer = PipePcmConsumer({ _, _, _ -> writes++; 0 }, {}, {
            waiting.countDown()
            Thread.sleep(10_000)
        })
        consumer.onFormat(PcmFormat(64_000, 1))
        val worker = Thread { try { consumer.onPcm(ByteArray(960), 0) } catch (failure: Throwable) { error.set(failure) } }
        worker.start()
        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        worker.interrupt(); worker.join(1000)
        assertFalse(worker.isAlive)
        assertTrue(error.get() is InterruptedException)
        assertEquals(1, writes)
        assertEquals(0L, consumer.writtenBytes)
        consumer.close()
    }

    @Test fun intentionalStopAndCloseDoNotWaitForPollOrWriteAnyMoreBytes() {
        for (close in listOf(false, true)) {
            val waiting = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            var writes = 0
            var closes = 0
            val consumer = PipePcmConsumer({ _, _, _ -> if (++writes == 1) 3 else 0 }, { closes++ }, {
                waiting.countDown(); check(resume.await(1, TimeUnit.SECONDS))
            })
            consumer.onFormat(PcmFormat(64_000, 1))
            val worker = Thread { try { consumer.onPcm(ByteArray(960), 0) } catch (failure: Throwable) { error.set(failure) } }
            worker.start(); assertTrue(waiting.await(1, TimeUnit.SECONDS))
            if (close) consumer.close() else consumer.stopSending()
            assertEquals(if (close) 1 else 0, closes)
            resume.countDown(); worker.join(1000)
            assertFalse(worker.isAlive)
            assertNull(error.get())
            assertNull(consumer.lastFailure)
            assertEquals(2, writes)
            assertEquals(3L, consumer.writtenBytes)
            assertEquals(1L, consumer.partialWrites)
            consumer.close(); assertEquals(1, closes)
        }
    }

    @Test fun permanentErrorsFailWithoutRetryAndKeepTheCause() {
        var writes = 0
        val consumer = PipePcmConsumer({ _, _, _ -> writes++; throw ErrnoException("write", OsConstants.EPIPE) }, {})
        consumer.onFormat(PcmFormat(64_000, 1))
        assertThrows(ErrnoException::class.java) { consumer.onPcm(ByteArray(960), 0) }
        assertEquals(1, writes)
        assertTrue(consumer.lastFailure.orEmpty().contains("ErrnoException"))
        consumer.close()
    }

    @Test fun malformedAndBackwardsBuffersNeverReachTheOutput() {
        var writes = 0
        val consumer = PipePcmConsumer({ _, _, count -> writes++; count }, {})
        consumer.onFormat(PcmFormat(64_000, 1))
        consumer.onPcm(byteArrayOf(0, 0), 100)
        for ((data, time) in listOf(byteArrayOf(0) to 101L, byteArrayOf() to 101L,
            ByteArray(16 * 480 * 2 + 2) to 101L, byteArrayOf(0, 0) to 99L)) {
            assertThrows(IllegalArgumentException::class.java) { consumer.onPcm(data, time) }
        }
        assertEquals(1, writes)
        consumer.close()
    }

    @Test fun intentionalStopQuietsTheWriterBeforeClosingItsDescriptor() {
        var writes = 0
        var closes = 0
        val consumer = PipePcmConsumer({ _, _, count -> writes++; count }, { closes++ })
        consumer.onFormat(PcmFormat(64_000, 1))
        consumer.onPcm(byteArrayOf(0, 0), 0)
        consumer.stopSending()
        consumer.onPcm(byteArrayOf(0, 0), 7500)
        assertEquals(1, writes)
        assertEquals(0, closes)
        consumer.close()
        assertEquals(1, closes)
    }
}
