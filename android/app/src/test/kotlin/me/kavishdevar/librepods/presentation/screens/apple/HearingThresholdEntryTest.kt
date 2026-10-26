/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
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
@Config(sdk = [35], application = Application::class)
// Text selection owns a platform magnifier surface; use real rendering for its lifecycle.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HearingThresholdEntryTest {
    @get:Rule val compose = createComposeRule()
    private val value = mutableStateOf<Float?>(5f)
    private val session = mutableStateOf(7L)
    private val revision = mutableStateOf(0L)
    private val enabled = mutableStateOf(true)
    private val edits = mutableListOf<Float>()
    private fun field() = compose.onNodeWithContentDescription("Left 250Hz")
    private fun show() {
        compose.setContent {
            MaterialTheme {
                val focus = LocalFocusManager.current
                Column {
                    HearingThresholdField(value.value, session.value, revision.value, enabled.value, "Left 250Hz") {
                        edits += it
                        value.value = it
                    }
                    Button(onClick = { focus.clearFocus() }) { Text("Other control") }
                }
            }
        }
    }

    @Test fun typingAndOwnReadbackDoNotSendOrReplaceTheActiveText() {
        show()
        field().performClick().performTextReplacement("12,5")
        compose.runOnIdle { assertTrue(edits.isEmpty()); value.value = 3f }
        field().assertTextEquals("12,5").performImeAction()
        compose.runOnIdle { assertEquals(listOf(12.5f), edits) }
        compose.onNodeWithText("Other control").performClick()
        compose.runOnIdle { assertEquals("Done followed by focus loss commits only once", 1, edits.size) }
    }

    @Test fun onlyCompleteFiniteInRangeValuesCommit() {
        show()
        for (invalid in listOf("", "-", ".", "12.", "NaN", "Infinity", "-1", "91")) {
            field().performClick().performTextReplacement(invalid)
            field().performImeAction()
        }
        compose.runOnIdle { assertTrue(edits.isEmpty()) }
        for (valid in listOf("0", "90")) {
            field().performClick().performTextReplacement(valid)
            field().performImeAction()
        }
        compose.runOnIdle { assertEquals(listOf(0f, 90f), edits) }
    }

    @Test fun focusLossSettlesAValidValueOnceWithoutAnApplyButton() {
        show()
        field().performClick().performTextReplacement("25")
        compose.onNodeWithText("Other control").performClick()
        compose.runOnIdle { assertEquals(listOf(25f), edits) }
        compose.onNodeWithText("Apply").assertDoesNotExist()
    }

    @Test fun newSessionRevisionOrLostEligibilityDiscardsUnsettledText() {
        show()
        field().performClick().performTextReplacement("25")
        compose.runOnIdle { session.value++; value.value = 8f }
        field().assertTextEquals("8.0").performImeAction()
        field().performClick().performTextReplacement("35")
        compose.runOnIdle { revision.value++; value.value = 9f }
        field().assertTextEquals("9.0").performImeAction()
        field().performClick().performTextReplacement("45")
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Other control").performClick()
        compose.runOnIdle { assertTrue(edits.isEmpty()) }
    }

    @Test fun aBandEditPreservesEveryOtherBandAndAdjustmentByte() {
        val baseline = record()
        for (left in listOf(true, false)) for (band in 0..7) {
            val updated = updateHearingThreshold(baseline, band, left, 27.5f)!!
            val offset = (if (left) 4 else 52) + band * 4
            assertEquals(27.5f, ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN).getFloat(offset), 0f)
            for (index in baseline.indices) if (index != 2 && index !in offset until offset + 4)
                assertEquals("Untouched byte $index", baseline[index], updated[index])
            assertEquals(0x64.toByte(), updated[2])
        }
        assertArrayEquals(record(), baseline)
        assertNull(updateHearingThreshold(baseline, -1, true, 2f))
        assertNull(updateHearingThreshold(baseline, 8, true, 2f))
        assertNull(updateHearingThreshold(baseline, 0, true, Float.NaN))
        assertNull(updateHearingThreshold(baseline, 0, true, 91f))
        assertNull(updateHearingThreshold(baseline.copyOf(103), 0, true, 2f))
    }

    private fun record() = ByteArray(104).also {
        byteArrayOf(2, 2, 0x60, 0).copyInto(it)
        val data = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        for (band in 0..7) { data.putFloat(4 + band * 4, band.toFloat()); data.putFloat(52 + band * 4, 10f + band) }
        data.putFloat(4, -0f)
        data.putFloat(36, -.25f); data.putFloat(84, .15f)
        data.putFloat(40, -.5f); data.putFloat(88, .75f)
        data.putFloat(44, 1f); data.putFloat(92, 0f)
        data.putFloat(48, .3f); data.putFloat(96, .7f); data.putFloat(100, .4f)
    }
}
