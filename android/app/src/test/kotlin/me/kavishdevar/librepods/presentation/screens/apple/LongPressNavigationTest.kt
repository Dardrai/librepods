package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "fr-rFR-w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class LongPressNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val uiState = AppleUiState(
        metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348"),
        settings = AppleSettings(leftLongPressAction = StemAction.DIGITAL_ASSISTANT,
            rightLongPressAction = StemAction.CYCLE_NOISE_CONTROL_MODES),
        state = AppleState(controlStates = mapOf(ControlCommandIdentifier.ALLOW_OFF_OPTION to byteArrayOf(1),
            ControlCommandIdentifier.LISTENING_MODE_CONFIGS to byteArrayOf(11))))

    @Test fun stableSideRoutesSelectAndEditTheCorrectBudInFrench() = exerciseSides("left", "right")

    @Test fun currentFrenchLegacyRoutesKeepTheirOriginalBud() {
        assertEquals("Gauche", context.getString(R.string.left))
        assertEquals("Droite", context.getString(R.string.right))
        exerciseSides("Gauche", "Droite")
    }

    private fun exerciseSides(leftRoute: String, rightRoute: String) {
        val side = mutableStateOf(leftRoute)
        val writes = mutableListOf<Pair<String, StemAction>>()
        val cycles = mutableListOf<Int>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            LongPressSettings(uiState, side.value, true, false, null,
                { bud, action -> writes += bud to action }, { mask, _ -> cycles += mask })
        } }
        val assistant = hasText(context.getString(R.string.digital_assistant)) and hasClickAction()
        val noise = hasText(context.getString(R.string.noise_control)) and hasClickAction()
        compose.onNodeWithText("Gauche").assertIsDisplayed()
        compose.onNode(assistant).assertIsSelected()
        compose.onNode(noise).assertIsNotSelected().performClick()
        assertEquals(listOf("left" to StemAction.CYCLE_NOISE_CONTROL_MODES), writes)
        compose.runOnIdle { side.value = rightRoute }
        compose.onNodeWithText("Droite").assertIsDisplayed()
        compose.onNode(noise).assertIsSelected()
        compose.onNode(assistant).assertIsNotSelected().performClick()
        assertEquals(listOf("left" to StemAction.CYCLE_NOISE_CONTROL_MODES,
            "right" to StemAction.DIGITAL_ASSISTANT), writes)
        assertTrue("Opening or changing side must not write the shared cycle", cycles.isEmpty())
    }

    @Test fun staleEnglishSideCannotSilentlySelectOrEditTheRightBudAfterLocaleChange() {
        val writes = mutableListOf<Pair<String, StemAction>>()
        val cycles = mutableListOf<Int>()
        compose.setContent { LibrePodsTheme(designSystem = DesignSystem.Apple) {
            LongPressSettings(uiState, "Left", true, false, null,
                { bud, action -> writes += bud to action }, { mask, _ -> cycles += mask })
        } }
        compose.onNodeWithText(context.getString(R.string.press_and_hold)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.digital_assistant)).assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.noise_control)).assertIsNotSelected().assertIsNotEnabled()
        compose.onAllNodesWithText(context.getString(R.string.noise_cycle_apply)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.noise_cycle_shared_description)).assertCountEquals(0)
        assertTrue(writes.isEmpty())
        assertTrue(cycles.isEmpty())
    }
}
