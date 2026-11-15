package me.kavishdevar.librepods.audio

import android.app.Application
import android.os.Bundle
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.services.headTrackingTimingJson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class HeadTrackingTimingTest {
    @Test fun collectorWorkIncludesFailuresAndKeepsTheFirstMaximumCompletionTime() {
        val stats = HeadTrackingTiming()
        stats.collectorFinished(100, 110)
        stats.collectorFinished(120, 150)
        stats.collectorFinished(160, 190)
        stats.collectorFinished(200, 199)
        stats.collectorFinished(0, 10)
        val value = stats.snapshot()
        assertEquals(3L, value.getLong("collector_work_count"))
        assertEquals(70.0 / 3, value.getDouble("collector_work_mean_nanos"), 0.0)
        assertEquals(30L, value.getLong("collector_work_max_nanos"))
        assertEquals(150L, value.getLong("collector_work_max_at_nanos"))
    }

    @Test fun latestEpochMilestonesResetWithoutResettingCumulativeWorkOrSamples() {
        val stats = HeadTrackingTiming()
        stats.begin(100)
        stats.validAttitude(105); stats.poseSubmitted(106) // No accepted collection yet.
        assertEquals(0L, stats.snapshot().getLong("latest_epoch_first_attitude_at_nanos"))
        stats.sample(sample(1, 110), 112)
        stats.validAttitude(111); stats.poseSubmitted(113)
        assertEquals(0L, stats.snapshot().getLong("latest_epoch_first_submit_at_nanos"))
        stats.validAttitude(112); stats.poseSubmitted(114)
        stats.validAttitude(120); stats.poseSubmitted(121)
        stats.collectorFinished(110, 114)
        var value = stats.snapshot()
        assertEquals(100L, value.getLong("latest_epoch_request_at_nanos"))
        assertEquals(112L, value.getLong("latest_epoch_first_collection_at_nanos"))
        assertEquals(112L, value.getLong("latest_epoch_first_attitude_at_nanos"))
        assertEquals(114L, value.getLong("latest_epoch_first_submit_at_nanos"))
        stats.begin(200)
        value = stats.snapshot()
        assertEquals(200L, value.getLong("latest_epoch_request_at_nanos"))
        assertEquals(0L, value.getLong("latest_epoch_first_collection_at_nanos"))
        assertEquals(0L, value.getLong("latest_epoch_first_attitude_at_nanos"))
        assertEquals(0L, value.getLong("latest_epoch_first_submit_at_nanos"))
        assertEquals(1L, value.getLong("source_samples"))
        assertEquals(1L, value.getLong("collector_work_count"))
    }

    @Test fun sourceRateAndHostForwardingAgeStaySeparate() {
        val stats = HeadTrackingTiming()
        stats.begin(); stats.requested(5_000)
        stats.sample(sample(1, 1_000_000_000), 1_002_000_000)
        stats.sample(sample(2, 1_005_000_000), 1_008_000_000)
        stats.sample(sample(3, 1_010_000_000), 1_011_000_000)
        stats.submitted(); stats.submitted(); stats.stale()
        val value = stats.snapshot()
        assertEquals(3L, value.getLong("source_samples"))
        assertEquals(2L, value.getLong("submitted_poses"))
        assertEquals(1L, value.getLong("stale_samples_dropped"))
        assertEquals(5_000_000.0, value.getDouble("device_interval_mean_nanos"), 0.0)
        assertEquals(5_000_000.0, value.getDouble("source_arrival_interval_mean_nanos"), 0.0)
        assertEquals(2_000_000.0, value.getDouble("app_collection_age_mean_nanos"), 0.0)
        assertEquals(3_000_000L, value.getLong("app_collection_age_max_nanos"))
        assertTrue(value.getBoolean("excludes_acoustic_latency"))
    }

    @Test fun reportingEpochChangesDoNotMixDifferentSensorClocksOrIdleGaps() {
        val stats = HeadTrackingTiming()
        stats.begin(); stats.sample(sample(100, 100), 102)
        stats.begin(); stats.sample(sample(1, 1_000_000), 1_000_001)
        val value = stats.snapshot()
        assertEquals(2L, value.getLong("source_epochs")); assertEquals(2L, value.getLong("source_samples"))
        assertEquals(0L, value.getLong("device_interval_count"))
        assertEquals(0L, value.getLong("source_arrival_interval_count"))
        assertEquals(1.5, value.getDouble("app_collection_age_mean_nanos"), 0.0)
    }

    @Test fun emptyStatsAndFallbackAreExplicitAndFutureArrivalsCannotInventAnAge() {
        val stats = HeadTrackingTiming()
        assertEquals(0L, stats.snapshot().getLong("source_samples"))
        assertEquals(0.0, stats.snapshot().getDouble("device_interval_mean_nanos"), 0.0)
        stats.requested(20_000, fallback = true)
        assertTrue(stats.snapshot().getBoolean("fallback_rate"))
        assertEquals(20_000L, stats.snapshot().getLong("requested_interval_us"))
        assertThrows(IllegalArgumentException::class.java) { stats.sample(sample(1, 100), 99) }
        assertEquals(0L, stats.snapshot().getLong("source_samples"))
    }

    @Test fun cachedDumpSerializesScalarsAndNestedCountersWithoutPoseOrIdentityData() {
        val value = HeadTrackingTiming().snapshot().apply {
            putBundle("sink", Bundle().apply { putLong("hid_inputs", 100); putDouble("host_source_age_mean_nanos", 123.5) })
            putString("reason", "off")
        }
        val json = headTrackingTimingJson(value)
        assertEquals(100L, json.getJSONObject("sink").getLong("hid_inputs"))
        assertEquals(123.5, json.getJSONObject("sink").getDouble("host_source_age_mean_nanos"), 0.0)
        assertEquals("off", json.getString("reason"))
        assertFalse(json.toString().contains("attitude_q15")); assertFalse(json.toString().contains("mac"))
    }

    @Test fun firstFallbackDecisionSurvivesLaterReportingEpochsWithoutClaimingAWriteSucceeded() {
        val stats = HeadTrackingTiming()
        stats.begin(); stats.requested(10_000)
        stats.fallingBack(1_200, "attitude_stall", 1_000)
        assertFalse(stats.snapshot().getBoolean("fallback_rate"))
        stats.requested(20_000, fallback = true)
        stats.begin(); stats.fallingBack(2_000, "socket_fallback")
        val value = stats.snapshot()
        assertEquals(1_200L, value.getLong("first_fallback_at_nanos"))
        assertEquals("attitude_stall", value.getString("first_fallback_reason"))
        assertEquals(1_000L, value.getLong("first_fallback_last_attitude_at_nanos"))
        assertTrue(value.getBoolean("fallback_rate"))
    }

    private fun sample(tick: Int, arrival: Long) = MotionSample(MotionReport(tick * 5_000_000L,
        listOf(1, 2, 3), listOf(0, 0, 0), listOf(0, 0, 0), listOf(0, 0, -1000), tick.toLong()), arrival)
}
