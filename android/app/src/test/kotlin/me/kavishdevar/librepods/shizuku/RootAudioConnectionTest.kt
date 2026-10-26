package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import com.topjohnwu.superuser.Shell
import org.mockito.Mockito.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RootAudioConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = ComponentName(context, RootAudioService::class.java)
    private var grant: ((Boolean) -> Unit)? = null
    private var bound: ServiceConnection? = null
    private val released = mutableListOf<ServiceConnection>()
    private var connections = 0
    private var losses = 0
    private var failures = 0
    private fun connection() = RootAudioConnection(context,
        connected = { connections++ }, disconnected = { losses++ }, unavailable = { failures++ },
        authorize = { grant = it }, bind = { bound = it }, unbind = { released += it })
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun grant() { grant!!(true); idle() }

    @Test fun grantBindsAndCloseReleasesOnlyItsBinding() {
        val client = connection()
        client.start(); idle(); grant()
        val selected = bound!!
        selected.onServiceConnected(name, Binder())
        assertEquals(1, connections)
        client.close(); idle()
        assertEquals(listOf(selected), released)
        assertEquals(0, failures)
    }

    @Test fun closedClientIgnoresLateAuthorization() {
        val client = connection()
        client.start(); idle()
        client.close(); idle(); grant()
        assertNull(bound)
        assertEquals(0, connections)
        assertEquals(0, failures)
    }

    @Test fun rootDenialFallsBackWithoutBinding() {
        val client = connection()
        client.start(); idle(); grant!!(false); idle()
        assertNull(bound)
        assertEquals(1, failures)
        client.close(); idle()
    }

    @Test fun explicitRetryAfterDenialCanBindSuccessfully() {
        val client = connection()
        client.start(); idle(); grant!!(false); idle()
        client.start(); idle(); grant()
        bound!!.onServiceConnected(name, Binder())
        assertEquals(1, connections)
        assertEquals(1, failures)
        client.close(); idle()
    }

    @Test fun deniedShellIsRetiredButAuthorizedSharedRootShellIsPreserved() {
        val denied = mock(Shell::class.java)
        assertFalse(acceptRootShell(denied))
        verify(denied).close()
        val granted = mock(Shell::class.java)
        `when`(granted.isRoot).thenReturn(true)
        assertTrue(acceptRootShell(granted))
        verify(granted, never()).close()
    }

    @Test fun hungAuthorizationTimesOutAndCannotBindLater() {
        val client = connection()
        client.start(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(1, failures)
        grant()
        assertNull(bound)
        client.close(); idle()
    }

    @Test fun hungBindingIsReleasedAndLateServiceCannotConnect() {
        val client = connection()
        client.start(); idle(); grant()
        val selected = bound!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        selected.onServiceConnected(name, Binder())
        assertEquals(0, connections)
        assertEquals(1, failures)
        assertEquals(listOf(selected), released)
        client.close(); idle()
    }

    @Test fun serviceDeathRetiresItsBinding() {
        val client = connection()
        client.start(); idle(); grant()
        val selected = bound!!
        selected.onServiceConnected(name, Binder())
        selected.onServiceDisconnected(name)
        assertEquals(1, losses)
        assertEquals(listOf(selected), released)
        client.close(); idle()
    }

    @Test fun aLostRootServiceCanBindAgainWithoutRevivingThePreviousConnection() {
        val client = connection()
        client.start(); idle(); grant()
        val previous = bound!!
        previous.onServiceConnected(name, Binder())
        client.retireBinding(); idle()
        client.start(); idle(); grant()
        val replacement = bound!!
        assertNotSame(previous, replacement)
        previous.onServiceConnected(name, Binder())
        assertEquals(1, connections)
        replacement.onServiceConnected(name, Binder())
        assertEquals(2, connections)
        client.close(); idle()
        assertEquals(listOf(previous, replacement), released)
    }

    @Test fun rootAndShellHaveDifferentRealAttribution() {
        assertEquals("root", ShellAudioContext.packageForUid(0))
        assertEquals("com.android.shell", ShellAudioContext.packageForUid(2000))
        assertThrows(SecurityException::class.java) { ShellAudioContext.packageForUid(10001) }
    }
}
