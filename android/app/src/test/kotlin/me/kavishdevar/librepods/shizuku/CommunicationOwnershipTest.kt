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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CommunicationOwnershipTest {
    private lateinit var service: ShizukuAudioService
    private var originalCaller = 0
    private fun owner() = mock(IPlaybackRecoveryOwner::class.java).also { `when`(it.asBinder()).thenReturn(Binder()) }
    private fun field(name: String) = ShizukuAudioService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun put(owner: IPlaybackRecoveryOwner, route: RootCommunicationRoute) = field("communication").set(service, owner to route)

    @Before fun setup() {
        originalCaller = Binder.getCallingUid()
        val context = mock(Context::class.java); val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
        service = ShizukuAudioService(context, false); ShadowBinder.setCallingUid(10123)
    }
    @After fun reset() { ShadowBinder.setCallingUid(originalCaller) }

    @Test fun strangerCannotInspectMuteOrFinishTheOwnedCall() {
        val live = owner(); val route = mock(RootCommunicationRoute::class.java); put(live, route)
        val stranger = owner()
        assertFalse(service.communicationState(stranger).getBoolean("owned"))
        assertFalse(service.setCommunicationMuted(stranger, false))
        assertFalse(service.finishCommunication(stranger, true).getBoolean("owned"))
        verifyNoInteractions(route); assertEquals(live to route, field("communication").get(service))
    }
    @Test fun oldFinishCannotRetireReplacementAndUncertainCleanupIsQuarantined() {
        val oldOwner = owner(); val old = mock(RootCommunicationRoute::class.java)
        val freshOwner = owner(); val fresh = mock(RootCommunicationRoute::class.java)
        put(oldOwner, old)
        `when`(old.finish(true)).thenAnswer { put(freshOwner, fresh); Bundle() }
        `when`(old.isCleanupComplete).thenReturn(true)
        service.finishCommunication(oldOwner, true)
        assertEquals(freshOwner to fresh, field("communication").get(service)); verifyNoInteractions(fresh)
        `when`(fresh.finish(true)).thenReturn(Bundle()); `when`(fresh.isCleanupComplete).thenReturn(false)
        service.finishCommunication(freshOwner, true)
        assertEquals(freshOwner to fresh, field("communication").get(service))
        assertTrue((field("cleanupFailed").get(service) as AtomicBoolean).get())
    }
    @Test fun ownedFinishClearsOnlyAfterRootConfirmedCleanup() {
        val live = owner(); val route = mock(RootCommunicationRoute::class.java); put(live, route)
        val receipt = Bundle().apply { putBoolean("cleanupComplete", true) }
        `when`(route.finish(true)).thenReturn(receipt); `when`(route.isCleanupComplete).thenReturn(true)
        assertSame(receipt, service.finishCommunication(live, true)); assertNull(field("communication").get(service))
    }
    @Test fun unauthorizedPrepareClosesPcmWithoutCreatingAnyCallRoute() {
        ShadowBinder.setCallingUid(10124); val fd = mock(ParcelFileDescriptor::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            service.prepareCommunication(fd, owner(), "AA:BB:CC:DD:EE:FF", 2, "com.discord", 32, false, "", 2,
                mock(IHfpCallControl::class.java))
        }
        verify(fd).close(); assertNull(field("communication").get(service))
    }

    @Test fun quiescingKeepsTheOwnerUntilInputQuietIsAcknowledged() {
        val live = owner(); val route = mock(RootCommunicationRoute::class.java); put(live, route)
        val quieting = Bundle().apply { putBoolean("resourcesStopped", true); putBoolean("awaitingInputQuiet", true) }
        `when`(route.finish(false)).thenReturn(quieting)
        `when`(route.isCleanupComplete).thenReturn(false)
        assertSame(quieting, service.finishCommunication(live, false))
        assertEquals(live to route, field("communication").get(service))
        assertFalse((field("cleanupFailed").get(service) as AtomicBoolean).get())
        val finished = Bundle().apply { putBoolean("cleanupComplete", true) }
        `when`(route.finish(true)).thenReturn(finished); `when`(route.isCleanupComplete).thenReturn(true)
        assertSame(finished, service.finishCommunication(live, true))
        assertNull(field("communication").get(service))
    }

    @Test fun incompatibleProtocolClosesPcmBeforeAnyRouteIsCreated() {
        val fd = mock(ParcelFileDescriptor::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            service.prepareCommunication(fd, owner(), "AA:BB:CC:DD:EE:FF", 1, "", 0, false, "", 0, null)
        }
        verify(fd).close(); assertNull(field("communication").get(service))
    }

    @Test fun missingBluetoothControlClosesPcmBeforeAnyRouteIsCreated() {
        val fd = mock(ParcelFileDescriptor::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            service.prepareCommunication(fd, owner(), "AA:BB:CC:DD:EE:FF", 1, "", 0, false, "", 2, null)
        }
        verify(fd).close(); assertNull(field("communication").get(service))
    }
}
