package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDescriptor
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
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
    ) : AutoCloseable {
        val writes = CopyOnWriteArrayList<AACPPacket>()
        @Volatile var accept: (Pair<Int, Int>) -> Boolean = { true }
        val manager = RTBuddyManager({ writes += it; accept(command(it)) }, { true }, { true }, { 9 }, {}, {}, {}, {},
            motionIntervalUs = baselineUs, sharedMotionOwners = owners)
        init {
            manager.feed(RTBuddyPacket.create(SensorDataWxBuddyPayload(SensorDataWX.newBuilder()
                .setSeq(3).setLogType(3).addSensorDescriptor(SensorDescriptor.newBuilder()
                    .setServiceValue(27).setSensorDescriptor(ByteString.copyFrom(motionDictionary()))).build())).rawPacket)
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

        private fun motionDictionary(): ByteArray {
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
                Triple("AccessoryService", 9, "devmotion6\u0000".toByteArray()),
                Triple("MaxReportSize", 4, number(191)),
                Triple("ReportDescriptor", 10, SensorDescriptorLayout.MOTION_DESCRIPTOR),
            )) {
                val bytes = (key + '\u0000').toByteArray()
                item(9, bytes.size, bytes)
                item(kind, if (kind == 4) 64 else value.size, value)
            }
            return out.toByteArray()
        }
    }
}
