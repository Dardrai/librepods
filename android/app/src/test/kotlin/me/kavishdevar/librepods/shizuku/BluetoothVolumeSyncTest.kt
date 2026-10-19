package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.IBinder
import android.os.IInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class BluetoothVolumeSyncTest {
    private val address = "00:11:22:33:44:55"
    private val owner = Any()
    private var current = true
    private var granted = true
    private var elapsed = 0L
    private var onPause: () -> Unit = {}
    private val audio = FakeAudio()
    private val sync = BluetoothVolumeSync(RuntimeEnvironment.getApplication(), audio, { granted }, { elapsed }, {
        elapsed += it
        onPause()
    })
    init { sync.selectRoute(address) }
    private fun check(selectedOwner: Any = owner) = sync.check(address, selectedOwner) { current }
    private fun loseSync() {
        assertFalse(check())
        audio.behavior = 0
        assertFalse(check())
        elapsed += 500
    }
    private inner class FakeAudio : BluetoothVolumeSync.Audio {
        var enabled = true
        var mode = AudioManager.MODE_NORMAL
        @JvmField var volume = 13
        var maximum = 25
        var behavior = 3
        var role = 2
        var type = 8
        var routeAddress = address
        var routeCount = 1
        var afterWrite: (Int) -> Unit = { volume = it }
        var afterAbsolute: () -> Unit = { behavior = 3 }
        var beforeConfig: () -> Unit = {}
        val configChecks = mutableListOf<String>()
        val writes = mutableListOf<String>()
        override fun syncEnabled(address: String): Boolean { configChecks += address; beforeConfig(); return enabled }
        override fun snapshot() = BluetoothVolumeSync.Snapshot(mode, volume, maximum, List(routeCount) {
            BluetoothVolumeSync.Route(routeAddress, role, type, behavior, this)
        })
        override fun setVolume(index: Int) { writes += "volume:$index"; afterWrite(index) }
        override fun setAbsolute(route: BluetoothVolumeSync.Route) { writes += "absolute"; afterAbsolute() }
    }

    @Test fun aPreviouslyHealthyStablePairIsMutedThenRestoredAtTheSafeCeiling() {
        loseSync()
        assertTrue(check())
        assertEquals(listOf("volume:0", "absolute") + (1..11).map { "volume:$it" }, audio.writes)
        assertEquals(11, audio.volume)
        assertEquals(3, audio.behavior)
        assertEquals(listOf(address, address), audio.configChecks)
        assertFalse(check())
        assertEquals(13, audio.writes.size)
    }

    @Test fun aVariableRouteWithoutAHealthyObservationIsNeverModified() {
        audio.behavior = 0
        assertFalse(check())
        elapsed += 10_000
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
        assertTrue(audio.configChecks.isEmpty())
    }

    @Test fun healthyAndTransientRoutesDoNotRunTheExpensiveBluetoothConfigurationCheck() {
        assertFalse(check())
        assertFalse(check())
        audio.behavior = 0
        assertFalse(check())
        elapsed += 499
        assertFalse(check())
        assertTrue(audio.configChecks.isEmpty())
        elapsed += 1
        audio.enabled = false
        assertFalse(check())
        assertEquals(listOf(address), audio.configChecks)
        assertTrue(audio.writes.isEmpty())
    }

    @Test fun oneTransientVariableSampleDoesNotTriggerARepair() {
        loseSync()
        audio.behavior = 3
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
    }

    @Test fun healthyCapabilityIsBoundToTheExactOwnerAndCanBeForgotten() {
        loseSync()
        assertFalse(check(Any()))
        elapsed += 1000
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
        audio.behavior = 3
        assertFalse(check())
        sync.forget(address.lowercase())
        audio.behavior = 0
        assertFalse(check())
        elapsed += 500
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
    }

    @Test fun aCarHandoffKeepsPriorCapabilityButResetsTheStabilityWindow() {
        loseSync()
        sync.selectRoute("00:11:22:33:44:66")
        elapsed += 10_000
        sync.selectRoute(address)
        assertFalse(check())
        elapsed += 499
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
        elapsed += 1
        assertTrue(check())
    }

    @Test fun losingTheSoleSelectedRouteAlsoRequiresAFreshStabilityWindowOnReturn() {
        loseSync()
        sync.selectRoute(null)
        elapsed += 10_000
        assertFalse(check())
        sync.selectRoute(address)
        assertFalse(check())
        elapsed += 499
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
        elapsed += 1
        assertTrue(check())
    }

    @Test fun repeatedSelectionOfTheSameRoutePreservesTheTimerAndInFlightRepair() {
        loseSync()
        sync.selectRoute(address.lowercase())
        onPause = { sync.selectRoute(address.lowercase()) }
        assertTrue(check())
        assertEquals(11, audio.volume)
    }

    private fun assertRetiresBlockedCheck(retire: () -> Unit) {
        loseSync()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        audio.beforeConfig = {
            entered.countDown()
            assertTrue("Configuration read was never released", release.await(5, SECONDS))
        }
        val workers = Executors.newFixedThreadPool(2)
        val blocked = workers.submit<Boolean> { check() }
        try {
            assertTrue(entered.await(5, SECONDS))
            assertFalse(workers.submit<Boolean> { check() }.get(5, SECONDS))
            workers.submit { retire() }.get(5, SECONDS)
            assertEquals(1L, release.count)
            release.countDown()
            assertFalse(blocked.get(5, SECONDS))
            assertTrue(audio.writes.isEmpty())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun forgettingAnOwnerDoesNotWaitForBlockedIoAndStopsLaterWrites() {
        assertRetiresBlockedCheck { sync.forget(owner) }
    }

    @Test fun forgettingAnAddressDoesNotWaitForBlockedIoAndStopsLaterWrites() {
        assertRetiresBlockedCheck { sync.forget(address.lowercase()) }
    }

    @Test fun closingDoesNotWaitForBlockedIoAndStopsLaterWrites() {
        assertRetiresBlockedCheck { sync.close() }
    }

    @Test fun aRouteChangeDoesNotWaitForBlockedIoAndStopsLaterWrites() {
        assertRetiresBlockedCheck { sync.selectRoute(null) }
    }

    @Test fun callsUncertainRoutesDisabledSyncAndMissingShizukuDoNotWrite() {
        val invalid: List<() -> Unit> = listOf(
            { audio.mode = AudioManager.MODE_IN_CALL }, { audio.routeCount = 2 },
            { audio.role = 1 }, { audio.type = 7 }, { audio.enabled = false },
            { granted = false }, { current = false }, { audio.maximum = 0 },
            { audio.volume = -1 }, { audio.volume = 26 }, { audio.behavior = 1 })
        for (makeInvalid in invalid) {
            audio.mode = AudioManager.MODE_NORMAL; audio.routeCount = 1; audio.role = 2; audio.type = 8
            audio.enabled = true; granted = true; current = true; audio.maximum = 25; audio.volume = 13
            audio.behavior = 3
            assertFalse(check())
            audio.behavior = 0
            assertFalse(check())
            elapsed += 500
            makeInvalid()
            assertFalse(check())
            assertTrue(audio.writes.isEmpty())
        }
    }

    @Test fun theLastHealthyIndexAndCurrentLevelCanEachLowerTheCeiling() {
        audio.volume = 8
        loseSync()
        audio.volume = 15
        assertTrue(check())
        assertEquals(8, audio.volume)
        audio.volume = 4
        loseSync()
        assertTrue(check())
        assertEquals(4, audio.volume)
    }

    @Test fun anAlreadyMutedPairStaysMuted() {
        loseSync()
        audio.volume = 0
        assertTrue(check())
        assertEquals(listOf("absolute"), audio.writes)
        assertEquals(0, audio.volume)
    }

    @Test fun asynchronousZeroAndRampReadbackAreVerifiedWithoutRealSleeping() {
        loseSync()
        var pending: Int? = null
        var due = 0L
        audio.afterWrite = { pending = it; due = elapsed + 75 }
        onPause = { if (pending != null && elapsed >= due) { audio.volume = pending!!; pending = null } }
        assertTrue(check())
        assertEquals(11, audio.volume)
    }

    @Test fun cumulativeReadbackDelaysStopAtTheCheckBudgetAndLaterResumeTheOwnedSafeLevel() {
        loseSync()
        val started = elapsed
        var pending: Int? = null
        var due = 0L
        val writeTimes = mutableListOf<Long>()
        audio.afterWrite = { writeTimes += elapsed; pending = it; due = elapsed + 400 }
        onPause = { if (pending != null && elapsed >= due) { audio.volume = pending!!; pending = null } }
        assertFalse(check())
        assertTrue(elapsed >= started + 2_000)
        assertTrue(writeTimes.all { it < started + 2_000 })
        assertEquals(listOf("volume:0", "absolute", "volume:1", "volume:2", "volume:3", "volume:4"), audio.writes)
        elapsed += 30_000
        onPause() // Android completes the last already-issued write after our check has retired.
        audio.afterWrite = { audio.volume = it }
        onPause = {}
        assertTrue(check())
        assertEquals(11, audio.volume)
    }

    @Test fun aRejectedOrNeverAppliedMuteNeverEnablesAbsoluteVolume() {
        loseSync()
        audio.afterWrite = {}
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
        assertEquals(1000, elapsed.toInt())
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
    }

    @Test fun aFailedMuteCanBeRetriedAfterABoundedCooldownWithoutRapidRepeatedWrites() {
        loseSync()
        audio.afterWrite = {}
        assertFalse(check())
        elapsed += 29_499
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
        elapsed += 1
        audio.afterWrite = { audio.volume = it }
        assertTrue(check())
        assertEquals(11, audio.volume)
    }

    @Test fun asynchronousBehaviorReadbackKeepsTheRouteMutedUntilItIsAbsolute() {
        loseSync()
        audio.afterAbsolute = {}
        onPause = { if (audio.writes.lastOrNull() == "absolute" && elapsed >= 600) audio.behavior = 3 }
        assertTrue(check())
        assertEquals(11, audio.volume)
        assertTrue(elapsed >= 1040)
    }

    @Test fun androidRestoringSyncWhileOurMuteAppliesStillRestoresTheOwnedVolume() {
        loseSync()
        audio.afterWrite = { audio.volume = it; audio.behavior = 3 }
        assertTrue(check())
        assertEquals(11, audio.volume)
        assertEquals(listOf("volume:0") + (1..11).map { "volume:$it" }, audio.writes)
    }

    @Test fun aBehaviorWriteFailureRetainsOnlyOurMutedLevelAndOriginalCappedRestoration() {
        loseSync()
        audio.afterAbsolute = { throw SecurityException("Temporary binder failure") }
        assertFalse(check())
        assertEquals(0, audio.volume)
        assertEquals(0, audio.behavior)
        assertEquals(listOf("volume:0", "absolute"), audio.writes)
        elapsed += 30_000
        assertFalse(check())
        elapsed += 500
        audio.afterAbsolute = { audio.behavior = 3 }
        assertTrue(check())
        assertEquals(11, audio.volume)
        assertEquals(listOf("volume:0", "absolute", "absolute") + (1..11).map { "volume:$it" }, audio.writes)
    }

    @Test fun aRouteHandoffDiscardsPendingRestorationOfThePreviousSelectedOutput() {
        loseSync()
        audio.afterAbsolute = { throw SecurityException("Temporary binder failure") }
        assertFalse(check())
        assertEquals(0, audio.volume)
        sync.selectRoute("00:11:22:33:44:66")
        elapsed += 30_000
        sync.selectRoute(address)
        audio.afterAbsolute = { audio.behavior = 3 }
        assertFalse(check())
        elapsed += 500
        assertTrue(check())
        assertEquals(0, audio.volume)
        assertEquals(listOf("volume:0", "absolute", "absolute"), audio.writes)
    }

    @Test fun userOverrideAfterAFailedBehaviorWriteDiscardsThePendingRestoration() {
        loseSync()
        audio.afterAbsolute = { throw SecurityException("Temporary binder failure") }
        assertFalse(check())
        audio.volume = 2
        elapsed += 30_000
        assertFalse(check())
        elapsed += 500
        audio.afterAbsolute = { audio.behavior = 3 }
        assertTrue(check())
        assertEquals(2, audio.volume)
    }

    @Test fun aCallAfterOurMutePausesRecoveryWithoutLosingTheOriginalSafeLevel() {
        loseSync()
        audio.afterWrite = { audio.volume = it; if (it == 0) audio.mode = AudioManager.MODE_IN_CALL }
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
        assertEquals(0, audio.volume)
        elapsed += 30_000
        audio.mode = AudioManager.MODE_NORMAL
        assertTrue(check())
        assertEquals(11, audio.volume)
    }

    @Test fun temporaryShizukuLossAfterOurMutePreservesTheOwnedRestoration() {
        loseSync()
        audio.afterWrite = { audio.volume = it; if (it == 0) granted = false }
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
        elapsed += 30_000
        granted = true
        assertTrue(check())
        assertEquals(11, audio.volume)
    }

    @Test fun changedVolumeScaleAfterAnInterruptedMuteDiscardsThePendingRestoration() {
        loseSync()
        audio.afterWrite = { audio.volume = it; if (it == 0) audio.mode = AudioManager.MODE_IN_CALL }
        assertFalse(check())
        elapsed += 30_000
        audio.mode = AudioManager.MODE_NORMAL
        audio.maximum = 10
        assertTrue(check())
        assertEquals(0, audio.volume)
        assertEquals(listOf("volume:0", "absolute"), audio.writes)
    }

    @Test fun routeChangeAfterMutingPreventsBehaviorAndRampWrites() {
        loseSync()
        audio.afterWrite = { audio.volume = it; audio.routeAddress = "00:11:22:33:44:66" }
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
    }

    @Test fun manualVolumeChangeDuringPendingMuteAbortsWithoutOverwritingIt() {
        loseSync()
        audio.afterWrite = {}
        onPause = { audio.volume = 7 }
        assertFalse(check())
        assertEquals(7, audio.volume)
        assertEquals(listOf("volume:0"), audio.writes)
    }

    @Test fun userChangesAndOwnerRevocationStopTheRampImmediately() {
        loseSync()
        onPause = { if (audio.volume == 2) audio.volume = 9 }
        assertFalse(check())
        assertEquals(9, audio.volume)
        assertEquals(listOf("volume:0", "absolute", "volume:1", "volume:2"), audio.writes)
        audio.behavior = 3
        audio.writes.clear()
        loseSync()
        onPause = { current = false }
        assertFalse(check())
        assertEquals(listOf("volume:0", "absolute"), audio.writes)
    }

    @Test fun permissionLossDuringMuteReadbackCannotEnableSync() {
        loseSync()
        audio.afterWrite = {}
        onPause = { granted = false; audio.volume = 0 }
        assertFalse(check())
        assertEquals(listOf("volume:0"), audio.writes)
    }

    @Test fun closeAndExplicitOwnerRetirementRemoveEnrollment() {
        loseSync()
        sync.forget(owner)
        assertFalse(check())
        elapsed += 500
        assertFalse(check())
        sync.close()
        audio.behavior = 3
        assertFalse(check())
        audio.behavior = 0
        assertFalse(check())
        assertTrue(audio.writes.isEmpty())
    }

    @Test @Config(shadows = [VolumeSyncAudioStubShadow::class])
    fun theRealAdapterUsesTheMediaRouteAndScopedShellAudioArguments() {
        val context = RuntimeEnvironment.getApplication()
        val token = Class.forName("android.media.AudioDeviceAttributes")
            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            .newInstance(2, 8, address)
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val serviceType = Class.forName("android.media.IAudioService")
        val service = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { _, method, args ->
            calls += method.name to args.orEmpty().toList()
            when (method.name) {
                "getDevicesForAttributes" -> listOf(token)
                "getMode" -> 0
                "getStreamVolume" -> 13
                "getStreamMaxVolume" -> 25
                "getDeviceVolumeBehavior" -> 0
                "setStreamVolume", "setDeviceVolumeBehavior" -> null
                else -> error("Unexpected call ${method.name}")
            }
        } as IInterface
        val binder = mock(IBinder::class.java)
        mockStatic(SystemServiceHelper::class.java).use { services ->
            services.`when`<IBinder> { SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE) }.thenReturn(binder)
            mockConstruction(ShizukuBinderWrapper::class.java) { wrapper, construction ->
                assertEquals(listOf(binder), construction.arguments())
                `when`(wrapper.queryLocalInterface("android.media.IAudioService")).thenReturn(service)
            }.use {
                val adapter = ShizukuVolumeSyncAudio(context)
                val result = adapter.snapshot()
                assertEquals(13, result.volume)
                assertEquals(address, result.routes.single().address)
                assertEquals(2, result.routes.single().role)
                assertEquals(8, result.routes.single().type)
                assertSame(token, result.routes.single().token)
                adapter.setVolume(0)
                adapter.setAbsolute(result.routes.single())
                val attributes = calls.single { call -> call.first == "getDevicesForAttributes" }.second.single() as AudioAttributes
                assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
                assertEquals(listOf(AudioManager.STREAM_MUSIC, 0, 0, "com.android.shell"),
                    calls.single { call -> call.first == "setStreamVolume" }.second)
                assertEquals(listOf(token, 3, "com.android.shell"),
                    calls.single { call -> call.first == "setDeviceVolumeBehavior" }.second)
            }
        }
    }

}

@Implements(className = "android.media.IAudioService\$Stub", isInAndroidSdk = false)
class VolumeSyncAudioStubShadow {
    companion object {
        @JvmStatic @Implementation
        fun asInterface(binder: IBinder): IInterface? = binder.queryLocalInterface("android.media.IAudioService")
    }
}
