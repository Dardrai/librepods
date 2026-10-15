package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class RtBuddySensorsTest {
    private fun frame(payload: ByteArray) = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0,
        payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
    private fun varint(value: Int): ByteArray {
        var number = value
        val out = ByteArrayOutputStream()
        do {
            val byte = number and 0x7f
            number = number ushr 7
            out.write(byte or if (number != 0) 0x80 else 0)
        } while (number != 0)
        return out.toByteArray()
    }
    private fun bytes(id: Int, value: ByteArray) = varint(id * 8 + 2) + varint(value.size) + value
    private fun word(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun number(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
    private fun dictionary(name: String, descriptor: ByteArray, maximum: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xd3.toByte(), 0, 0, 0, 4, 0, 0, 0x81.toByte()))
        fun item(kind: Int, count: Int, value: ByteArray) {
            out.write(word(kind shl 24 or count))
            out.write(value)
            while (out.size() % 4 != 0) out.write(0)
        }
        for ((key, kind, value) in listOf(
            Triple("VendorID", 4, number(1452)), Triple("AccessoryService", 9, (name + '\u0000').toByteArray()),
            Triple("MaxReportSize", 4, number(maximum)), Triple("ReportDescriptor", 10, descriptor),
        )) {
            val keyBytes = (key + '\u0000').toByteArray()
            item(9, keyBytes.size, keyBytes)
            item(kind, if (kind == 4) 64 else value.size, value)
        }
        return out.toByteArray()
    }
    private fun announcement(sequence: Int, service: Int, dictionary: ByteArray) = frame(
        byteArrayOf(8, sequence.toByte(), 16, 3) + bytes(5, byteArrayOf(8, service.toByte()) + bytes(2, dictionary)))
    private fun heart(service: Int, confidence: Int = 180, bpm: Int = 72, log: Int = 3, tail: IntArray = intArrayOf(0x10, 0, 0x80)): ByteArray {
        val report = ByteArray(18).apply {
            this[1] = bpm.toByte(); this[2] = confidence.toByte()
            for (index in 0..2) this[15 + index] = tail[index].toByte()
        }
        return frame(byteArrayOf(8, 10, 16, log.toByte()) + bytes(7, byteArrayOf(8, service.toByte()) + bytes(3, report)))
    }

    @Test fun exactDescriptorsDiscoverDynamicServicesAcrossEverySplit() {
        val motion = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        val announcement = announcement(1, 27, motion)
        for (split in announcement.indices) {
            val parser = RtBuddySensors()
            assertFalse(parser.feed(announcement.copyOfRange(0, split)).mappingChanged)
            val result = parser.feed(announcement.copyOfRange(split, announcement.size))
            assertTrue(result.mappingChanged)
            assertEquals(27, result.motionService)
        }
    }

    @Test fun truncationAndUnknownDescriptorsCannotRemapKnownServices() {
        val dictionary = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        val parser = RtBuddySensors()
        assertEquals(16, parser.feed(announcement(1, 16, dictionary)).motionService)
        for (end in dictionary.indices) {
            val result = parser.feed(announcement(3, 27, dictionary.copyOf(end)))
            assertFalse(result.mappingChanged)
            assertEquals(16, result.motionService)
        }
        val wrongName = dictionary("SPL0", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        assertEquals(16, parser.feed(announcement(3, 27, wrongName)).motionService)
        val wrongLayout = dictionary("devmotion6", RtBuddySensors.HEART_DESCRIPTOR, 191)
        assertEquals(16, parser.feed(announcement(3, 27, wrongLayout)).motionService)
    }

    @Test fun handoffGenerationResetsOnceAndConflictingMappingsAreRejected() {
        val motion = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        val heart = dictionary("HeartRate", RtBuddySensors.HEART_DESCRIPTOR, 601)
        val parser = RtBuddySensors()
        parser.feed(announcement(1, 16, motion))
        assertNull(parser.feed(announcement(3, 16, heart)).heartRateService)
        assertEquals(20, parser.feed(announcement(3, 20, heart)).heartRateService)
        val restart = parser.feed(announcement(1, 20, motion))
        assertEquals(1L, restart.generation)
        assertNull(restart.heartRateService)
        assertEquals(20, restart.motionService)
        val duplicate = parser.feed(announcement(1, 20, motion))
        assertEquals(1L, duplicate.generation)
        assertFalse(duplicate.mappingChanged)
    }

    @Test fun heartSamplesRequireConfidenceStatusAndDiscoveredService() {
        val parser = RtBuddySensors()
        assertEquals(RtBuddySensors.HeartRate(72, 180), parser.feed(heart(20)).heartRate)
        for (packet in listOf(heart(20, confidence = 20), heart(20, bpm = 29), heart(20, bpm = 221),
            heart(20, log = 2), heart(20, tail = intArrayOf(1, 2, 3)), heart(14)))
            assertNull(parser.feed(packet).heartRate)
        parser.feed(announcement(3, 27, dictionary("HeartRate", RtBuddySensors.HEART_DESCRIPTOR, 601)))
        assertNull(parser.feed(heart(20)).heartRate)
        assertEquals(72, parser.feed(heart(27)).heartRate?.bpm)
    }

    @Test fun movementUsesReportOffsetsAndRejectsActivityPackets() {
        val parser = RtBuddySensors(16)
        val report = ByteArray(58).apply {
            this[28] = 0xfe.toByte(); this[29] = 0xff.toByte(); this[30] = 0x34; this[31] = 0x12
        }
        val command = byteArrayOf(8, 16) + bytes(3, report)
        assertEquals(RtBuddySensors.Motion(-2, 0x1234), parser.feed(frame(byteArrayOf(8, 1, 16, 3) + bytes(7, command))).motion)
        val activity = byteArrayOf(8, 14) + bytes(3, ByteArray(23))
        assertNull(parser.feed(frame(byteArrayOf(8, 1, 16, 3) + bytes(7, activity))).motion)
        parser.feed(announcement(3, 27, dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)))
        val packed = byteArrayOf(27, 0) + report
        assertEquals(RtBuddySensors.Motion(-2, 0x1234), parser.feed(frame(byteArrayOf(8, 3, 16, 3) + bytes(3, packed))).motion)
    }

    @Test fun malformedProtobufAndOversizedFramesAreBoundedAndDoNotCorruptMappings() {
        val parser = RtBuddySensors()
        val motion = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        parser.feed(announcement(1, 16, motion))
        for (bad in listOf(ByteArray(10) { 0x80.toByte() }, ByteArray(194) { if (it % 2 == 0) 8 else 0 },
            byteArrayOf(0x2a, 0x7f, 1), byteArrayOf(0))) {
            assertFalse(parser.feed(frame(bad)).mappingChanged)
        }
        assertEquals(16, parser.feed(ByteArray(100_000)).motionService)
        assertEquals(72, parser.feed(heart(20)).heartRate?.bpm)
    }

    @Test fun streamCommandsMatchCapturedHeartAndMotionWireLayouts() {
        val heart = RtBuddySensors.streamCommand(1, 84, 1_000_000, heartRate = true)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, 17, 0, 8, 1, 16, 2,
            0x42, 11, 8, 84, 16, 2, 0x1a, 5, 1, 0x40, 0x42, 0x0f, 0), heart)
        val motion = RtBuddySensors.streamCommand(152, 19, 1_000_000)
        assertEquals(28, motion.size)
        assertEquals(0x98.toByte(), motion[13])
        assertEquals(1.toByte(), motion[14])
        try { RtBuddySensors.streamCommand(1, 128, 0); fail("Invalid sensor address") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun firmwareGatesAcceptPublicVersionsAndRejectInternalBuildNumbers() {
        assertEquals(9, RtBuddySensors.publicFirmwareMajor("9A348"))
        assertEquals(8, RtBuddySensors.publicFirmwareMajor(" 8B34 "))
        assertEquals(6, RtBuddySensors.publicFirmwareMajor("6.8.8"))
        assertEquals(9, RtBuddySensors.publicFirmwareMajor("9442752"))
        for (value in listOf("", "unknown9", "999A1", "0A1", "90.3388000000000000.1786", "8.300.1", "8.2"))
            assertNull(RtBuddySensors.publicFirmwareMajor(value))
    }

    @Test fun firmwareArrivingLaterUpdatesFallbackWithoutOverridingDescriptors() {
        val parser = RtBuddySensors()
        val report = ByteArray(58).apply { this[28] = 7; this[30] = 9 }
        fun movement(service: Int) = frame(byteArrayOf(8, 1, 16, 3) +
            bytes(7, byteArrayOf(8, service.toByte()) + bytes(3, report)))
        assertNull(parser.feed(movement(16)).motion)
        parser.setLegacyMotionService(16)
        assertEquals(RtBuddySensors.Motion(7, 9), parser.feed(movement(16)).motion)
        parser.feed(announcement(3, 27, dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)))
        parser.setLegacyMotionService(14)
        assertNull(parser.feed(movement(14)).motion)
        assertEquals(RtBuddySensors.Motion(7, 9), parser.feed(movement(27)).motion)
    }

    private fun movement(service: Int): ByteArray {
        val report = ByteArray(58).apply { this[28] = 7; this[30] = 9 }
        return frame(byteArrayOf(8, 10, 16, 3) + bytes(7,
            byteArrayOf(8, service.toByte()) + bytes(3, report)))
    }

    @Test fun coalescedReportsBeforeARemapCannotLeakIntoTheNewMapping() {
        val parser = RtBuddySensors(16)
        val motion = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        parser.feed(announcement(1, 16, motion))
        parser.feed(announcement(3, 20, dictionary("HeartRate", RtBuddySensors.HEART_DESCRIPTOR, 601)))
        val result = parser.feed(heart(20) + movement(16) + announcement(1, 27, motion))
        assertTrue(result.mappingChanged)
        assertEquals(1L, result.generation)
        assertNull(result.heartRate)
        assertNull(result.motion)
        assertEquals(27, result.motionService)
        assertEquals(RtBuddySensors.Motion(7, 9), parser.feed(movement(27)).motion)
    }

    @Test fun descriptorsRetireLegacyFallbackEvenWhenTheNextGenerationHasNoMatchingSensor() {
        val parser = RtBuddySensors(16)
        val motion = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        val heart = dictionary("HeartRate", RtBuddySensors.HEART_DESCRIPTOR, 601)
        parser.feed(announcement(1, 16, motion))
        parser.feed(announcement(3, 20, heart))
        val heartOnly = parser.feed(announcement(1, 27, heart))
        assertNull(heartOnly.motionService)
        assertFalse(heartOnly.legacyMotionAllowed)
        assertNull(parser.feed(movement(16)).motion)
        parser.feed(announcement(3, 27, heart))
        parser.feed(announcement(1, 16, motion))
        assertNull(parser.feed(heart(20)).heartRate)
    }

    @Test fun legacyFallbackCannotInterpretTheOtherDiscoveredSensor() {
        val heartParser = RtBuddySensors()
        heartParser.feed(announcement(1, 20, dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)))
        assertNull(heartParser.feed(heart(20)).heartRate)
        val motionParser = RtBuddySensors(16)
        motionParser.feed(announcement(1, 16, dictionary("HeartRate", RtBuddySensors.HEART_DESCRIPTOR, 601)))
        assertNull(motionParser.feed(movement(16)).motion)
    }

    @Test fun unsignedOverflowSequenceCannotChangeTheServiceTable() {
        val parser = RtBuddySensors()
        val motion = dictionary("devmotion6", RtBuddySensors.MOTION_DESCRIPTOR, 191)
        parser.feed(announcement(1, 16, motion))
        val invalidSequence = byteArrayOf(8) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(1, 16, 3)
        val result = parser.feed(frame(invalidSequence + bytes(5, byteArrayOf(8, 27) + bytes(2, motion))))
        assertFalse(result.mappingChanged)
        assertEquals(16, result.motionService)
    }

    @Test fun inFlightCommandsSurviveReportsButNotChangedServiceOwnership() {
        val owner = RtBuddySensors.Batch(true, 3, 27, 20, null, null, legacyMotionAllowed = false)
        assertTrue(owner.sameMapping(owner.copy(mappingChanged = false,
            heartRate = RtBuddySensors.HeartRate(72, 180), motion = RtBuddySensors.Motion(1, 2))))
        for (changed in listOf(owner.copy(generation = 4), owner.copy(motionService = 16),
            owner.copy(heartRateService = 19), owner.copy(legacyMotionAllowed = true)))
            assertFalse(owner.sameMapping(changed))
    }
}
