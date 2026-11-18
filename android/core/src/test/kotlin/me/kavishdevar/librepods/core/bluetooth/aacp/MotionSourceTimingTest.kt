package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceSetting
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorTypeAck
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MotionSourceTimingTest {
    @Test fun stagesKeepOnlyIndependentMaximaAndPreserveNullFalseAndExceptions() {
        val timing = MotionSourceTiming()
        var clock = 100L
        var calls = 0
        val absent: Any? = timing.measure(MotionSourceTiming.Stage.PARSE, { clock }) {
            calls++; clock += 7; null
        }
        assertNull(absent)
        assertEquals(1, calls)
        assertFalse(timing.measure(MotionSourceTiming.Stage.SENSOR_SEND, { clock }) {
            clock += 30; false
        })
        val failure = IllegalStateException("unchanged")
        try {
            timing.measure(MotionSourceTiming.Stage.SENSOR_SEND, { clock }) {
                clock += 40; throw failure
            }
            fail("original failure must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        val before = timing.snapshot()
        assertEquals(2L, before["sensor_send_count"])
        assertEquals(40L, before["sensor_send_max_nanos"])
        assertEquals(177L, before["sensor_send_max_at_nanos"])
        timing.stageFinished(MotionSourceTiming.Stage.SENSOR_SEND, 0, 500)
        timing.stageFinished(MotionSourceTiming.Stage.SENSOR_SEND, 500, 400)
        timing.measure(MotionSourceTiming.Stage.SENSOR_SEND, { clock }) { clock += 2 }
        assertEquals(3L, timing.snapshot()["sensor_send_count"])
        assertEquals(2L, before["sensor_send_count"])
        assertEquals(177L, timing.snapshot()["sensor_send_max_at_nanos"])
        assertEquals(before.keys, timing.snapshot().keys)
    }

    @Test fun publicationSeparatesMonitorWaitFromCheckedBodyAndRejectsReplacedOwner() {
        val timing = MotionSourceTiming()
        val monitor = Any()
        val owner = Any()
        var current = owner
        var published = 0
        val clock = AtomicLong(100)
        val attempted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var publisher: Thread
        synchronized(monitor) {
            publisher = thread {
                try {
                    assertTrue(timing.publication(monitor, { clock.get().also { attempted.countDown() } }) {
                        publishForTransport(monitor, owner, { current }) {
                            published++; clock.addAndGet(30)
                        }
                    })
                } catch (error: Throwable) { failure.set(error) }
            }
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            clock.set(200)
        }
        publisher.join(2_000)
        assertFalse(publisher.isAlive)
        assertNull(failure.get())
        val stats = timing.snapshot()
        assertEquals(100L, stats["publication_wait_max_nanos"])
        assertEquals(200L, stats["publication_wait_max_at_nanos"])
        assertEquals(30L, stats["publication_body_max_nanos"])
        assertEquals(230L, stats["publication_body_max_at_nanos"])
        synchronized(monitor) { current = Any() }
        assertFalse(timing.publication(monitor, clock::get) {
            publishForTransport(monitor, owner, { current }) { published++ }
        })
        assertEquals(1, published)
        assertEquals(2L, timing.snapshot()["publication_body_count"])
        val expected = IllegalArgumentException("body")
        try {
            timing.publication(monitor, clock::get) { clock.addAndGet(5); throw expected }
            fail("publication failure must propagate")
        } catch (actual: IllegalArgumentException) { assertSame(expected, actual) }
        assertEquals(3L, timing.snapshot()["publication_body_count"])
        assertFalse(Thread.holdsLock(monitor))
    }

    @Test fun feedAttributesActiveAndMotionWorkSeparatelyWithoutChangingBatchDelivery() {
        val timing = MotionSourceTiming()
        var clock = 110L
        var current = true
        var delivered = 0
        val manager = RTBuddyManager({ true }, { true }, { current }, { 9 },
            { _, _ -> delivered++; clock += 23 }, {}, { if (it == BuddyState.ACTIVE) clock += 17 }, {},
            sourceTiming = timing, sourceClockNanos = { clock })
        try {
            assertTrue(manager.setHeadTrackingEnabled(true))
            manager.feed(frame(10, 4) + frame(15, 5), 100)
            val stats = timing.snapshot()
            assertEquals(2, delivered)
            assertEquals(1L, stats["feed_reconcile_count"])
            assertEquals(5L, stats["feed_parse_count"])
            assertEquals(2L, stats["active_callback_count"])
            assertEquals(17L, stats["active_callback_max_nanos"])
            assertEquals(127L, stats["active_callback_max_at_nanos"])
            assertEquals(2L, stats["motion_callback_count"])
            assertEquals(23L, stats["motion_callback_max_nanos"])
            assertEquals(150L, stats["motion_callback_max_at_nanos"])
            assertEquals(80L, stats["feed_work_max_nanos"])
            current = false
            manager.feed(frame(20, 6), 190)
            assertEquals(stats["feed_parse_count"], timing.snapshot()["feed_parse_count"])
            assertEquals(2, delivered)
        } finally { manager.close() }
    }

    @Test fun failedMotionCallbackIsTimedOnceAndDoesNotPublishOrConsumeTheNextReport() {
        val timing = MotionSourceTiming()
        var clock = 110L
        var calls = 0
        val expected = IllegalStateException("callback")
        val manager = RTBuddyManager({ true }, { true }, { true }, { 9 },
            { _, _ -> calls++; clock += 250; throw expected }, {}, {}, {},
            sourceTiming = timing, sourceClockNanos = { clock })
        try {
            assertTrue(manager.setHeadTrackingEnabled(true))
            try {
                manager.feed(frame(10, 4) + frame(15, 5), 100)
                fail("original callback failure must propagate")
            } catch (actual: IllegalStateException) { assertSame(expected, actual) }
            assertEquals(1, calls)
            assertEquals(1L, timing.snapshot()["motion_callback_count"])
            assertEquals(250L, timing.snapshot()["motion_callback_max_nanos"])
            assertEquals(360L, timing.snapshot()["motion_callback_max_at_nanos"])
            assertEquals(250L, timing.snapshot()["feed_work_max_nanos"])
            assertEquals(0L, timing.snapshot()["motion_reports"])
        } finally { manager.close() }
    }

    @Test fun onlyClockCorroboratedSixteenBitCrossingsIncrementTheWrapDiagnostic() {
        val timing = MotionSourceTiming()
        timing.motionPublished(report(1_000_000_000, 65534), 100, 110)
        timing.motionPublished(report(1_010_000_000, 0), 120, 130)
        assertEquals(1L, timing.snapshot()["counter_wrap16_count"])
        assertEquals(2L, timing.snapshot()["counter_step_max"])
        timing.motionPublished(report(1_020_000_000, 30000), 140, 150)
        timing.motionPublished(report(1_030_000_000, 0), 160, 170)
        assertEquals(1L, timing.snapshot()["counter_wrap16_count"])
        assertEquals((0L - 30000) and 0xffffffffL, timing.snapshot()["counter_step_max"])
        timing.motionPublished(report(null, 65534), 180, 190)
        timing.motionPublished(report(1_040_000_000, 0), 200, 210)
        assertEquals(1L, timing.snapshot()["counter_wrap16_count"])
    }

    private fun report(time: Long?, counter: Long?) = MotionReport(time,
        listOf(1, 2, 3), listOf(0, 0, 0), listOf(0, 0, 0), listOf(0, 0, -16384), counter)

    @Test fun controlMetadataIsSelectedServiceOnlyAndDoesNotConfirmOrChangeRequestedRate() {
        val timing = MotionSourceTiming()
        timing.motionStarted(10_000, 10)
        val ack = SensorTypeAck.newBuilder().setServiceValue(27)
        val data = SensorDataWX.newBuilder().setSeq(127).setStartAck(ack).setCommandAck(ack).build()
        for (service in listOf(null, 0, 16, 128)) timing.motionControlReceived(data, service, 20)
        timing.motionControlReceived(data, 27, 0)
        assertEquals(0L, timing.snapshot()["motion_start_ack_count"])
        timing.motionControlReceived(data, 27, 30)
        val before = timing.snapshot()
        timing.motionStopped()
        timing.motionControlReceived(data.toBuilder().setSeq(0).build(), 27, 40)
        val after = timing.snapshot()
        assertEquals(1L, before["motion_start_ack_count"])
        assertEquals(127L, before["motion_start_ack_last_sequence"])
        for (prefix in listOf("motion_start_ack", "motion_command_ack")) {
            assertEquals(2L, after["${prefix}_count"])
            assertEquals(0L, after["${prefix}_last_sequence"])
            assertEquals(40L, after["${prefix}_last_at_nanos"])
        }
        assertEquals(10_000L, after["motion_interval_us"])
        assertEquals(0L, after["motion_interval_change_count"])
        assertEquals(0L, after["motion_reports"])
    }

    @Test fun intervalEchoRetainsOnlyExactSelectedReportOneLiteralIncludingUnexpectedUnsignedValues() {
        val timing = MotionSourceTiming()
        fun echo(bytes: ByteArray, service: Int = 27, setting: Int = 2, seq: Int = 9) =
            SensorDataWX.newBuilder().setSeq(seq).setServiceSettings(SensorServiceSetting.newBuilder()
                .setServiceValue(service).setSetting(setting).setConfiguration(ByteString.copyFrom(bytes))).build()
        val literal = byteArrayOf(1, -1, -1, -1, -1)
        val invalid = listOf(echo(literal, service = 20), echo(literal, setting = 1),
            echo(literal.copyOf(4)), echo(literal.copyOf(6)), echo(byteArrayOf(4, 1, 0, 0, 0)))
        for (data in invalid) timing.motionControlReceived(data, 27, 20)
        assertEquals(0L, timing.snapshot()["motion_interval_echo_count"])
        timing.motionControlReceived(echo(literal), 27, 30)
        assertEquals(0xffffffffL, timing.snapshot()["motion_interval_echo_last_us"])
        timing.motionControlReceived(echo(byteArrayOf(1, 0, 0, 0, 0), seq = 10), 27, 40)
        val stats = timing.snapshot()
        assertEquals(2L, stats["motion_interval_echo_count"])
        assertEquals(0L, stats["motion_interval_echo_last_us"])
        assertEquals(10L, stats["motion_interval_echo_last_sequence"])
        assertEquals(40L, stats["motion_interval_echo_last_at_nanos"])
        assertEquals(0L, stats["motion_interval_us"])
        assertEquals(0L, stats["motion_interval_change_count"])
    }

    @Test fun readAndStageGapsKeepTheirBootTimeAndDoNotRetainMutableSnapshots() {
        val timing = MotionSourceTiming()
        timing.readReturned(1_000, 80, 900)
        timing.readReturned(1_160, 120)
        timing.readReturned(1_200, 60)
        timing.dispatch(1_160, 1_170)
        timing.feedEntered(1_170, 1_190)
        timing.feedFinished(1_190, 1_220)
        timing.readProcessed(1_160, 1_240)
        timing.motionPublished(report(10, 2), 1_160, 1_210)
        val before = timing.snapshot()
        assertEquals(3L, before["reads"])
        assertEquals(260L, before["read_bytes"])
        assertEquals(100L, before["read_interval_mean_nanos"])
        assertEquals(160L, before["read_interval_max_nanos"])
        assertEquals(1_160L, before["read_interval_max_at_nanos"])
        assertEquals(100L, before["read_wait_max_nanos"])
        assertEquals(80L, before["read_work_max_nanos"])
        assertEquals(10L, before["dispatch_delay_max_nanos"])
        assertEquals(20L, before["feed_wait_max_nanos"])
        assertEquals(30L, before["feed_work_max_nanos"])
        assertEquals(50L, before["publish_delay_max_nanos"])
        timing.readReturned(1_400, 10)
        assertEquals(3L, before["reads"])
        assertEquals(before.keys, timing.snapshot().keys)
    }

    @Test fun parserCountersCountEveryDeliveryAndSeparateWrapDuplicatesAndClockResets() {
        val timing = MotionSourceTiming()
        for ((index, value) in listOf(100L to 0xfffffffeL, 105L to 0xffffffffL,
                110L to 0L, 120L to 2L, 120L to 2L, 5L to 3L).withIndex()) {
            timing.motionPublished(report(value.first, value.second), 1_000, 1_010L + index)
        }
        val snapshot = timing.snapshot()
        assertEquals(6L, snapshot["motion_reports"])
        assertEquals(3L, snapshot["device_interval_count"])
        assertEquals(10L, snapshot["device_interval_max_nanos"])
        assertEquals(2L, snapshot["device_clock_non_increasing"])
        assertEquals(5L, snapshot["counter_step_count"])
        assertEquals(2L, snapshot["counter_step_max"])
        assertEquals(2L, snapshot["counter_non_unit_steps"])
        timing.motionPublished(report(null, null), 1_000, 1_030)
        timing.motionPublished(report(10, 8), 1_000, 1_040)
        assertEquals(3L, timing.snapshot()["device_interval_count"])
        assertEquals(5L, timing.snapshot()["counter_step_count"])
    }

    @Test fun activeGapsExcludeIdleAndMarkMidGapRateChangesAsMixed() {
        val timing = MotionSourceTiming()
        timing.readReturned(10, 80)
        timing.motionStarted(10_000, 20)
        timing.readReturned(100, 80)
        timing.motionPublished(report(10, 2), 100, 110)
        timing.readReturned(150, 80)
        timing.motionPublished(report(50, 10), 150, 170)
        timing.motionStopped()
        timing.readReturned(10_000, 80)
        timing.motionStarted(10_000, 20_000)
        timing.readReturned(21_000, 80)
        timing.motionPublished(report(30_000, 20), 21_000, 21_010)
        timing.motionIntervalChanged(20_000, 21_020)
        timing.readReturned(21_090, 80)
        timing.motionPublished(report(30_080, 36), 21_090, 21_120)
        timing.readReturned(21_100, 80)
        timing.motionPublished(report(30_090, 38), 21_100, 21_130)
        val stats = timing.snapshot()
        assertEquals(2L, stats["motion_start_count"])
        assertEquals(1L, stats["motion_interval_change_count"])
        assertEquals(21_020L, stats["last_motion_interval_change_at_nanos"])
        assertEquals(20_000L, stats["motion_interval_us"])
        assertEquals(3L, stats["active_read_interval_count"])
        assertEquals(90L, stats["active_read_interval_max_nanos"])
        assertEquals(110L, stats["active_publish_interval_max_nanos"])
        assertEquals(21_120L, stats["active_publish_interval_max_at_nanos"])
        assertEquals(0L, stats["active_publish_interval_max_requested_us"])
        assertEquals(80L, stats["active_device_interval_max_nanos"])
        assertTrue(requireNotNull(stats["read_interval_max_nanos"]) > 1_000L)
        assertTrue(requireNotNull(stats["device_interval_max_nanos"]) > 1_000L)
    }

    @Test fun uniformRateGapsKeepTheirIntervalButChangingAwayAndBackMakesTheGapMixed() {
        val timing = MotionSourceTiming()
        timing.motionStarted(10_000, 10)
        timing.motionPublished(report(100, 2), 100, 110)
        timing.motionPublished(report(200, 4), 200, 210)
        assertEquals(10_000L, timing.snapshot()["active_publish_interval_max_requested_us"])
        timing.motionIntervalChanged(20_000, 220)
        timing.motionIntervalChanged(10_000, 230)
        timing.motionPublished(report(400, 8), 400, 410)
        val stats = timing.snapshot()
        assertEquals(200L, stats["active_publish_interval_max_nanos"])
        assertEquals(410L, stats["active_publish_interval_max_at_nanos"])
        assertEquals(0L, stats["active_publish_interval_max_requested_us"])
    }

    @Test fun batchedMotionIsMeasuredBeforeDownstreamConflationAndRetiredSourcesCannotPublish() {
        val timing = MotionSourceTiming()
        var clock = 110L
        var current = true
        val receipts = mutableListOf<Long>()
        val manager = RTBuddyManager({ true }, { true }, { current }, { 9 },
            { _, receivedAt -> receipts += receivedAt; clock += 20 }, {}, {}, {}, sourceTiming = timing, sourceClockNanos = { clock })
        try {
            assertTrue(manager.setHeadTrackingEnabled(true))
            manager.feed(frame(10, 4) + frame(15, 5), 100)
            val snapshot = timing.snapshot()
            assertEquals(listOf(100L, 100L), receipts)
            assertEquals(2L, snapshot["motion_reports"])
            assertEquals(5L, snapshot["device_interval_max_nanos"])
            assertEquals(1L, snapshot["counter_step_max"])
            assertEquals(50L, snapshot["publish_delay_max_nanos"])
            assertEquals(150L, snapshot["publish_delay_max_at_nanos"])
            assertEquals(40L, snapshot["feed_work_max_nanos"])
            current = false
            manager.feed(frame(20, 6), 150)
            assertEquals(listOf(100L, 100L), receipts)
            assertEquals(2L, timing.snapshot()["motion_reports"])
        } finally { manager.close() }
    }

    @Test fun feedMonitorWaitIsMeasuredSeparatelyFromParsing() {
        val timing = MotionSourceTiming()
        val clock = AtomicLong(110)
        val attempted = CountDownLatch(1)
        val manager = RTBuddyManager({ true }, { true }, { true }, { 9 }, { _, _ -> }, {}, {}, {},
            sourceTiming = timing, sourceClockNanos = { clock.get().also { attempted.countDown() } })
        lateinit var feeder: Thread
        try {
            synchronized(manager) {
                feeder = thread { manager.feed(byteArrayOf(), 100) }
                assertTrue(attempted.await(2, TimeUnit.SECONDS))
                clock.set(210)
            }
            feeder.join(2_000)
            assertFalse("feed must finish after the monitor is released", feeder.isAlive)
            assertEquals(10L, timing.snapshot()["dispatch_delay_max_nanos"])
            assertEquals(100L, timing.snapshot()["feed_wait_max_nanos"])
            assertEquals(0L, timing.snapshot()["feed_work_max_nanos"])
        } finally { manager.close() }
    }

    @Test fun onlyDuplicateActiveSkipsStateWorkAndEveryResetRemainsObservable() {
        for (old in BuddyState.entries) for (incoming in BuddyState.entries) {
            assertEquals(!(old == BuddyState.ACTIVE && incoming == BuddyState.ACTIVE),
                needsMotionStateUpdate(old, incoming))
        }
    }

    private fun frame(time: Long, counter: Long): ByteArray {
        val bytes = ByteArray(58).apply {
            this[0] = 1
            for (index in 0..7) this[index + 1] = (time ushr (8 * index)).toByte()
            for (index in 0..3) this[index + 54] = (counter ushr (8 * index)).toByte()
        }
        return RTBuddyPacket.create(SensorDataWxBuddyPayload(SensorDataWX.newBuilder()
            .setSeq(10).setLogType(3).setCommand(SensorCommand.newBuilder().setServiceValue(16)
                .setPayload(ByteString.copyFrom(bytes))).build())).rawPacket
    }
}
