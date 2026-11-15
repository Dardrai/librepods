package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDescriptor
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class MotionIntervalOwnersTest {
    @Test fun lateHeartDiscoveryAndItsDuplicateDoNotRestartOrResetLiveMotion() {
        Fixture().use { fixture ->
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, Any(), 10.milliseconds))
            fixture.manager.feed(motionFrame(27, 1))
            val states = fixture.motionStates.toList()
            val writes = fixture.commands()
            assertEquals(BuddyState.ACTIVE, states.last())
            fixture.clock = 2_000
            val heart = announcement(4, 30, heart = true)
            fixture.manager.feed(heart)
            fixture.manager.feed(heart)
            assertEquals(writes, fixture.commands())
            assertEquals(states, fixture.motionStates.toList())
            fixture.manager.feed(motionFrame(27, 2))
            assertEquals(2, fixture.motionReports.size)
            assertEquals(listOf(BuddyState.WAITING, BuddyState.ACTIVE, BuddyState.ACTIVE), fixture.motionStates.toList())
            val timing = fixture.sourceTiming.snapshot()
            assertEquals(1L, timing["motion_start_count"])
            assertEquals(1_000L, timing["last_motion_start_at_nanos"])
            assertEquals(10_000L, timing["last_motion_start_interval_us"])
            assertEquals(1L, timing["motion_mapping_changes"])
            assertEquals(1L, timing["heart_mapping_changes"])
            assertEquals(2_000L, timing["last_heart_mapping_change_at_nanos"])
        }
    }

    @Test fun realMotionRemapAndSameAddressSequenceRestartRearmOnlyTheCurrentMotionAddress() {
        Fixture().use { fixture ->
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, Any(), 10.milliseconds))
            fixture.manager.feed(motionFrame(27, 1))
            fixture.manager.feed(announcement(4, 30, heart = true))
            fixture.clock = 3_000
            fixture.manager.feed(announcement(5, 28))
            assertEquals(listOf(4 to 1, 1 to 10_000, 4 to 1, 1 to 10_000), fixture.commands())
            assertEquals(listOf(27, 27, 28, 28), fixture.writes.map { packet ->
                ((packet as RTBuddyPacket).rtBuddyPayload as SensorDataWxBuddyPayload).data.serviceSettings.serviceValue })
            assertEquals(BuddyState.WAITING, fixture.motionStates.last())
            fixture.manager.feed(motionFrame(27, 2))
            assertEquals(1, fixture.motionReports.size)
            fixture.manager.feed(motionFrame(28, 3))
            assertEquals(2, fixture.motionReports.size)
            fixture.clock = 4_000
            val restart = announcement(1, 28)
            fixture.manager.feed(restart)
            assertEquals(listOf(4 to 1, 1 to 10_000), fixture.commands().takeLast(2))
            assertEquals(6, fixture.writes.size)
            assertEquals(BuddyState.WAITING, fixture.motionStates.last())
            fixture.manager.feed(restart)
            assertEquals(6, fixture.writes.size)
            val timing = fixture.sourceTiming.snapshot()
            assertEquals(3L, timing["motion_start_count"])
            assertEquals(4_000L, timing["last_motion_start_at_nanos"])
            assertEquals(3L, timing["motion_mapping_changes"])
            assertEquals(2L, timing["heart_mapping_changes"])
        }
    }

    @Test fun legacyMotionWaitsWhenHeartClaimsItsAddressThenStartsTheDiscoveredNativeServiceAtTenMs() {
        Fixture(announceMotion = false).use { fixture ->
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, Any(), 10.milliseconds))
            assertEquals(listOf(1 to 40_000), fixture.commands())
            fixture.manager.feed(announcement(3, 16, heart = true))
            assertEquals(listOf(1 to 40_000), fixture.commands()) // Never Stop or configure the new heart address.
            fixture.manager.feed(announcement(4, 27))
            assertEquals(listOf(1 to 40_000, 4 to 1, 1 to 10_000), fixture.commands())
            fixture.manager.feed(announcement(4, 27))
            assertEquals(3, fixture.writes.size)
            assertEquals(2L, fixture.sourceTiming.snapshot()["motion_start_count"])
        }
    }

    @Test fun initialFastWriteFailureReturnsFalseSoTheSameOwnerCanUseTheBaseline() {
        val fixture = Fixture()
        val owner = Any()
        try {
            fixture.accept = { it != (1 to 5_000) }
            assertFalse(fixture.manager.setHeadTrackingEnabled(true, owner, 5.milliseconds))
            assertFalse(fixture.owners.wanted())
            assertEquals(1 to 0, fixture.commands().last())
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, owner, 20.milliseconds))
            assertEquals(1 to 20_000, fixture.commands().last())
            assertNotNull(fixture.owners.request(owner))
        } finally { fixture.close() }
    }

    @Test fun anOwnerWaitingForDescriptorsIsAcceptedWithoutAStartWrite() {
        val owners = MotionOwners()
        val manager = RTBuddyManager({ error("No known motion service") }, { true }, { true }, { null },
            {}, {}, {}, {}, sharedMotionOwners = owners)
        val owner = Any()
        try {
            assertTrue(manager.setHeadTrackingEnabled(true, owner, 5.milliseconds))
            assertEquals(5_000, owners.request(owner)?.intervalUs)
        } finally { manager.close() }
    }

    @Test fun fastestOwnerWinsAndReleaseRestoresTheLatestUserIntervalWithoutRestarting() {
        val fixture = Fixture(baselineUs = 80_000)
        val ordinary = Any()
        val fast = Any()
        val slower = Any()
        val slowest = Any()
        try {
            assertTrue(fixture.writes.isEmpty()) // Discovery alone never starts motion.
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, ordinary))
            assertEquals(listOf(4 to 1, 1 to 80_000), fixture.commands())
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, fast, 5.milliseconds))
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, slower, 10.milliseconds))
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, slowest, 20.milliseconds))
            assertTrue(fixture.manager.setMotionInterval(60.milliseconds))
            assertEquals(listOf(4 to 1, 1 to 80_000, 1 to 5_000), fixture.commands())
            assertTrue(fixture.manager.setHeadTrackingEnabled(false, fast))
            assertEquals(1 to 10_000, fixture.commands().last())
            assertTrue(fixture.manager.setHeadTrackingEnabled(false, slower))
            assertEquals(1 to 20_000, fixture.commands().last())
            assertTrue(fixture.manager.setHeadTrackingEnabled(false, slowest))
            assertEquals(1 to 60_000, fixture.commands().last())
            assertEquals(1, fixture.commands().count { it.first == 4 })
            assertFalse(fixture.commands().any { it == (1 to 0) })
            assertTrue(fixture.manager.setHeadTrackingEnabled(false, ordinary))
            assertEquals(listOf(4 to 10, 1 to 0), fixture.commands().takeLast(2))
        } finally { fixture.close() }
    }

    @Test fun failedRateChangesPreserveOtherOwnersAndRetryRestorationAfterRelease() {
        val fixture = Fixture(baselineUs = 80_000)
        val ordinary = Any()
        val fast = Any()
        try {
            fixture.manager.setHeadTrackingEnabled(true, ordinary)
            fixture.accept = { it != (1 to 10_000) }
            assertFalse(fixture.manager.setHeadTrackingEnabled(true, fast, 10.milliseconds))
            assertNull(fixture.owners.request(fast))
            assertNotNull(fixture.owners.request(ordinary))
            fixture.accept = { true }
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, fast, 10.milliseconds))
            fixture.accept = { it != (1 to 80_000) }
            assertFalse(fixture.manager.setHeadTrackingEnabled(false, fast))
            assertNull(fixture.owners.request(fast))
            assertNotNull(fixture.owners.request(ordinary))
            synchronized(fixture.manager) {
                val attempts = fixture.writes.size
                repeat(10) { fixture.manager.feed(byteArrayOf()) }
                assertEquals(attempts, fixture.writes.size) // Failed restoration is not retried per input chunk.
            }
            fixture.accept = { true }
            assertTrue(fixture.manager.reconcileHeadTracking())
            assertEquals(1 to 80_000, fixture.commands().last())
            assertEquals(1, fixture.commands().count { it.first == 4 })
            assertFalse(fixture.commands().any { it == (1 to 0) })
        } finally { fixture.close() }
    }

    @Test fun onlySuccessfulChangedLiveRatesAreTimedAndTheyDoNotCountAsStarts() {
        Fixture().use { fixture ->
            val owner = Any()
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, owner, 10.milliseconds))
            fixture.clock = 2_000
            fixture.accept = { it != (1 to 20_000) }
            assertFalse(fixture.manager.setHeadTrackingEnabled(true, owner, 20.milliseconds))
            assertEquals(0L, fixture.sourceTiming.snapshot()["motion_interval_change_count"])
            assertEquals(10_000L, fixture.sourceTiming.snapshot()["motion_interval_us"])
            fixture.accept = { true }
            fixture.clock = 3_000
            assertTrue(fixture.manager.setHeadTrackingEnabled(true, owner, 20.milliseconds))
            fixture.clock = 4_000
            assertTrue(fixture.manager.reconcileHeadTracking())
            val stats = fixture.sourceTiming.snapshot()
            assertEquals(1L, stats["motion_start_count"])
            assertEquals(1L, stats["motion_interval_change_count"])
            assertEquals(3_000L, stats["last_motion_interval_change_at_nanos"])
            assertEquals(20_000L, stats["motion_interval_us"])
        }
    }

    @Test fun releaseDuringFailedRateWriteCannotReplayTheOwnersPreviousRequest() {
        val fixture = Fixture(baselineUs = 80_000)
        val ordinary = Any()
        val fast = Any()
        val entered = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        var accepted: Boolean? = null
        var worker: Thread? = null
        try {
            fixture.manager.setHeadTrackingEnabled(true, ordinary)
            fixture.manager.setHeadTrackingEnabled(true, fast, 30.milliseconds)
            fixture.accept = {
                if (it == (1 to 10_000)) {
                    entered.countDown()
                    check(finishWrite.await(2, TimeUnit.SECONDS))
                    false
                } else true
            }
            val running = thread { accepted = fixture.manager.setHeadTrackingEnabled(true, fast, 10.milliseconds) }
            worker = running
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // AACP releases from its shared registry before waiting for the transport monitor.
            fixture.owners.set(fast, false)
            finishWrite.countDown()
            running.join(2_000)
            assertFalse(running.isAlive)
            assertEquals(false, accepted)
            assertNull(fixture.owners.request(fast))
            assertNotNull(fixture.owners.request(ordinary))
            assertTrue(fixture.manager.reconcileHeadTracking())
            assertEquals(1 to 80_000, fixture.commands().last())
        } finally {
            finishWrite.countDown()
            worker?.join(2_000)
            fixture.close()
        }
    }

    @Test fun suspendedOwnerReleaseRestoresBaselineWhenTheTransportResumes() {
        val fixture = Fixture()
        val ordinary = Any()
        val fast = Any()
        try {
            fixture.manager.setHeadTrackingEnabled(true, ordinary)
            fixture.manager.setHeadTrackingEnabled(true, fast, 10.milliseconds)
            fixture.manager.setSuspended(true)
            assertEquals(1 to 0, fixture.commands().last())
            val pausedWrites = fixture.writes.size
            assertTrue(fixture.manager.setHeadTrackingEnabled(false, fast))
            assertEquals(pausedWrites, fixture.writes.size)
            fixture.manager.setSuspended(false)
            assertEquals(listOf(4 to 1, 1 to 20_000), fixture.commands().takeLast(2))
        } finally { fixture.close() }
    }

    @Test fun controlRefreshKeepsLiveRatesAndHonorsAReleaseDuringTheManagerGap() {
        val owners = MotionOwners()
        val ordinary = Any()
        val fast = Any()
        Fixture(owners = owners, baselineUs = 60_000).use { original ->
            original.manager.setHeadTrackingEnabled(true, ordinary)
            original.manager.setHeadTrackingEnabled(true, fast, 10.milliseconds)
        }
        Fixture(owners = owners, baselineUs = 60_000).use { replacement ->
            replacement.manager.reconcileHeadTracking()
            assertEquals(1 to 10_000, replacement.commands().last())
        }
        owners.set(fast, false)
        Fixture(owners = owners, baselineUs = 60_000).use { replacement ->
            replacement.manager.reconcileHeadTracking()
            assertEquals(1 to 60_000, replacement.commands().last())
            replacement.manager.setHeadTrackingEnabled(true, Any(), 5.milliseconds)
            replacement.manager.stopAllHeadTracking()
            replacement.manager.setSuspended(true)
            replacement.manager.setSuspended(false)
            assertFalse(owners.wanted())
            assertEquals(1 to 0, replacement.commands().last())
        }
    }

    @Test fun invalidRequestsAndLegacyFallbackCannotRaiseTheLegacyRate() {
        val writes = mutableListOf<AACPPacket>()
        var current = true
        val manager = RTBuddyManager({ writes += it; true }, { true }, { current }, { 9 }, {}, {}, {}, {})
        try {
            assertFalse(manager.setHeadTrackingEnabled(true, Any(), 4.milliseconds))
            assertFalse(manager.setHeadTrackingEnabled(true, Any(), 5.milliseconds + 500.nanoseconds))
            assertTrue(writes.isEmpty())
            assertTrue(manager.setHeadTrackingEnabled(true, Any(), 10.milliseconds))
            assertEquals(1 to 40_000, command(writes.single()))
            assertFalse(manager.setMotionInterval(10.milliseconds))
            current = false
            val retiredWrites = writes.size
            assertFalse(manager.setHeadTrackingEnabled(true, Any(), 10.milliseconds))
            manager.close()
            assertEquals(retiredWrites, writes.size)
        } finally { manager.close() }
    }

    private class Fixture(
        val owners: MotionOwners = MotionOwners(),
        baselineUs: Int? = null,
        announceMotion: Boolean = true,
    ) : AutoCloseable {
        val writes = CopyOnWriteArrayList<AACPPacket>()
        val motionStates = CopyOnWriteArrayList<BuddyState>()
        val motionReports = CopyOnWriteArrayList<SensorReports.Motion>()
        val sourceTiming = MotionSourceTiming()
        @Volatile var clock = 1_000L
        @Volatile var accept: (Pair<Int, Int>) -> Boolean = { true }
        val manager = RTBuddyManager({ writes += it; accept(command(it)) }, { true }, { true }, { 9 },
            { motionReports += it }, {}, { motionStates += it }, {}, motionIntervalUs = baselineUs,
            sharedMotionOwners = owners, sourceTiming = sourceTiming, sourceClockNanos = { clock })
        init {
            if (announceMotion) manager.feed(announcement(3, 27))
        }
        fun commands() = writes.map(::command)
        override fun close() = manager.close()
    }

    companion object {
        private fun command(packet: AACPPacket): Pair<Int, Int> {
            val bytes = ((packet as RTBuddyPacket).rtBuddyPayload as SensorDataWxBuddyPayload)
                .data.serviceSettings.configuration.toByteArray()
            val value = (1..4).fold(0) { result, index -> result or ((bytes[index].toInt() and 255) shl ((index - 1) * 8)) }
            return bytes[0].toInt() to value
        }

        private fun announcement(sequence: Int, service: Int, heart: Boolean = false): ByteArray =
            RTBuddyPacket.create(SensorDataWxBuddyPayload(SensorDataWX.newBuilder().setSeq(sequence).setLogType(3)
                .addSensorDescriptor(SensorDescriptor.newBuilder().setServiceValue(service)
                    .setSensorDescriptor(ByteString.copyFrom(dictionary(heart)))).build())).rawPacket

        private fun motionFrame(service: Int, counter: Long): ByteArray {
            val payload = ByteArray(58).apply {
                this[0] = 1
                for (index in 0..7) this[1 + index] = ((counter * 10_000_000) ushr (8 * index)).toByte()
                for (index in 0..3) this[54 + index] = (counter ushr (8 * index)).toByte()
            }
            return RTBuddyPacket.create(SensorDataWxBuddyPayload(SensorDataWX.newBuilder().setLogType(3)
                .setCommand(SensorCommand.newBuilder().setServiceValue(service)
                    .setPayload(ByteString.copyFrom(payload))).build())).rawPacket
        }

        private fun dictionary(heart: Boolean = false): ByteArray {
            val out = ByteArrayOutputStream()
            fun word(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
            fun item(kind: Int, count: Int, bytes: ByteArray) {
                out.write(word((kind shl 24) or count))
                out.write(bytes)
                while (out.size() % 4 != 0) out.write(0)
            }
            fun number(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
            out.write(byteArrayOf(0xd3.toByte(), 0, 0, 0, 4, 0, 0, 0x81.toByte()))
            for ((key, kind, value) in listOf(
                Triple("VendorID", 4, number(1452)),
                Triple(if (heart) "HeartRateService" else "AccessoryService", 9,
                    (if (heart) "HeartRate\u0000" else "devmotion6\u0000").toByteArray()),
                Triple("MaxReportSize", 4, number(if (heart) 601 else 191)),
                Triple("ReportDescriptor", 10, if (heart) SensorDescriptorLayout.HEART_DESCRIPTOR else SensorDescriptorLayout.MOTION_DESCRIPTOR),
            )) {
                val bytes = (key + '\u0000').toByteArray()
                item(9, bytes.size, bytes)
                item(kind, if (kind == 4) 64 else value.size, value)
            }
            return out.toByteArray()
        }
    }
}
