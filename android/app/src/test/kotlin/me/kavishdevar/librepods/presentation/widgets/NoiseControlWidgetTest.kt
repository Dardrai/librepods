package me.kavishdevar.librepods.presentation.widgets

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import me.kavishdevar.librepods.services.LibrePodsService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NoiseControlWidgetTest {
    @Test fun eachWidgetRetainsItsOwnDeviceTargetWhenOtherWidgetsAreUpdated() {
        val context = RuntimeEnvironment.getApplication()
        val manager = AppWidgetManager.getInstance(context)
        val widget = NoiseControlWidget()
        for (id in listOf(11, 22)) shadowOf(manager).bindAppWidgetId(id, ComponentName(context, NoiseControlWidget::class.java))
        widget.onUpdate(context, manager, intArrayOf(11, 22))
        widget.onUpdate(context, manager, intArrayOf(22))
        val actions = mutableSetOf<PendingIntent>()
        for (widgetId in listOf(11, 22)) {
            for ((requestCode, mode) in listOf(1, 3, 4, 2).withIndex()) {
                val intent = Intent(context, LibrePodsService::class.java)
                    .setAction("ACTION_SET_ANC_MODE")
                    .setData(Uri.parse("librepods://noise-control-widget/$widgetId"))
                val action = PendingIntent.getForegroundService(context, requestCode, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                assertNotNull(action)
                val saved = shadowOf(requireNotNull(action)).savedIntent
                assertEquals(widgetId, saved.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                assertEquals(mode, saved.getIntExtra("ANC_MODE", -1))
                actions.add(action)
            }
        }
        assertEquals("Each widget and mode needs its own action", 8, actions.size)
    }
}
