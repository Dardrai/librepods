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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
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
class HearingAidAdjustmentControlsTest {
    @get:Rule val compose = createComposeRule()
    private val source = mutableStateOf(record())
    private val session = mutableStateOf(7L)
    private val revision = mutableStateOf(0L)
    private val enabled = mutableStateOf(true)
    // UI intents update the displayed overlay. LiveRecordEditorTest covers actual ATT writes.
    private data class Edit(val value: ByteArray, val expected: ByteArray)
    private val edits = mutableListOf<Edit>()
    private var finished = 0
    private var canceled = 0
    private var confirmed = record()
    private fun thumb(index: Int = 0) = compose.onAllNodesWithTag("styled-slider-thumb", useUnmergedTree = true)[index]

    private fun show() {
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HearingAidAdjustmentControls(source.value, session.value, revision = revision.value,
                        enabled = enabled.value, gainSwipe = false, onGainSwipe = {},
                        onEdit = { transform ->
                            val expected = source.value.copyOf()
                            transform(expected)?.let { value ->
                                edits += Edit(value.copyOf(), expected)
                                source.value = value
                            }
                        }, onFinished = { finished++ }, onCanceled = {
                            canceled++
                            source.value = confirmed.copyOf()
                        })
                }
            }
        }
    }

    private fun beginDrag() {
        thumb().performTouchInput { down(center); repeat(60) { moveBy(Offset(1f, 0f), delayMillis = 8) } }
    }

    @Test fun sliderEditsAreLiveAndPreserveTheCompleteFitting() {
        val original = source.value.copyOf()
        show()
        beginDrag()
        compose.runOnIdle { assertTrue(edits.isNotEmpty()); source.value = source.value.copyOf() }
        thumb().performTouchInput { up() }
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Read settings").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, finished)
            val sent = edits.last()
            assertArrayEquals(original, edits.first().expected)
            val actual = parseHearingAidSettingsResponse(sent.value)!!
            assertTrue("The final drag target must survive an identical fresh poll", actual.netAmplification > .2f)
            for (range in listOf(4 until 36, 52 until 84, 40 until 52, 88 until 104)) {
                assertArrayEquals(original.sliceArray(range), sent.value.sliceArray(range))
            }
        }
    }

    @Test fun freshRevisionAndNewSessionRetireTheOldGesture() {
        show()
        beginDrag()
        val replacement = source.value.copyOf().also { put(it, 4, 34f) }
        compose.runOnIdle {
            confirmed = replacement.copyOf(); source.value = replacement
            revision.value++; edits.clear(); finished = 0
        }
        thumb().performTouchInput { up() }
        compose.runOnIdle { assertTrue(edits.isEmpty()); assertEquals(0, finished) }
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
            assertArrayEquals(replacement.copyOfRange(4, 36), edits.last().value.copyOfRange(4, 36))
        }
    }

    @Test fun successiveGesturesKeepTheLatestRequestedValueEditable() {
        show()
        beginDrag()
        thumb().performTouchInput { up() }
        val firstGain = parseHearingAidSettingsResponse(source.value)!!.netAmplification
        compose.onNodeWithText("These hearing settings can be viewed but not changed on this device.").assertDoesNotExist()
        beginDrag()
        thumb().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(2, finished)
            assertTrue(parseHearingAidSettingsResponse(source.value)!!.netAmplification > firstGain)
        }
    }

    @Test fun eligibilityLossStopsFurtherEditsAndBoostPreservesTheFitting() {
        val original = source.value.copyOf()
        show()
        beginDrag()
        compose.runOnIdle { enabled.value = false; edits.clear(); finished = 0 }
        thumb().performTouchInput { up() }
        compose.runOnIdle { assertTrue(edits.isEmpty()); assertEquals(0, finished); enabled.value = true }
        val baseline = source.value.copyOf()
        compose.onNodeWithText("Conversation Boost").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, edits.size)
            assertArrayEquals(baseline, edits.single().expected)
            assertArrayEquals(original.copyOfRange(4, 36), edits.single().value.copyOfRange(4, 36))
            assertArrayEquals(original.copyOfRange(52, 84), edits.single().value.copyOfRange(52, 84))
        }
    }

    @Test fun cancelNotifiesTheOwnerAndALaterBoostUsesItsLatestBaseline() {
        val original = source.value.copyOf()
        show()
        beginDrag()
        thumb().performTouchInput { cancel() }
        compose.runOnIdle {
            assertEquals(1, canceled)
            assertEquals(0, finished)
            assertArrayEquals(confirmed, source.value)
            edits.clear()
        }
        compose.onNodeWithText("Conversation Boost").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, edits.size)
            val sent = edits.single()
            assertArrayEquals(original, sent.expected)
            assertArrayEquals(original.copyOfRange(4, 44), sent.value.copyOfRange(4, 44))
            assertArrayEquals(original.copyOfRange(48, 104), sent.value.copyOfRange(48, 104))
            assertEquals(1f, ByteBuffer.wrap(sent.value).order(ByteOrder.LITTLE_ENDIAN).getFloat(44), 0f)
        }
    }

    companion object {
        private fun put(data: ByteArray, offset: Int, value: Float) { ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value) }
        private fun record() = ByteArray(104).also {
            byteArrayOf(2, 2, 0x60, 0).copyInto(it)
            for (index in 0 until 8) { put(it, 4 + index * 4, index * 2f); put(it, 52 + index * 4, 10f + index) }
            put(it, 4, -0f); put(it, 40, -.25f); put(it, 88, .35f)
            put(it, 48, .2f); put(it, 96, .7f); put(it, 92, 1f); put(it, 100, .31f)
        }
    }
}
