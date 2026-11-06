/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class UsbVolumeActivationFailureTest {
    @Test fun diagnosticsExposeOnlyFixedStageAndExceptionType() {
        for (stage in UsbVolumeActivationStage.entries) {
            val info = usbVolumeActivationFailure(stage, InvocationTargetException(SecurityException("serial and profile must stay private")))
            assertEquals(setOf("failure_stage", "exception_type"), info.keySet())
            assertEquals(stage.name.lowercase(java.util.Locale.ROOT), info.getString("failure_stage"))
            assertEquals("SecurityException", info.getString("exception_type"))
        }
    }
    @Test fun directFailureRetainsItsTypeWithoutMessageOrStack() {
        val info = usbVolumeActivationFailure(UsbVolumeActivationStage.NATIVE_UNITY, IllegalStateException("native result"))
        assertEquals("native_unity", info.getString("failure_stage"))
        assertEquals("IllegalStateException", info.getString("exception_type"))
    }
}
