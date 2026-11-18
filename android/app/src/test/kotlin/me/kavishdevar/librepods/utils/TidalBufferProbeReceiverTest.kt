/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TidalBufferProbeReceiverTest {
    private val previousService = XposedServiceHolder.service
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val writes = mutableListOf<Bundle>()
    private val uid = 10414
    private val prefix = TidalBufferProbePolicy.PREFIX
    private val claim = TidalComponentClaimPermit("a".repeat(32), 500, 60_000, 1, uid, 500)

    @Test fun freshPreferencesDefaultToTheSmallBufferAndRespectAnExplicitOptOut() {
        val fresh = RuntimeEnvironment.getApplication().getSharedPreferences("fresh-buffer", Context.MODE_PRIVATE)
        assertTrue(TidalBufferProbeHooks.retained(fresh))
        fresh.edit().putBoolean(TidalBufferProbePolicy.RETAINED_KEY, false).apply()
        assertFalse(TidalBufferProbeHooks.retained(fresh))
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = TidalBufferProbePolicy.PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = TidalBufferProbePolicy.PACKAGE; uid = this@TidalBufferProbeReceiverTest.uid }
        })
        val initial = hashMapOf<String, Any>(prefix + "issued_ms" to 500L, prefix + "deadline_ms" to 60_000L,
            prefix + "boot" to 1, prefix + "route_id" to 7, prefix + "peer" to "02:11:22:33:44:55",
            prefix + "production" to true, prefix + "choice_epoch" to 7L, TidalBufferProbePolicy.RETAINED_KEY to true,
            "unrelated_volume" to 14)
        val api = Class.forName("io.github.libxposed.service.IXposedService")
        val remote = Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, args ->
            when (method.name) {
                "requestRemotePreferences" -> Bundle().apply { putSerializable("map", initial) }
                "updateRemotePreferences" -> { writes += Bundle(args!![1] as Bundle); null }
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
        assertTrue(claim.write(prefs.edit()).commit()); writes.clear()
    }

    @After fun tearDown() { XposedServiceHolder.service = previousService }

    private fun dispatch(operation: String, deadline: Long = 50_000) = TidalBufferProbeReceiver().onReceive(context,
        Intent().putExtra("operation", operation).putExtra("deadline_ms", deadline))

    @Test fun observationOnlyWritesItsBoundedWindowAndNeverArmsOrChangesPlaybackPolicy() {
        val before = prefs.all.toMap()
        dispatch("observe")
        val observation = requireNotNull(TidalBufferProbeHooks.observation(prefs))
        assertTrue(TidalBufferProbeHooks.observationLive(prefs, observation, SystemClock.elapsedRealtime(), 1, uid))
        assertEquals(1, writes.size)
        assertEquals(before, prefs.all.filterKeys { !it.startsWith(TidalBufferProbePolicy.OBSERVE_PREFIX) })
        assertEquals(4, prefs.all.size - before.size)
        assertEquals(7L, observation.choiceEpoch); assertEquals(1, observation.bootCount)
        writes.clear(); dispatch("query"); assertTrue(writes.isEmpty())
        dispatch("unobserve")
        assertNull(TidalBufferProbeHooks.observation(prefs)); assertEquals(before, prefs.all)
    }

    @Test fun missingExpiredOrNonproductionAuthorityCannotCreateAnObservationWindow() {
        val baseline = prefs.all.toMap()
        for (change in listOf<(SharedPreferences.Editor) -> Unit>(
            { it.remove(TidalComponentClaimPermit.PREFIX + "nonce") },
            { it.putBoolean(prefix + "production", false) },
            { it.putLong(prefix + "choice_epoch", 0) },
            { it.putInt(prefix + "boot", 2) },
            { it.putLong(prefix + "deadline_ms", SystemClock.elapsedRealtime()) })) {
            val edit = prefs.edit(); change(edit); assertTrue(edit.commit()); writes.clear()
            dispatch("observe"); assertTrue(writes.isEmpty()); assertNull(TidalBufferProbeHooks.observation(prefs))
            val restored = prefs.edit().clear()
            baseline.forEach { (key, value) -> when (value) {
                is String -> restored.putString(key, value)
                is Int -> restored.putInt(key, value)
                is Long -> restored.putLong(key, value)
                is Boolean -> restored.putBoolean(key, value)
            } }
            assertTrue(restored.commit()); writes.clear()
        }
        dispatch("observe", SystemClock.elapsedRealtime()); assertTrue(writes.isEmpty())
        dispatch("observe", SystemClock.elapsedRealtime() + TidalBufferProbePolicy.MAX_WINDOW_MS + 1); assertTrue(writes.isEmpty())
    }

    @Test fun sameBufferAndClaimCannotKeepAnObservationLiveAfterRootChoiceRevocation() {
        dispatch("observe")
        val window = requireNotNull(TidalBufferProbeHooks.observation(prefs))
        val buffer = TidalBufferProbeHooks.permit(prefs)
        val owner = TidalBufferProbeHooks.claimPermit(prefs)
        assertTrue(prefs.edit().putLong(prefix + "choice_epoch", 8).commit())
        assertFalse(TidalBufferProbeHooks.observationLive(prefs, window, SystemClock.elapsedRealtime(), 1, uid))
        assertEquals(buffer, TidalBufferProbeHooks.permit(prefs)); assertEquals(owner, TidalBufferProbeHooks.claimPermit(prefs))
        assertTrue(prefs.edit().putLong(prefix + "choice_epoch", 7).putBoolean(prefix + "production", false).commit())
        assertFalse(TidalBufferProbeHooks.observationLive(prefs, window, SystemClock.elapsedRealtime(), 1, uid))
    }

    @Test fun restartedPlayerMayUseAFreshLiveGrantInsideTheSameBootAndChoiceWindow() {
        dispatch("observe")
        val window = requireNotNull(TidalBufferProbeHooks.observation(prefs))
        assertTrue(prefs.edit().remove(TidalComponentClaimPermit.PREFIX + "nonce").commit())
        assertTrue(window.windowLive(SystemClock.elapsedRealtime(), 1))
        assertFalse(TidalBufferProbeHooks.observationLive(prefs, window, SystemClock.elapsedRealtime(), 1, uid))
        val fresh = claim.copy(nonce = "b".repeat(32), issuedMs = 1800, bufferIssuedMs = 1800)
        assertTrue(fresh.write(prefs.edit().putLong(prefix + "issued_ms", 1800)).commit())
        assertTrue(TidalBufferProbeHooks.observationLive(prefs, window, SystemClock.elapsedRealtime(), 1, uid))
        assertEquals(window, TidalBufferProbeHooks.observation(prefs))
    }
}
