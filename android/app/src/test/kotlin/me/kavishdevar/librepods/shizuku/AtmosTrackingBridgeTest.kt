/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.IBinder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosTrackingBridgeTest {
    @Test fun rootAdmissionCapturesExactRejectedPredicateUnderItsLock() {
        val previousUid = Process.myUid()
        try {
            ShadowProcess.setUid(10123)
            val context = mock(Context::class.java); val pm = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(pm)
            `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
            val service = ShizukuAudioService(context, false)
            val lock = ReflectionHelpers.getField<Any>(service, "lock")
            val microphone = mock(RootMicrophoneProbe::class.java)
            `when`(microphone.isCleanupComplete).thenAnswer { assertTrue(Thread.holdsLock(lock)); false }
            fun read() = ReflectionHelpers.callInstanceMethod<String?>(service, "atmosAdmissionRejection")
            val cases = listOf(
                Triple("cleanupFailed", AtomicBoolean(true), "cleanup_failed"),
                Triple("volumeRepairing", true, "volume_repairing"),
                Triple("spatialMutating", true, "spatial_mutating"),
                Triple("playbackRecovery", mock(A2dpPlaybackRecovery::class.java), "playback_recovery"),
                Triple("playbackRelease", mock(NativePlaybackRelease::class.java), "playback_release"),
                Triple("callConnection", mock(NativeCallConnection::class.java), "call_connection"),
                Triple("microphoneProbe", mock(IPlaybackRecoveryOwner::class.java) to microphone, "probe_not_quiet"))
            assertNull(read())
            for ((fieldName, value, expected) in cases) {
                val field = ShizukuAudioService::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
                val previous = field.get(service)
                synchronized(lock) { field.set(service, value) }
                val captured = read()
                synchronized(lock) { field.set(service, previous) }
                assertEquals(expected, captured) // The rejection survives the transient flag being cleared.
                assertNull(read())
            }
            ReflectionHelpers.setField(service, "volumeChecking", true)
            assertNull(read()) // Observation retains its existing reservation without blocking native tracking.
            ReflectionHelpers.setField(service, "volumeRepairing", true)
            ReflectionHelpers.setField(service, "spatialMutating", true)
            assertEquals("volume_repairing", read())
        } finally { ShadowProcess.setUid(previousUid) }
    }

    @Test fun rootLockArbitratesBothAcquisitionOrdersAndKeepsCleanupAvailable() {
        val previousUid = Process.myUid(); val previousCaller = Binder.getCallingUid()
        val release = CountDownLatch(1)
        var registration: Thread? = null
        try {
            ShadowProcess.setUid(10123)
            val context = mock(Context::class.java); val pm = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(pm)
            `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
            `when`(pm.checkPermission(anyString(), eq(BuildConfig.APPLICATION_ID))).thenReturn(PackageManager.PERMISSION_GRANTED)
            val service = ShizukuAudioService(context, false)
            val manager = mock(RootAtmosHeadTracking::class.java)
            val live = AtomicBoolean(false)
            `when`(manager.hasLiveLease()).thenAnswer { live.get() }
            ReflectionHelpers.setField(service, "atmosHeadTracking", manager)
            val serviceLock = ReflectionHelpers.getField<Any>(service, "lock")
            val lease = mock(AtmosTrackingLease::class.java)
            val owner = mock(IPlaybackRecoveryOwner::class.java)
            val placeholderPipe = mock(ParcelFileDescriptor::class.java)
            `when`(manager.open(anyString(), any<IPlaybackRecoveryOwner>() ?: owner,
                any<IBinder>() ?: Binder(), any<Bundle>() ?: Bundle(), any<Bundle>() ?: Bundle(),
                any<ParcelFileDescriptor>() ?: placeholderPipe)).thenAnswer {
                assertTrue(Thread.holdsLock(serviceLock)); live.set(true); lease
            }
            val method = ShizukuAudioService::class.java.getDeclaredMethod("headTrackerMutation",
                Boolean::class.javaPrimitiveType, Function0::class.java).apply { isAccessible = true }
            fun mutate(cleanup: Boolean, action: () -> Unit) {
                try { method.invoke(service, cleanup, action) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
            }
            ShadowProcess.setUid(0); ShadowBinder.setCallingUid(10123)
            fun open(pipe: ParcelFileDescriptor) = service.openAtmosHeadTracking(
                "02:11:22:33:44:55", owner, Binder(), Bundle(), Bundle(), pipe)
            val entered = CountDownLatch(1); val threadError = AtomicReference<Throwable?>()
            registration = kotlin.concurrent.thread {
                try { mutate(false) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) } }
                catch (error: Throwable) { threadError.set(error) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val denied = mock(ParcelFileDescriptor::class.java)
            assertEquals("atmos_busy", assertThrows(IllegalStateException::class.java) { open(denied) }.message)
            verify(denied).close()
            assertFalse(mockingDetails(manager).invocations.any { it.method.name == "open" })
            release.countDown(); registration.join(2_000); assertFalse(registration.isAlive)
            assertNull(threadError.get())
            assertSame(lease, open(mock(ParcelFileDescriptor::class.java)))
            var ran = false
            assertEquals("busy", assertThrows(IllegalStateException::class.java) { mutate(false) { ran = true } }.message)
            assertFalse(ran)
            mutate(true) { ran = true }; assertTrue(ran) // Actual restoration must remain available.
            live.set(false) // Stop releases registration admission even while old async cleanup is unfinished.
            ran = false; mutate(false) { ran = true }; assertTrue(ran)
        } finally {
            release.countDown(); registration?.join(2_000)
            ShadowProcess.setUid(previousUid); ShadowBinder.setCallingUid(previousCaller)
        }
    }

    @Test fun rootOwnerFailureIdentifiesFirstRejectedCheckWithoutCallingLaterChecks() {
        val stages = listOf("owner_admission", "owner_binder", "source_binder", "owner_callback")
        for (failed in stages.indices) {
            val called = mutableListOf<Int>(); var stage = ""
            fun check(index: Int): Boolean { called += index; return index != failed }
            val error = assertThrows(IllegalStateException::class.java) {
                checkAtmosOwner({ check(0) }, { check(1) }, { check(2) }, { check(3) }) { stage = it }
            }
            assertEquals("owner_changed", error.message)
            assertEquals(stages[failed], stage)
            assertEquals((0..failed).toList(), called)
        }
        checkAtmosOwner({ true }, { true }, { true }, { true }) {}
    }

    @Test fun rootEntryPreservesActualCallerUidAndRootPermissionChecks() {
        val previousUid = Process.myUid(); val previousCaller = Binder.getCallingUid()
        try {
            ShadowProcess.setUid(10123)
            val context = mock(Context::class.java); val pm = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(pm)
            `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
            val service = ShizukuAudioService(context, false)
            val owner = mock(IPlaybackRecoveryOwner::class.java)
            val first = mock(ParcelFileDescriptor::class.java)
            ShadowProcess.setUid(0); ShadowBinder.setCallingUid(10124)
            assertThrows(IllegalArgumentException::class.java) {
                service.openAtmosHeadTracking("02:11:22:33:44:55", owner, Binder(), Bundle(), Bundle(), first)
            }
            verify(first).close()
            val second = mock(ParcelFileDescriptor::class.java)
            ShadowProcess.setUid(2000); ShadowBinder.setCallingUid(10123)
            assertThrows(IllegalStateException::class.java) {
                service.openAtmosHeadTracking("02:11:22:33:44:55", owner, Binder(), Bundle(), Bundle(), second)
            }
            verify(second).close()
            val third = mock(ParcelFileDescriptor::class.java)
            ShadowProcess.setUid(0)
            `when`(pm.checkPermission(anyString(), eq(BuildConfig.APPLICATION_ID))).thenReturn(PackageManager.PERMISSION_DENIED)
            assertThrows(IllegalStateException::class.java) {
                service.openAtmosHeadTracking("02:11:22:33:44:55", owner, Binder(), Bundle(), Bundle(), third)
            }
            verify(third).close()
        } finally { ShadowProcess.setUid(previousUid); ShadowBinder.setCallingUid(previousCaller) }
    }

    @Test fun oldScopedClientCleanupNeverRetiresReplacementBinder() {
        val old = mock(AtmosTrackingLease::class.java); val replacement = mock(AtmosTrackingLease::class.java)
        var oldInvalidations = 0; var newInvalidations = 0
        val a = AtmosClientLease(old) { oldInvalidations++ }
        val b = AtmosClientLease(replacement) { newInvalidations++ }
        a.close(); a.close()
        verify(old, times(1)).retire(); verify(replacement, never()).retire()
        assertEquals(1, oldInvalidations); assertEquals(0, newInvalidations)
        b.close(); verify(replacement).retire()
    }

    @Test fun retiredProducerCannotAdoptLateRootOpenResult() = runBlocking {
        val client = ShizukuAudioClient(ApplicationProvider.getApplicationContext<Application>(), requireRoot = true)
        val binder = mock(IBinder::class.java); val remote = mock(IAudioBridge::class.java)
        `when`(remote.asBinder()).thenReturn(binder)
        `when`(binder.queryLocalInterface("me.kavishdevar.librepods.shizuku.IAudioBridge")).thenReturn(remote)
        ShizukuAudioClient::class.java.getDeclaredMethod("acceptBridge", IBinder::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(client, binder, 0, true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val captured = AtomicReference<IPlaybackRecoveryOwner>()
        val current = AtomicBoolean(true)
        val poses = mock(ParcelFileDescriptor::class.java)
        `when`(remote.openAtmosHeadTracking(anyString(), any(), any(), any(), any(), any())).thenAnswer {
            captured.set(it.getArgument(1)); entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            mock(AtmosTrackingLease::class.java)
        }
        try {
            val result = async(Dispatchers.Default) {
                runCatching { client.openAtmosHeadTracking("02:11:22:33:44:55", Binder(), Bundle(), Bundle(), poses, current::get) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            current.set(false); release.countDown()
            assertEquals("owner_changed", withTimeout(2_000) { result.await() }.exceptionOrNull()?.message)
            verify(remote).retireOwner(captured.get()); verify(poses).close()
        } finally { release.countDown(); client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun sourceAndPermitMustBeCompleteBoundedAndCurrent() {
        val source = Bundle().apply {
            putByteArray("full_role", byteArrayOf(1, 0, 1, 0)); putString("model", "model"); putString("firmware", "fw")
            putInt("profile_schema", 1); putByteArray("profile_digest", ByteArray(32) { 1 })
            putLong("source_epoch", 1); putLong("reference_epoch", 2); putInt("discontinuity", 3)
        }
        assertTrue(AtmosTrackingContract.validSource(source))
        for (name in listOf("full_role", "model", "firmware", "profile_schema", "profile_digest", "source_epoch", "reference_epoch", "discontinuity"))
            assertFalse(AtmosTrackingContract.validSource(Bundle(source).apply { remove(name) }))
        assertFalse(AtmosTrackingContract.validSource(Bundle(source).apply { putByteArray("profile_digest", ByteArray(32)) }))
        val permit = Bundle().apply {
            putLong("issued_ms", 100); putLong("deadline_ms", 1000); putInt("boot", 9)
            putInt("route_id", 12); putString("peer", "02:11:22:33:44:55"); putInt("directional_mapping", 3)
        }
        assertTrue(AtmosTrackingContract.validPermit(permit, 200, 9, "02:11:22:33:44:55"))
        assertFalse(AtmosTrackingContract.validPermit(permit, 200, 10, "02:11:22:33:44:55"))
        assertFalse(AtmosTrackingContract.validPermit(permit, 1000, 9, "02:11:22:33:44:55"))
        assertFalse(AtmosTrackingContract.validPermit(permit, 200, 9, "02:11:22:33:44:56"))
        for (mapping in 1..4) {
            permit.putInt("directional_mapping", mapping)
            assertTrue(AtmosTrackingContract.validPermit(permit, 200, 9, "02:11:22:33:44:55"))
        }
        for (mapping in listOf(-1, 0, 5)) {
            permit.putInt("directional_mapping", mapping)
            assertFalse(AtmosTrackingContract.validPermit(permit, 200, 9, "02:11:22:33:44:55"))
        }
        permit.remove("directional_mapping")
        assertFalse(AtmosTrackingContract.validPermit(permit, 200, 9, "02:11:22:33:44:55"))
    }

    @Test fun productionGrantPreservesVerifiedMappingThroughTheNativeSnapshot() {
        val address = "02:11:22:33:44:55"
        val permit = Bundle().apply {
            putBoolean("production", true); putLong("choice_epoch", 7); putInt("directional_mapping", 3)
            putBoolean("enabled", false) // The legacy experiment switch is not production authorization.
        }
        issueAtmosProductionGrant(permit, 1000, 9, 12, address)
        assertTrue(AtmosTrackingContract.validPermit(permit, 1001, 9, address))
        val source = Bundle().apply {
            putByteArray("full_role", byteArrayOf(1, 0, 1, 0)); putString("model", "A3064"); putString("firmware", "8-test")
            putInt("profile_schema", 1); putByteArray("profile_digest", ByteArray(32) { 1 })
            putLong("source_epoch", 2); putLong("reference_epoch", 2); putInt("discontinuity", 2)
        }
        val snapshot = prepareAtmosNativeSnapshot(0, 123, 10123, 456, 10414, address, source, permit,
            Binder(), Binder(), 1_001_000_000)
        assertEquals(3, snapshot.directionalMapping)
        assertEquals(1_000L, snapshot.bufferIssuedBoottimeMs)
        assertEquals((1000 + AtmosTrackingContract.MAX_WINDOW_MS) * 1_000_000, snapshot.deadlineBoottimeNs)
        assertFalse(permit.getBoolean("enabled"))
    }

    @Test fun productionRejectsInvalidMappingBeforeIssuingAnyGrant() {
        for (mapping in listOf(null, -1, 0, 5)) {
            val permit = Bundle().apply {
                putBoolean("production", true); putLong("choice_epoch", 7)
                if (mapping != null) putInt("directional_mapping", mapping)
            }
            assertEquals("invalid_directional_mapping", assertThrows(IllegalStateException::class.java) {
                issueAtmosProductionGrant(permit, 1000, 9, 12, "02:11:22:33:44:55")
            }.message)
            assertFalse(permit.containsKey("issued_ms")); assertFalse(permit.containsKey("deadline_ms"))
        }
    }

    @Test fun cachedStatusRequiresExactSchemaAndActualRootProcess() {
        val status = LongArray(20).apply { this[0] = 1; this[1] = 1; this[2] = 3; this[19] = 321 }
        assertTrue(AtmosTrackingContract.validStatus(status, 321))
        assertFalse(AtmosTrackingContract.validStatus(status, 322))
        assertFalse(AtmosTrackingContract.validStatus(status.copyOf(19), 321))
        assertFalse(AtmosTrackingContract.validStatus(status.copyOf().apply { this[0] = 2 }, 321))
        assertFalse(AtmosTrackingContract.validStatus(status.copyOf().apply { this[1] = 7 }, 321))
    }

    @Test fun optionalFirstFailureDiagnosticRequiresExactEnvelopeAndNeverPollsHealthyNativeState() {
        val diagnostic = LongArray(36) { Long.MIN_VALUE }.apply {
            this[0] = 1; this[1] = 1; this[19] = 321; this[35] = 0
        }
        assertTrue(AtmosTrackingContract.validDiagnostic(diagnostic, 321)) // Other raw words are evidence, not authority.
        assertFalse(AtmosTrackingContract.validDiagnostic(diagnostic.copyOf(35), 321))
        assertFalse(AtmosTrackingContract.validDiagnostic(diagnostic.copyOf(37), 321))
        assertFalse(AtmosTrackingContract.validDiagnostic(diagnostic, 322))
        for ((index, value) in listOf(0 to 2L, 1 to 0L, 19 to 0L, 35 to 1L))
            assertFalse(AtmosTrackingContract.validDiagnostic(diagnostic.copyOf().apply { this[index] = value }, 321))
        var calls = 0
        val read = { calls++; diagnostic }
        val status = LongArray(20).apply { this[0] = 1; this[19] = 321 }
        for (phase in 0L..4L) {
            status[1] = phase
            assertNull(AtmosTrackingContract.terminalDiagnostic(true, status, 321, read))
        }
        assertNull(AtmosTrackingContract.terminalDiagnostic(true, null, 321, read))
        assertNull(AtmosTrackingContract.terminalDiagnostic(true, status.copyOf(1), 321, read))
        assertEquals(0, calls)
        for (phase in 5L..6L) {
            status[1] = phase
            assertSame(diagnostic, AtmosTrackingContract.terminalDiagnostic(true, status, 321, read))
        }
        assertSame(diagnostic, AtmosTrackingContract.terminalDiagnostic(false, null, 321, read))
        assertEquals(3, calls)
        assertNull(AtmosTrackingContract.terminalDiagnostic(false, null, 321) { throw UnsatisfiedLinkError("old JNI") })
        assertNull(AtmosTrackingContract.terminalDiagnostic(false, null, 321) { LongArray(36) })
    }
}
