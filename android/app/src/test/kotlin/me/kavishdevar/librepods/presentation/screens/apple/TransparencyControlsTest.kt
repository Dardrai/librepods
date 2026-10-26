/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class TransparencyControlsTest {
    @get:Rule val compose = createComposeRule()
    private val source = mutableStateOf(record())
    private val session = mutableStateOf(7L)
    private val revision = mutableStateOf(0L)
    private val writable = mutableStateOf(true)
    private data class Write(val expected: ByteArray, val requested: ByteArray)
    // These are UI edit intents, not physical ATT writes. LiveRecordEditorTest covers pacing.
    private val edits = mutableListOf<Write>()
    private var finished = 0
    private fun thumb() = compose.onAllNodesWithTag("styled-slider-thumb", useUnmergedTree = true)[0]
    private fun toggle(index: Int) = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))[index]

    private fun show() {
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TransparencyControls(source.value, session.value, revision = revision.value,
                        writable = writable.value, canEnable = true,
                        transportAvailable = true, result = null,
                        onEdit = { transform ->
                            val expected = source.value.copyOf()
                            transform(expected)?.let { requested ->
                                edits += Write(expected, requested.copyOf())
                                source.value = requested
                            }
                        }, onFinished = { finished++ })
                }
            }
        }
    }

    private fun beginDrag() {
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
    }

    @Test fun sliderEditsAreLiveAndIdenticalPollsPreserveTheLatestPosition() {
        val original = source.value.copyOf()
        show()
        beginDrag()
        compose.runOnIdle { assertTrue(edits.isNotEmpty()); source.value = source.value.copyOf() }
        thumb().performTouchInput { up() }
        compose.runOnIdle { source.value = source.value.copyOf() }
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Read settings").assertDoesNotExist()
        compose.runOnIdle {
            val sent = edits.last()
            assertEquals(1, finished)
            assertTrue(parseTransparencySettingsResponse(sent.requested)!!.netAmplification > .2f)
            for (range in listOf(0 until 36, 40 until 84, 88 until 104)) {
                assertArrayEquals(original.sliceArray(range), sent.requested.sliceArray(range))
            }
        }
    }

    @Test fun freshRevisionCancelsTheOldGestureAndUsesTheNewFullBaseline() {
        show()
        beginDrag()
        val replacement = source.value.copyOf().also { put(it, 4, 34f) }
        compose.runOnIdle { source.value = replacement; revision.value++; edits.clear(); finished = 0 }
        thumb().performTouchInput { up() }
        compose.runOnIdle { assertTrue(edits.isEmpty()); assertEquals(0, finished) }
        toggle(1).performClick()
        compose.runOnIdle {
            val sent = edits.single()
            assertArrayEquals(replacement, sent.expected)
            assertArrayEquals(replacement.copyOfRange(0, 44), sent.requested.copyOfRange(0, 44))
            assertArrayEquals(replacement.copyOfRange(48, 92), sent.requested.copyOfRange(48, 92))
            assertArrayEquals(replacement.copyOfRange(96, 104), sent.requested.copyOfRange(96, 104))
        }
    }

    @Test fun newSessionRetiresTheOldGestureEvenWhenAllRecordBytesAreIdentical() {
        show()
        beginDrag()
        compose.runOnIdle { session.value++; edits.clear(); finished = 0 }
        thumb().performTouchInput { up() }
        compose.runOnIdle { assertTrue(edits.isEmpty()); assertEquals(0, finished) }
        val baseline = source.value.copyOf()
        beginDrag()
        thumb().performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(edits.isNotEmpty())
            assertEquals(1, finished)
            assertArrayEquals(baseline, edits.first().expected)
            assertTrue(parseTransparencySettingsResponse(edits.last().requested)!!.netAmplification > .2f)
        }
    }

    @Test fun masterSwitchChangesLiveAndPreservesAllOtherBytes() {
        val original = source.value.copyOf()
        show()
        toggle(0).performClick()
        compose.runOnIdle { source.value = source.value.copyOf() }
        compose.runOnIdle {
            val sent = edits.single()
            assertArrayEquals(original, sent.expected)
            assertFalse(parseTransparencySettingsResponse(sent.requested)!!.enabled)
            assertArrayEquals(original.copyOfRange(4, 104), sent.requested.copyOfRange(4, 104))
        }
    }

    @Test fun freshOffRecordReplacesTheRequestedChoiceAndItsFullBaseline() {
        source.value = source.value.copyOf().also { put(it, 0, 0f) }
        show()
        toggle(0).performClick().assertIsOn()
        val replacement = source.value.copyOf().also { put(it, 0, 0f); put(it, 56, 31f) }
        compose.runOnIdle { source.value = replacement; revision.value++; edits.clear() }
        toggle(0).assertIsOff()
        toggle(0).performClick()
        compose.runOnIdle {
            assertArrayEquals(replacement, edits.single().expected)
            assertTrue(parseTransparencySettingsResponse(edits.single().requested)!!.enabled)
            assertArrayEquals(replacement.copyOfRange(4, 104), edits.single().requested.copyOfRange(4, 104))
        }
    }

    @Test fun missingRecordThenFreshReadbackDoesNotWriteDuringHydration() {
        source.value = source.value.copyOf().also { put(it, 0, 0f) }
        show()
        toggle(0).performClick().assertIsOn()
        val replacement = source.value.copyOf().also { put(it, 0, 0f) }
        compose.runOnIdle { source.value = byteArrayOf(); revision.value++; edits.clear() }
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.runOnIdle { source.value = replacement; revision.value++ }
        toggle(0).assertIsOff()
        compose.runOnIdle { assertTrue(edits.isEmpty()) }
    }

    @Test fun losingWriteEligibilityStopsFurtherEdits() {
        show()
        beginDrag()
        compose.runOnIdle { writable.value = false; edits.clear(); finished = 0 }
        thumb().performTouchInput { up() }
        toggle(1).performClick()
        compose.runOnIdle { assertTrue(edits.isEmpty()); assertEquals(0, finished) }
    }

    companion object {
        private fun put(data: ByteArray, offset: Int, value: Float) {
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
        }
        private fun record() = ByteArray(104).also {
            put(it, 0, 1f)
            for (index in 0 until 8) { put(it, 4 + index * 4, index * 2f); put(it, 52 + index * 4, 10f + index) }
            put(it, 4, -0f); put(it, 40, -.25f); put(it, 88, .35f)
            put(it, 48, .2f); put(it, 96, .7f); put(it, 100, .31f)
        }
    }
}
