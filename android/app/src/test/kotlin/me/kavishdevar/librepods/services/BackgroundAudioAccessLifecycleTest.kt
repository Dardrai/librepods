package me.kavishdevar.librepods.services

import android.app.Application
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.shizuku.BackgroundAudioAccess
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class BackgroundAudioAccessLifecycleTest {
    @Test fun startupAndGrantedShizukuCallbacksRetryAndDestructionRetiresBothListeners() = runBlocking {
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        val access = mock(BackgroundAudioAccess::class.java)
        val attempts = MutableStateFlow(0)
        `when`(access.ensureAllowed()).thenAnswer { attempts.update { it + 1 }; true }
        field("backgroundAudioAccess\$delegate").set(service, lazyOf(access))
        field("startedWithPermissions").set(service, true)
        val received = field("backgroundAudioBinderReceived").get(service) as Shizuku.OnBinderReceivedListener
        val permission = field("backgroundAudioPermissionResult").get(service) as Shizuku.OnRequestPermissionResultListener
        mockStatic(Shizuku::class.java).use { shizuku ->
            try {
                start(service)
                shizuku.verify { Shizuku.addBinderReceivedListener(received) }
                shizuku.verify { Shizuku.addRequestPermissionResultListener(permission) }
                withTimeout(2_000) { attempts.first { it >= 1 } }
                verify(access, times(1)).ensureAllowed()
                received.onBinderReceived()
                withTimeout(2_000) { attempts.first { it >= 2 } }
                verify(access, times(2)).ensureAllowed()
                permission.onRequestPermissionResult(10, PackageManager.PERMISSION_DENIED)
                permission.onRequestPermissionResult(10, PackageManager.PERMISSION_GRANTED)
                withTimeout(2_000) { attempts.first { it >= 3 } }
                verify(access, times(3)).ensureAllowed()
                // Listener cleanup also precedes the partial-startup destruction guard.
                field("startedWithPermissions").set(service, false)
                service.onDestroy()
                shizuku.verify { Shizuku.removeBinderReceivedListener(received) }
                shizuku.verify { Shizuku.removeRequestPermissionResultListener(permission) }
                received.onBinderReceived()
                permission.onRequestPermissionResult(10, PackageManager.PERMISSION_GRANTED)
                verifyNoMoreInteractions(access)
            } finally {
                (field("serviceScope").get(service) as CoroutineScope).cancel()
            }
        }
    }

    @Test @Config(sdk = [36]) fun olderAndroidDoesNotRegisterListenersOrRunTheHelper() {
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        val access = mock(BackgroundAudioAccess::class.java)
        field("backgroundAudioAccess\$delegate").set(service, lazyOf(access))
        field("startedWithPermissions").set(service, true)
        mockStatic(Shizuku::class.java).use { shizuku ->
            try {
                start(service)
                verifyNoInteractions(access)
                shizuku.verifyNoInteractions()
            } finally {
                (field("serviceScope").get(service) as CoroutineScope).cancel()
            }
        }
    }

    private fun start(service: LibrePodsService) = LibrePodsService::class.java
        .getDeclaredMethod("startBackgroundAudioAccess").apply { isAccessible = true }.invoke(service)
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
