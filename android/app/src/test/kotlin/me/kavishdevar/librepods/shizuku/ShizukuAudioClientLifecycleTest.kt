package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ShizukuAudioClientLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val accept = ShizukuAudioClient::class.java.getDeclaredMethod("acceptBridge",
        IBinder::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    private val disconnect = ShizukuAudioClient::class.java.getDeclaredMethod("disconnected",
        String::class.java, IBinder::class.java).apply { isAccessible = true }

    private class Bridge {
        val binder = mock(IBinder::class.java)
        val remote = mock(IAudioBridge::class.java)
        lateinit var death: IBinder.DeathRecipient
        init {
            `when`(remote.asBinder()).thenReturn(binder)
            `when`(binder.queryLocalInterface("me.kavishdevar.librepods.shizuku.IAudioBridge")).thenReturn(remote)
            doAnswer { death = it.getArgument(0); null }.`when`(binder).linkToDeath(any(), eq(0))
        }
    }

    @Test fun oldBinderDeathCannotRetireTheReplacementOrItsBackend() {
        val client = ShizukuAudioClient(context)
        try {
            val old = Bridge()
            accept.invoke(client, old.binder, 0, true)
            disconnect.invoke(client, "Old root stopped", old.binder)
            val replacement = Bridge()
            accept.invoke(client, replacement.binder, 0, true)
            old.death.binderDied()
            assertTrue(client.state.value.connected)
            assertTrue(client.isRoot)
            assertEquals("root", client.state.value.backend)
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun rootUidShizukuStillUsesShizukuTransportLifecycle() {
        val client = ShizukuAudioClient(context)
        try {
            val remote = Bridge()
            accept.invoke(client, remote.binder, 0, false)
            assertTrue(client.state.value.connected)
            assertFalse(client.isRoot)
            assertEquals("shizuku", client.state.value.backend)
            remote.death.binderDied()
            assertFalse(client.state.value.connected)
            assertNull(client.state.value.backend)
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun alreadyGrantedShizukuFallbackDoesNotRetryAnUnavailableRootService() {
        assertShizukuFallback { client ->
            ShizukuAudioClient::class.java.getDeclaredMethod("requestShizukuPermission")
                .apply { isAccessible = true }.invoke(client)
        }
    }

    @Test fun aNewShizukuPermissionGrantUsesTheSelectedFallbackTransport() {
        assertShizukuFallback { client ->
            val listener = ShizukuAudioClient::class.java.getDeclaredField("permissionResult")
                .apply { isAccessible = true }.get(client) as Shizuku.OnRequestPermissionResultListener
            listener.onRequestPermissionResult(18570, PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun assertShizukuFallback(action: (ShizukuAudioClient) -> Unit) {
        mockStatic(Shizuku::class.java).use { shizuku ->
            mockStatic(Shell::class.java).use { root ->
                shizuku.`when`<Boolean> { Shizuku.pingBinder() }.thenReturn(true)
                shizuku.`when`<Int> { Shizuku.checkSelfPermission() }.thenReturn(PackageManager.PERMISSION_GRANTED)
                shizuku.`when`<Int> { Shizuku.getVersion() }.thenReturn(13)
                root.`when`<Boolean> { Shell.isAppGrantedRoot() }.thenReturn(true)
                val client = ShizukuAudioClient(context)
                try {
                    action(client)
                    val args = ShizukuAudioClient::class.java.getDeclaredField("args")
                        .apply { isAccessible = true }.get(client) as Shizuku.UserServiceArgs
                    val connection = ShizukuAudioClient::class.java.getDeclaredField("connection")
                        .apply { isAccessible = true }.get(client) as ServiceConnection
                    shizuku.verify { Shizuku.bindUserService(args, connection) }
                    root.verify({ Shell.isAppGrantedRoot() }, never())
                    assertEquals("Starting audio service", client.state.value.availability)
                } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
            }
        }
    }
}
