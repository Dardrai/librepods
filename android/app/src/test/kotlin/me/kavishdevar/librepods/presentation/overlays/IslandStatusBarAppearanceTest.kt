/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandStatusBarAppearanceTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var host: FrameLayout
    private lateinit var manager: WindowManager
    private lateinit var appearance: IslandStatusBarAppearance
    private val requests = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private val updates = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private var contextCreations = 0
    private var failAfterAdd = false
    private var failUpdate = false
    private var failRemoval = false
    private var failMetrics = false
    private var displayInsets = navigationInsets()

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        host = FrameLayout(activity.get())
        activity.get().setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        manager = mock(WindowManager::class.java)
        doAnswer {
            if (failMetrics) throw IllegalStateException("Test metrics unavailable")
            WindowMetrics(Rect(0, 0, 1344, 2992), displayInsets)
        }.`when`(manager).currentWindowMetrics
        doAnswer { call ->
            val view = call.getArgument<View>(0)
            requests += view to call.getArgument<WindowManager.LayoutParams>(1)
            host.addView(view)
            if (failAfterAdd) throw WindowManager.BadTokenException("Test failure after partial attachment")
            null
        }.`when`(manager).addView(any(View::class.java), any(android.view.ViewGroup.LayoutParams::class.java))
        doAnswer { call ->
            updates += call.getArgument<View>(0) to call.getArgument<WindowManager.LayoutParams>(1)
            if (failUpdate) throw IllegalStateException("Test layout update failure")
            null
        }.`when`(manager).updateViewLayout(any(View::class.java), any(android.view.ViewGroup.LayoutParams::class.java))
        doAnswer { call ->
            if (failRemoval) throw IllegalStateException("Test removal failure")
            host.removeView(call.getArgument<View>(0))
            null
        }.`when`(manager).removeViewImmediate(any(View::class.java))
        val context = object : ContextWrapper(activity.get()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        appearance = IslandStatusBarAppearance(activity.get()) { contextCreations++; context }
    }

    @After fun cleanup() {
        failRemoval = false
        appearance.release()
        activity.pause().stop().destroy()
    }

    @Test fun preparationIsIneligibleAndCoverageOnlyUpdatesTheSameWindowAlpha() {
        appearance.setEnabled(false)
        assertEquals("The application-overlay context must be created lazily", 0, contextCreations)
        val insets = activity.get().window.insetsController!!
        val status = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        val navigation = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        insets.setSystemBarsAppearance(status or navigation, status or navigation)
        appearance.prepare()
        shadowOf(Looper.getMainLooper()).idle()
        val (view, params) = requests.single()
        assertEquals(1, contextCreations)
        assertEquals(1, params.width)
        assertEquals(1, params.height)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        assertEquals(0f, params.dimAmount, 0f)
        assertEquals("A prepared window must be policy-ineligible until covered", 0f, params.alpha, 0f)
        assertEquals(0, params.fitInsetsTypes)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertNull("The companion must draw no background", view.background)
        assertTrue(view.isAttachedToWindow)
        assertEquals(0, insets.systemBarsAppearance and status)
        assertEquals("The request must not rewrite navigation appearance", navigation,
            insets.systemBarsAppearance and navigation)
        appearance.prepare()
        appearance.setEnabled(false)
        assertTrue("The initial false callback must preserve the prepared window", view.isAttachedToWindow)
        assertTrue(updates.isEmpty())
        verify(manager, never()).removeViewImmediate(view)
        appearance.setEnabled(true)
        assertSame(view, updates.single().first)
        assertEquals(1f, updates.single().second.alpha, 0f)
        appearance.setEnabled(true)
        assertEquals("Repeated synchronization cannot add another companion", 1, requests.size)
        assertEquals(1, updates.size)
        appearance.setEnabled(false)
        assertEquals(0f, updates.last().second.alpha, 0f)
        assertTrue(view.isAttachedToWindow)
        appearance.setEnabled(false)
        assertEquals(2, updates.size)
        verify(manager, never()).removeViewImmediate(view)
        appearance.setEnabled(true)
        assertEquals("Reopening must not reattach the prepared companion", 1, requests.size)
        assertEquals(1f, updates.last().second.alpha, 0f)
        appearance.release()
        assertNull(view.parent)
        assertFalse(view.isAttachedToWindow)
        appearance.release()
        verify(manager, times(1)).removeViewImmediate(view)
        appearance.setEnabled(true)
        assertEquals(2, requests.size)
        assertEquals("One helper reuses its application-overlay context", 1, contextCreations)
        assertNotSame(view, requests.last().first)
    }

    @Test fun staleViewCallbacksCannotRetireOrReapplyAReplacementOwner() {
        appearance.setEnabled(true)
        val original = requests.single().first
        val listenerInfo = ReflectionHelpers.getField<Any>(original, "mListenerInfo")
        val callbacks = ReflectionHelpers.getField<List<View.OnAttachStateChangeListener>>(
            listenerInfo, "mOnAttachStateChangeListeners")
        val previousCallback = callbacks.single()
        appearance.release()
        appearance.setEnabled(true)
        val replacement = requests.last().first
        previousCallback.onViewDetachedFromWindow(original)
        previousCallback.onViewAttachedToWindow(original)
        appearance.setEnabled(true)
        assertEquals(2, requests.size)
        assertSame(replacement, host.getChildAt(0))
        appearance.release()
        assertNull(replacement.parent)
        verify(manager).removeViewImmediate(replacement)
    }

    @Test fun failedPartialAttachmentIsRemovedBeforeAnotherAcquisition() {
        failAfterAdd = true
        appearance.setEnabled(true)
        val failed = requests.single().first
        assertNull("A failed acquisition cannot leave its attached window behind", failed.parent)
        assertFalse(failed.isAttachedToWindow)
        verify(manager).removeViewImmediate(failed)
        failAfterAdd = false
        appearance.setEnabled(true)
        assertEquals(2, requests.size)
        assertNotSame(failed, requests.last().first)
        assertEquals(1, host.childCount)
    }

    @Test fun aFailedRemovalRetainsItsOwnerAndRetriesWithoutAddingADuplicate() {
        appearance.setEnabled(true)
        val original = requests.single().first
        failRemoval = true
        appearance.release()
        assertSame(host, original.parent)
        appearance.setEnabled(true)
        assertEquals("A still-owned window must block duplicate attachment after failed removal", 1, requests.size)
        failRemoval = false
        appearance.release()
        assertNull(original.parent)
        verify(manager, times(3)).removeViewImmediate(original)
        appearance.setEnabled(true)
        assertEquals(2, requests.size)
    }

    @Test fun failedAttachmentAndFailedCleanupKeepThePartialWindowAvailableForRetry() {
        failAfterAdd = true
        failRemoval = true
        appearance.setEnabled(true)
        val partial = requests.single().first
        assertSame(host, partial.parent)
        appearance.setEnabled(true)
        assertEquals("An orphaned partial attachment must block another window", 1, requests.size)
        failAfterAdd = false
        failRemoval = false
        appearance.setEnabled(true)
        assertNull(partial.parent)
        assertEquals(2, requests.size)
        assertEquals(1, host.childCount)
        verify(manager, times(3)).removeViewImmediate(partial)
    }

    @Test fun aFailedActivationOrDeactivationRemovesItsUncertainWindowBeforeRetrying() {
        for (activating in listOf(true, false)) {
            appearance.prepare()
            if (!activating) appearance.setEnabled(true)
            val failed = requests.last().first
            failUpdate = true
            appearance.setEnabled(activating)
            assertNull("A failed alpha update cannot retain an eligible companion", failed.parent)
            verify(manager).removeViewImmediate(failed)
            failUpdate = false
            appearance.setEnabled(true)
            assertNotSame(failed, requests.last().first)
            assertEquals(1, host.childCount)
            appearance.release()
        }
    }

    @Test fun aFailedUpdateAndRemovalKeepTheExactOwnerUntilCleanupCanSucceed() {
        appearance.setEnabled(true)
        val original = requests.single().first
        failUpdate = true
        failRemoval = true
        appearance.setEnabled(false)
        assertSame(host, original.parent)
        appearance.setEnabled(true)
        assertEquals("An uncertain update must not allow a second companion", 1, requests.size)
        failUpdate = false
        failRemoval = false
        appearance.setEnabled(true)
        assertNull(original.parent)
        assertEquals(2, requests.size)
        assertEquals(1, host.childCount)
        verify(manager, times(3)).removeViewImmediate(original)
    }

    @Test fun buttonNavigationNeverAcquiresACompanionEvenWhenTheIslandCoversTheStatusBar() {
        for ((navigation, tappable) in listOf(
            Insets.of(0, 0, 0, 144) to Insets.of(0, 200, 0, 144),
            Insets.of(0, 0, 0, 96) to Insets.of(0, 200, 0, 96),
            Insets.of(144, 0, 0, 0) to Insets.of(144, 200, 0, 0),
            Insets.of(0, 0, 144, 0) to Insets.of(0, 200, 144, 0),
        )) {
            displayInsets = navigationInsets(navigation, tappable)
            appearance.prepare()
            appearance.setEnabled(true)
        }
        assertTrue("Button navigation must retain the foreground app's icon policy", requests.isEmpty())
        assertTrue(updates.isEmpty())
    }

    @Test fun missingOrUnavailableNavigationMetricsCannotOverrideTheForegroundPolicy() {
        displayInsets = WindowInsets.CONSUMED
        appearance.setEnabled(true)
        displayInsets = navigationInsets(Insets.NONE)
        appearance.setEnabled(true)
        displayInsets = navigationInsets(Insets.of(0, 72, 0, 0))
        appearance.setEnabled(true)
        failMetrics = true
        appearance.setEnabled(true)
        assertTrue(requests.isEmpty())
        assertTrue(updates.isEmpty())
    }

    @Test fun changingToButtonNavigationRetiresTheActiveOwnerAndGestureNavigationCanReopen() {
        appearance.setEnabled(true)
        val original = requests.single().first
        displayInsets = navigationInsets(Insets.of(0, 0, 0, 144), Insets.of(0, 200, 0, 144))
        original.dispatchApplyWindowInsets(displayInsets)
        assertNull("An insets change must release the active appearance immediately", original.parent)
        verify(manager).removeViewImmediate(original)
        appearance.setEnabled(true)
        assertEquals(1, requests.size)
        displayInsets = navigationInsets()
        appearance.setEnabled(true)
        val replacement = requests.last().first
        assertNotSame(original, replacement)
        original.dispatchApplyWindowInsets(displayInsets)
        assertSame(host, replacement.parent)
    }

    @Test fun unavailableMetricsReleaseAnExistingPreparedOrActiveCompanion() {
        for (active in listOf(false, true)) {
            appearance.prepare()
            if (active) appearance.setEnabled(true)
            val original = requests.last().first
            failMetrics = true
            appearance.prepare()
            assertNull(original.parent)
            verify(manager).removeViewImmediate(original)
            failMetrics = false
        }
    }

    private fun navigationInsets(navigation: Insets = Insets.of(0, 0, 0, 72),
                                 tappable: Insets = Insets.of(0, 200, 0, 0)): WindowInsets =
        WindowInsets.Builder()
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), navigation)
            .setInsetsIgnoringVisibility(WindowInsets.Type.tappableElement(), tappable)
            .build()
}
