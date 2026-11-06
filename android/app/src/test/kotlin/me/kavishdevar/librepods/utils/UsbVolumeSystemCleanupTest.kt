/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.os.Handler
import android.os.IBinder
import me.kavishdevar.librepods.shizuku.UsbAbsoluteVolumeBroker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class UsbVolumeSystemCleanupTest {
    @Test fun aFailedAcquisitionAlreadyCleanedByNativeOwnedDoesNotLeaveAnOrphanOwner() {
        // Skip platform construction: only exercise end() after begin() has cleaned its debt.
        val policy = mock(UsbAbsoluteVolumeSystemPolicy::class.java)
        val broker = mock(UsbAbsoluteVolumeBroker::class.java)
        val owner = mock(IBinder::class.java)
        fun field(name: String) = UsbAbsoluteVolumeSystemPolicy::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("broker").set(policy, broker)
        field("owner").set(policy, owner)
        field("handler").set(policy, mock(Handler::class.java))
        assertNull(field("nativeOwned").get(policy))
        `when`(broker.release(owner)).thenReturn(false) // acquire() never installed a held lease.
        val end = UsbAbsoluteVolumeSystemPolicy::class.java.getDeclaredMethod("end", String::class.java).apply { isAccessible = true }
        assertEquals(true, end.invoke(policy, "native_policy_unavailable"))
        assertNull("No cleanup debt remains to justify retaining this owner", field("owner").get(policy))
        verify(broker, times(2)).release(owner)
    }
}
