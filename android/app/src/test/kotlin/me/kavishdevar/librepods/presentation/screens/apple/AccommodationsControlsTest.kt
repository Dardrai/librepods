/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.kavishdevar.librepods.core.devices.AccommodationsLevel
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AccommodationsSelection
import me.kavishdevar.librepods.core.devices.AccommodationsTuning
import me.kavishdevar.librepods.core.devices.ReceivedAccommodations
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class AccommodationsControlsTest {
    @get:Rule val compose = createComposeRule()
    private val report = mutableStateOf(report())
    private val request = mutableStateOf(AccommodationsRequestState())
    private val ready = mutableStateOf(true)
    private val presetsWritable = mutableStateOf(true)
    private val audiogramAvailable = mutableStateOf(false)
    private val requested = mutableStateOf<AccommodationsSelection?>(null)
    private val revision = mutableStateOf(0L)
    private val ownPending = mutableStateOf(false)
    private data class Applied(val source: AccommodationsReport, val phone: Boolean?, val media: Boolean?, val preset: AccommodationsPreset?, val editRevision: Long, val audiogram: Boolean)
    private val applied = mutableListOf<Applied>()

    private fun show() {
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    val current = report.value
                    val received = ReceivedAccommodations.parse(current.packet(), current.session, current.receptionRevision)!!
                    AccommodationsControls(current, request.value, null,
                        received,
                        true, presetsWritable.value, ready.value,
                        onEdit = { curves, transform ->
                            // Display the newest UI intent without manufacturing a received report.
                            val base = requested.value ?: request.value.requested ?: AccommodationsSelection(
                                received.phoneEnabled, received.mediaEnabled, received.preset, received.audiogram)
                            val next = transform(base)
                            applied += Applied(current, next.phone, next.media, next.preset,
                                request.value.editRevision, curves && next.audiogram)
                            requested.value = next
                        }, navigateToTransparency = {}, audiogramAvailable = audiogramAvailable.value,
                        requested = requested.value, revision = revision.value, ownPending = ownPending.value)
                }
            }
        }
    }

    private fun toggle(index: Int) = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))[index]

    @Test fun selectionsAndDestinationsChangeLiveWithoutApplyOrReadButtons() {
        show()
        compose.onNodeWithText("Balanced Tone").performClick().assertIsSelected()
        compose.onNodeWithText("Vocal Range").performClick().assertIsSelected()
        compose.onNodeWithText("Balanced Tone").assertIsNotSelected()
        compose.onNodeWithText("Phone").performClick()
        toggle(1).assertIsOff()
        compose.onNodeWithText("Media").performClick()
        toggle(2).assertIsOff()
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Read settings").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(4, applied.size)
            assertEquals(false, applied.last().phone)
            assertEquals(false, applied.last().media)
            assertEquals(AccommodationsPreset(AccommodationsTuning.VOCAL_RANGE, AccommodationsLevel.SLIGHT), applied.last().preset)
        }
    }

    @Test fun freshSessionWithIdenticalValuesRebindsCallbacksToTheCurrentDraft() {
        show()
        compose.runOnIdle { report.value = report(session = 8) }
        compose.onNodeWithText("Phone").performClick()
        toggle(1).assertIsOff()
        compose.runOnIdle {
            assertEquals(1, applied.size)
            assertEquals(8L, applied.single().source.session)
            assertEquals(false, applied.single().phone)
            assertEquals(true, applied.single().media)
            assertNull(applied.single().preset)
        }
    }

    @Test fun freshReportsAndReadinessUpdateRowsWithoutKeepingStaleSelectionsOrActions() {
        show()
        compose.onNodeWithText("Balanced Tone").performClick().assertIsSelected()
        val preset = AccommodationsPreset(AccommodationsTuning.BRIGHTNESS, AccommodationsLevel.STRONG)
        compose.runOnIdle {
            report.value = report(revision = 2, phone = false, preset = preset)
            requested.value = null; revision.value++; applied.clear()
        }
        compose.onNodeWithText("Brightness").assertIsSelected()
        compose.onNodeWithText("Balanced Tone").assertIsNotSelected()
        toggle(1).assertIsOff()
        toggle(2).assertIsOn()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.runOnIdle { presetsWritable.value = false }
        compose.onNodeWithText("Brightness").assertIsNotEnabled()
        compose.onNodeWithText("Phone").assertIsNotEnabled()
        compose.runOnIdle { presetsWritable.value = true; request.value = AccommodationsRequestState(pending = true) }
        compose.onNodeWithText("Brightness").assertIsNotEnabled()
        compose.onNodeWithText("Phone").assertIsNotEnabled()
        compose.runOnIdle { request.value = AccommodationsRequestState(); ready.value = false }
        compose.onNodeWithText("Media").assertIsNotEnabled()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithText("Brightness").assertIsEnabled()
        compose.onNodeWithText("Phone").performClick()
        toggle(1).assertIsOn()
        compose.runOnIdle { assertEquals(1, applied.size); assertTrue(applied.single().phone == true) }
    }

    @Test fun reportedValuesStayVisibleButAllLiveChangesWaitForCurrentCtReadiness() {
        report.value = report(phone = false, preset = AccommodationsPreset(AccommodationsTuning.VOCAL_RANGE, AccommodationsLevel.SLIGHT))
        presetsWritable.value = false
        show()
        compose.onNodeWithText("Vocal Range").assertIsSelected().assertIsNotEnabled()
        toggle(0).assertIsOn().assertIsNotEnabled()
        toggle(1).assertIsOff().assertIsNotEnabled()
        toggle(2).assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Headphone Accommodations").performClick()
        compose.onNodeWithText("Phone").performClick()
        compose.onNodeWithText("Media").performClick()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.runOnIdle { presetsWritable.value = true }
        toggle(0).assertIsOn().assertIsEnabled()
        toggle(1).assertIsOff().assertIsEnabled()
        toggle(2).assertIsOn().assertIsEnabled()
        compose.onNodeWithText("Vocal Range").assertIsSelected().assertIsEnabled()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun newerOpaqueReportReplacesTheRequestedSelectionWithoutWritingItBack() {
        report.value = report(phone = false, media = false)
        show()
        toggle(0).performClick().assertIsOn()
        compose.onNodeWithText("Balanced Tone").performClick().assertIsSelected()
        val replacement = report.value.packet().also { it[9] = 0; it[14] = 0x80.toByte(); it[15] = 0x3f }
        compose.runOnIdle {
            report.value = AccommodationsReport.parse(replacement, 7, 2, false, false)!!
            requested.value = null; revision.value++; applied.clear()
        }
        toggle(0).assertIsOff()
        compose.onNodeWithText("Balanced Tone").assertIsNotSelected()
        compose.runOnIdle { assertNull(report.value.preset); assertTrue(applied.isEmpty()) }
    }

    @Test fun sentSelectionAllowsAnotherEditAndFreshReportReplacesIt() {
        report.value = report(phone = false, media = false)
        val preset = AccommodationsPreset(AccommodationsTuning.BRIGHTNESS, AccommodationsLevel.STRONG)
        request.value = AccommodationsRequestState(result = AccommodationsResult.SENT,
            requested = AccommodationsSelection(true, false, preset), editRevision = 1)
        show()
        toggle(0).assertIsOn()
        toggle(1).assertIsOn()
        toggle(2).assertIsOff()
        compose.onNodeWithText("Brightness").assertIsSelected()
        compose.onNodeWithText("Media").performClick()
        compose.runOnIdle {
            assertEquals(1L, applied.single().editRevision)
            assertEquals(true, applied.single().media)
            report.value = report(revision = 2, phone = false, media = false)
            request.value = AccommodationsRequestState(editRevision = 2)
            requested.value = null; revision.value++
        }
        toggle(0).assertIsOff()
        compose.onNodeWithText("Brightness").assertIsNotSelected()
        compose.runOnIdle { assertEquals(1, applied.size) }
    }

    @Test fun audiogramIsAnExplicitAvailableSourceWithoutAStockOptimizationLevel() {
        report.value = report(preset = AccommodationsPreset(AccommodationsTuning.BALANCED_TONE, AccommodationsLevel.SLIGHT))
        show()
        compose.onNodeWithText("Audiogram").assertIsNotEnabled().assertIsNotSelected()
        compose.runOnIdle { audiogramAvailable.value = true }
        compose.onNodeWithText("Audiogram").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithText("Balanced Tone").assertIsNotSelected()
        compose.onNodeWithText("Slight").assertDoesNotExist()
        compose.runOnIdle { assertTrue(applied.single().audiogram); assertNull(applied.single().preset) }
        compose.runOnIdle { audiogramAvailable.value = false }
        compose.onNodeWithText("Audiogram").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, applied.size) }
    }

    @Test fun flagsOnlyFromOwnedAudiogramPreservesItsSourceWithoutRequestingAnotherImport() {
        request.value = AccommodationsRequestState(result = AccommodationsResult.SENT,
            requested = AccommodationsSelection(true, true, null, true), editRevision = 3)
        audiogramAvailable.value = true
        show()
        compose.onNodeWithText("Audiogram").assertIsSelected()
        compose.onNodeWithText("Phone").performClick()
        compose.runOnIdle {
            assertFalse(applied.single().audiogram)
            assertEquals(false, applied.single().phone)
            assertEquals(3L, applied.single().editRevision)
        }
    }

    @Test fun offDestinationChoicesStayLocalUntilTheMasterIsEnabled() {
        request.value = AccommodationsRequestState(result = AccommodationsResult.SENT,
            requested = AccommodationsSelection(false, false, null), editRevision = 1)
        show()
        toggle(0).assertIsOff()
        compose.onNodeWithText("Phone").performClick()
        compose.runOnIdle {
            assertTrue(applied.isEmpty())
        }
        toggle(0).performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(1, applied.size)
            assertEquals(false, applied.last().phone)
            assertEquals(true, applied.last().media)
            assertEquals(1L, applied.last().editRevision)
        }
    }

    @Test fun ownPendingWriteAcceptsLatestChoiceButForeignPendingWriteDoesNot() {
        request.value = AccommodationsRequestState(pending = true)
        show()
        compose.onNodeWithText("Balanced Tone").assertIsNotEnabled()
        compose.runOnIdle { ownPending.value = true }
        compose.onNodeWithText("Balanced Tone").assertIsEnabled().performClick()
        compose.onNodeWithText("Brightness").assertIsEnabled().performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(2, applied.size)
            assertEquals(AccommodationsTuning.BRIGHTNESS, applied.last().preset!!.tuning)
        }
    }

    companion object {
        private fun report(session: Long = 7, revision: Long = 1, phone: Boolean = true, media: Boolean = true,
            preset: AccommodationsPreset? = null): AccommodationsReport {
            val packet = ByteArray(140).also {
                byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 0, if (phone) 1 else 2, if (media) 1 else 2).copyInto(it)
            }
            val original = AccommodationsReport.parse(packet, session, revision, false, false)!!
            val desired = original.plan(preset = preset).packet().also { it[9] = 0 }
            return AccommodationsReport.parse(desired, session, revision, false, false)!!
        }
    }
}
