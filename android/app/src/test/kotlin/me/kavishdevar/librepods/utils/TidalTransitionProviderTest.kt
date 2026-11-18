/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import io.github.libxposed.service.XposedService
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TidalTransitionProviderTest {
    private val uid = 10123
    private val pid = 123
    private val previousUid = Binder.getCallingUid()
    private val previousPid = Binder.getCallingPid()
    private val previousService = XposedServiceHolder.service
    private lateinit var context: Context
    private lateinit var provider: TidalTransitionProvider
    private lateinit var prefs: SharedPreferences
    private val writes = mutableListOf<Bundle>()
    private var failCommit = false
    private var lastGroup: String? = null
    private val claim = TidalComponentClaimPermit("a".repeat(32), 500, 60_000, 1, uid, 500)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = TidalBufferProbePolicy.PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = TidalBufferProbePolicy.PACKAGE; uid = this@TidalTransitionProviderTest.uid }
        })
        val prefix = TidalBufferProbePolicy.PREFIX
        val initial = hashMapOf<String, Any>(prefix + "issued_ms" to 500L, prefix + "deadline_ms" to 60_000L,
            prefix + "boot" to 1, prefix + "route_id" to 7, prefix + "peer" to "02:11:22:33:44:55")
        // The service AAR exposes its AIDL interface only at runtime, not on the test compile classpath.
        val api = Class.forName("io.github.libxposed.service.IXposedService")
        val remote = Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, args ->
            when (method.name) {
                "requestRemotePreferences" -> Bundle().apply { putSerializable("map", initial) }
                "updateRemotePreferences" -> {
                    if (failCommit) throw RemoteException("offline")
                    lastGroup = args!![0] as String
                    writes += Bundle(args[1] as Bundle)
                    null
                }
                "asBinder" -> Binder()
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "FakeXposedFramework"
                else -> error("Unexpected framework call: ${method.name}")
            }
        }
        val constructor = XposedService::class.java.getDeclaredConstructor(api).apply { isAccessible = true }
        XposedServiceHolder.service = constructor.newInstance(remote)
        prefs = XposedServiceHolder.service!!.getRemotePreferences(TidalBufferProbeHooks.PREFERENCES)
        assertTrue(claim.write(prefs.edit()).commit())
        writes.clear(); lastGroup = null
        provider = Robolectric.buildContentProvider(TidalTransitionProvider::class.java).create(TidalComponentTransition.AUTHORITY).get()
        ShadowBinder.setCallingUid(uid); ShadowBinder.setCallingPid(pid)
    }

    @After fun tearDown() {
        XposedServiceHolder.service = previousService
        ShadowBinder.setCallingUid(previousUid); ShadowBinder.setCallingPid(previousPid)
    }

    private fun marker() = TidalComponentTransition(claim.nonce, claim.bufferIssuedMs, pid, 456, 7,
        SystemClock.elapsedRealtimeNanos() - 10_000_000)
    private fun accepted(extras: Bundle? = marker().bundle(), method: String = TidalComponentTransition.METHOD,
        arg: String? = null): Boolean = provider.call(method, arg, extras).getBoolean("accepted")
    private fun reject(extras: Bundle? = marker().bundle()) {
        val previous = TidalComponentTransition.read(prefs)
        val count = writes.size
        assertFalse(accepted(extras))
        assertEquals(count, writes.size); assertEquals(previous, TidalComponentTransition.read(prefs))
    }

    @Test fun hookPublishesThroughProviderAndTheRealModuleEditorStoresOneAtomicObservation() {
        val original = marker()
        val parcel = Parcel.obtain()
        val wire = try {
            original.bundle().writeToParcel(parcel, 0); parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        assertEquals(original, TidalComponentTransition.fromBundle(wire))
        assertTrue(original.publish(context))
        assertEquals(original, TidalComponentTransition.read(prefs))
        assertEquals(1, writes.size)
        @Suppress("DEPRECATION") val put = writes.single().getSerializable("put") as Map<*, *>
        assertEquals(6, put.size)
        assertTrue(put.keys.all { (it as String).startsWith(TidalComponentTransition.PREFIX) })
        assertFalse(writes.single().getBoolean("clear"))
        assertEquals(TidalBufferProbeHooks.PREFERENCES, lastGroup)
        assertEquals(claim, TidalBufferProbeHooks.claimPermit(prefs))
    }

    @Test fun onlyActualTidalUidAndMarkerPidMayStoreAnObservation() {
        for (other in listOf(0, 2000, uid + 1)) { ShadowBinder.setCallingUid(other); reject() }
        ShadowBinder.setCallingUid(uid); ShadowBinder.setCallingPid(pid + 1); reject()
        ShadowBinder.setCallingPid(pid)
        assertTrue(accepted())
    }

    @Test fun onlyTheFixedPrimitiveEnvelopeAndMethodAreAccepted() {
        val data = marker().bundle()
        for (key in data.keySet()) reject(Bundle(data).apply { remove(key) })
        reject(Bundle(data).apply { putString("extra", "unexpected") })
        reject(Bundle(data).apply { putString("process_pid", pid.toString()) })
        reject(Bundle(data).apply { putInt("event_boottime_ns", 1) })
        for (key in listOf("buffer_issued_ms", "ownership_epoch", "event_boottime_ns"))
            reject(Bundle(data).apply { putLong(key, 0) })
        reject(null)
        assertFalse(accepted(data, "edit")); assertFalse(accepted(data, arg = "other"))
        assertTrue(writes.isEmpty())
    }

    @Test fun staleFutureForgedAndPreviousPermitMarkersCannotReplaceTheCurrentObservation() {
        val original = marker()
        assertTrue(accepted(original.bundle()))
        reject(original.copy(atNs = SystemClock.elapsedRealtimeNanos() + 1).bundle())
        reject(original.copy(atNs = SystemClock.elapsedRealtimeNanos() - 500_000_001).bundle())
        reject(original.copy(atNs = 499_000_000).bundle())
        reject(original.copy(nonce = "b".repeat(32)).bundle())
        reject(original.copy(nonce = TidalComponentClaimPermit.CLEAR).bundle())
        reject(original.copy(bufferIssuedMs = 499).bundle())
        reject(original.copy(atNs = original.atNs - 1).bundle())
        val before = writes.size
        assertTrue(accepted(original.bundle())); assertEquals(before, writes.size)
    }

    @Test fun expiredRevokedWrongBootOrWrongTargetClaimFailClosed() {
        val prefix = TidalBufferProbePolicy.PREFIX
        for (change in listOf<(SharedPreferences.Editor) -> Unit>(
            { it.putLong(prefix + "deadline_ms", SystemClock.elapsedRealtime()) },
            { it.putInt(prefix + "boot", 2) },
            { it.remove(TidalComponentClaimPermit.PREFIX + "nonce") },
            { it.putInt(TidalComponentClaimPermit.PREFIX + "target_uid", uid + 1) })) {
            val baseline = prefs.all
            val edit = prefs.edit(); change(edit); assertTrue(edit.commit())
            writes.clear(); reject()
            val restored = prefs.edit().clear()
            for ((key, value) in baseline) when (value) {
                is String -> restored.putString(key, value)
                is Int -> restored.putInt(key, value)
                is Long -> restored.putLong(key, value)
            }
            assertTrue(restored.commit()); writes.clear()
        }
        XposedServiceHolder.service = null; reject()
    }

    @Test fun failedFrameworkCommitDoesNotReportDeliverySuccess() {
        failCommit = true
        assertFalse(accepted())
    }

    @Test fun visibilityGrantTargetsOnlyTheFixedObservationUri() {
        val app = mock(Context::class.java)
        TidalComponentTransition.grantVisibility(app)
        verify(app).grantUriPermission(TidalBufferProbePolicy.PACKAGE, Uri.parse("content://${TidalComponentTransition.AUTHORITY}"),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        verifyNoMoreInteractions(app)
    }
}
