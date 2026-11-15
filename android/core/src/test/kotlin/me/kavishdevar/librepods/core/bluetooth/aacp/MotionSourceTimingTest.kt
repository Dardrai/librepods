package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MotionSourceTimingTest {
    private fun report(time: Long?, counter: Long?) = MotionReport(time,
        listOf(1, 2, 3), listOf(0, 0, 0), listOf(0, 0, 0), listOf(0, 0, -16384), counter)

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
        var delivered = 0
        val manager = RTBuddyManager({ true }, { true }, { current }, { 9 },
            { delivered++; clock += 20 }, {}, {}, {}, sourceTiming = timing, sourceClockNanos = { clock })
        try {
            assertTrue(manager.setHeadTrackingEnabled(true))
            manager.feed(frame(10, 4) + frame(15, 5), 100)
            val snapshot = timing.snapshot()
            assertEquals(2, delivered)
            assertEquals(2L, snapshot["motion_reports"])
            assertEquals(5L, snapshot["device_interval_max_nanos"])
            assertEquals(1L, snapshot["counter_step_max"])
            assertEquals(50L, snapshot["publish_delay_max_nanos"])
            assertEquals(150L, snapshot["publish_delay_max_at_nanos"])
            assertEquals(40L, snapshot["feed_work_max_nanos"])
            current = false
            manager.feed(frame(20, 6), 150)
            assertEquals(2, delivered)
            assertEquals(2L, timing.snapshot()["motion_reports"])
        } finally { manager.close() }
    }

    @Test fun feedMonitorWaitIsMeasuredSeparatelyFromParsing() {
        val timing = MotionSourceTiming()
        val clock = AtomicLong(110)
        val attempted = CountDownLatch(1)
        val manager = RTBuddyManager({ true }, { true }, { true }, { 9 }, {}, {}, {}, {},
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
