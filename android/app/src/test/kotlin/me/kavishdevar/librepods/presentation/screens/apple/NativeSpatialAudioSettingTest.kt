/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSpatialAudioSettingTest {
    @get:Rule val compose = createComposeRule()

    private fun report(enabled: Boolean = true) = Bundle().apply {
        putInt("version", 1); putBoolean("known", true); putBoolean("enabled", true)
        putBoolean("device_enabled", enabled); putBoolean("head_tracker_enabled", false)
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

    @Test fun anOffDeviceCanBeEnabledEvenWhenAndroidsCurrentEngineIsOff() {
        val state = FixedSpatialState.read(report(false).apply { putBoolean("enabled", false) })
        assertEquals(false, state.checked)
        assertTrue(state.writable)
        assertEquals("ok", state.reason)
        val disabled = FixedSpatialState.read(report(true).apply {
            putBoolean("enabled", false)
            putBoolean("fixed_supported", false)
            putString("reason", "global_disabled")
        })
        assertFalse(disabled.writable)
        assertEquals("global_disabled", disabled.reason)
    }

    @Test fun renderingDoesNotChangeAnythingAndTheLiveToggleCallsOnlyItsWriter() {
        val writes = mutableListOf<Boolean>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                StyledList { item { index, count ->
                    SpatialAudioSettingContent(FixedSpatialState.read(report()), true, true, change = writes::add,
                        index = index, count = count)
                } }
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
                StyledList { item { index, count ->
                    SpatialAudioSettingContent(null, true, false, requestRoot = { roots++ }, change = writes::add,
                        index = index, count = count)
                } }
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
                StyledList { item { index, count ->
                    SpatialAudioSettingContent(FixedSpatialState.read(report()), true, true,
                        busy = true, requested = false, change = { writes++ }, index = index, count = count)
                } }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        assertEquals(0, writes)
    }

    @Test fun aConnectedRootBridgeRefreshesItsRenderedRowAndReadbackWithoutDuplicatingSiblings() {
        val viewModel = mock(AppleViewModel::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(viewModel.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
        `when`(viewModel.uiState).thenReturn(MutableStateFlow(AppleUiState(state = AppleState(controlSession = 1))))
        `when`(viewModel.captureSpatialAudioOwner()).thenReturn(socket)
        `when`(viewModel.ownsSpatialAudio(socket)).thenReturn(true)
        `when`(viewModel.spatialAudioAddress).thenReturn(address)
        val bridge = MutableStateFlow(BridgeState())
        val enabled = AtomicBoolean(true)
        val writes = mutableListOf<Boolean>()
        mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
            `when`(client.state).thenReturn(bridge)
            `when`(client.hasRootPrivileges).thenReturn(true)
            runBlocking {
                `when`(client.inspectSpatialAudio(eq(address) ?: address, anyCurrent())).thenAnswer { report(enabled.get()) }
                `when`(client.setFixedSpatialEnabled(eq(address) ?: address, anyBoolean(), anyCurrent())).thenAnswer {
                    val requested = it.getArgument<Boolean>(1)
                    writes += requested
                    enabled.set(requested)
                    report(requested).apply { putBoolean("applied", true) }
                }
            }
        }.use { clients ->
            compose.setContent {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    StyledList {
                        styledListItem(contentText = "Before spatial", onClick = null)
                        NativeSpatialAudioSetting(viewModel)
                        styledListItem(contentText = "After spatial", onClick = null)
                    }
                }
            }
            compose.onNodeWithText("Allow root access to control spatial audio on this phone.").assertIsDisplayed()
            compose.onAllNodes(isToggleable()).assertCountEquals(0)
            val client = clients.constructed().single()
            verify(client, never()).requestPermission()
            compose.runOnIdle { bridge.value = BridgeState(connected = true, backend = "root") }
            awaitSpatialToggle(true)
            compose.onNode(isToggleable()).assertIsOn().assertIsEnabled()
            compose.onNodeWithText("Allow root access to control spatial audio on this phone.").assertDoesNotExist()
            assertSingleSpatialRows()
            compose.onNode(isToggleable()).performClick()
            awaitSpatialToggle(false)
            compose.onNode(isToggleable()).assertIsOff().assertIsEnabled()
            assertSingleSpatialRows()
            compose.onNode(isToggleable()).performClick()
            awaitSpatialToggle(true)
            compose.onNode(isToggleable()).assertIsOn().assertIsEnabled()
            assertEquals(listOf(false, true), writes)
            assertSingleSpatialRows()
            runBlocking { verify(client, times(1)).inspectSpatialAudio(eq(address) ?: address, anyCurrent()) }
            compose.runOnIdle { bridge.value = BridgeState() }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Allow root access to control spatial audio on this phone.").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Allow root access to control spatial audio on this phone.").assertIsDisplayed()
            compose.runOnIdle { bridge.value = BridgeState(connected = true, backend = "root") }
            awaitSpatialToggle(true)
            compose.onNode(isToggleable()).assertIsOn().assertIsEnabled()
            assertSingleSpatialRows()
            assertEquals(listOf(false, true), writes)
            verify(client, never()).requestPermission()
        }
    }

    @Test fun theRootExplanationStacksBelowItsTitleAt320dpWithLargerText() {
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                LibrePodsTheme(designSystem = DesignSystem.Apple) {
                    Box(Modifier.width(320.dp).testTag("spatial-rail")) {
                        StyledList { item { index, count ->
                            SpatialAudioSettingContent(null, true, false, change = {}, index = index, count = count)
                        } }
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.6f)) {
            compose.runOnIdle { fontScale.value = scale }
            val title = compose.onNodeWithText("Fixed Spatial Audio", useUnmergedTree = true).assertIsDisplayed()
            val explanation = compose.onNodeWithText("Allow root access to control spatial audio on this phone.", useUnmergedTree = true)
                .assertIsDisplayed()
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val descriptionBounds = explanation.fetchSemanticsNode().boundsInRoot
            val rail = compose.onNodeWithTag("spatial-rail").fetchSemanticsNode().boundsInRoot
            assertEquals(320f, rail.width, 0.5f)
            assertTrue("The explanation must sit below the complete title at scale $scale",
                descriptionBounds.top >= titleBounds.bottom - 0.5f)
            for (bounds in listOf(titleBounds, descriptionBounds)) {
                assertTrue(bounds.left >= rail.left - 0.5f && bounds.right <= rail.right + 0.5f)
                assertTrue(bounds.top >= rail.top - 0.5f && bounds.bottom <= rail.bottom + 0.5f)
            }
            val layouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("The title must wrap as words, not a vertical stack of letters", layouts.single().lineCount in 1..2)
            for (node in listOf(title, explanation)) {
                val text = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
                for (layout in text) {
                    assertFalse("Text cannot lose lines at scale $scale", layout.multiParagraph.didExceedMaxLines)
                    assertTrue("Text bottom ${layout.getLineBottom(layout.lineCount - 1)} must fit height ${layout.size.height} at scale $scale",
                        layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 0.5f)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("Line $line must fit width ${layout.size.width} at scale $scale",
                            layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f)
                    }
                }
            }
        }
    }

    private fun assertSingleSpatialRows() {
        compose.onAllNodesWithText("Fixed Spatial Audio", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Before spatial", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("After spatial", useUnmergedTree = true).assertCountEquals(1)
    }

    private fun awaitSpatialToggle(checked: Boolean) {
        val state = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
            if (checked) ToggleableState.On else ToggleableState.Off)
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodes(isToggleable() and state and isEnabled()).fetchSemanticsNodes().size == 1
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected enabled spatial toggle checked=$checked:\n${compose.onRoot(useUnmergedTree = true).printToString()}", timeout)
        }
    }

    private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
}
