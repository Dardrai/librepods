/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
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
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSpatialAudioSettingTest {
    @get:Rule val compose = createComposeRule()

    private fun report() = Bundle().apply {
        putInt("version", 1); putBoolean("known", true); putBoolean("enabled", true)
        putBoolean("device_enabled", true); putBoolean("head_tracker_enabled", false)
        putBoolean("route_selected", true); putBoolean("fixed_supported", true); putString("reason", "ok")
    }

    @Test fun incompleteOrWrongVersionReportsCannotAuthorizeAChangeOrInventAnOffState() {
        for (key in listOf("known", "enabled", "device_enabled", "head_tracker_enabled")) {
            val value = report().apply { remove(key) }
            val state = FixedSpatialState.read(value)
            assertNull(key, state.checked)
            assertFalse(key, state.writable)
        }
        assertNull(FixedSpatialState.read(report().apply { putInt("version", 2) }).checked)
        assertFalse(FixedSpatialState.read(report().apply { remove("fixed_supported") }).writable)
        assertFalse(FixedSpatialState.read(report().apply { putBoolean("route_selected", false) }).writable)
        assertEquals(false, FixedSpatialState.read(report().apply { putBoolean("head_tracker_enabled", true) }).checked)
    }

    @Test fun renderingDoesNotChangeAnythingAndTheLiveToggleCallsOnlyItsWriter() {
        val writes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { SpatialAudioSettingContent(FixedSpatialState.read(report()), true, true, change = writes::add) }
            }
        }
        assertTrue(writes.isEmpty())
        compose.onNode(isToggleable()).assertIsOn().performClick()
        assertEquals(listOf(false), writes)
    }

    @Test fun rootIsRequestedOnlyFromTheExplicitFeatureAction() {
        var roots = 0
        val writes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { SpatialAudioSettingContent(null, true, false, requestRoot = { roots++ }, change = writes::add) }
            }
        }
        assertEquals(0, roots)
        compose.onNodeWithText("Fixed Spatial Audio").performClick()
        assertEquals(1, roots)
        assertTrue(writes.isEmpty())
    }

    @Test fun pendingChoiceStaysVisibleWhileDuplicateChangesAreDisabled() {
        var writes = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { SpatialAudioSettingContent(FixedSpatialState.read(report()), true, true,
                    busy = true, requested = false, change = { writes++ }) }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        assertEquals(0, writes)
    }
}
