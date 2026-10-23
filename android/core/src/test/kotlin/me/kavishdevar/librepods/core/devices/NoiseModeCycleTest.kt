package me.kavishdevar.librepods.core.devices

import org.junit.Assert.*
import org.junit.Test

class NoiseModeCycleTest {
    private val pro3 = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")

    @Test fun everyTwoOrMoreModeSubsetIsAllowedWithoutAddingAnUnselectedMode() {
        val all = pro3.supportedNoiseModeCycleMask(true)
        assertEquals(15, all)
        for (mask in 0..31) assertEquals("mask=$mask", mask in 1..15 && mask.countOneBits() >= 2,
            validNoiseModeCycle(mask, all))
        assertTrue(validNoiseModeCycle(0x0b, all)) // Off + ANC + Adaptive, without Transparency.
        assertEquals(14, pro3.supportedNoiseModeCycleMask(false))
        assertFalse(validNoiseModeCycle(0x0b, pro3.supportedNoiseModeCycleMask(null)))
    }

    @Test fun modelFirmwareAndOffPrerequisiteRestrictOnlySupportedModes() {
        assertEquals(0, AppleMetadata().supportedNoiseModeCycleMask(true))
        assertEquals(0, AppleMetadata(model = AirPodsModel.AIRPODS_3).supportedNoiseModeCycleMask(true))
        val pro1 = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_1, modelNumber = "A2084", version1 = "6A326")
        assertEquals(7, pro1.supportedNoiseModeCycleMask(null))
        assertFalse(validNoiseModeCycle(0x0a, pro1.supportedNoiseModeCycleMask(true)))
        val pro2 = pro3.copy(model = AirPodsModel.AIRPODS_PRO_2_USBC, modelNumber = "A3047", version1 = "5A377")
        assertEquals(7, pro2.supportedNoiseModeCycleMask(null))
        assertEquals(15, pro2.copy(version1 = "6A300").supportedNoiseModeCycleMask(null))
        assertEquals(14, pro2.copy(version1 = "8A300").supportedNoiseModeCycleMask(null))
    }

    @Test fun unknownPartialAndMalformedReportsNeverBecomeAnEditableBaseline() {
        for (raw in listOf(null, byteArrayOf(), byteArrayOf(0), byteArrayOf(1), byteArrayOf(2),
            byteArrayOf(16), byteArrayOf(-1), byteArrayOf(11, 1), byteArrayOf(11, 0, 0, 0, 0)))
            assertNull(noiseModeCycleValue(raw))
        assertEquals(11, noiseModeCycleValue(byteArrayOf(11, 0, 0, 0)))
    }
}
