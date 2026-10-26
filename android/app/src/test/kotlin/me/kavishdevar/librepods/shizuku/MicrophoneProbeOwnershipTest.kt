package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import me.kavishdevar.librepods.BuildConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the real Binder-facing cleanup without creating an audio policy or recorder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class MicrophoneProbeOwnershipTest {
    private lateinit var service: ShizukuAudioService
    private var originalCaller = 0
    private fun owner() = mock(IPlaybackRecoveryOwner::class.java).also {
        `when`(it.asBinder()).thenReturn(Binder())
    }
    private fun putProbe(owner: IPlaybackRecoveryOwner, probe: RootMicrophoneProbe) =
        field("microphoneProbe").set(service, owner to probe)
    private fun field(name: String) = ShizukuAudioService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
    private fun currentProbe() = field("microphoneProbe").get(service)

    @Before fun setUp() {
        originalCaller = Binder.getCallingUid()
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0))
            .thenReturn(ApplicationInfo().apply { uid = 10123 })
        service = ShizukuAudioService(context, exitOnDestroy = false)
        ShadowBinder.setCallingUid(10123)
    }

    @After fun resetCaller() { ShadowBinder.setCallingUid(originalCaller) }

    @Test fun staleOwnerCannotStopOrClearTheCurrentProbe() {
        val liveOwner = owner()
        val probe = mock(RootMicrophoneProbe::class.java)
        putProbe(liveOwner, probe)
        val before = currentProbe()
        val result = service.finishMicrophoneProbe(owner())
        assertFalse(result.getBoolean("owned"))
        assertSame(before, currentProbe())
        verifyNoInteractions(probe)
    }

    @Test fun successfulOwnedFinishReleasesOnlyItsSlot() {
        val liveOwner = owner()
        val probe = mock(RootMicrophoneProbe::class.java)
        val receipt = Bundle().apply { putBoolean("cleanupComplete", true) }
        `when`(probe.finish()).thenReturn(receipt)
        `when`(probe.isCleanupComplete).thenReturn(true)
        putProbe(liveOwner, probe)
        assertSame(receipt, service.finishMicrophoneProbe(liveOwner))
        assertNull(currentProbe())
        verify(probe).finish()
    }

    @Test fun anOldFinishCannotClearAReplacementPublishedBeforeItsReply() {
        val oldOwner = owner()
        val old = mock(RootMicrophoneProbe::class.java)
        val replacementOwner = owner()
        val replacement = mock(RootMicrophoneProbe::class.java)
        putProbe(oldOwner, old)
        `when`(old.finish()).thenAnswer {
            putProbe(replacementOwner, replacement)
            Bundle().apply { putBoolean("cleanupComplete", true) }
        }
        `when`(old.isCleanupComplete).thenReturn(true)
        service.finishMicrophoneProbe(oldOwner)
        assertEquals(replacementOwner to replacement, currentProbe())
        verifyNoInteractions(replacement)
    }

    @Test fun uncertainCleanupRetainsTheOwnerAndQuarantinesTheBridge() {
        val liveOwner = owner()
        val probe = mock(RootMicrophoneProbe::class.java)
        `when`(probe.finish()).thenReturn(Bundle())
        `when`(probe.isCleanupComplete).thenReturn(false)
        putProbe(liveOwner, probe)
        service.finishMicrophoneProbe(liveOwner)
        assertEquals(liveOwner to probe, currentProbe())
        assertTrue((field("cleanupFailed").get(service) as AtomicBoolean).get())
    }

    @Test fun unauthorizedPreparationClosesItsDescriptorBeforeAnyRouteCanStart() {
        ShadowBinder.setCallingUid(10124)
        val input = mock(ParcelFileDescriptor::class.java)
        assertThrows(IllegalArgumentException::class.java) { service.prepareMicrophoneProbe(input, owner()) }
        verify(input).close()
        assertNull(currentProbe())
    }
}
