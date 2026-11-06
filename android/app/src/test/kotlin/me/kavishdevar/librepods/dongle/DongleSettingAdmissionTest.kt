package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import org.junit.Assert.*
import org.junit.Test

class DongleSettingAdmissionTest {
    private val pro3 = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3064", version3 = "9442752")
    private val ready = DongleState(connected = true, metadata = pro3,
        status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 0, listOf(null, null, null), 0, 0x18))
    private fun received(id: Int, value: Long) = ready.copy(settings = mapOf(id to DongleProtocol.Setting(value, 0, 1)))

    @Test fun onlyReviewedExplicitChoicesCanStartWithoutReadback() {
        for (id in dongleSavedChoiceIds) assertTrue("id=$id", ready.canEditSetting(id))
        for (id in listOf(0x0d, 0x17, 0x18, 0x1b, 0x1f, 0x24, 0x25, 0x26, 0x28, 0x35))
            assertFalse("id=$id", ready.canEditSetting(id))
        assertFalse(ready.copy(metadata = AppleMetadata()).canEditSetting(0x01))
        assertFalse(ready.copy(status = ready.status!!.copy(capabilities = 0x10)).canEditSetting(0x01))
        assertFalse(ready.copy(status = ready.status!!.copy(flags = 19)).canEditSetting(0x01))
        assertFalse(ready.copy(connected = false).canEditSetting(0x01))
    }

    @Test fun rawReportsRemainReadOnlyWhenTheirSchemaIsNotAReviewedCommand() {
        assertFalse(received(0x24, 0x00022301).canEditSetting(0x24))
        assertFalse(received(0x24, 0x00022301).canWriteSetting(0x24, 0x0200))
        assertTrue(received(0x24, 0x0300).canWriteSetting(0x24, 0x0200))
        assertFalse(received(0x0a, 0x0102).canWriteSetting(0x0a, 2))
        val hold = received(0x16, 0x01050501)
        assertTrue(hold.canWriteSetting(0x16, 0x0505))
        assertEquals(0x0501L, currentDongleSetting(0x16, 0x01050501))
        assertFalse(hold.canWriteSetting(0x16, 0x01050501))
        assertFalse(received(0x23, 3).canEditSetting(0x23))
    }

    @Test fun adaptiveAndOffUseCurrentModelFirmwareAndReceivedPrerequisites() {
        val modes = received(0x0d, 3)
        assertTrue(modes.canWriteSetting(0x0d, 4))
        assertTrue(modes.canWriteSetting(0x1a, 14))
        assertFalse(modes.canWriteSetting(0x1a, 11))
        val savedOff = modes.copy(savedChoices = mapOf(0x34 to 1L))
        assertTrue(savedOff.canWriteSetting(0x1a, 11))
        assertFalse(savedOff.copy(settings = modes.settings + (0x34 to DongleProtocol.Setting(2, 0, 1))).canWriteSetting(0x1a, 11))
        assertFalse(modes.copy(metadata = pro3.copy(version3 = "", version1 = "5A377")).canWriteSetting(0x0d, 4))
        assertFalse(modes.copy(metadata = pro3.copy(version3 = "", version1 = "90.3431.6807")).canWriteSetting(0x1a, 14))
        val pro1 = pro3.copy(model = AirPodsModel.AIRPODS_PRO_1, modelNumber = "A2083", version1 = "6F21", version3 = "")
        assertFalse(modes.copy(metadata = pro1).canWriteSetting(0x0d, 4))
        assertFalse(modes.copy(metadata = pro1).canEditSetting(0x31))
        assertFalse(received(0x2e, 50).canWriteSetting(0x2e, 75))
        assertTrue(received(0x2e, 50).copy(settings = mapOf(0x2e to DongleProtocol.Setting(50, 0, 1),
            0x0d to DongleProtocol.Setting(4, 0, 1))).canWriteSetting(0x2e, 75))
    }

    @Test fun modelsWithoutListeningModesCanChooseAssistantButNeverNoiseManagement() {
        val airpods3 = ready.copy(metadata = AppleMetadata(model = AirPodsModel.AIRPODS_3,
            modelNumber = "A2565", version1 = "6F21"))
        assertTrue(airpods3.canWriteSetting(0x16, 0x0101))
        for (value in listOf(0x0505L, 0x0501L, 0x0105L)) assertFalse(airpods3.canWriteSetting(0x16, value))
    }
}
