package me.kavishdevar.librepods.core.bluetooth.att.types

import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TransparencySettingsTest {
    private fun report(size: Int = 104): ByteArray = ByteArray(size).also { data ->
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(0, 1f)
        for (index in 0 until 8) {
            buffer.putFloat(4 + index * 4, index * 3f)
            buffer.putFloat(52 + index * 4, index * 5f)
        }
        buffer.putFloat(36, -0.25f).putFloat(40, -0.5f)
            .putFloat(48, 0.25f).putFloat(84, 0.75f)
            .putFloat(88, 0.5f).putFloat(92, 1f).putFloat(96, 0.75f)
        if (size == 104) buffer.putFloat(100, 0.45f)
    }

    private fun ByteArray.put(offset: Int, value: Float) = apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
    }

    @Test fun onlyExactLegacyAndCompleteLengthsAreRecognized() {
        val complete = report()
        for (size in 0..128) {
            val parsed = parseTransparencySettingsResponse(complete.copyOf(size))
            if (size == 100 || size == 104) assertNotNull("size=$size", parsed)
            else assertNull("size=$size", parsed)
        }
        assertNull(parseTransparencySettingsResponse(report(100))!!.ownVoiceAmplification)
        assertEquals(0.45f, parseTransparencySettingsResponse(complete)!!.ownVoiceAmplification!!, 0f)
    }

    @Test fun flagsRequireZeroOrOneAndAcceptUntouchedSignedZero() {
        for (offset in listOf(0, 44, 92)) {
            for (invalid in listOf(-1f, 0.25f, 0.5f, 0.75f, 2f, Float.MIN_VALUE)) {
                assertNull("offset=$offset value=$invalid", parseTransparencySettingsResponse(report().put(offset, invalid)))
            }
            for (valid in listOf(-0f, 0f, 1f)) {
                assertNotNull("offset=$offset value=$valid", parseTransparencySettingsResponse(report().put(offset, valid)))
            }
        }
    }

    @Test fun knownScalarsStayInRangeAndEveryFloatMustBeFinite() {
        for (offset in 0 until 104 step 4) {
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertNull("offset=$offset value=$invalid", parseTransparencySettingsResponse(report().put(offset, invalid)))
            }
        }
        for (offset in listOf(36, 40, 84, 88)) {
            for (invalid in listOf(-1.01f, 1.01f)) {
                assertNull(parseTransparencySettingsResponse(report().put(offset, invalid)))
            }
            for (valid in listOf(-1f, 1f)) {
                assertNotNull(parseTransparencySettingsResponse(report().put(offset, valid)))
            }
        }
        for (offset in listOf(48, 96)) {
            for (invalid in listOf(-0.01f, 1.01f)) {
                assertNull(parseTransparencySettingsResponse(report().put(offset, invalid)))
            }
            for (valid in listOf(0f, 1f)) {
                assertNotNull(parseTransparencySettingsResponse(report().put(offset, valid)))
            }
        }
    }

    @Test fun scalarEditPreservesCurvesOwnVoiceAndOtherSignedZeroBytes() {
        val current = report()
        for (offset in listOf(0, 4, 36, 40, 44, 48, 52, 84, 88, 92, 96, 100)) {
            current.put(offset, -0f)
        }
        val original = current.copyOf()
        val settings = parseTransparencySettingsResponse(current)!!
        val requested = settings.copy(
            leftTone = 0.25f,
            leftEQ = settings.leftEQ.copyOf().also { it[0] = 0f },
            ownVoiceAmplification = 0f,
        )
        var written: ByteArray? = null
        sendTransparencySettings(current, requested) { handle, value, _ ->
            assertEquals(ATTHandle.TRANSPARENCY, handle)
            written = value
        }
        assertArrayEquals(original.copyOf().put(40, 0.25f), written)
        assertArrayEquals(original, current)
    }

    @Test fun explicitCurveEditKeepsTheOtherEarAndAllScalarBytes() {
        val current = report()
        val settings = parseTransparencySettingsResponse(current)!!
        var written: ByteArray? = null
        sendTransparencySettings(current, settings.copy(
            leftEQ = settings.leftEQ.copyOf().also { it[3] = 8.5f },
        )) { _, value, _ -> written = value }
        assertArrayEquals(current.copyOf().put(16, 8.5f), written)
    }

    @Test fun invalidEditsAndUnknownLayoutsNeverReachTheWriter() {
        val current = report()
        val settings = parseTransparencySettingsResponse(current)!!
        var writes = 0
        val writer: (ATTHandle, ByteArray, ByteArray) -> Unit = { _, _, _ -> writes++ }
        for (requested in listOf(
            settings.copy(leftAmplification = -1.01f),
            settings.copy(rightAmplification = 1.01f),
            settings.copy(leftTone = 1.01f),
            settings.copy(rightTone = -1.01f),
            settings.copy(leftAmbientNoiseReduction = -0.01f),
            settings.copy(rightAmbientNoiseReduction = 1.01f),
            settings.copy(leftEQ = FloatArray(7)),
            settings.copy(rightEQ = FloatArray(9)),
            settings.copy(leftEQ = settings.leftEQ.copyOf().also { it[2] = Float.NaN }),
            settings.copy(ownVoiceAmplification = Float.POSITIVE_INFINITY),
        )) sendTransparencySettings(current, requested, writer)
        for (invalid in listOf(byteArrayOf(), current.copyOf(108), current.copyOf().put(44, 0.5f))) {
            sendTransparencySettings(invalid, settings.copy(enabled = false), writer)
            disableCustomizedTransparency(invalid, writer)
        }
        assertEquals(0, writes)
        assertArrayEquals(report(), current)
    }

    @Test fun legacyEditsKeepOneHundredBytesAndNeverInventOwnVoice() {
        val current = report(100)
        val settings = parseTransparencySettingsResponse(current)!!
        var written: ByteArray? = null
        sendTransparencySettings(current, settings.copy(leftTone = 0.25f)) { _, value, _ -> written = value }
        assertArrayEquals(current.copyOf().put(40, 0.25f), written)
        var writes = 0
        sendTransparencySettings(current, settings.copy(ownVoiceAmplification = 0.5f)) { _, _, _ -> writes++ }
        assertEquals(0, writes)
    }

    @Test fun onlyTheExactUnenrolledRecordEstablishesInactiveWithoutEditableSettings() {
        val inactive = byteArrayOf(2, 0, 0, 0)
        assertEquals(false, customTransparencyEnabled(inactive))
        assertNull(parseTransparencySettingsResponse(inactive))
        for (unknown in listOf(
            byteArrayOf(), byteArrayOf(2), byteArrayOf(2, 0, 0),
            inactive + byteArrayOf(0), byteArrayOf(1, 0, 0, 0), byteArrayOf(2, 1, 0, 0),
            byteArrayOf(2, 0, 1, 0), byteArrayOf(2, 0, 0, 1),
        )) assertNull(customTransparencyEnabled(unknown))
        assertEquals(true, customTransparencyEnabled(report()))
        assertEquals(false, customTransparencyEnabled(report().put(0, 0f)))
        var writes = 0
        sendTransparencySettings(inactive, parseTransparencySettingsResponse(report())!!) { _, _, _ -> writes++ }
        disableCustomizedTransparency(inactive) { _, _, _ -> writes++ }
        assertEquals(0, writes)
    }
}
