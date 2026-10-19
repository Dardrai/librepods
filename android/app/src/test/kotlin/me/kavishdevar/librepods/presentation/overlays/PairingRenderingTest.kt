package me.kavishdevar.librepods.presentation.overlays

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PairingRenderingTest {
    private data class Profile(val label: String, val width: Int, val height: Int, val fontScale: Float, val night: Boolean = false)

    @Test fun phasesRemainLegibleAndActionsStayReachableWithLargeTextAndShortScreens() {
        for (profile in listOf(Profile("light", 412, 900, 1f), Profile("dark-large", 320, 900, 1.3f, true),
            Profile("landscape-large", 800, 360, 2f))) {
            val context = context(profile)
            var clicked = 0
            var dismissed = 0
            val popup = PopupWindow(context) { dismissed++ }
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                invoke(popup, "cancelAnimation")
                render(popup, 1f)
                val view = view(popup)
                val button = view.findViewById<Button>(R.id.pairing_primary)
                for (stage in PopupWindow.Stage.entries) {
                    val busy = stage == PopupWindow.Stage.SETUP || stage == PopupWindow.Stage.CONNECTING
                    val message = when (stage) {
                        PopupWindow.Stage.NEARBY -> context.getString(R.string.pairing_double_tap) + "\n\n" +
                            context.getString(R.string.pairing_nearby_hint)
                        PopupWindow.Stage.SETUP -> context.getString(R.string.pairing_double_tap)
                        PopupWindow.Stage.CONNECTING -> context.getString(R.string.pairing_confirm_system)
                        PopupWindow.Stage.COMPLETE -> context.getString(R.string.pairing_complete)
                        PopupWindow.Stage.RETRY -> context.getString(R.string.pairing_double_tap) + "\n\n" +
                            context.getString(R.string.pairing_bond_failed)
                    }
                    val label = when (stage) {
                        PopupWindow.Stage.COMPLETE -> "Continue"
                        PopupWindow.Stage.RETRY -> context.getString(R.string.pairing_retry)
                        else -> context.getString(R.string.pairing_connect)
                    }
                    val actions: List<Pair<String, () -> Unit>> = if (busy) emptyList() else listOf(label to { clicked += 1 })
                    popup.showPairingActions(message, actions, busy, stage)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                    layout(popup)
                    render(popup, 1f)
                    view.scrollTo(0, 0)
                    assertFalse(view.isForceDarkAllowed)
                    val lightTheme = context.resources.getIdentifier("isLightTheme", "attr", "android")
                    view.context.obtainStyledAttributes(intArrayOf(lightTheme, android.R.attr.forceDarkAllowed)).let {
                        try { assertFalse(it.getBoolean(0, true)); assertFalse(it.getBoolean(1, true)) } finally { it.recycle() }
                    }
                    val name = view.findViewById<TextView>(R.id.name)
                    val body = view.findViewById<TextView>(R.id.pairing_message)
                    assertTrue(Color.luminance(name.currentTextColor) < 0.2)
                    val headline = requireNotNull(name.layout)
                    for (line in 0 until headline.lineCount)
                        assertEquals("The actual pairing headline must remain complete", 0, headline.getEllipsisCount(line))
                    assertTrue("Body text contrast", 1.05 / (Color.luminance(body.currentTextColor) + 0.05) >= 4.5)
                    assertEquals(message, body.text.toString())
                    assertEquals(if (stage == PopupWindow.Stage.COMPLETE) View.INVISIBLE else View.VISIBLE, body.visibility)
                    assertEquals(if (stage == PopupWindow.Stage.COMPLETE) View.VISIBLE else View.INVISIBLE,
                        view.findViewById<View>(R.id.pairing_battery_row).visibility)
                    assertNotNull(view.findViewById<ImageView>(R.id.popup_artwork).drawable)
                    val close = view.findViewById<View>(R.id.close_button)
                    assertTrue(close.width >= 48 * context.resources.displayMetrics.density)
                    assertTrue(close.height >= 48 * context.resources.displayMetrics.density)
                    assertContained(view, close)
                    assertContained(view, name)
                    assertEquals(if (busy) View.GONE else View.VISIBLE, button.visibility)
                    assertEquals(if (busy) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.pairing_progress).visibility)
                    assertEquals(!busy, button.isEnabled)
                    assertEquals(if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                        button.importantForAccessibility)
                    val preview = "pairing-" + profile.label + "-" + stage.name.lowercase()
                    save(view, preview)
                    if (profile.height == 360) {
                        assertTrue("Short screens must scroll", view.getChildAt(0).height > view.height)
                        view.scrollTo(0, (view.getChildAt(0).height - view.height).coerceAtLeast(0))
                        assertTrue(view.scrollY > 0)
                        assertContained(view, view.findViewById(R.id.pairing_actions))
                        if (!busy) assertContained(view, button)
                        save(view, preview + "-scrolled")
                        view.scrollTo(0, 0)
                        assertContained(view, close)
                    } else {
                        for (id in listOf(R.id.popup_artwork_container, R.id.pairing_message, R.id.pairing_actions))
                            view.findViewById<View>(id).takeIf { it.visibility == View.VISIBLE }?.let { assertContained(view, it) }
                        if (!busy) assertContained(view, button)
                    }
                    if (!busy) {
                        assertTrue(button.height >= 48 * context.resources.displayMetrics.density)
                        val before = clicked
                        button.performClick()
                        assertEquals(before + 1, clicked)
                    }
                    val previousHeight = view.height
                    val previousArtwork = bounds(view, view.findViewById(R.id.popup_artwork_container))
                    popup.showPairingActions(message, actions, busy, stage)
                    layout(popup)
                    assertEquals("Duplicate status must retain its layout", previousHeight, view.height)
                    assertEquals(previousArtwork, bounds(view, view.findViewById(R.id.popup_artwork_container)))
                    assertEquals("Status must not replay entrance", 0f, view.translationY, 0f)
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
                assertEquals("Active pairing must not retain invitation expiry", 0, dismissed)
                popup.close()
                popup.close()
                assertEquals(1, dismissed)
                val before = clicked
                button.performClick()
                assertEquals("Closing must retire the action", before, clicked)
            } finally { popup.forceClose() }
        }
    }

    @Test fun completeBatteryReadingsPreserveBothBudsAndNeverTurnUnavailableDataIntoZeroOrCharging() {
        for (profile in listOf(Profile("light", 412, 900, 1f), Profile("dark-large", 320, 900, 1.3f, true),
            Profile("landscape-large", 800, 360, 2f))) {
            val context = context(profile)
            val popup = PopupWindow(context)
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                invoke(popup, "cancelAnimation")
                render(popup, 1f)
                val view = view(popup)
                val row = view.findViewById<View>(R.id.pairing_battery_row)
                val buds = view.findViewById<TextView>(R.id.pairing_buds_battery_text)
                val right = view.findViewById<TextView>(R.id.pairing_right_battery_text)
                val case = view.findViewById<TextView>(R.id.pairing_case_battery_text)
                val now = System.currentTimeMillis()
                val balanced = setOf(Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING, now),
                    Battery(BatteryComponent.RIGHT, 85, BatteryStatus.OPTIMIZED_CHARGING, now),
                    Battery(BatteryComponent.CASE, 97, BatteryStatus.NOT_CHARGING, now))
                popup.updatePairingBattery(balanced)
                assertEquals(View.INVISIBLE, row.visibility)
                popup.showPairingActions(context.getString(R.string.pairing_complete),
                    listOf(context.getString(R.string.pairing_continue) to {}), busy = false, stage = PopupWindow.Stage.COMPLETE)
                assertEquals(context.getString(R.string.pairing_battery_unknown), buds.text.toString())
                assertEquals(context.getString(R.string.pairing_battery_unknown), case.text.toString())
                layout(popup)
                val unknownHeight = row.height
                popup.updatePairingBattery(balanced)
                layout(popup)
                assertEquals("The reserved value lines must keep the sheet steady", unknownHeight, row.height)
                assertEquals("85%", buds.text.toString())
                assertEquals(View.GONE, right.visibility)
                assertEquals("97%", case.text.toString())
                assertEquals(3, (buds.compoundDrawablesRelative[0] as LayerDrawable).numberOfLayers)
                val chargedIcon = buds.compoundDrawablesRelative[0] as LayerDrawable
                assertEquals((8 * context.resources.displayMetrics.density).toInt(), chargedIcon.getLayerWidth(2))
                assertEquals((12 * context.resources.displayMetrics.density).toInt(), chargedIcon.getLayerHeight(2))
                assertEquals(2, (case.compoundDrawablesRelative[0] as LayerDrawable).numberOfLayers)
                assertTrue(buds.contentDescription.toString().contains(context.getString(R.string.island_battery_left)))
                assertTrue(buds.contentDescription.toString().contains(context.getString(R.string.island_battery_right)))
                if (profile.height == 360) view.scrollTo(0, bounds(view, row).top.coerceAtLeast(0))
                save(view, "pairing-battery-" + profile.label + "-balanced")
                view.scrollTo(0, 0)

                popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING, now),
                    Battery(BatteryComponent.RIGHT, 69, BatteryStatus.CHARGING, now),
                    Battery(BatteryComponent.CASE, 10, BatteryStatus.CHARGING, now)))
                layout(popup)
                assertEquals("Unequal readings must not move the success card", unknownHeight, row.height)
                assertEquals(context.getString(R.string.pairing_battery_component_value,
                    context.getString(R.string.island_battery_left), "80%"), buds.text.toString())
                assertEquals(context.getString(R.string.pairing_battery_component_value,
                    context.getString(R.string.island_battery_right), "69%"), right.text.toString())
                assertEquals(View.VISIBLE, right.visibility)
                assertEquals(2, (buds.compoundDrawablesRelative[0] as LayerDrawable).numberOfLayers)
                assertEquals(3, (right.compoundDrawablesRelative[0] as LayerDrawable).numberOfLayers)
                val leftBounds = bounds(view, buds)
                val rightBounds = bounds(view, right)
                assertTrue("Both bud readings need separate lines", leftBounds.bottom <= rightBounds.top)
                assertTrue("Case and bud columns must not overlap", leftBounds.right <= bounds(view, case).left)
                for (text in listOf(buds, right, case)) {
                    val textLayout = requireNotNull(text.layout)
                    for (line in 0 until textLayout.lineCount) assertEquals(0, textLayout.getEllipsisCount(line))
                }
                if (profile.height != 360) assertContained(view, row)
                assertEquals("Battery reports must not replay entrance", 0f, view.translationY, 0f)
                if (profile.height == 360) view.scrollTo(0, bounds(view, row).top.coerceAtLeast(0))
                save(view, "pairing-battery-" + profile.label + "-split")
                view.scrollTo(0, 0)

                popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 0, BatteryStatus.NOT_CHARGING, now),
                    Battery(BatteryComponent.RIGHT, 50, BatteryStatus.UNKNOWN, now),
                    Battery(BatteryComponent.CASE, 90, BatteryStatus.NOT_CHARGING, now - 90_000)))
                assertTrue(buds.text.toString().endsWith("0%"))
                assertTrue(right.text.toString().endsWith(context.getString(R.string.pairing_battery_unknown)))
                assertEquals(context.getString(R.string.pairing_battery_unknown), case.text.toString())
                popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 101, BatteryStatus.NOT_CHARGING, now),
                    Battery(BatteryComponent.RIGHT, 45, BatteryStatus.NOT_CHARGING, null),
                    Battery(BatteryComponent.CASE, 98, BatteryStatus.CHARGING, now, present = false)))
                assertEquals(context.getString(R.string.pairing_battery_unknown), buds.text.toString())
                assertEquals(context.getString(R.string.pairing_battery_unknown), case.text.toString())
                assertEquals(View.GONE, right.visibility)
                assertEquals(2, (case.compoundDrawablesRelative[0] as LayerDrawable).numberOfLayers)
                assertEquals(0, (case.compoundDrawablesRelative[0] as LayerDrawable).getDrawable(1).level)
                popup.showPairingActions("Retry", listOf("Retry" to {}), stage = PopupWindow.Stage.RETRY)
                assertEquals(View.INVISIBLE, row.visibility)
            } finally { popup.forceClose() }
        }
    }

    @Test fun successBatteryReadingsExpireOnceWithoutAReportOrDismissalTimer() {
        val context = context(Profile("expiry", 412, 900, 1f))
        var dismissed = 0
        val popup = PopupWindow(context) { dismissed++ }
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            invoke(popup, "cancelAnimation")
            render(popup, 1f)
            popup.showPairingActions("Paired", listOf("Continue" to {}), busy = false, stage = PopupWindow.Stage.COMPLETE)
            val now = System.currentTimeMillis()
            popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 50, BatteryStatus.NOT_CHARGING, now - 1_000),
                Battery(BatteryComponent.RIGHT, 50, BatteryStatus.CHARGING, now - 1_000),
                Battery(BatteryComponent.CASE, 80, BatteryStatus.CHARGING, now)))
            layout(popup)
            val row = view(popup).findViewById<View>(R.id.pairing_battery_row)
            val height = row.height
            val buds = view(popup).findViewById<TextView>(R.id.pairing_buds_battery_text)
            val case = view(popup).findViewById<TextView>(R.id.pairing_case_battery_text)
            assertEquals(View.VISIBLE, view(popup).findViewById<View>(R.id.pairing_right_battery_text).visibility)
            // Advancing Robolectric's Handler clock does not advance Java wall time in this runtime.
            val batteryField = PopupWindow::class.java.getDeclaredField("batteries").apply { isAccessible = true }
            val firstExpiry = System.currentTimeMillis()
            batteryField.set(popup, setOf(Battery(BatteryComponent.LEFT, 50, BatteryStatus.NOT_CHARGING, firstExpiry - 90_000),
                Battery(BatteryComponent.RIGHT, 50, BatteryStatus.CHARGING, firstExpiry - 90_000),
                Battery(BatteryComponent.CASE, 80, BatteryStatus.CHARGING, firstExpiry - 89_000)))
            assertTrue("Aging the fixture alone must not redraw it", buds.text.toString().contains("50%"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(89_100))
            layout(popup)
            assertEquals(0, dismissed)
            assertEquals(context.getString(R.string.pairing_battery_unknown), buds.text.toString())
            assertEquals("80%", case.text.toString())
            assertEquals("Expiry must keep the reserved rows steady", height, row.height)
            batteryField.set(popup, setOf(Battery(BatteryComponent.CASE, 80, BatteryStatus.CHARGING,
                System.currentTimeMillis() - 90_000)))
            assertEquals("80%", case.text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
            assertEquals(context.getString(R.string.pairing_battery_unknown), case.text.toString())
            assertEquals(2, (case.compoundDrawablesRelative[0] as LayerDrawable).numberOfLayers)
            popup.close()
            popup.updatePairingBattery(setOf(Battery(BatteryComponent.CASE, 99, BatteryStatus.CHARGING)))
            assertEquals(context.getString(R.string.pairing_battery_unknown), case.text.toString())
            assertEquals(1, dismissed)
        } finally { popup.forceClose() }
    }

    @Test fun maxShowsTheHeadsetReadingWithoutInventingBudsOrACase() {
        val context = context(Profile("headset", 412, 900, 1f))
        val popup = PopupWindow(context)
        try {
            popup.openPairing("AirPods Max", 0x0a20)
            popup.showPairingActions("Paired", listOf("Continue" to {}), busy = false, stage = PopupWindow.Stage.COMPLETE)
            popup.updatePairingBattery(setOf(Battery(BatteryComponent.HEADSET, 73, BatteryStatus.NOT_CHARGING)))
            layout(popup)
            assertEquals("73%", view(popup).findViewById<TextView>(R.id.pairing_buds_battery_text).text.toString())
            assertEquals(context.getString(R.string.island_battery_headset),
                view(popup).findViewById<TextView>(R.id.pairing_buds_battery_label).text.toString())
            assertEquals(View.GONE, view(popup).findViewById<View>(R.id.pairing_right_battery_text).visibility)
            assertEquals(View.GONE, view(popup).findViewById<View>(R.id.pairing_case_battery_column).visibility)
        } finally { popup.forceClose() }
    }

    @Test fun theWholeCardMovesWithoutPayloadFadesOrReflow() {
        val profile = Profile("motion", 412, 900, 1f)
        val context = context(profile)
        val popup = PopupWindow(context)
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            assertEquals(420L, (field(popup, "animation") as ValueAnimator).duration)
            invoke(popup, "cancelAnimation")
            popup.showPairingActions(context.getString(R.string.pairing_double_tap) + "\n\n" +
                context.getString(R.string.pairing_nearby_hint), listOf("Connect" to {}), stage = PopupWindow.Stage.NEARBY)
            layout(popup)
            val view = view(popup)
            val payloads = listOf(R.id.name, R.id.popup_artwork_container, R.id.pairing_message, R.id.pairing_actions)
                .map { view.findViewById<View>(it) }
            val settled = payloads.map { bounds(view, it) }
            for ((label, amount) in listOf("enter-start" to 0f, "enter-middle" to .5f, "entered" to 1f,
                "exit-middle" to .5f, "exit-end" to 0f)) {
                render(popup, amount)
                assertEquals((view.measuredHeight + 16 * view.resources.displayMetrics.density) * (1 - amount), view.translationY, 0.5f)
                assertEquals(1f, view.alpha, 0f)
                assertEquals(1f, view.scaleX, 0f)
                assertEquals(1f, view.scaleY, 0f)
                for ((index, child) in payloads.withIndex()) {
                    assertEquals("Content moves with the card", 1f, child.alpha, 0f)
                    assertEquals("Endpoint layout survives motion", settled[index], bounds(view, child))
                }
                saveOnScreen(popup, profile, "pairing-motion-" + label)
            }
            render(popup, 1f)
            popup.close()
            assertEquals(220L, (field(popup, "animation") as ValueAnimator).duration)
        } finally { popup.forceClose() }
    }

    @Test fun reducedMotionSnapsAndDismissesImmediately() {
        val context = context(Profile("reduced", 412, 900, 1f), motionScale = 0f)
        var dismissed = 0
        val popup = PopupWindow(context) { dismissed++ }
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            layout(popup)
            assertNull(field(popup, "animation"))
            assertEquals(0f, view(popup).translationY, 0f)
            assertEquals(1f, view(popup).findViewById<View>(R.id.popup_artwork_container).alpha, 0f)
            popup.close()
            assertFalse(field(popup, "attached") as Boolean)
            assertEquals(1, dismissed)
        } finally { popup.forceClose() }
    }

    @Test fun lidInvitationsWaitForTheCaseWhileGenericCardsKeepTheirExpiry() {
        val context = context(Profile("invitation", 412, 900, 1f))
        var lidDismissed = 0
        var genericDismissed = 0
        val lid = PopupWindow(context) { lidDismissed++ }
        val generic = PopupWindow(context) { genericDismissed++ }
        try {
            lid.openPairing("AirPods Pro 3", 0x2720)
            generic.openPairing("Headphones", 0xffff)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertEquals("Preparing a nearby case must not lose its card", 0, lidDismissed)
            assertEquals("Other popup callers retain their timeout", 1, genericDismissed)
            lid.close()
            assertEquals(1, lidDismissed)
        } finally { lid.forceClose(); generic.forceClose() }
    }

    @Test fun stateChangesFadeWithoutDeferringControlsOrRestartingForBatteryReports() {
        val profile = Profile("content-motion", 412, 900, 1f)
        val context = context(profile)
        val popup = PopupWindow(context)
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            popup.showPairingActions("Prepare the case, then Connect", listOf("Connect" to {}))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            layout(popup)
            render(popup, 1f)
            val view = view(popup)
            val beforeHeight = view.height
            val button = view.findViewById<Button>(R.id.pairing_primary)
            popup.showPairingActions("Connecting", emptyList(), stage = PopupWindow.Stage.CONNECTING)
            val connecting = field(popup, "contentAnimation") as ValueAnimator
            assertEquals(350L, connecting.duration)
            assertFalse("Outgoing Connect must stop accepting input immediately", button.isEnabled)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, button.importantForAccessibility)
            assertEquals(View.GONE, button.visibility)
            connecting.currentPlayTime = 175
            assertTrue(view.findViewById<View>(R.id.pairing_content).alpha in .01f.. .99f)
            save(view, "pairing-content-motion-connecting-middle")

            var continued = 0
            popup.showPairingActions("Paired", listOf("Continue" to { continued++ }), busy = false, stage = PopupWindow.Stage.COMPLETE)
            val complete = field(popup, "contentAnimation") as ValueAnimator
            assertNotSame("Interrupted motion must retire its old animator", connecting, complete)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.findViewById<View>(R.id.pairing_message).importantForAccessibility)
            assertTrue("The new live action is usable during the fade", button.isEnabled)
            button.performClick()
            assertEquals(1, continued)
            val battery = setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 82, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 76, BatteryStatus.CHARGING))
            popup.updatePairingBattery(battery)
            assertEquals("82%", view.findViewById<TextView>(R.id.pairing_buds_battery_text).text.toString())
            assertSame("Routine batteries cannot restart content motion", complete, field(popup, "contentAnimation"))
            popup.showPairingActions("Paired", listOf("Continue" to { continued++ }), busy = false, stage = PopupWindow.Stage.COMPLETE)
            assertSame("An identical state cannot restart content motion", complete, field(popup, "contentAnimation"))
            layout(popup)
            assertEquals("The shared body viewport keeps the card height", beforeHeight, view.height)
            complete.currentPlayTime = 175
            save(view, "pairing-content-motion-paired-middle")
            popup.close()
            assertNull(field(popup, "contentAnimation"))
            assertTrue((field(popup, "contentSnapshots") as List<*>).isEmpty())
            button.performClick()
            assertEquals("Closing retires the active callback", 1, continued)
        } finally { popup.forceClose() }
    }

    @Test fun largeTextRetainsMeasuredSlotsAcrossSetupProgressAndSuccessBeforeAnotherLayout() {
        for (profile in listOf(Profile("retained-portrait", 412, 900, 2f), Profile("retained-landscape", 800, 360, 2f))) {
            val context = context(profile)
            val popup = PopupWindow(context)
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                popup.showPairingActions(context.getString(R.string.pairing_double_tap) + "\n\n" +
                    context.getString(R.string.pairing_nearby_hint), emptyList(), stage = PopupWindow.Stage.SETUP)
                val view = view(popup)
                val body = view.findViewById<View>(R.id.pairing_content)
                val header = view.findViewById<View>(R.id.name).parent as View
                val measuredBody = body.measuredHeight
                val measuredHeader = header.measuredHeight
                val measuredCard = (field(popup, "params") as WindowManager.LayoutParams).height
                // Pairing callbacks may replace instructions before their first layout traversal.
                popup.showPairingActions("Connecting…", emptyList(), stage = PopupWindow.Stage.CONNECTING)
                assertEquals("Progress retains the measured instruction viewport", measuredBody, body.measuredHeight)
                assertEquals("The shorter title cannot move the artwork", measuredHeader, header.measuredHeight)
                assertEquals(measuredCard, (field(popup, "params") as WindowManager.LayoutParams).height)
                layout(popup)
                val geometry = listOf(R.id.popup_artwork_container, R.id.pairing_content, R.id.pairing_actions)
                    .map { bounds(view, view.findViewById(it)) }
                popup.showPairingActions("Paired", listOf("Continue" to {}), busy = false, stage = PopupWindow.Stage.COMPLETE)
                popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING),
                    Battery(BatteryComponent.RIGHT, 82, BatteryStatus.NOT_CHARGING), Battery(BatteryComponent.CASE, 76, BatteryStatus.CHARGING)))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                layout(popup)
                assertEquals("Success keeps the card geometry", measuredCard, view.height)
                assertEquals(geometry, listOf(R.id.popup_artwork_container, R.id.pairing_content, R.id.pairing_actions)
                    .map { bounds(view, view.findViewById(it)) })
                save(view, "pairing-" + profile.label + "-success")
                if (profile.height == 360) {
                    assertTrue(view.getChildAt(0).height > view.height)
                    view.scrollTo(0, view.getChildAt(0).height - view.height)
                    assertContained(view, view.findViewById(R.id.pairing_primary))
                }
            } finally { popup.forceClose() }
            val next = PopupWindow(context)
            try {
                next.openPairing("AirPods Pro 3", 0x2720)
                assertEquals("A new invitation starts with its own content floor", (96 * context.resources.displayMetrics.density).toInt(),
                    view(next).findViewById<View>(R.id.pairing_content).minimumHeight)
            } finally { next.forceClose() }
        }
    }

    private fun context(profile: Profile, motionScale: Float = 1f): Context {
        ShadowSettings.setCanDrawOverlays(true)
        RuntimeEnvironment.setQualifiers("w" + profile.width + "dp-h" + profile.height + "dp-" +
            (if (profile.width > profile.height) "land" else "port") + "-" + (if (profile.night) "night" else "notnight") + "-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { fontScale = profile.fontScale }
        val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_LibrePods)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, motionScale)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(null, motionScale)
        return context
    }

    private fun field(popup: PopupWindow, name: String): Any? = PopupWindow::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(popup)
    private fun view(popup: PopupWindow) = field(popup, "mView") as ScrollView
    private fun invoke(popup: PopupWindow, name: String) = PopupWindow::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(popup)
    private fun render(popup: PopupWindow, amount: Float) = PopupWindow::class.java.getDeclaredMethod("render", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(popup, amount)

    private fun layout(popup: PopupWindow) {
        shadowOf(Looper.getMainLooper()).idle()
        val params = field(popup, "params") as WindowManager.LayoutParams
        view(popup).apply {
            measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    private fun bounds(root: ViewGroup, child: View) = Rect().also {
        child.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(child, it)
        it.offset(-root.scrollX, -root.scrollY)
    }
    private fun assertContained(root: ViewGroup, child: View) {
        val visible = bounds(root, child)
        assertTrue("Control outside viewport: " + child.id + " " + visible + " in " + root.width + "x" + root.height,
            visible.left >= 0 && visible.top >= 0 && visible.right <= root.width && visible.bottom <= root.height)
    }

    private fun save(view: View, label: String) = bitmap(view.width, view.height, label) {
        // View.draw expects the parent renderer to have applied the viewport's scroll offset.
        it.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(it)
    }
    private fun saveOnScreen(popup: PopupWindow, profile: Profile, label: String) {
        val view = view(popup)
        val density = view.resources.displayMetrics.density
        val width = (profile.width * density).toInt()
        val height = (profile.height * density).toInt()
        val params = field(popup, "params") as WindowManager.LayoutParams
        bitmap(width, height, label) {
            it.drawColor(Color.rgb(38, 38, 38))
            it.translate((width - view.width) / 2f, height - params.y - view.height + view.translationY)
            view.draw(it)
        }
    }

    private fun bitmap(width: Int, height: Int, label: String, draw: (Canvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            val output = File("build/overlay-previews/" + label + ".png")
            output.parentFile.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
