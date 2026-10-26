/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DongleGameModeControlsTest {
    @get:Rule val compose = createComposeRule()
    private val current = mutableStateOf(DongleState(connected = true, controlSession = 7,
        status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(90, 90, 70), 3, 0x1f)))
    private val writes = CopyOnWriteArrayList<Boolean>()
    private lateinit var fixture: DongleControlTestEditors
    @After fun close() { if (::fixture.isInitialized) fixture.close() }

    private fun show(requested: Boolean, supported: Boolean, design: DesignSystem = DesignSystem.Material) {
        current.value = current.value.copy(audio = DongleProtocol.AudioProfile(3, 3, 0, 256000, 256000,
            requested, if (supported) 1 else 5, 0, if (supported) 8 else 0, null))
        fixture = DongleControlTestEditors(current, onGame = { writes += it })
        compose.setContent { LibrePodsTheme(designSystem = design) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                DongleControls(current.value, fixture.controller)
            }
        } }
        game().performScrollTo()
    }
    private fun game() = compose.onNodeWithText("Game Mode")
    private fun queued() = fixture.controller.liveGameModeEditor().state.value.requested
    private fun oldClick(): () -> Boolean = checkNotNull(game().fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    @Test fun requestedOnCanBeTurnedOffAfterEligibilityIsLostButCannotBeEnabledAgain() {
        show(requested = true, supported = false)
        game().assertIsEnabled().performClick()
        compose.waitUntil(3_000) { writes == listOf(false) && current.value.audio?.gameRequested == false }
        game().assertIsNotEnabled()
        assertEquals(listOf(false), writes)
    }

    @Test fun unsupportedOffStaysDisabledAndHydrationSendsNothing() {
        show(requested = false, supported = false, design = DesignSystem.Apple)
        game().assertIsNotEnabled()
        assertTrue(writes.isEmpty())
        assertNull(queued())
    }

    @Test fun enablingPressCannotAffectAReplacementPeerAfterItsRelease() {
        show(requested = false, supported = true)
        game().performTouchInput { down(center) }
        compose.runOnIdle { current.value = current.value.copy(status = current.value.status!!.copy(
            generation = 4, address = listOf(6, 5, 4, 3, 2, 1))) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        assertNull(queued())
        assertTrue(writes.isEmpty())
        assertFalse(current.value.audio!!.gameRequested)
    }

    @Test fun recoveryOffPressCannotAffectANewControlSessionAfterItsRelease() {
        show(requested = true, supported = false)
        game().performTouchInput { down(center) }
        compose.runOnIdle { current.value = current.value.copy(controlSession = 8) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        assertNull(queued())
        assertTrue(writes.isEmpty())
        assertTrue(current.value.audio!!.gameRequested)
    }

    @Test fun anOldSupportedProfileCannotEnableAfterTheSupportFlagClears() {
        show(requested = false, supported = true)
        val click = oldClick()
        compose.runOnIdle { current.value = current.value.copy(audio = current.value.audio!!.copy(flags = 0, gameState = 5)) }
        compose.waitForIdle()
        game().assertIsNotEnabled()
        compose.runOnIdle { click() }
        assertNull(queued())
        assertTrue(writes.isEmpty())
        assertFalse(current.value.audio!!.gameRequested)
    }

    @Test fun microphoneAndDisconnectedStatesStillBlockRecoveryWrites() {
        show(requested = true, supported = false)
        compose.runOnIdle { current.value = current.value.copy(status = current.value.status!!.copy(flags = 3 or 16)) }
        game().assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(connected = false, status = current.value.status!!.copy(flags = 3)) }
        game().assertIsNotEnabled()
        assertTrue(writes.isEmpty())
        assertNull(queued())
    }
}
