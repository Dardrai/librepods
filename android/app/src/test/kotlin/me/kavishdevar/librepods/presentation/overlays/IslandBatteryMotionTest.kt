package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import me.kavishdevar.librepods.core.devices.BatteryComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandBatteryMotionTest {
    @get:Rule val compose = createComposeRule()
    private val level = mutableStateOf<Int?>(80)
    private val component = mutableStateOf(BatteryComponent.LEFT)
    private val owner = mutableStateOf("first-pair")
    private val motionEnabled = mutableStateOf(true)
    private val frozen = mutableStateOf(false)
    private var displayed = Float.NaN

    @Test fun changedReadingsContinueFromTheVisibleRingAndSettleOnTheLatestLevel() {
        render()
        assertEquals(80f, displayed, 0.001f)
        mutate { level.value = 40 }
        advance(112)
        val midway = displayed
        assertTrue("A report must interpolate rather than jump", midway > 40f && midway < 80f)
        mutate { level.value = 100 }
        assertTrue("An interrupted report must continue near the visible position", abs(midway - displayed) < 8f)
        advance(320)
        assertEquals(100f, displayed, 0.001f)
    }

    @Test fun switchingPairsNeverInterpolatesFromAnotherDevicesReading() {
        render()
        mutate { level.value = 20 }
        advance(80)
        assertTrue(displayed > 20f && displayed < 80f)
        mutate { owner.value = "second-pair"; level.value = 95 }
        assertEquals(95f, displayed, 0.001f)
    }

    @Test fun absentReadingsAndNewComponentsNeverInventABatterySweep() {
        render()
        mutate { level.value = null }
        assertEquals(0f, displayed, 0.001f)
        mutate { level.value = 65 }
        assertEquals("A newly available reading starts at its reported level", 65f, displayed, 0.001f)
        mutate { component.value = BatteryComponent.CASE; level.value = 90 }
        assertEquals("A different component cannot inherit the old ring", 90f, displayed, 0.001f)
    }

    @Test fun reducedMotionSnapsAndAnEmptyBatteryRemainsAValidReading() {
        render()
        mutate { level.value = 20 }
        advance(80)
        assertTrue(displayed > 20f && displayed < 80f)
        mutate { motionEnabled.value = false }
        assertEquals(20f, displayed, 0.001f)
        mutate { level.value = 0 }
        assertEquals(0f, displayed, 0.001f)
        mutate { motionEnabled.value = true; level.value = 60 }
        advance(112)
        assertTrue("Zero is a real starting reading", displayed > 0f && displayed < 60f)
    }

    @Test fun dismissalFreezesTheOutgoingRingAndReopeningResumesFromThatPosition() {
        render()
        mutate { level.value = 20 }
        advance(80)
        mutate { frozen.value = true }
        val outgoing = displayed
        advance(320)
        assertEquals(outgoing, displayed, 0.001f)
        mutate { level.value = 95 }
        advance(320)
        assertEquals("Reports cannot move the outgoing payload", outgoing, displayed, 0.001f)
        mutate { frozen.value = false }
        assertTrue("Reopening starts from the outgoing position", abs(outgoing - displayed) < 8f)
        advance(320)
        assertEquals(95f, displayed, 0.001f)
    }

    private fun render() {
        compose.setContent {
            displayed = rememberIslandBatteryGauge(component.value, level.value, motionEnabled.value, frozen.value, owner.value)
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun mutate(change: () -> Unit) {
        compose.runOnIdle { change(); Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }
}
