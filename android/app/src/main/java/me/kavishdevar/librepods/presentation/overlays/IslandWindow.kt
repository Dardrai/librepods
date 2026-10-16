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
import android.database.ContentObserver
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.math.abs
import kotlin.math.min

enum class IslandType { CONNECTED, TAKING_OVER, MOVED_TO_REMOTE, MOVED_TO_OTHER_DEVICE }

/** One non-focus-taking capsule; only explicit events extend its lifetime. */
@SuppressLint("ClickableViewAccessibility")
class IslandWindow(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val state = IslandState()
    @SuppressLint("InflateParams")
    private val view = LayoutInflater.from(context).inflate(R.layout.island_window, null)
    private val header = view.findViewById<View>(R.id.island_header)
    private val batteryRow = view.findViewById<View>(R.id.island_battery_row)
    private val name = view.findViewById<TextView>(R.id.island_device_name)
    private val message = view.findViewById<TextView>(R.id.island_connected_text)
    private val artwork = view.findViewById<ImageView>(R.id.island_artwork)
    private val undo = view.findViewById<ImageButton>(R.id.island_action_button)
    private val cells = listOf(
        batteryCell(BatteryComponent.LEFT, R.id.island_left_cell, R.id.island_left_ring, R.id.island_left_value, R.id.island_left_label, R.string.left),
        batteryCell(BatteryComponent.RIGHT, R.id.island_right_cell, R.id.island_right_ring, R.id.island_right_value, R.id.island_right_label, R.string.right),
        batteryCell(BatteryComponent.CASE, R.id.island_case_cell, R.id.island_case_ring, R.id.island_case_value, R.id.island_case_label, R.string.case_alt)
    )
    private var animation: ValueAnimator? = null
    private var animationGeneration = 0L
    private var expansion = 0f
    private var closing = false
    private var attached = false
    private var observingAnimations = false
    private var artworkResource = 0
    private var startX = 0f
    private var startY = 0f
    private var dragged = false
    private val dismiss: Runnable = Runnable { close() }
    private val refreshAge = Runnable { if (attached && !closing) renderSnapshot() }
    private val animations = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                cancelAnimation()
                if (closing) remove() else renderExpansion(1f)
            }
        }
    }

    val isVisible: Boolean get() = attached && !closing

    init {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val width = (dp(96) + (v.width - dp(96)) * expansion).toInt().coerceAtMost(v.width)
                val height = (dp(34) + (v.height - dp(34)) * expansion).toInt().coerceAtMost(v.height)
                val left = (v.width - width) / 2
                outline.setRoundRect(left, 0, left + width, height, min(dp(28).toFloat(), height / 2f))
            }
        }
        view.setOnClickListener {
            ServiceManager.getService()?.startMainActivity()
            close()
        }
        view.setOnTouchListener { _, event -> touch(event) }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            adaptBatteryRow()
            view.invalidateOutline()
        }
    }

    fun show(snapshot: IslandSnapshot, type: IslandType = IslandType.CONNECTED,
             reversed: Boolean = false, otherDeviceName: String? = null, message: String? = null): Unit = onMain {
        if (snapshot.address.isBlank() || !Settings.canDrawOverlays(context)) return@onMain
        if (state.snapshot?.address != snapshot.address && attached) remove()
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        val hold = accessibility.getRecommendedTimeoutMillis(4500,
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_CONTROLS)
        state.show(snapshot, SystemClock.uptimeMillis(), hold.toLong())
        closing = false
        handler.removeCallbacks(dismiss)
        val body = message ?: when (type) {
            IslandType.CONNECTED -> context.getString(R.string.island_connected_text)
            IslandType.TAKING_OVER -> context.getString(R.string.island_taking_over_text)
            IslandType.MOVED_TO_REMOTE -> context.getString(R.string.island_moved_to_remote_text)
            IslandType.MOVED_TO_OTHER_DEVICE -> if (reversed || otherDeviceName.isNullOrBlank())
                context.getString(R.string.island_moved_to_other_device_reversed_text)
            else context.getString(R.string.island_moved_to_other_device_text, otherDeviceName)
        }
        this.message.text = body
        undo.visibility = if (type == IslandType.MOVED_TO_OTHER_DEVICE && !reversed) View.VISIBLE else View.GONE
        undo.setOnClickListener {
            ServiceManager.getService()?.takeOver("reverse")
            close()
        }
        renderSnapshot()
        if (!attached) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val params = WindowManager.LayoutParams(
                min(dp(400), bounds.width() - insets.left - insets.right - dp(32)).coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(12) }
            renderExpansion(0f)
            if (runCatching { windowManager.addView(view, params) }.isFailure) {
                state.hide()
                return@onMain
            }
            attached = true
            observingAnimations = runCatching {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animations)
            }.isSuccess
        }
        animateTo(1f)
        handler.postAtTime(dismiss, state.dismissAt)
        scheduleAgeRefresh()
    }

    fun updateSnapshot(snapshot: IslandSnapshot): Unit = onMain {
        if (!attached || closing || !state.update(snapshot)) return@onMain
        renderSnapshot()
    }

    private fun renderSnapshot() {
        val snapshot = state.snapshot ?: return
        name.text = snapshot.name
        val resource = OverlayArtwork.island(snapshot.modelNumber)
        if (resource != artworkResource) {
            artworkResource = resource
            artwork.setImageResource(resource)
        }
        val headphones = OverlayArtwork.isHeadphones(snapshot.modelNumber)
        cells[1].view.visibility = if (headphones) View.GONE else View.VISIBLE
        cells[2].view.visibility = if (headphones) View.GONE else View.VISIBLE
        cells[0].label.text = if (headphones) snapshot.name else context.getString(R.string.left)
        val now = System.currentTimeMillis()
        val descriptions = cells.filter { it.view.visibility == View.VISIBLE }.map { cell ->
            val battery = snapshot.batteries.find { it.component == cell.component }
                ?: snapshot.batteries.find { headphones && it.component == BatteryComponent.RIGHT }
            val reading = islandBatteryReading(battery, now)
            cell.value.text = reading.level?.let { "$it%" } ?: "—"
            cell.view.alpha = if (reading.live) 1f else 0.75f
            cell.ring.setReading(reading)
            val detail = listOfNotNull(
                cell.label.text.toString(),
                reading.level?.let { "$it%" } ?: context.getString(R.string.island_battery_unavailable),
                context.getString(R.string.island_battery_charging).takeIf { reading.charging },
                reading.updatedAt?.takeIf { !reading.live }?.let {
                    context.getString(R.string.battery_cached_age, ((now - it) / 60_000).coerceAtLeast(0))
                }
            ).joinToString(", ")
            cell.view.contentDescription = detail
            detail
        }
        view.contentDescription = listOf(snapshot.name, message.text.toString(), descriptions.joinToString(", "),
            context.getString(R.string.island_open_controls)).joinToString(", ")
        scheduleAgeRefresh()
    }

    private fun scheduleAgeRefresh() {
        handler.removeCallbacks(refreshAge)
        if (!attached || closing) return
        val now = System.currentTimeMillis()
        val expires = state.snapshot?.batteries?.filter { it.isLive(now) }
            ?.mapNotNull { it.updatedAt?.plus(me.kavishdevar.librepods.bluetooth.BleBatteryCache.FRESH_MS) }?.minOrNull() ?: return
        val delay = expires - now
        if (delay > 0 && SystemClock.uptimeMillis() + delay < state.dismissAt) handler.postDelayed(refreshAge, delay)
    }

    private fun animateTo(target: Float) {
        cancelAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            renderExpansion(target)
            if (target == 0f) remove()
            return
        }
        val generation = animationGeneration
        animation = ValueAnimator.ofFloat(expansion, target).apply {
            duration = ((if (target == 1f) 420 else 220) * abs(target - expansion)).toLong().coerceAtLeast(1)
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { renderExpansion(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    if (generation != animationGeneration) return
                    animation = null
                    if (target == 0f) remove()
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        animationGeneration++
        animation?.removeAllListeners()
        animation?.cancel()
        animation?.removeAllUpdateListeners()
        animation = null
    }

    private fun renderExpansion(value: Float) {
        expansion = value
        header.alpha = islandStageOpacity(value, 0.08f)
        header.translationY = dp(5) * (1 - header.alpha)
        batteryRow.alpha = islandStageOpacity(value, 0.32f)
        batteryRow.translationY = dp(7) * (1 - batteryRow.alpha)
        view.invalidateOutline()
    }

    private fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY; dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) dragged = true
                if (dragged) {
                    view.translationX = dx * 0.5f
                    view.translationY = dy * 0.5f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                view.translationX = 0f; view.translationY = 0f
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    when {
                        !dragged -> view.performClick()
                        dy > dp(48) && abs(dy) > abs(dx) -> view.performClick()
                        abs(dx) > dp(48) || dy < -dp(32) -> close()
                    }
                }
                return true
            }
        }
        return false
    }

    fun close(): Unit = onMain {
        if (!attached || closing) return@onMain
        closing = true
        handler.removeCallbacks(dismiss)
        handler.removeCallbacks(refreshAge)
        animateTo(0f)
    }

    fun forceClose(): Unit = onMain { remove() }

    private fun remove() {
        cancelAnimation()
        handler.removeCallbacks(dismiss)
        handler.removeCallbacks(refreshAge)
        if (observingAnimations) runCatching { context.contentResolver.unregisterContentObserver(animations) }
        observingAnimations = false
        if (attached) runCatching { windowManager.removeViewImmediate(view) }
        attached = false
        closing = false
        state.hide()
        expansion = 0f
        view.translationX = 0f; view.translationY = 0f
    }

    private data class Cell(val component: Int, val view: LinearLayout, val ring: IslandBatteryRingView,
                            val value: TextView, val label: TextView)

    private fun batteryCell(component: Int, cell: Int, ring: Int, value: Int, label: Int, text: Int): Cell =
        Cell(component, view.findViewById(cell), view.findViewById(ring), view.findViewById(value),
            view.findViewById<TextView>(label).apply { setText(text) })

    private fun adaptBatteryRow() {
        val compact = (view.width - view.paddingStart - view.paddingEnd) / context.resources.displayMetrics.scaledDensity < 300
        cells.forEach { cell ->
            val orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (cell.view.orientation != orientation) {
                cell.view.orientation = orientation
                cell.view.gravity = Gravity.CENTER
                (cell.value.parent as View).layoutParams = LinearLayout.LayoutParams(
                    if (compact) LinearLayout.LayoutParams.WRAP_CONTENT else 0, LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (compact) 0f else 1f).apply { marginStart = if (compact) 0 else dp(6); topMargin = if (compact) dp(4) else 0 }
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() } }
}
