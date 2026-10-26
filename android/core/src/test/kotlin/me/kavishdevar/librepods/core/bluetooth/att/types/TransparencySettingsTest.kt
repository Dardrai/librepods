package me.kavishdevar.librepods.core.bluetooth.att.types

import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsTuning
import me.kavishdevar.librepods.core.devices.AccommodationsLevel
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TransparencySettingsTest {
    @Test fun capturedStockPresetMappingOnlyReplacesTheTwoTransparencyCurveArrays() {
        val baseline = report().put(40, -0f).put(100, 0.43f)
        for (tuning in AccommodationsTuning.entries) for (level in AccommodationsLevel.entries) {
            val preset = AccommodationsPreset(tuning, level)
            val requested = accommodationsTransparencyPreset(baseline, preset)!!
            val curves = preset.curves()
            assertArrayEquals(curves.copyOfRange(0, 32), requested.copyOfRange(4, 36))
            assertArrayEquals(curves.copyOfRange(64, 96), requested.copyOfRange(52, 84))
            for (offset in baseline.indices) if (offset !in 4..35 && offset !in 52..83)
                assertEquals("offset=$offset", baseline[offset], requested[offset])
            assertNotNull(parseTransparencySettingsResponse(requested))
        }
        assertNull(accommodationsTransparencyPreset(report(100), AccommodationsPreset(AccommodationsTuning.BALANCED_TONE, AccommodationsLevel.SLIGHT)))
        assertNull(accommodationsTransparencyPreset(baseline.put(100, Float.NaN), AccommodationsPreset(AccommodationsTuning.BALANCED_TONE, AccommodationsLevel.SLIGHT)))
    }
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

    @Test fun nativeTransparencyGainConversionBoostsTheSelectedBalanceSideAndAllowsStoredGainTwo() {
        val original = parseTransparencySettingsResponse(report())!!
        for (amplification in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) for (balance in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val adjusted = original.withAmplification(amplification, balance)
            assertEquals(amplification - minOf(balance, 0f), adjusted.leftAmplification, 0f)
            assertEquals(amplification + maxOf(balance, 0f), adjusted.rightAmplification, 0f)
            var value: ByteArray? = null
            sendTransparencySettings(report(), adjusted) { _, packet, _ -> value = packet }
            val parsed = parseTransparencySettingsResponse(value!!)!!
            assertEquals(amplification, parsed.netAmplification, 0f)
            assertEquals(balance, parsed.balance, 0f)
        }
        for (offset in listOf(36, 84)) {
            assertNotNull(parseTransparencySettingsResponse(report().put(offset, 2f)))
            assertNull(parseTransparencySettingsResponse(report().put(offset, 2.01f)))
            assertNull(parseTransparencySettingsResponse(report().put(offset, -1.01f)))
        }
        val neutral = original.withAmplification(0f, 0f)
        val amplified = neutral.withAmplification(0.348610f)
        assertEquals(0.348610f, amplified.leftAmplification, 0.000001f)
        assertEquals(0.348610f, amplified.rightAmplification, 0.000001f)
        val balanced = amplified.withAmplification(balance = 0.359965f)
        assertEquals(amplified.leftAmplification, balanced.leftAmplification, 0f)
        assertEquals(0.359965f, balanced.rightAmplification - amplified.rightAmplification, 0.000001f)
    }

    @Test fun unrelatedEditsPreserveNoncanonicalRawGainPairsAndBothCurveArrays() {
        val baseline = report().put(36, 2f).put(84, -1f)
        val settings = parseTransparencySettingsResponse(baseline)!!
        var written: ByteArray? = null
        sendTransparencySettings(baseline, settings.copy(ownVoiceAmplification = 0.6f)) { _, value, _ -> written = value }
        assertArrayEquals(baseline.copyOf().put(100, 0.6f), written)
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
        for (offset in listOf(40, 88)) {
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
            settings.copy(rightAmplification = 2.01f),
            settings.copy(leftTone = 1.01f),
            settings.copy(rightTone = -1.01f),
            settings.copy(leftAmbientNoiseReduction = -0.01f),
            settings.copy(rightAmbientNoiseReduction = 1.01f),
            settings.copy(leftEQ = FloatArray(7)),
            settings.copy(rightEQ = FloatArray(9)),
            settings.copy(leftEQ = settings.leftEQ.copyOf().also { it[2] = Float.NaN }),
            settings.copy(ownVoiceAmplification = Float.POSITIVE_INFINITY),
            settings.copy(ownVoiceAmplification = -0.01f),
            settings.copy(ownVoiceAmplification = 1.01f),
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
