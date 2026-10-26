/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class HearingModeRowsTest {
    @get:Rule val compose = createComposeRule()
    private val modes = mutableStateOf(reports().withRequestState(null, HearingMode.HEARING_AID))
    private val connected = mutableStateOf(true)
    private val disabled = mutableListOf<HearingMode>()
    private val enabled = mutableListOf<HearingMode>()

    @Test fun uncertainModeOffersOneGuardedOffActionWithoutRenderingUnknownAsOff() {
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList(key = modes.value to connected.value) {
                    HearingModeRows(modes.value, connected.value, true, enabled::add, disabled::add)
                }
            }
        }
        compose.onAllNodesWithText("Turn off Hearing Aid").assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
        compose.onNodeWithText("Turn off Hearing Aid").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(HearingMode.HEARING_AID), disabled); assertEquals(emptyList<HearingMode>(), enabled); connected.value = false }
        compose.onNodeWithText("Turn off Hearing Aid").assertIsNotEnabled()
        compose.runOnIdle { modes.value = reports(); connected.value = true }
        compose.waitForIdle()
        compose.onAllNodesWithText("Turn off Hearing Aid").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(2)
        compose.onNodeWithText("Hearing Aid").performClick()
        compose.runOnIdle { assertEquals(listOf(HearingMode.HEARING_AID, HearingMode.HEARING_AID), disabled) }
    }

    companion object {
        private fun reports(): HearingModes = HearingModes.empty()
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 1, 0, 0))
            .withReport(byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0))
            .withReport(ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 2, 2).copyInto(it) })
    }
}
