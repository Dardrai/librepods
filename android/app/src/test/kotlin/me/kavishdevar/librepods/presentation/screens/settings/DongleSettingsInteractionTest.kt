package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DongleSettingsInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val profile = HearingAidSettings(FloatArray(8) { 10f + it }, FloatArray(8) { 20f + it },
        -0.25f, 0.5f, -1f, 1f, true, false, 0.2f, 0.8f, 0.125f, 0.75f, 0.3f)
    private val current = mutableStateOf(DongleState(connected = true,
        status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(91, 92, 70), 3, 0x1f)))
    private val audioRequests = mutableListOf<DongleAudioRequest>()
    private val hearingRequests = mutableListOf<(HearingAidSettings) -> HearingAidSettings>()

    @Test fun applying_without_edits_preserves_every_per_ear_value() {
        show(hearing = profile)
        applyHearing()
        assertEquals(profile, hearingRequests.single()(profile))
    }

    @Test fun changing_only_gain_preserves_asymmetric_tones_boosts_and_fitting() {
        show(hearing = profile)
        adjust(-1.5f..1.5f, 0.25f)
        applyHearing()
        assertEquals(profile.withAmplification(0.25f), hearingRequests.single()(profile))
    }

    @Test fun changing_only_balance_preserves_asymmetric_tones_boosts_and_fitting() {
        show(hearing = profile)
        adjust(-3f..3f, 0.5f)
        applyHearing()
        assertEquals(profile.withAmplification(balance = 0.5f), hearingRequests.single()(profile))
    }

    @Test fun explicit_tone_and_boost_edits_change_both_ears_without_recalculating_gain() {
        val original = profile.copy(leftAmplification = 0.000000001f, rightAmplification = 1.1f,
            netAmplification = 0.55f, balance = 1.1f)
        show(hearing = original)
        adjust(-1f..1f, 0.5f)
        compose.onNodeWithText("Conversation Boost").performScrollTo().performClick()
        applyHearing()
        assertEquals(original.copy(leftTone = 0.5f, rightTone = 0.5f,
            leftConversationBoost = true, rightConversationBoost = true), hearingRequests.single()(original))
    }

    @Test fun codec_selection_normalizes_automatic_sbc_and_aac_requests() {
        show(audio = audioProfile(3, 1, 256000))
        choose("Output codec", "AAC")
        assertEquals(DongleAudioRequest(2, 0, 0), audioRequests.last())
        compose.onNodeWithText("Encoding").performScrollTo().assertIsNotEnabled()
        for (codec in listOf(0, 1)) {
            compose.runOnIdle { current.value = current.value.copy(audio = audioProfile(3, 1, 256000)) }
            choose("Output codec", if (codec == 0) "Automatic" else "SBC")
            assertEquals(DongleAudioRequest(codec, 0, 0), audioRequests.last())
            compose.onNodeWithText("Encoding").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Bitrate or limit").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun aac_bitrate_picker_offers_automatic_and_96k_but_never_256k() {
        show(audio = audioProfile(2, 0, 0))
        open("Bitrate or limit")
        for (choice in listOf("Automatic", "96 kbps", "128 kbps", "174 kbps", "192 kbps", "220 kbps"))
            dialogChoice(choice).assertExists()
        dialogChoice("256 kbps").assertDoesNotExist()
        dialogChoice("96 kbps").performClick()
        assertEquals(DongleAudioRequest(2, 0, 96000), audioRequests.last())
        choose("Bitrate or limit", "Automatic")
        assertEquals(DongleAudioRequest(2, 0, 0), audioRequests.last())
    }

    @Test fun eld_encoding_and_bitrate_handlers_use_the_supported_requests() {
        show(audio = audioProfile(3, 0, 256000))
        open("Encoding")
        dialogChoice("Constant bitrate").assertExists()
        dialogChoice("Variable bitrate").performClick()
        assertEquals(DongleAudioRequest(3, 1, 256000), audioRequests.last())
        open("Bitrate or limit")
        for (choice in listOf("Automatic", "128 kbps", "174 kbps", "192 kbps", "220 kbps", "256 kbps"))
            dialogChoice(choice).assertExists()
        dialogChoice("96 kbps").assertDoesNotExist()
        dialogChoice("220 kbps").performClick()
        assertEquals(DongleAudioRequest(3, 1, 220000), audioRequests.last())
    }

    private fun show(hearing: HearingAidSettings? = null, audio: DongleProtocol.AudioProfile? = null) {
        current.value = current.value.copy(hearingProfile = hearing, audio = audio)
        val controller = mock(DongleController::class.java) { call ->
            when (call.method.name) {
                "audioProfile" -> {
                    val request = DongleAudioRequest(call.getArgument(0), call.getArgument(1), call.getArgument(2))
                    audioRequests += request
                    current.value = current.value.copy(audio = audioProfile(request.codec, request.rateMode, request.bitrate))
                    null
                }
                "updateHearing" -> { hearingRequests += call.getArgument<(HearingAidSettings) -> HearingAidSettings>(0); null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DongleControls(current.value, controller) }
            }
        }
    }

    private fun audioProfile(codec: Int, rateMode: Int, bitrate: Long) =
        DongleProtocol.AudioProfile(codec, codec, rateMode, bitrate, bitrate, false, 0, 0, 8, null)

    private fun adjust(range: ClosedFloatingPointRange<Float>, value: Float) {
        compose.onNode(SemanticsMatcher("slider with range $range") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == range
        }).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun applyHearing() = compose.onNodeWithText("Apply adjustments").performScrollTo().performClick()
    private fun open(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
    private fun dialogChoice(choice: String) = compose.onNode(hasText(choice) and hasAnyAncestor(isDialog()))
    private fun choose(label: String, choice: String) { open(label); dialogChoice(choice).performClick() }
}
