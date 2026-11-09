package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test

class QuickSettingsRecoveryTest {
    private val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063")
    private fun values(state: AppleState) = nativeQuickSettings(metadata, state, AppleSettings(), null,
        loudSoundWritable = false, profileAvailable = false).associateBy { it.setting }

    @Test fun uncertainClinicalModesOfferOnlyTheirExistingRecoveryOffWithoutClaimingAnOnStateOrRequiringAFitting() {
        for (mode in HearingMode.entries) for (active in listOf(false, true)) {
            val state = AppleState(hearingModes = modes(mode, active).withRequestState(null, mode))
            val key = if (mode == HearingMode.HEARING_AID) QuickSetting.HEARING_AID else QuickSetting.MEDIA_ASSIST
            val control = values(state).getValue(key)
            assertTrue(state.hearingAidData.isEmpty())
            assertNull(control.checked)
            assertTrue(control.recoveryOff)
            assertTrue(control.enabled)
            val other = values(state).getValue(if (key == QuickSetting.HEARING_AID) QuickSetting.MEDIA_ASSIST else QuickSetting.HEARING_AID)
            assertFalse(other.recoveryOff)
            assertFalse(other.enabled)

            val previous = ListeningModeSnapshot(metadata, AppleState(), AppleSettings(),
                listOf(QuickSettingState(key, true, true, true)))
            val current = ListeningModeSnapshot(metadata, state, AppleSettings(), values(state).values.toList())
            val kept = retainQuickPresentation(previous, current, ownedRefresh = true)
            assertSame(state, kept.state)
            assertNull(kept.quickSettings.first { it.setting == key }.checked)
            assertTrue(kept.quickSettings.first { it.setting == key }.recoveryOff)
        }
    }

    @Test fun knownOffAndUnknownClinicalStatesNeverGainAnOnControlWithoutProof() {
        for (mode in HearingMode.entries) {
            val key = if (mode == HearingMode.HEARING_AID) QuickSetting.HEARING_AID else QuickSetting.MEDIA_ASSIST
            val off = values(AppleState(hearingModes = modes(mode, false))).getValue(key)
            assertEquals(false, off.checked)
            assertFalse(off.enabled)
            assertFalse(off.recoveryOff)
            val unknown = values(AppleState()).getValue(key)
            assertNull(unknown.checked)
            assertFalse(unknown.enabled)
            assertFalse(unknown.recoveryOff)
            val unrecoverable = values(AppleState(hearingModes = HearingModes.empty().withRequestState(null, mode))).getValue(key)
            assertNull(unrecoverable.checked)
            assertTrue(unrecoverable.recoveryOff)
            assertFalse(unrecoverable.enabled)
        }
    }

    @Test fun retainedClinicalPresentationKeepsCurrentProofAndStopsClaimingAnActiveReadWhenLoadingHasEnded() {
        val key = QuickSetting.LOUD_SOUND_REDUCTION
        val previous = ListeningModeSnapshot(metadata, AppleState(), AppleSettings(),
            listOf(QuickSettingState(key, true, true, true)))
        val state = AppleState(loudSoundReductionReadStatus = SettingsReadStatus.UNAVAILABLE)
        val current = ListeningModeSnapshot(metadata, state, AppleSettings(),
            listOf(QuickSettingState(key, null, true, false)))
        for (reading in listOf(false, true)) {
            val result = retainQuickPresentation(previous, current, ownedRefresh = true, reading = reading)
            assertSame(current.state, result.state)
            assertSame(current.settings, result.settings)
            assertSame(current.metadata, result.metadata)
            assertTrue(result.state.capabilities.isEmpty())
            assertEquals(SettingsReadStatus.UNAVAILABLE, result.state.loudSoundReductionReadStatus)
            assertEquals(true, result.quickSettings.single().checked)
            assertTrue(result.quickSettings.single().retained)
            assertEquals(reading, result.quickSettings.single().reading)
            assertFalse(result.quickSettings.single().enabled)
            assertFalse(result.quickSettings.single().requested)
        }
        assertSame(current, retainQuickPresentation(previous, current, ownedRefresh = false, reading = false))
    }

    private fun modes(mode: HearingMode, active: Boolean): HearingModes = HearingModes.empty()
        .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, if (active && mode == HearingMode.HEARING_AID) 1 else 2, 0, 0))
        .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, if (active) 1 else 2, 0, 0, 0))
        .withReport(ByteArray(140).also { data ->
            val media = if (active && mode == HearingMode.MEDIA_ASSIST) 1.toByte() else 2.toByte()
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, media, media).copyInto(data)
        })
}
