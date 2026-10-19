/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandStatusBarCoverageTest {
    @Test fun theWidePixelShellCoversTheIconRowWithEightDpBelowIt() {
        assertTrue(covers(height = 90f))
        assertFalse(covers(height = 89.99f))
        assertTrue(covers(height = 600f))
    }

    @Test fun compactFoldedAndCustomNarrowShellsCannotWhitenTheWholeStatusBar() {
        for (width in listOf(300f, 900f, 1_200f)) assertFalse(covers(width = width))
    }

    @Test fun theWidthToleranceOnlyAllowsHalfAPixelOfRounding() {
        assertTrue(covers(width = 1_247.5f))
        assertFalse(covers(width = 1_247.49f))
        assertTrue(covers(width = 1_248f))
    }

    @Test fun theIconRowNeedsEightDpOfCoverageAboveAndBelowItsCenter() {
        assertTrue(covers(height = 48f, iconCenter = 24f))
        assertFalse(covers(iconCenter = 23.99f))
        assertFalse(covers(iconCenter = 0f))
        assertFalse(covers(iconCenter = -30f))
        assertTrue(covers(height = 32f, iconCenter = 16f, density = 2f))
        assertFalse(covers(height = 31.99f, iconCenter = 16f, density = 2f))
    }

    @Test fun invalidGeometryCannotAcquireStatusBarAppearance() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse("width=$value", covers(width = value))
            assertFalse("height=$value", covers(height = value))
            assertFalse("minimumWidth=$value", covers(minimumWidth = value))
            assertFalse("iconCenter=$value", covers(iconCenter = value))
            assertFalse("density=$value", covers(density = value))
            assertFalse("cornerRadius=$value", covers(cornerRadius = value))
        }
        for (value in listOf(0f, -1f)) {
            assertFalse(covers(width = value))
            assertFalse(covers(height = value))
            assertFalse(covers(minimumWidth = value))
            assertFalse(covers(density = value))
        }
        assertFalse(covers(cornerRadius = -0.01f))
    }

    @Test fun onlySufficientlyOpaqueBackgroundsCanCoverTheIcons() {
        assertTrue(covers(background = Color.Black.copy(alpha = 0.9f)))
        assertFalse(covers(background = Color.Black.copy(alpha = 0.89f)))
        assertFalse(covers(background = Color.Transparent))
        assertFalse(covers(background = Color.Unspecified))
    }

    @Test fun actualDarkCustomColorsQualifyAndLightColorsDoNot() {
        for (color in listOf(Color.Black, Color(0xFF102040), Color.Blue, Color(0xFF757575))) {
            assertTrue("dark background=$color", covers(background = color))
        }
        for (color in listOf(Color.White, Color.LightGray, Color.Yellow, Color.Red, Color(0xFF777777))) {
            assertFalse("light background=$color", covers(background = color))
        }
    }

    @Test fun theDefaultPixelCornersCoverTheIconBandButDisplacedRoundCornersDoNot() {
        assertTrue(covers(cornerRadius = 85f))
        assertFalse(covers(iconCenter = 24f, cornerRadius = 150f))
        assertTrue(covers(iconCenter = 24f, cornerRadius = 24f))
        assertFalse(covers(iconCenter = 24f, cornerRadius = 24.01f))
    }

    @Test fun oversizedRadiiUseTheActualRoundedRectangleClamp() {
        assertTrue(covers(height = 90f, iconCenter = 45f, cornerRadius = 45f))
        assertTrue(covers(height = 90f, iconCenter = 45f, cornerRadius = 5_000f))
        assertTrue(covers(height = 90f, iconCenter = 45f, cornerRadius = Float.MAX_VALUE))
        assertFalse(covers(iconCenter = 66f, cornerRadius = 5_000f))
    }

    @Test fun theBottomCornersMustCoverTheWholeIconBandToo() {
        assertTrue(covers(height = 600f, iconCenter = 534f, cornerRadius = 85f))
        assertFalse(covers(height = 600f, iconCenter = 576f, cornerRadius = 85f))
        assertTrue(covers(height = 600f, iconCenter = 576f, cornerRadius = 24f))
    }

    private fun covers(width: Float = 1_248f, height: Float = 600f, minimumWidth: Float = 1_248f,
                       iconCenter: Float = 66f, density: Float = 3f, background: Color = Color.Black,
                       cornerRadius: Float = 0f) =
        islandCoversStatusBar(width, height, minimumWidth, iconCenter, density, background, cornerRadius)
}
