package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun mediaInspectionReportsMissingBridgeAndSettingsBusyWithoutCallingTheHelper() = runBlocking<Unit> {
        val client = ShizukuAudioClient(context)
        try {
            val absent = client.inspectCompanionMedia("AA:BB:CC:DD:EE:FF")
            assertEquals(MediaInspectionReason.BRIDGE_UNAVAILABLE, CompanionMediaInspection.reason(absent))
            assertFalse(absent.getBoolean("known"))
            val remote = Bridge(); accept.invoke(client, remote.binder, 0, true)
            ShizukuAudioClient::class.java.getDeclaredField("settingsRefresh").apply { isAccessible = true }.set(client, true)
            val busy = client.inspectCompanionMedia("AA:BB:CC:DD:EE:FF")
            assertEquals(MediaInspectionReason.SETTINGS_BUSY, CompanionMediaInspection.reason(busy))
            assertFalse(busy.getBoolean("known"))
            verify(remote.remote, never()).inspectCompanionMedia(anyString())
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun mediaBinderFailuresAreFailClosedAndDoNotExposeExceptionMessages() = runBlocking {
        val client = ShizukuAudioClient(context)
        try {
            val remote = Bridge(); accept.invoke(client, remote.binder, 0, true)
            `when`(remote.remote.inspectCompanionMedia(anyString())).thenThrow(SecurityException("private.player/title/key"))
            val value = client.inspectCompanionMedia("AA:BB:CC:DD:EE:FF")
            assertEquals(MediaInspectionStage.CLIENT, CompanionMediaInspection.stage(value))
            assertEquals(MediaInspectionReason.SECURITY, CompanionMediaInspection.reason(value))
            assertFalse(value.getBoolean("known")); assertFalse(value.getBoolean("eligible"))
            assertFalse(value.toString().contains("private.player"))
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

    @Test fun cancelledUsbOpenClosesABinderThatReturnsAfterCancellation() = runBlocking<Unit> {
        val client = ShizukuAudioClient(context)
        val bridge = Bridge()
        val hid = mock(IUsbHidConnection::class.java)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        `when`(bridge.remote.openDongleHid(eq("/dev/bus/usb/001/002"), eq("board"), eq(5), any()))
            .thenAnswer { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); hid }
        accept.invoke(client, bridge.binder, 0, true)
        val pending = launch(Dispatchers.IO) {
            runCatching { client.openDongleHid("/dev/bus/usb/001/002", "board", 5) { true } }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancel(); finish.countDown(); pending.join()
            verify(hid).close()
        } finally { finish.countDown(); pending.cancel(); client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun closingTheRootClientClosesItsUsbControlLinksWithoutAnAudioRouteChange() = runBlocking<Unit> {
        val client = ShizukuAudioClient(context)
        val bridge = Bridge(); val hid = mock(IUsbHidConnection::class.java)
        `when`(bridge.remote.openDongleHid(eq("/dev/bus/usb/001/002"), eq("board"), eq(5), any())).thenReturn(hid)
        accept.invoke(client, bridge.binder, 0, true)
        try {
            assertNotNull(client.openDongleHid("/dev/bus/usb/001/002", "board", 5) { true })
            client.close()
            verify(hid).close()
            verify(bridge.remote, never()).connectNativePlayback(anyString(), any())
            verify(bridge.remote, never()).releaseNativePlayback(anyString(), any())
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun usbGenerationCallbacksRunOutsideClientLockAndLateSelectionIsClosed() = runBlocking<Unit> {
        val client = ShizukuAudioClient(context)
        val bridge = Bridge(); val hid = mock(IUsbHidConnection::class.java)
        val lock = ShizukuAudioClient::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(client)
        `when`(bridge.remote.openDongleHid(eq("/dev/bus/usb/001/002"), eq("board"), eq(5), any())).thenReturn(hid)
        accept.invoke(client, bridge.binder, 0, true)
        var checks = 0
        try {
            val result = runCatching { client.openDongleHid("/dev/bus/usb/001/002", "board", 5) {
                assertFalse("Controller lifecycle callback must not run under client lock", Thread.holdsLock(lock))
                ++checks < 2
            } }
            assertTrue(result.isFailure); assertEquals(2, checks)
            verify(hid).close()
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun cancelledHeadTrackerStartInvalidatesOwnerBeforeTheBlockedReplyAndFinishesLateSuccess() = runBlocking<Unit> {
        val previousCaller = Binder.getCallingUid()
        val client = ShizukuAudioClient(context)
        val bridge = Bridge()
        val pipe = ParcelFileDescriptor.createPipe()
        val descriptor = pipe[0].fileDescriptor
        val captured = AtomicReference<IPlaybackRecoveryOwner>()
        val entered = CountDownLatch(1); val reply = CountDownLatch(1)
        `when`(bridge.remote.startHeadTrackerProbe(eq("AA:BB:CC:DD:EE:FF"), any(), any(), any(), eq(5)))
            .thenAnswer {
                captured.set(it.getArgument(1)); entered.countDown()
                check(reply.await(5, TimeUnit.SECONDS))
                headTrackerReady()
            }
        `when`(bridge.remote.finishHeadTrackerProbe(any())).thenReturn(Bundle().apply { putBoolean("cleanup_complete", true) })
        accept.invoke(client, bridge.binder, 0, true)
        val pending = launch(Dispatchers.IO) {
            runCatching { client.startHeadTrackerProbe("AA:BB:CC:DD:EE:FF", pipe[0], 5, { true }, { _, _ -> }, {}) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ShadowBinder.setCallingUid(0)
            assertTrue(captured.get().isCurrent)
            pending.cancel()
            assertFalse("A cancelled opening coroutine must retire its owner before the reply", captured.get().isCurrent)
            reply.countDown(); pending.join()
            verify(bridge.remote).finishHeadTrackerProbe(captured.get())
            assertFalse(descriptor.valid())
        } finally {
            reply.countDown(); pending.cancel(); pending.join()
            pipe[1].close(); client.close(); ShadowBinder.setCallingUid(previousCaller)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun headTrackerOwnerOutlivesSuccessfulStartJobAndFinishRemainsRetryable() = runBlocking<Unit> {
        val previousCaller = Binder.getCallingUid()
        val client = ShizukuAudioClient(context)
        val bridge = Bridge(); val pipe = ParcelFileDescriptor.createPipe()
        val captured = AtomicReference<IPlaybackRecoveryOwner>()
        `when`(bridge.remote.startHeadTrackerProbe(eq("AA:BB:CC:DD:EE:FF"), any(), any(), any(), eq(5)))
            .thenAnswer { captured.set(it.getArgument(1)); headTrackerReady() }
        `when`(bridge.remote.finishHeadTrackerProbe(any())).thenReturn(Bundle().apply { putBoolean("cleanup_complete", true) })
        accept.invoke(client, bridge.binder, 0, true)
        try {
            val opening = async(Dispatchers.IO) {
                client.startHeadTrackerProbe("AA:BB:CC:DD:EE:FF", pipe[0], 5, { true }, { _, _ -> }, {})
            }
            val lease = opening.await()
            assertTrue(opening.isCompleted)
            ShadowBinder.setCallingUid(0)
            assertTrue("The completed start Job must not retire the long-lived lease", captured.get().isCurrent)
            lease.finish(); lease.finish()
            assertFalse(captured.get().isCurrent)
            verify(bridge.remote, times(2)).finishHeadTrackerProbe(captured.get())
        } finally {
            pipe[1].close(); client.close(); ShadowBinder.setCallingUid(previousCaller)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun oldHeadTrackerCallbacksAndForeignUidsCannotRetireAReplacementBridgeLease() = runBlocking<Unit> {
        val previousCaller = Binder.getCallingUid()
        val client = ShizukuAudioClient(context)
        val old = Bridge(); val replacement = Bridge()
        val oldPipe = ParcelFileDescriptor.createPipe(); val newPipe = ParcelFileDescriptor.createPipe()
        lateinit var oldEvents: IHeadTrackerEvents
        lateinit var newEvents: IHeadTrackerEvents
        lateinit var newOwner: IPlaybackRecoveryOwner
        val oldReports = CopyOnWriteArrayList<Long>(); val oldStops = CopyOnWriteArrayList<String>()
        val newReports = CopyOnWriteArrayList<Long>(); val newStops = CopyOnWriteArrayList<String>()
        `when`(old.remote.startHeadTrackerProbe(anyString(), any(), any(), any(), eq(5)))
            .thenAnswer { oldEvents = it.getArgument(3); headTrackerReady() }
        `when`(replacement.remote.startHeadTrackerProbe(anyString(), any(), any(), any(), eq(5)))
            .thenAnswer { newOwner = it.getArgument(1); newEvents = it.getArgument(3); headTrackerReady() }
        `when`(old.remote.finishHeadTrackerProbe(any())).thenReturn(Bundle())
        `when`(replacement.remote.finishHeadTrackerProbe(any())).thenReturn(Bundle())
        try {
            accept.invoke(client, old.binder, 0, true)
            val first = client.startHeadTrackerProbe("AA:BB:CC:DD:EE:FF", oldPipe[0], 5, { true },
                { _, interval -> oldReports += interval }, { oldStops += it })
            first.finish()
            disconnect.invoke(client, "Old bridge retired", old.binder)
            accept.invoke(client, replacement.binder, 0, true)
            val next = client.startHeadTrackerProbe("AA:BB:CC:DD:EE:FF", newPipe[0], 5, { true },
                { _, interval -> newReports += interval }, { newStops += it })
            ShadowBinder.setCallingUid(0)
            oldEvents.onReportingChanged(true, 10_000_000); oldEvents.onStopped("late_old_stop")
            ShadowBinder.setCallingUid(2000)
            newEvents.onReportingChanged(true, 15_000_000); newEvents.onStopped("foreign_stop")
            ShadowBinder.setCallingUid(0)
            newEvents.onReportingChanged(true, 20_000_000)
            drainClientCallbacks(client)
            assertTrue(oldReports.isEmpty()); assertTrue(oldStops.isEmpty()); assertTrue(newStops.isEmpty())
            assertEquals(listOf(20_000_000L), newReports)
            assertTrue(newOwner.isCurrent)
            newEvents.onStopped("finished"); drainClientCallbacks(client)
            assertEquals(listOf("finished"), newStops); assertFalse(newOwner.isCurrent)
            next.finish()
        } finally {
            oldPipe[1].close(); newPipe[1].close(); client.close(); ShadowBinder.setCallingUid(previousCaller)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun headTrackerReady() = Bundle().apply { putBoolean("ready", true); putBoolean("hal_handshake", true) }

    private suspend fun drainClientCallbacks(client: ShizukuAudioClient) {
        val scope = ShizukuAudioClient::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(client) as CoroutineScope
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
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
