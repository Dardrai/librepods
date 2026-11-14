/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SpatialAudioBridgeTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun spatialMutationReservesSharedAudioAdmissionAndReleasesItAfterFailure() {
        val previousUid = Process.myUid()
        val previousCaller = Binder.getCallingUid()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var worker: Thread? = null
        try {
            ShadowProcess.setUid(10123)
            val context = mock(Context::class.java)
            val pm = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(pm)
            `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
            `when`(pm.checkPermission(anyString(), eq(BuildConfig.APPLICATION_ID))).thenReturn(PackageManager.PERMISSION_GRANTED)
            val service = ShizukuAudioService(context, false)
            val spatial = mock(SpatialAudioAccess::class.java)
            ShizukuAudioService::class.java.getDeclaredField("spatialAudio").apply { isAccessible = true }.set(service, spatial)
            val owner = mock(IPlaybackRecoveryOwner::class.java)
            val attempts = AtomicInteger()
            `when`(spatial.setFixedEnabled(address, owner, true)).thenAnswer {
                if (attempts.incrementAndGet() == 1) {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("Test spatial failure")
                }
                SpatialAudioAccess.failure("unsupported")
            }
            ShadowProcess.setUid(0)
            ShadowBinder.setCallingUid(10123)
            val result = AtomicReference<Bundle>()
            val failure = AtomicReference<Throwable>()
            worker = thread {
                ShadowBinder.setCallingUid(10123)
                try { result.set(service.setFixedSpatialEnabled(address, owner, true)) }
                catch (error: Throwable) { failure.set(error) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("busy", service.setFixedSpatialEnabled(address, owner, true).getString("reason"))
            assertThrows(IllegalStateException::class.java) { service.checkVolumeSync(address, owner) }
            for (operation in listOf<() -> Unit>(
                { service.nativeCall(address, owner, true) },
                { service.pauseOwnedMedia(address, owner) },
                { service.connectNativePlayback(address, owner) },
                { service.releaseNativePlayback(address, owner) },
            )) {
                assertEquals("Another audio operation is active",
                    assertThrows(IllegalStateException::class.java) { operation() }.message)
            }
            assertEquals(1, attempts.get())
            finish.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertEquals("unavailable", result.get().getString("reason"))
            assertEquals("unsupported", service.setFixedSpatialEnabled(address, owner, true).getString("reason"))
            assertEquals(2, attempts.get())
        } finally {
            finish.countDown()
            worker?.join(5_000)
            ShadowProcess.setUid(previousUid)
            ShadowBinder.setCallingUid(previousCaller)
        }
    }

    @Test fun bridgeChecksAppUidRootAndBluetoothPermissionBeforeItsSpatialHelper() {
        val previousUid = Process.myUid(); val previousCaller = Binder.getCallingUid()
        try {
            ShadowProcess.setUid(10123)
            val context = mock(Context::class.java); val pm = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(pm)
            `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
            val service = ShizukuAudioService(context, false)
            val spatial = mock(SpatialAudioAccess::class.java)
            ShizukuAudioService::class.java.getDeclaredField("spatialAudio").apply { isAccessible = true }.set(service, spatial)
            val owner = mock(IPlaybackRecoveryOwner::class.java)
            ShadowBinder.setCallingUid(10124)
            assertThrows(IllegalArgumentException::class.java) { service.inspectSpatialAudio(address, owner) }
            assertThrows(IllegalArgumentException::class.java) { service.setFixedSpatialEnabled(address, owner, true) }
            assertThrows(IllegalArgumentException::class.java) { service.restoreSpatialAudio(address, owner) }
            ShadowBinder.setCallingUid(10123)
            ShadowProcess.setUid(2000)
            assertEquals("root_required", service.setFixedSpatialEnabled(address, owner, true).getString("reason"))
            ShadowProcess.setUid(0)
            `when`(pm.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, BuildConfig.APPLICATION_ID))
                .thenReturn(PackageManager.PERMISSION_DENIED)
            assertEquals("permission_denied", service.setFixedSpatialEnabled(address, owner, true).getString("reason"))
            verifyNoInteractions(spatial)
        } finally { ShadowProcess.setUid(previousUid); ShadowBinder.setCallingUid(previousCaller) }
    }

    @Test fun clientOwnersBelongToRootAndRetireWithTheirCapturedSelection() = runBlocking<Unit> {
        val previousCaller = Binder.getCallingUid()
        val client = ShizukuAudioClient(ApplicationProvider.getApplicationContext<Application>())
        val binder = mock(IBinder::class.java); val remote = mock(IAudioBridge::class.java)
        `when`(remote.asBinder()).thenReturn(binder)
        `when`(binder.queryLocalInterface("me.kavishdevar.librepods.shizuku.IAudioBridge")).thenReturn(remote)
        ShizukuAudioClient::class.java.getDeclaredMethod("acceptBridge", IBinder::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(client, binder, 0, true)
        var captured: IPlaybackRecoveryOwner? = null
        `when`(remote.inspectSpatialAudio(eq(address), any())).thenAnswer {
            captured = it.getArgument(1)
            SpatialAudioAccess.failure("unsupported")
        }
        var current = true
        try {
            assertEquals("unsupported", client.inspectSpatialAudio(address) { current }.getString("reason"))
            val owner = checkNotNull(captured)
            ShadowBinder.setCallingUid(0); assertTrue(owner.isCurrent)
            ShadowBinder.setCallingUid(2000); assertFalse(owner.isCurrent)
            ShadowBinder.setCallingUid(10123); assertFalse(owner.isCurrent)
            ShadowBinder.setCallingUid(0); current = false; assertFalse(owner.isCurrent)
            val stale = client.setFixedSpatialEnabled(address, true) { false }
            assertEquals("owner_changed", stale.getString("reason"))
            verify(remote, never()).setFixedSpatialEnabled(anyString(), any(), anyBoolean())
            current = true; client.close(); assertFalse(owner.isCurrent)
            verify(remote).retireOwner(owner)
        } finally { client.close(); ShadowBinder.setCallingUid(previousCaller); shadowOf(Looper.getMainLooper()).idle() }
    }
}
