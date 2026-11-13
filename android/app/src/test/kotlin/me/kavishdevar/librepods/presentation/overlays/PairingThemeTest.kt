/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.Manifest
import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.NearbyPairingController
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.design.NightTheme
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.services.LibrePodsService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, qualifiers = "w412dp-h900dp-notnight")
@LooperMode(LooperMode.Mode.PAUSED)
class PairingThemeTest {
    private val settings = MutableStateFlow(AppSettingsEntity())
    private lateinit var repository: AppDataRepository
    private lateinit var context: Context

    @Before fun setUp() {
        val real = RuntimeEnvironment.getApplication()
        val app = mock(LibrePodsApplication::class.java)
        repository = mock(AppDataRepository::class.java)
        doReturn(settings).`when`(repository).settings
        doReturn(repository).`when`(app).appDataRepository
        context = object : ContextWrapper(ContextThemeWrapper(real, R.style.Theme_LibrePods)) {
            override fun getApplicationContext(): Context = app
        }
        shadowOf(real).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(null, 0f)
    }

    @Test fun explicitAppThemeOverridesTheOppositeSystemThemeAndSystemFollowsIt() {
        for (systemDark in listOf(false, true)) {
            setSystemDark(systemDark)
            for (choice in NightTheme.entries) {
                settings.value = settings.value.copy(nightMode = choice)
                val themed = PopupWindow.pairingContext(context)
                val dark = when (choice) { NightTheme.Dark -> true; NightTheme.Light -> false; NightTheme.System -> systemDark }
                assertEquals("$choice with systemDark=$systemDark", dark,
                    themed.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                assertEquals(dark, Color.luminance(themed.getColor(R.color.pairing_card)) < 0.2)
            }
        }
    }

    @Test fun refreshingAnOpenCardKeepsItsStageActionBatteryAndLayoutInsteadOfReopeningIt() {
        settings.value = settings.value.copy(nightMode = NightTheme.Light)
        for (stage in PopupWindow.Stage.entries) {
            var actions = 0
            var dismissals = 0
            val popup = PopupWindow(context) { dismissals++ }
            try {
                popup.openPairing("AirPods Pro 3", 0x2720)
                val busy = stage == PopupWindow.Stage.SETUP || stage == PopupWindow.Stage.CONNECTING
                popup.showPairingActions(stage.name, if (busy) emptyList() else listOf("Action" to { actions++ }), busy, stage)
                popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING,
                    observedAt = System.currentTimeMillis()), Battery(BatteryComponent.CASE, 71, BatteryStatus.CHARGING,
                    observedAt = System.currentTimeMillis())))
                val root = ReflectionHelpers.getField<View>(popup, "mView")
                val action = root.findViewById<Button>(R.id.pairing_primary)
                val name = root.findViewById<TextView>(R.id.name).text.toString()
                val message = root.findViewById<TextView>(R.id.pairing_message).text.toString()
                val batteries = ReflectionHelpers.getField<Set<Battery>>(popup, "batteries")
                val params = ReflectionHelpers.getField<Any>(popup, "params")
                val icon = root.findViewById<TextView>(R.id.pairing_buds_battery_text).compoundDrawablesRelative[0]
                val padding = listOf(action.paddingLeft, action.paddingTop, action.paddingRight, action.paddingBottom)
                val close = root.findViewById<View>(R.id.close_button)
                val closePadding = listOf(close.paddingLeft, close.paddingTop, close.paddingRight, close.paddingBottom)
                settings.value = settings.value.copy(nightMode = NightTheme.Dark)
                popup.refreshTheme()
                assertSame(root, ReflectionHelpers.getField(popup, "mView"))
                assertSame(params, ReflectionHelpers.getField(popup, "params"))
                assertSame(batteries, ReflectionHelpers.getField(popup, "batteries"))
                assertEquals(stage, ReflectionHelpers.getField<PopupWindow.Stage>(popup, "stage"))
                assertEquals(name, root.findViewById<TextView>(R.id.name).text.toString())
                assertEquals(message, root.findViewById<TextView>(R.id.pairing_message).text.toString())
                assertEquals(!busy, action.isEnabled)
                assertEquals(padding, listOf(action.paddingLeft, action.paddingTop, action.paddingRight, action.paddingBottom))
                assertEquals(closePadding, listOf(close.paddingLeft, close.paddingTop, close.paddingRight, close.paddingBottom))
                assertTrue(Color.luminance(requireNotNull((root.background as GradientDrawable).color).defaultColor) < 0.2)
                if (stage == PopupWindow.Stage.COMPLETE) {
                    val battery = root.findViewById<TextView>(R.id.pairing_buds_battery_text)
                    assertSame("Theme refresh recolours existing battery icons without rebuilding their readings", icon, battery.compoundDrawablesRelative[0])
                    assertEquals(8200, (icon as LayerDrawable).getDrawable(1).level)
                }
                val background = root.background
                popup.refreshTheme()
                assertSame("An unchanged effective theme is a no-op", background, root.background)
                action.performClick()
                assertEquals(if (busy) 0 else 1, actions)
                assertEquals(0, dismissals)
            } finally { popup.forceClose(); settings.value = settings.value.copy(nightMode = NightTheme.Light) }
        }
    }

    @Test fun refreshingThePaletteDoesNotExtendAnExistingBatteryReadingDeadline() {
        settings.value = settings.value.copy(nightMode = NightTheme.Light)
        val popup = PopupWindow(context)
        try {
            popup.openPairing("AirPods Pro 3", 0x2720)
            popup.showPairingActions("Paired", listOf("Continue" to {}), stage = PopupWindow.Stage.COMPLETE)
            val observedAt = System.currentTimeMillis() - 89_000
            popup.updatePairingBattery(setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING, observedAt),
                Battery(BatteryComponent.RIGHT, 82, BatteryStatus.NOT_CHARGING, observedAt)))
            val battery = ReflectionHelpers.getField<View>(popup, "mView").findViewById<TextView>(R.id.pairing_buds_battery_text)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            settings.value = settings.value.copy(nightMode = NightTheme.Dark)
            popup.refreshTheme()
            assertEquals("82%", battery.text.toString())
            // This runtime advances Handler uptime independently of Java wall time, as in PairingRenderingTest.
            // Age the retained observation without redrawing it; the original one-second callback must still run.
            val expiredAt = System.currentTimeMillis() - 90_000
            val readings = ReflectionHelpers.getField<Set<Battery>>(popup, "batteries")
            ReflectionHelpers.setField(popup, "batteries", readings.map { it.copy(observedAt = expiredAt) }.toSet())
            assertEquals("Aging the fixture alone must not redraw the card", "82%", battery.text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(410))
            assertEquals("The original reading still expires one second after it was shown",
                context.getString(R.string.pairing_battery_unknown), battery.text.toString())
        } finally { popup.forceClose() }
    }

    @Test fun controllerThemeRefreshKeepsTheActiveInvitationSessionAndSystemChoiceTracksConfiguration() {
        val controller = NearbyPairingController(context)
        try {
            controller.onNearbyDevice(NearbyAirPods.Event(0x2720, true))
            idle()
            val popup = ReflectionHelpers.getField<PopupWindow>(controller, "popup")
            val session = ReflectionHelpers.getField<NearbyAirPods.Session>(controller, "session")
            val generation = session.generation
            val root = ReflectionHelpers.getField<View>(popup, "mView")
            setSystemDark(true)
            controller.refreshTheme()
            idle()
            assertSame(popup, ReflectionHelpers.getField(controller, "popup"))
            assertEquals(generation, session.generation)
            assertTrue(session.isCurrent(generation))
            assertTrue(Color.luminance(requireNotNull((root.background as GradientDrawable).color).defaultColor) < 0.2)
            assertEquals(PopupWindow.Stage.NEARBY, ReflectionHelpers.getField<PopupWindow.Stage>(popup, "stage"))
        } finally { controller.close(); idle() }
    }

    @Test fun serviceNotifiesTheCurrentInvitationForThemeAndConfigurationChangesOnly() = runBlocking<Unit> {
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        val controller = mock(NearbyPairingController::class.java)
        val island = mock(IslandWindow::class.java)
        val refreshes = AtomicInteger()
        val observations = AtomicInteger()
        doAnswer { refreshes.incrementAndGet(); null }.`when`(controller).refreshTheme()
        doAnswer { observations.incrementAndGet(); null }.`when`(island).updateSettings(any<AppSettingsEntity>() ?: AppSettingsEntity())
        ReflectionHelpers.setField(service, "nearbyPairing", controller)
        ReflectionHelpers.setField(service, "appDataRepository\$delegate", lazyOf(repository))
        ReflectionHelpers.setField(service, "islandWindow\$delegate", lazyOf(island))
        val observation = service.observeAppSettings()
        suspend fun observed(count: Int) = withTimeout(2_000) { while (observations.get() < count) delay(5) }
        try {
            observed(1)
            assertEquals(0, refreshes.get())
            settings.value = settings.value.copy(debugMode = true)
            observed(2)
            assertEquals("Unrelated settings do not restart artwork", 0, refreshes.get())
            settings.value = settings.value.copy(nightMode = NightTheme.Dark)
            observed(3)
            withTimeout(2_000) { while (refreshes.get() < 1) delay(5) }
            service.onConfigurationChanged(Configuration(context.resources.configuration))
            assertEquals(2, refreshes.get())
        } finally {
            observation.cancel()
            val scope = ReflectionHelpers.getField<CoroutineScope>(service, "serviceScope")
            scope.cancel()
            withTimeout(2_000) { scope.coroutineContext[Job]?.join() }
        }
    }

    private fun setSystemDark(dark: Boolean) = RuntimeEnvironment.setQualifiers(
        "w412dp-h900dp-" + if (dark) "night" else "notnight")

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
}
