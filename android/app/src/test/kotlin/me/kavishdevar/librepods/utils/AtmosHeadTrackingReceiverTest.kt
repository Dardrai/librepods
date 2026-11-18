/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.NativeHeadTrackingController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosHeadTrackingReceiverTest {
    @Test fun queryReadsCachedControllerStateWithoutOpeningPreferencesOrStartingAnything() {
        val context = mock(Context::class.java)
        val app = mock(LibrePodsApplication::class.java)
        val controller = mock(NativeHeadTrackingController::class.java)
        val controllers = ConcurrentHashMap<MacAddress, NativeHeadTrackingController>()
        controllers[MacAddress("00:AA:22:33:44:55")] = controller
        `when`(context.applicationContext).thenReturn(app)
        `when`(app.nativeHeadTrackingControllers).thenReturn(controllers)
        `when`(controller.timingSnapshot()).thenReturn(Bundle().apply { putBoolean("spatial_supported", true) })
        AtmosHeadTrackingReceiver().onReceive(context, Intent().putExtra("operation", "query"))
        verify(context, never()).getSharedPreferences(anyString(), anyInt())
        verify(controller).timingSnapshot()
        verifyNoMoreInteractions(controller)
    }
}
