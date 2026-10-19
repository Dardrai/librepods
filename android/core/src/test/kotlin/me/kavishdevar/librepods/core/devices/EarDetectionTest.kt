package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.*
import org.junit.Test

class EarDetectionTest {
    private val id = ControlCommandIdentifier.EAR_DETECTION_CONFIG

    @Test fun unknownReportsUseTheSavedChoiceAndValidReportsOverrideIt() {
        for (saved in listOf(false, true)) {
            val settings = AppleSettings(earDetectionEnabled = saved)
            assertEquals(saved, AppleState().effectiveEarDetection(settings))
            for (value in listOf(1, 2)) {
                val state = AppleState(controlStates = mapOf(id to byteArrayOf(value.toByte(), 0, 0, 0)))
                assertEquals(value == 2, state.effectiveEarDetection(settings))
            }
            for (invalid in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(3), byteArrayOf(2, 1), ByteArray(5) { 1 })) {
                assertEquals(saved, AppleState(controlStates = mapOf(id to invalid)).effectiveEarDetection(settings))
            }
        }
    }

    @Test fun validPendingChoicesWinAndInvalidChoicesCannotHideAValidReport() {
        for (requested in listOf(1, 2)) {
            val state = AppleState(controlStates = mapOf(id to byteArrayOf((3 - requested).toByte())),
                requestedControls = mapOf(id to byteArrayOf(requested.toByte(), 0)))
            assertEquals(requested == 2, state.effectiveEarDetection(AppleSettings(earDetectionEnabled = requested != 2)))
        }
        for (invalid in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(1, 1), ByteArray(5) { 2 })) {
            val state = AppleState(controlStates = mapOf(id to byteArrayOf(1)), requestedControls = mapOf(id to invalid))
            assertFalse(state.effectiveEarDetection(AppleSettings(earDetectionEnabled = true)))
        }
    }

    @Test fun matchingReportsRetirePendingChoicesAndExpiryReturnsToTheDeviceReport() {
        val settings = AppleSettings(earDetectionEnabled = true)
        val original = AppleState().withControlReport(id, byteArrayOf(2))
        val requested = original.withControlRequest(id, byteArrayOf(1), original.controlStates[id])
        assertFalse(requested.effectiveEarDetection(settings))
        val confirmed = requested.withControlReport(id, byteArrayOf(1, 0, 0, 0))
        assertFalse(id in confirmed.requestedControls)
        assertFalse(confirmed.effectiveEarDetection(settings))
        val unconfirmed = requested.withControlReport(id, byteArrayOf(2))
        assertFalse(unconfirmed.effectiveEarDetection(settings))
        assertTrue(unconfirmed.copy(requestedControls = emptyMap()).effectiveEarDetection(settings))
    }

    @Test fun earBooleanEncodingIsReversedWhileOrdinaryToggleEncodingStaysUnchanged() {
        assertEquals(2.toByte(), id.encodeBoolean(true))
        assertEquals(1.toByte(), id.encodeBoolean(false))
        for (ordinary in listOf(ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG, ControlCommandIdentifier.ALLOW_OFF_OPTION)) {
            assertEquals(1.toByte(), ordinary.encodeBoolean(true))
            assertEquals(2.toByte(), ordinary.encodeBoolean(false))
            for (value in listOf(1, 2)) {
                assertEquals(value == 1, ordinary.decodeBoolean(byteArrayOf(value.toByte(), 0, 0, 0)))
                assertEquals(value == 2, id.decodeBoolean(byteArrayOf(value.toByte(), 0, 0, 0)))
            }
        }
        for (invalid in listOf(null, byteArrayOf(), byteArrayOf(0), byteArrayOf(3), byteArrayOf(2, 1), ByteArray(5) { 2 })) {
            assertNull(id.decodeBoolean(invalid))
        }
    }
}
