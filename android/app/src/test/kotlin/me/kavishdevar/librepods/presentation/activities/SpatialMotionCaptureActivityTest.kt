package me.kavishdevar.librepods.presentation.activities

import android.app.Application
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import me.kavishdevar.librepods.services.LibrePodsService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SpatialMotionCaptureActivityTest {
    @Test fun aBareActivityCannotBindTheServiceOrCreateACapture() {
        assertNoCapture(Intent())
    }

    @Test fun invalidExplicitRequestsCannotBindTheServiceOrCreateACaptures() {
        for (intent in listOf(
            request().putExtra("expected_mac", "../invalid"),
            request().putExtra("phase", "../yaw"),
            request().putExtra("duration_seconds", 11),
        )) assertNoCapture(intent)
    }

    @Test fun recreatingAnActivityCannotReplayItsExplicitCaptureRequest() {
        assertNoCapture(request(), Bundle())
    }

    @Test fun headTrackingAlsoRequiresAnExactBoundedExplicitRequestAndCannotReplay() {
        val head = Intent().putExtra("spatial_head_tracking_probe", true)
            .putExtra("expected_mac", "00:11:22:33:44:55").putExtra("duration_seconds", 30)
        assertNoCapture(Intent(head).putExtra("expected_mac", "../invalid"))
        assertNoCapture(Intent(head).putExtra("duration_seconds", 31))
        assertNoCapture(Intent(head).putExtra("duration_seconds", 0))
        assertNoCapture(Intent(head).putExtra("spatial_motion_capture", true))
        assertNoCapture(head, Bundle())
    }

    @Test fun serviceReconnectionCannotReplayAnAlreadyHandledExplicitRequest() {
        val controller = Robolectric.buildActivity(TestActivity::class.java, request()).create().start().resume()
        try {
            val activity = controller.get()
            val field = TestActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }
            val connection = field.get(activity) as ServiceConnection
            connection.onServiceConnected(null, null)
            connection.onServiceDisconnected(null)
            val replacementBinder = mock(LibrePodsService.LocalBinder::class.java)
            connection.onServiceConnected(null, replacementBinder)
            verifyNoInteractions(replacementBinder)
            assertFalse(File(activity.filesDir, "spatial-motion-captures").exists())
        } finally { controller.pause().stop().destroy() }
    }

    private fun request() = Intent()
        .putExtra("spatial_motion_capture", true)
        .putExtra("expected_mac", "00:11:22:33:44:55")
        .putExtra("phase", "neutral")
        .putExtra("duration_seconds", 10)

    private fun assertNoCapture(intent: Intent, savedState: Bundle? = null) {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(TestActivity::class.java, intent)
            .create(savedState).start().resume()
        try {
            assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
            assertNull(shadowOf(app).nextStartedService)
            assertFalse(File(app.filesDir, "spatial-motion-captures").exists())
        } finally { controller.pause().stop().destroy() }
    }
}
