/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.platform.ComposeView
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class IslandStateTest {
    @Test fun openingStartsWithAnAlreadyVisibleCameraCapsuleForBothDisplayModes() {
        val settings = IslandSettings(compactWidth = 300f, compactHeight = 132f,
            expandedWidth = 1248f, expandedHeight = 630f)
        for (state in listOf(IslandState.EXPANDED, IslandState.COMPACT)) {
            val first = islandInitialSize(settings, state, motionEnabled = true)
            assertEquals(132f, first.first, 0f)
            assertEquals(132f, first.second, 0f)
            assertTrue(first.first < settings.compactWidth)
        }
        val wideCamera = islandInitialSize(settings, IslandState.EXPANDED, true, cameraCapsuleWidth = 210f)
        assertEquals(210f, wideCamera.first, 0f)
        assertEquals(132f, wideCamera.second, 0f)
    }

    @Test fun reducedMotionStartsAtTheRequestedSizeWithoutAnEntranceOrAttachmentDelay() {
        val settings = IslandSettings(compactWidth = 300f, compactHeight = 132f,
            expandedWidth = 1248f, expandedHeight = 630f)
        assertEquals(settings.expandedWidth to settings.expandedHeight,
            islandInitialSize(settings, IslandState.EXPANDED, motionEnabled = false))
        assertEquals(settings.compactWidth to settings.compactHeight,
            islandInitialSize(settings, IslandState.COMPACT, motionEnabled = false))
        assertEquals(0f to settings.compactHeight,
            islandInitialSize(settings, IslandState.CLOSE, motionEnabled = true))
    }

    @Test fun anInterruptedExitCannotRemoveAReopenedOrReplacementOverlay() {
        val context = RuntimeEnvironment.getApplication()
        val window = IslandWindow(context, AppSettingsEntity())
        val first = ComposeView(context)
        val replacement = ComposeView(context)
        val field = IslandWindow::class.java.getDeclaredField("composeView").apply { isAccessible = true }
        val completeExit = IslandWindow::class.java.getDeclaredMethod("cleanupAndRemoveView", ComposeView::class.java)
            .apply { isAccessible = true }
        field.set(window, first)
        window.changeState(IslandState.EXPANDED)
        completeExit.invoke(window, first)
        assertSame(first, field.get(window))
        window.changeState(IslandState.CLOSE)
        field.set(window, replacement)
        completeExit.invoke(window, first)
        assertSame(replacement, field.get(window))
        completeExit.invoke(window, replacement)
        assertNull(field.get(window))
    }

    @Test fun physicalCameraUsesAcceptedPixelGeometryAndWiderSafeWidth() {
        val placement = islandPlacement(Rect(0, 0, 1344, 2992), Insets.of(0, 200, 0, 72),
            RectF(623.5f, 52f, 719.5f, 148f), 3f, IslandSettings())
        assertEquals(1248, placement.expandedWidth)
        assertEquals(34, placement.y)
        assertEquals(150, placement.contentTop)
        assertEquals(132, placement.compactHeight)
        assertTrue(placement.compactWidth >= 288)
        assertEquals(0, placement.x)
        assertTrue(placement.expandedHeight >= placement.contentTop + 480)
    }

    @Test fun noCutoutStaysBelowStatusBarAndCustomPositionIsConstrained() {
        val settings = IslandSettings(expandedWidth = 2000f, offsetX = 500f, offsetY = 250f)
        val placement = islandPlacement(Rect(0, 0, 1080, 2400), Insets.of(0, 72, 0, 72), null, 3f, settings)
        assertEquals(984, placement.expandedWidth)
        assertEquals(250, placement.y)
        assertEquals(0, placement.x)
        val defaults = islandPlacement(Rect(0, 0, 1080, 2400), Insets.of(0, 72, 0, 72), null, 3f, IslandSettings())
        assertEquals(108, defaults.y)
    }

    @Test fun everyBatteryKeepsItsOwnAgeAndUnknownIsNotZero() {
        val now = 200_000L
        val cells = islandBatteryCells(AirPodsModel.AIRPODS_PRO_3, setOf(
            Battery(BatteryComponent.LEFT, 100, BatteryStatus.CHARGING, now - 1_000),
            Battery(BatteryComponent.RIGHT, 47, BatteryStatus.CHARGING, now - 90_000),
            Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)
        ), now)
        assertEquals(listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE), cells.map { it.component })
        assertTrue(cells[0].live)
        assertTrue(cells[0].charging)
        assertEquals(1L, cells[0].ageSeconds)
        assertEquals(47, cells[1].level)
        assertFalse(cells[1].live)
        assertFalse(cells[1].charging)
        assertEquals(90L, cells[1].ageSeconds)
        assertNull(cells[2].level)
        assertNull(cells[2].ageSeconds)
        assertFalse(cells[2].charging)
    }

    @Test fun explicitMissingComponentKeepsPercentageButCannotClaimCharging() {
        val cells = islandBatteryCells(AirPodsModel.AIRPODS_PRO_3, setOf(
            Battery(BatteryComponent.LEFT, 0, BatteryStatus.NOT_CHARGING, 10_000),
            Battery(BatteryComponent.RIGHT, 98, BatteryStatus.CHARGING, 10_000, present = false)
        ), 11_000)
        assertEquals(0, cells[0].level)
        assertTrue(cells[0].live)
        assertEquals(98, cells[1].level)
        assertFalse(cells[1].live)
        assertFalse(cells[1].charging)
        assertNull(cells[2].level)
        assertEquals(listOf(BatteryComponent.HEADSET),
            islandBatteryCells(AirPodsModel.AIRPODS_MAX_USBC, emptySet(), 11_000).map { it.component })
    }
}
