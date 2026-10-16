/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/


package me.kavishdevar.librepods.presentation.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import me.kavishdevar.librepods.R
import kotlin.math.min

/** A single invitation; Bluetooth work belongs to NearbyPairingController. */
@SuppressLint("InflateParams", "ClickableViewAccessibility")
class PopupWindow(private val context: Context, private val onCloseCallback: () -> Unit = {}) {
    private val mView: View = LayoutInflater.from(context).inflate(R.layout.popup_window, null)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val button = mView.findViewById<Button>(R.id.pairing_primary)
    private val progress = mView.findViewById<View>(R.id.pairing_progress)
    private val artwork = PairingArtwork(mView.findViewById(R.id.popup_artwork_container))
    private var attached = false
    private var closing = false
    private var notified = false
    private var interacted = false
    private var animation: ValueAnimator? = null
    private var generation = 0L
    private var downY = 0f
    private var downScroll = 0
    private var expansion = 0f
    private var params: WindowManager.LayoutParams? = null
    private val expire = Runnable { close() }

    init {
        mView.clipToOutline = true
        mView.findViewById<ImageButton>(R.id.close_button).apply {
            imageTintList = ColorStateList.valueOf(Color.rgb(99, 99, 102))
            setOnClickListener { close() }
        }
        mView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> { close(); true }
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; downScroll = mView.scrollY; false }
                MotionEvent.ACTION_UP -> {
                    if (downScroll == 0 && event.rawY - downY > dp(72)) { close(); true } else false
                }
                else -> false
            }
        }
    }

    fun openPairing(name: String, modelId: Int) {
        if (attached || closing || notified) return
        if (!Settings.canDrawOverlays(context)) {
            handler.post { forceClose() }
            return
        }
        mView.findViewById<TextView>(R.id.name).text = name
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val bounds = metrics.bounds
        val width = min(dp(400), bounds.width() - insets.left - insets.right - dp(32)).coerceAtLeast(1)
        val maxHeight = (bounds.height() - insets.top - insets.bottom - dp(32)).coerceAtLeast(1)
        mView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val params = WindowManager.LayoutParams(width, min(mView.measuredHeight, maxHeight),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(16)
            }
        this.params = params
        try {
            windowManager.addView(mView, params)
            attached = true
            artwork.start(modelId)
            animate(opening = true)
            handler.postDelayed(expire, 12_000)
        } catch (e: RuntimeException) {
            Log.w("PopupWindow", "Cannot show pairing invitation", e)
            // Post so the controller has stored this card before its dismissal callback.
            handler.post { forceClose() }
        }
    }

    fun showPairingActions(message: String, actions: List<Pair<String, () -> Unit>>, busy: Boolean = actions.isEmpty()) {
        if (closing || notified) return
        mView.findViewById<TextView>(R.id.pairing_message).text = message
        val action = actions.singleOrNull()
        button.text = action?.first ?: context.getString(R.string.pairing_connect)
        button.isEnabled = action != null && !busy
        button.importantForAccessibility = if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        button.visibility = if (!busy && action == null) View.INVISIBLE else View.VISIBLE
        button.setTextColor(if (busy) android.graphics.Color.TRANSPARENT else android.graphics.Color.WHITE)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        button.setOnClickListener {
            if (!closing && action != null) {
                interacted = true
                handler.removeCallbacks(expire)
                action.second()
            }
        }
        if (interacted || busy) handler.removeCallbacks(expire)
        resize()
    }

    private fun resize() {
        val layout = params ?: return
        if (!attached) return
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val maxHeight = (metrics.bounds.height() - insets.top - insets.bottom - dp(32)).coerceAtLeast(1)
        mView.measure(View.MeasureSpec.makeMeasureSpec(layout.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        layout.height = min(mView.measuredHeight, maxHeight)
        runCatching { windowManager.updateViewLayout(mView, layout) }.onFailure { forceClose() }
    }

    fun close() {
        if (closing || notified) return
        closing = true
        artwork.stop()
        handler.removeCallbacks(expire)
        notifyDismissed()
        if (attached) animate(opening = false) else remove()
    }

    fun forceClose() {
        closing = true
        artwork.stop()
        handler.removeCallbacks(expire)
        cancelAnimation()
        notifyDismissed()
        remove()
    }

    private fun notifyDismissed() {
        if (!notified) { notified = true; onCloseCallback() }
    }

    private fun animate(opening: Boolean) {
        cancelAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            render(if (opening) 1f else 0f)
            if (!opening) remove()
            return
        }
        val token = generation
        val from = expansion
        val to = if (opening) 1f else 0f
        render(from)
        animation = ValueAnimator.ofFloat(from, to).apply {
            duration = ((if (opening) 420 else 220) * kotlin.math.abs(to - from)).toLong().coerceAtLeast(1)
            interpolator = DecelerateInterpolator()
            addUpdateListener { render(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token == generation && !opening) remove()
                }
            })
            start()
        }
    }

    private fun render(amount: Float) {
        expansion = amount
        mView.translationY = dp(48) * (1 - amount)
        mView.alpha = amount
        mView.scaleX = .96f + .04f * amount
        mView.scaleY = .96f + .04f * amount
        mView.findViewById<View>(R.id.popup_artwork_container).alpha = islandStageOpacity(amount, .12f)
        mView.findViewById<View>(R.id.pairing_actions).alpha = islandStageOpacity(amount, .3f)
    }

    private fun cancelAnimation() {
        generation++
        animation?.cancel()
        animation = null
    }

    private fun remove() {
        artwork.stop()
        cancelAnimation()
        if (attached) runCatching { windowManager.removeViewImmediate(mView) }
        attached = false
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
