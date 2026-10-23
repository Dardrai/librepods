package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.*
import org.junit.Test

class CallControlsTest {
    private val id = ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG
    private val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, version1 = "9A348")
    private fun reported(value: ByteArray) = AppleState(controlStates = mapOf(id to value), liveControlReports = setOf(id))

    @Test fun exactLegacyEndCallValuesAndZeroPaddingPreserveSingleAndDoublePress() {
        assertArrayEquals(byteArrayOf(0, 3), legacyEndCallValue(false))
        assertArrayEquals(byteArrayOf(0, 2), legacyEndCallValue(true))
        for (padding in 0..2) {
            assertEquals(false, legacyEndCallSinglePress(byteArrayOf(0, 3) + ByteArray(padding)))
            assertEquals(true, legacyEndCallSinglePress(byteArrayOf(0, 2) + ByteArray(padding)))
        }
    }

    @Test fun malformedOrVersionedValuesCannotBeMistakenForLegacyEndCallSelection() {
        assertNull(legacyEndCallSinglePress(null))
        for (size in listOf(0, 1) + (5..32)) {
            val bytes = ByteArray(size)
            if (size > 1) bytes[1] = 2
            assertNull("size=$size", legacyEndCallSinglePress(bytes))
        }
        for (value in 0..255) {
            if (value != 0) assertNull("first=$value", legacyEndCallSinglePress(byteArrayOf(value.toByte(), 2)))
            if (value !in 2..3) assertNull("layout=$value", legacyEndCallSinglePress(byteArrayOf(0, value.toByte())))
            if (value != 0) {
                assertNull("third=$value", legacyEndCallSinglePress(byteArrayOf(0, 2, value.toByte())))
                assertNull("fourth=$value", legacyEndCallSinglePress(byteArrayOf(0, 3, 0, value.toByte())))
            }
        }
    }

    @Test fun modelGateDoesNotInferAdditionalFirmwareOrReportRequirements() {
        val excluded = setOf(AirPodsModel.AIRPODS_1, AirPodsModel.AIRPODS_2, AirPodsModel.UNKNOWN)
        for (model in AirPodsModel.entries) {
            for (firmware in listOf("", "5A377", "6A326", "9A348")) {
                assertEquals("$model/$firmware", model !in excluded,
                    metadata.copy(model = model, version1 = firmware).supportsCallControls())
            }
        }
    }

    @Test fun validPendingChoiceWinsUntilItIsRetiredAndMalformedPendingFallsBackToReadback() {
        assertNull(AppleState().legacyEndCallSinglePress())
        val confirmed = reported(byteArrayOf(0, 3, 0, 0))
        assertEquals(false, confirmed.legacyEndCallSinglePress())
        val pending = confirmed.copy(requestedControls = mapOf(id to byteArrayOf(0, 2)))
        assertEquals(true, pending.legacyEndCallSinglePress())
        assertEquals(false, pending.copy(requestedControls = emptyMap()).legacyEndCallSinglePress())
        assertEquals(false, confirmed.copy(requestedControls = mapOf(id to byteArrayOf(0, 2, 1)))
            .legacyEndCallSinglePress())
        assertEquals(true, reported(byteArrayOf(0, 0)).copy(requestedControls = mapOf(id to byteArrayOf(0, 2, 0)))
            .legacyEndCallSinglePress())
        assertNull(reported(byteArrayOf(0, 0)).copy(requestedControls = mapOf(id to byteArrayOf(0, 2, 1)))
            .legacyEndCallSinglePress())
    }

    @Test fun legacyWritesRequireFreshConfirmedReadbackAndAReviewedPendingLayout() {
        assertFalse(AppleState().canSetLegacyEndCall())
        assertFalse(AppleState(liveControlReports = setOf(id)).canSetLegacyEndCall())
        for (value in listOf(byteArrayOf(0, 2), byteArrayOf(0, 3, 0, 0))) {
            assertTrue(reported(value).canSetLegacyEndCall())
            assertFalse(reported(value).copy(liveControlReports = emptySet()).canSetLegacyEndCall())
            assertFalse(AppleState(requestedControls = mapOf(id to value)).canSetLegacyEndCall())
            assertFalse(reported(value).copy(liveControlReports = emptySet(), requestedControls = mapOf(id to value))
                .canSetLegacyEndCall())
            assertTrue(reported(value).copy(requestedControls = mapOf(id to byteArrayOf(0, 2)))
                .canSetLegacyEndCall())
        }
        for (unsupported in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0),
            byteArrayOf(0x10, 3), byteArrayOf(1, 0x23, 2, 0), byteArrayOf(0, 2, 1),
            byteArrayOf(0, 3, 0, 1), byteArrayOf(0, 2, 0, 0, 0))) {
            assertFalse(reported(unsupported).canSetLegacyEndCall())
            assertFalse(AppleState(requestedControls = mapOf(id to unsupported)).canSetLegacyEndCall())
            assertFalse(reported(unsupported).copy(requestedControls = mapOf(id to byteArrayOf(0, 2)))
                .canSetLegacyEndCall())
            assertFalse(reported(byteArrayOf(0, 3)).copy(requestedControls = mapOf(id to unsupported))
                .canSetLegacyEndCall())
        }
    }

    @Test fun stableRoutesSurviveLocaleChangesAndOnlyCurrentLegacyLabelsAreAccepted() {
        assertEquals("mute_unmute", CallControlAction.MUTE_UNMUTE.route)
        assertEquals("hang_up", CallControlAction.HANG_UP.route)
        val mute = "Muet / Activer le son"
        val hangUp = "Raccrocher"
        assertEquals(CallControlAction.MUTE_UNMUTE, resolveCallControlAction("mute_unmute", mute, hangUp))
        assertEquals(CallControlAction.HANG_UP, resolveCallControlAction("hang_up", mute, hangUp))
        assertEquals(CallControlAction.MUTE_UNMUTE, resolveCallControlAction(mute, mute, hangUp))
        assertEquals(CallControlAction.HANG_UP, resolveCallControlAction(hangUp, mute, hangUp))
        for (unknown in listOf("", "Mute/Unmute", "Hang Up", "MUTE_UNMUTE", "mute_unmute ", "unknown")) {
            assertNull(unknown, resolveCallControlAction(unknown, mute, hangUp))
        }
    }
}
