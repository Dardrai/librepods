/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.MainThread

/** A prewarmed appearance request; alpha zero restores the current foreground app's policy. */
internal class IslandStatusBarAppearance(
    context: Context,
    windowContextFactory: () -> Context = {
        val display = runCatching { context.display }.getOrNull()
            ?: checkNotNull(context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY))
        context.applicationContext.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    },
) {
    private val windowContext by lazy(windowContextFactory)
    private var view: View? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    @MainThread
    fun prepare() {
        try {
            val context = windowContext
            val windowManager = checkNotNull(context.getSystemService(WindowManager::class.java))
            if (!allowsAppearance(windowManager)) {
                release()
                return
            }
            if (view != null) {
                if (added) return
                // A failed attachment may still own a window when its first removal also failed.
                release()
                if (view != null) return
            }
            val next = View(context)
            next.setOnApplyWindowInsetsListener { attached, insets ->
                if (view === attached && !allowsAppearance(windowManager)) release()
                insets
            }
            next.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) {
                    if (view !== attached) return
                    attached.windowInsetsController?.setSystemBarsAppearance(
                        0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                }

                override fun onViewDetachedFromWindow(detached: View) {
                    forget(detached)
                }
            })
            val params = WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                dimAmount = 0f
                // Fully transparent windows cannot affect DisplayPolicy's system-bar appearance.
                alpha = 0f
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setTitle("LibrePods island status appearance")
            }
            view = next
            manager = windowManager
            this.params = params
            windowManager.addView(next, params)
            if (view === next) added = true
        } catch (error: Exception) {
            Log.w("IslandStatusBar", "Could not attach island status appearance", error)
            release()
        }
    }

    @MainThread
    fun setEnabled(enabled: Boolean) {
        if (enabled) prepare()
        val current = view ?: return
        if (!added) {
            if (!enabled) release()
            return
        }
        val previous = params ?: return
        val alpha = if (enabled) 1f else 0f
        if (previous.alpha == alpha) return
        val next = WindowManager.LayoutParams().apply {
            copyFrom(previous)
            this.alpha = alpha
        }
        try {
            manager?.updateViewLayout(current, next)
            if (view === current) params = next
        } catch (error: Exception) {
            Log.w("IslandStatusBar", "Could not update island status appearance", error)
            release()
        }
    }

    @MainThread
    fun release() {
        val previous = view ?: return
        added = false
        try {
            manager?.removeViewImmediate(previous)
        } catch (error: Exception) {
            Log.w("IslandStatusBar", "Could not remove island status appearance", error)
        } finally {
            // Retain an attached failed removal so a later call can retry it.
            if (previous.parent == null && !previous.isAttachedToWindow) forget(previous)
        }
    }

    private fun forget(expected: View) {
        if (view !== expected) return
        view = null
        manager = null
        params = null
        added = false
    }

    private fun allowsAppearance(windowManager: WindowManager): Boolean = runCatching {
        val insets = windowManager.currentWindowMetrics.windowInsets
        val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        val tappable = insets.getInsetsIgnoringVisibility(WindowInsets.Type.tappableElement())
        // A dimming window also wins navigation icon policy; only gesture handles sample their own contrast.
        navigation.bottom > 0 && navigation.left == 0 && navigation.top == 0 && navigation.right == 0 &&
            tappable.left == 0 && tappable.right == 0 && tappable.bottom == 0
    }.getOrDefault(false)
}
