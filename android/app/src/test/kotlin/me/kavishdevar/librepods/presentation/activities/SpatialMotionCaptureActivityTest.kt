package me.kavishdevar.librepods.presentation.activities

import android.app.Application
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

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
