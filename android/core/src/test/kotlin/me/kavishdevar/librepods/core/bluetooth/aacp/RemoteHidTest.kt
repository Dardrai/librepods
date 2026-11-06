package me.kavishdevar.librepods.core.bluetooth.aacp

import kotlinx.serialization.json.*
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.utils.HeadTracking
import org.junit.Assert.*
import org.junit.Test
import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteHidTest {
    private val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/sensor-wire-v1.json")).readText()).jsonObject
    private fun hex(value: JsonElement) = value.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun frame(name: String) = hex(fixture.getValue("motion").jsonObject.getValue(name))
    private fun body(frame: ByteArray) = frame.copyOfRange(12, frame.size)
    private fun motionAnnouncement() = fixture.getValue("announcements").jsonArray.single {
        it.jsonObject.getValue("service").jsonPrimitive.content == "devmotion6"
    }.jsonObject.getValue("frame").let(::hex)

    @Test fun capturedAnnouncementsAndCommandsMatchTheWindowsContractAtEveryReadBoundary() {
        for (announcement in fixture.getValue("announcements").jsonArray) {
            val a = announcement.jsonObject
            val frame = hex(a.getValue("frame"))
            val packet = checkNotNull(RemoteHid.packets(body(frame))).single()
            assertEquals(a.getValue("device").jsonPrimitive.int, packet.device)
            assertEquals(a.getValue("service").jsonPrimitive.content == "devmotion6", RemoteHid.motionDescriptor(packet.payload))
            for (size in packet.payload.indices) assertFalse(RemoteHid.motionDescriptor(packet.payload.copyOf(size)))
            for (split in 1 until frame.size) {
                val parser = SensorFrames()
                assertTrue(parser.feed(frame.copyOfRange(0, split)).isEmpty())
                assertArrayEquals(frame, parser.feed(frame.copyOfRange(split, frame.size)).single())
            }
        }
        val commands = fixture.getValue("commands").jsonObject
        assertArrayEquals(hex(commands.getValue("host_connect")), RemoteHid.connect(1))
        assertArrayEquals(hex(commands.getValue("start_40ms")), RemoteHid.feature(1, 7, 1, 40_000))
        assertArrayEquals(hex(commands.getValue("stop")), RemoteHid.feature(1, 7, 1, 0))
    }

    @Test fun completeRawMotionAndTimestampedInputsPreserveIdentityAndRejectBadAxes() {
        for (name in listOf("input", "timestamped_input")) {
            val frame = frame(name)
            for (split in 1 until frame.size) {
                val parser = SensorFrames()
                assertTrue(parser.feed(frame.copyOfRange(0, split)).isEmpty())
                assertArrayEquals(frame, parser.feed(frame.copyOfRange(split, frame.size)).single())
            }
            val bytes = body(frame)
            val packet = checkNotNull(RemoteHid.packets(bytes)).single()
            val report = checkNotNull(MotionReport.parse(packet.payload, packet.timestampNs))
            assertEquals(1234567890L, report.timestampNs)
            assertEquals(listOf(-10, 20, -30), report.gyroRaw)
            assertEquals(listOf(100, -200, 300), report.userAccelerationRaw)
            assertEquals(listOf(0, 0, 1000), report.gravityRaw)
            assertEquals(77L, report.counter)
            val wx = SensorDataWX.newBuilder().setLogType(3).setCommand(SensorCommand.newBuilder()
                .setServiceValue(16).setPayload(ByteString.copyFrom(packet.payload))).build()
            assertEquals(report, SensorReports().receive(wx, 16).motion?.report)
            assertEquals(listOf(1.0, 0.0, 0.0, 0.0), report.airPods3Quaternion())
            assertEquals(listOf(-3.66, 2.44, 1.22), report.airPods3Rates(listOf(0.0, 1.0, 0.0)))
            assertNull(report.airPods3Rates(listOf(0.0, 0.0, 1.0)))
            assertNull(report.airPods3Rates(listOf(Double.NaN, 0.0, 0.0)))
            for (size in bytes.indices) assertNull(RemoteHid.packets(bytes.copyOf(size)))
            for (size in 0 until 56) assertNull(MotionReport.parse(packet.payload.copyOf(size)))
            assertNull(MotionReport.parse(packet.payload.copyOf(56))?.counter)
            val noClock = packet.payload.copyOf().apply { for (i in 1..8) this[i] = 0 }
            assertEquals(packet.timestampNs?.takeIf { it > 0 }, MotionReport.parse(noClock, packet.timestampNs)?.timestampNs)
            assertNull(MotionReport.parse(packet.payload.copyOf().apply { this[8] = 0x80.toByte() }))
        }
        val report = MotionReport.parse(hex(fixture.getValue("motion").jsonObject.getValue("report")))!!
        HeadTracking.addMotion(report, -30f, 20f)
        assertEquals(report, HeadTracking.motion.value)
        assertEquals(HeadTracking.acceleration.value, HeadTracking.angularVelocity.value)
        HeadTracking.reset()
        assertNull(HeadTracking.motion.value)
    }

    @Test fun anotherClientCannotSmuggleAnEmbeddedSensorFrame() {
        val input = frame("input")
        val outer = RemoteHid.connect(1).copyOf(12).apply {
            this[7] = 4; this[10] = input.size.toByte(); this[11] = (input.size ushr 8).toByte()
        } + input
        val parser = SensorFrames()
        assertTrue(parser.feed(outer).isEmpty())
        assertArrayEquals(input, parser.feed(input).single())
    }

    @Test fun malformedBatchesAndUntrustedXmlCannotInstallMappings() {
        val bytes = body(motionAnnouncement())
        val xml = RemoteHid.packets(bytes)!!.single().payload.toString(Charsets.UTF_8)
        for (bad in listOf(xml.replace("1452", "1453"), xml.replace("182", "191"),
            xml.replace("<key>VendorID</key>", "<key>VendorID</key><integer>1452</integer><key>VendorID</key>"),
            xml.replace("<string>devmotion6", "<string>&x;devmotion6"),
            xml.replace("<!DOCTYPE", "<!DOCTYPE invalid"), "$xml<plist version=\"1.0\"></plist>")) {
            assertFalse(RemoteHid.motionDescriptor(bad.toByteArray()))
        }
        assertNull(RemoteHid.packets(bytes + byteArrayOf(1, 2, 3)))
        assertNull(RemoteHid.packets(bytes.copyOf().apply { this[7] = (this[7].toInt() or 0x80).toByte() }))
        assertNull(RemoteHid.packets(body(frame("timestamped_input")).apply { this[16] = 0x80.toByte() }))
    }

    @Test fun optInDiscoveryRearmsMappedStreamAndStopsOnlyTheCurrentSocket() {
        val announcement = motionAnnouncement().apply { this[16] = (this[16].toInt() and 0x80 or 6).toByte() }
        val raw = mutableListOf<ByteArray>()
        val motion = mutableListOf<SensorReports.Motion>()
        var current = true
        val manager = RTBuddyManager({ error("RemoteHID must not guess a WX service") },
            { raw += it; true }, { current }, { 9 }, { motion += it }, {}, {}, {}, remoteAllowed = { true })
        try {
            manager.requestDescriptors()
            manager.feed(announcement)
            assertTrue(raw.isEmpty())
            manager.setHeadTrackingEnabled(true)
            assertEquals(1, raw.size)
            assertArrayEquals(RemoteHid.feature(1, 6, 1, 40_000), raw.last())
            manager.feed(announcement)
            assertEquals(3, raw.size)
            // Shared synthetic input uses ID 7; move it to the announced ID 6.
            fun input() = frame("input").apply { this[16] = (this[16].toInt() and 0x80 or 6).toByte() }
            manager.feed(frame("input"))
            assertTrue(motion.isEmpty())
            manager.feed(input())
            assertNotNull(motion.single().report)
            assertTrue(manager.setMotionInterval(kotlin.time.Duration.parse("20ms")))
            assertFalse(manager.setMotionInterval(kotlin.time.Duration.parse("5ms")))
            manager.setSuspended(true)
            assertArrayEquals(RemoteHid.feature(5, 6, 1, 0), raw.last())
            manager.setSuspended(false)
            assertArrayEquals(RemoteHid.feature(6, 6, 1, 20_000), raw.last())
            current = false
            val count = raw.size
            assertFalse(manager.setHeadTrackingEnabled(false))
            manager.feed(input())
            assertEquals(count, raw.size)
        } finally { manager.close() }
        val gated = RTBuddyManager({ true }, { error("RemoteHID model gate") }, { true }, { null }, {}, {}, {}, {})
        try { gated.feed(motionAnnouncement()); gated.setHeadTrackingEnabled(true) } finally { gated.close() }
    }

    @Test fun remoteDiscoveryIsOptInAndFailedStopRetriesOnceBeforeAnotherStart() {
        val raw = CopyOnWriteArrayList<ByteArray>()
        var rejectStop = true
        val retry = CountDownLatch(2)
        val manager = RTBuddyManager({ error("Unexpected WX command") }, { bytes ->
            raw += bytes
            val stop = bytes.size == 26 && bytes.takeLast(4).all { it == 0.toByte() }
            if (stop) retry.countDown()
            !(stop && rejectStop)
        }, { true }, { null }, {}, {}, {}, {}, remoteAllowed = { true })
        try {
            assertTrue(raw.isEmpty())
            manager.setHeadTrackingEnabled(true)
            assertArrayEquals(RemoteHid.connect(1), raw.single())
            manager.feed(motionAnnouncement())
            manager.setHeadTrackingEnabled(false)
            assertTrue(retry.await(2, TimeUnit.SECONDS))
            assertFalse(manager.setMotionInterval(kotlin.time.Duration.parse("40ms")))
            rejectStop = false
            manager.setHeadTrackingEnabled(true)
            assertEquals(6, raw.size) // Discovery, Start, Stop, retry, successful Stop, new Start.
            manager.setHeadTrackingEnabled(false)
        } finally { manager.close() }
    }
}
