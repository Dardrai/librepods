package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import me.kavishdevar.librepods.R
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.data.LiveRecordEditor
import me.kavishdevar.librepods.data.LiveRecordSource
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource
import me.kavishdevar.librepods.dongle.DongleController
import org.mockito.Mockito.mock
import org.mockito.Answers
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.dongle.appleUiState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DongleAppleSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val metadata = AppleMetadata(name = "AirPods Pro", model = AirPodsModel.AIRPODS_PRO_3,
        modelNumber = "A3063", version3 = "9442752")

    @Test fun projectedDongleMetadataShowsAdaptiveInTheNormalPressAndHoldChoices() {
        val ui = DongleState(metadata = metadata, savedChoices = mapOf(0x16 to 0x0505L, 0x1a to 10L)).appleUiState()
        val cycles = mutableListOf<Int>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            LongPressSettings(ui, "left", true, false, null, { _, _ -> }, { mask, _ -> cycles += mask })
        } }
        compose.onNodeWithText("Adaptive").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Transparency").performScrollTo().performClick()
        assertEquals(listOf(14), cycles)
    }

    @Test fun normalAccessibilityUsesZeroBasedWireDurationsAndDoesNotWriteOnEntry() {
        val ui = DongleState(metadata = metadata).appleUiState()
        val writes = mutableListOf<Pair<ControlCommandIdentifier, Int>>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            AccessibilitySettingsContent(ui, null, {}, { id, value -> writes += id to value }, { _, _ -> }, dongle = true)
        } }
        assertTrue(writes.isEmpty())
        compose.onNodeWithText("Longer").performScrollTo().performClick()
        compose.onNodeWithText("Longest").performScrollTo().performClick()
        assertEquals(listOf(ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL to 1,
            ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL to 2), writes)
    }



    @Test fun microphoneOwnershipDisablesHearingChangesWithoutCallingThemDisconnected() {
        val modes = HearingModes.empty()
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 1, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0))
            .withReport(ByteArray(140).also { packet ->
                byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 2, 2).copyInto(packet)
            })
        assertTrue(modes.canChange(me.kavishdevar.librepods.core.devices.HearingMode.HEARING_AID, false))
        val ui = DongleState(metadata = metadata, hearingModes = modes).appleUiState()
        val writes = mutableListOf<Boolean>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            HearingAidContent(ui, connected = true, transportAvailable = true, navigateBack = null,
                navigateToHearingAidAdjustments = {}, showHearingTest = false, writable = false,
                setHearingMode = { _, enabled -> writes += enabled })
        } }
        val label = org.robolectric.RuntimeEnvironment.getApplication().getString(me.kavishdevar.librepods.R.string.hearing_aid)
        compose.onNode(hasText(label) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(
            me.kavishdevar.librepods.R.string.settings_connect_airpods)).assertDoesNotExist()
        assertTrue(writes.isEmpty())
    }

    @Test fun leavingTransparencyRestartsTheSingleVisibleControlsObserver() {
        val page = mutableStateOf("transparency")
        val visibility = mutableListOf<Boolean>()
        val flow = MutableStateFlow(DongleState(connected = true, metadata = metadata,
            status = DongleProtocol.Status(1, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(90, 90, 70), 3, 0x38)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = LiveRecordEditor(scope, { LiveRecordSource(null, byteArrayOf(), false) },
                valid = { false }, matches = { _, _ -> false }, write = { _, _, _ -> false })
            val controller = mock(DongleController::class.java) { call -> when (call.method.name) {
                "getState" -> flow
                "liveTransparencyEditor" -> editor
                "controlsVisible" -> { visibility += call.getArgument<Boolean>(0); null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            } }
            compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
                DongleAppleSettings(page.value, "", null, { _, _ -> }, {}, controller)
            } }
            compose.waitForIdle()
            assertEquals(listOf(true), visibility.filterIndexed { index, value -> index == 0 || visibility[index - 1] != value })
            compose.runOnIdle { page.value = "version" }
            compose.waitForIdle()
            assertEquals(listOf(true, false, true), visibility.filterIndexed { index, value -> index == 0 || visibility[index - 1] != value })
        } finally { scope.cancel() }
    }


    @Test fun holdSideEditsUseTheCurrentEditorRecordInsteadOfAnOlderRenderedPair() {
        var raw = 0x0501L
        val writes = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val flow = MutableStateFlow(DongleState(connected = true, metadata = metadata,
            status = DongleProtocol.Status(1, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(90, 90, 70), 3, 0x38),
            settings = mapOf(0x16 to DongleProtocol.Setting(raw, 0, 1))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hold = LiveValueEditor(scope, { LiveValueSource("pair", raw, true) },
                valid = { true }, write = { _, value -> writes += value; true })
            val cycle = LiveValueEditor(scope, { LiveValueSource("cycle", 10L, true) },
                valid = { true }, write = { _, _ -> true })
            val controller = mock(DongleController::class.java) { call -> when (call.method.name) {
                "getState" -> flow
                "liveSettingEditor" -> if (call.getArgument<Int>(0) == 0x16) hold else cycle
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            } }
            compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
                DongleAppleSettings("long_press", "left", null, { _, _ -> }, {}, controller)
            } }
            compose.runOnIdle { raw = 0x0105L; hold.observe() }
            val noise = org.robolectric.RuntimeEnvironment.getApplication().getString(me.kavishdevar.librepods.R.string.noise_control)
            compose.onNode(hasText(noise) and hasClickAction()).performClick()
            compose.waitUntil(3_000) { writes.isNotEmpty() }
            assertEquals(listOf(0x0505L), writes.toList())
        } finally { scope.cancel() }
    }


    @Test fun initialHoldPairWritesOnlyAfterBothSidesAreExplicitlyChosen() {
        val writes = mutableListOf<Long>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            InitialDongleHoldActions("pair", 0, true) { writes += it; true }
        } }
        assertTrue(writes.isEmpty())
        initialChoice("left", R.string.noise_control).performClick()
        assertTrue(writes.isEmpty())
        initialChoice("right", R.string.digital_assistant).performClick()
        assertEquals(listOf(0x0501L), writes)
        initialChoice("left", R.string.digital_assistant).assertIsNotEnabled()
        assertEquals(1, writes.size)
    }

    @Test fun initialHoldDraftResetsOnRevisionOwnerAndLeavingThePage() {
        val owner = mutableStateOf("first")
        val revision = mutableStateOf(0L)
        val visible = mutableStateOf(true)
        val writes = mutableListOf<Long>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            Column {
                if (visible.value) InitialDongleHoldActions(owner.value, revision.value, true) { writes += it; true }
            }
        } }
        initialChoice("left", R.string.noise_control).performClick()
        compose.runOnIdle { revision.value++ }
        initialChoice("right", R.string.digital_assistant).performClick()
        assertTrue(writes.isEmpty())
        compose.runOnIdle { owner.value = "second" }
        initialChoice("left", R.string.digital_assistant).performClick()
        assertTrue(writes.isEmpty())
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        initialChoice("right", R.string.noise_control).performClick()
        assertTrue(writes.isEmpty())
        initialChoice("left", R.string.digital_assistant).performClick()
        assertEquals(listOf(0x0105L), writes)
    }


    @Test fun aReceivedUnsupportedHoldPairCannotBeOverwrittenByInitialization() {
        val writes = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val flow = MutableStateFlow(DongleState(connected = true, metadata = metadata,
            status = DongleProtocol.Status(1, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(90, 90, 70), 3, 0x38),
            settings = mapOf(0x16 to DongleProtocol.Setting(0x0203, 0, 1))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hold = LiveValueEditor(scope, { LiveValueSource("pair", 0x0203L, false) },
                valid = { true }, write = { _, value -> writes += value; true })
            val cycle = LiveValueEditor(scope, { LiveValueSource("cycle", 10L, true) },
                valid = { true }, write = { _, _ -> true })
            val controller = mock(DongleController::class.java) { call -> when (call.method.name) {
                "getState" -> flow
                "liveSettingEditor" -> if (call.getArgument<Int>(0) == 0x16) hold else cycle
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            } }
            compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
                DongleAppleSettings("long_press", "left", null, { _, _ -> }, {}, controller)
            } }
            initialChoice("left", R.string.noise_control).assertIsNotEnabled().assertIsNotSelected()
            initialChoice("right", R.string.digital_assistant).assertIsNotEnabled().assertIsNotSelected()
            assertTrue(writes.isEmpty())
        } finally { scope.cancel() }
    }


    @Test fun dongleAssistantIsReadOnlyForExistingAndInitialHoldAssignments() {
        var raw: Long? = 0x0501L
        val writes = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val flow = MutableStateFlow(DongleState(connected = true, metadata = metadata,
            status = DongleProtocol.Status(1, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(90, 90, 70), 3, 0x38),
            settings = mapOf(0x16 to DongleProtocol.Setting(0x0501, 0, 1))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hold = LiveValueEditor(scope, { LiveValueSource("pair", raw, true) },
                valid = { true }, write = { _, value -> writes += value; true })
            val cycle = LiveValueEditor(scope, { LiveValueSource("cycle", 10L, true) },
                valid = { true }, write = { _, _ -> true })
            val controller = mock(DongleController::class.java) { call -> when (call.method.name) {
                "getState" -> flow
                "liveSettingEditor" -> if (call.getArgument<Int>(0) == 0x16) hold else cycle
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            } }
            compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
                DongleAppleSettings("long_press", "right", null, { _, _ -> }, {}, controller)
            } }
            val assistant = org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.digital_assistant)
            compose.onNode(hasText(assistant) and hasClickAction()).assertIsSelected().assertIsNotEnabled()
            assertTrue(writes.isEmpty())
            compose.runOnIdle {
                raw = null
                hold.observe()
                flow.value = flow.value.copy(settings = emptyMap())
            }
            initialChoice("left", R.string.digital_assistant).assertIsNotEnabled().assertIsNotSelected()
            initialChoice("right", R.string.digital_assistant).assertIsNotEnabled().assertIsNotSelected()
            assertTrue(writes.isEmpty())
            initialChoice("left", R.string.noise_control).performScrollTo().performClick()
            assertTrue(writes.isEmpty())
            initialChoice("right", R.string.noise_control).performScrollTo().performClick()
            compose.waitUntil(3_000) { writes.isNotEmpty() }
            assertEquals(listOf(0x0505L), writes.toList())
        } finally { scope.cancel() }
    }

    @Test fun nativeLongPressKeepsAssistantAvailableByDefault() {
        val ui = DongleState(metadata = metadata, savedChoices = mapOf(0x16 to 0x0505L)).appleUiState()
        val actions = mutableListOf<StemAction>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            LongPressSettings(ui, "left", true, false, null, { _, action -> actions += action }, { _, _ -> })
        } }
        val assistant = org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.digital_assistant)
        compose.onNode(hasText(assistant) and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(listOf(StemAction.DIGITAL_ASSISTANT), actions)
    }

    private fun initialChoice(side: String, label: Int) = compose.onNode(
        hasText(org.robolectric.RuntimeEnvironment.getApplication().getString(label)) and hasAnyAncestor(hasTestTag("initial-hold-$side")))

    @Test fun pairedHoldEditsPreserveTheOtherBudAndDiscardHistoricalActionBytes() {
        assertEquals(0x0101L, dongleHoldActionValue(0x01050501, "left", StemAction.DIGITAL_ASSISTANT))
        assertEquals(0x0505L, dongleHoldActionValue(0x01050501, "right", StemAction.CYCLE_NOISE_CONTROL_MODES))
        assertNull(dongleHoldActionValue(null, "left", StemAction.DIGITAL_ASSISTANT))
        assertNull(dongleHoldActionValue(0x0500, "left", StemAction.DIGITAL_ASSISTANT))
    }
}
