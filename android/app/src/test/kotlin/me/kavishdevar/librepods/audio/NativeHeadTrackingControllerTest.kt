/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.HeadTrackerPoseGate
import me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NativeHeadTrackingControllerTest {
    private val identity = checkNotNull(SpatialPoseProfile.fromBasis(listOf(
        listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))))

    private inner class Target(calibrated: Boolean = true, rejectFirstStart: Boolean = false,
        spatialInitiallyOn: Boolean = true, nativeAtmos: Boolean = false, atmosSupported: Boolean = false,
        headInitiallyOn: Boolean? = false, clearSavedChoices: Boolean = true, atmosKnown: Boolean = true,
        rootInitiallyReady: Boolean = true, source: Flow<MotionSample?> = MutableStateFlow(null),
        atmosAbsent: Boolean = false, sourceTiming: Map<String, Long> = emptyMap()) : AutoCloseable {
        val parent = CoroutineScope(SupervisorJob())
        val started = CopyOnWriteArrayList<Any>()
        val released = CopyOnWriteArrayList<Any>()
        val device = mock(AppleDevice::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name.startsWith("startHeadTracking") && invocation.arguments.isNotEmpty()) {
                started += invocation.getArgument<Any>(0)
                !rejectFirstStart || started.size != 1
            } else RETURNS_DEFAULTS.answer(invocation)
        })
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val rootReady = AtomicBoolean(rootInitiallyReady)
        var openNative: ((() -> Boolean) -> ShizukuAudioClient.HeadTrackerLease)? = null
        val reporting = AtomicReference<(Boolean, Long) -> Unit>()
        var disable: (() -> Boolean) -> Bundle = {
            synchronized(snapshot) { snapshot.putBoolean("head_tracker_enabled", false) }
            Bundle().apply { putBoolean("applied", true) }
        }
        var fixed: (Boolean, () -> Boolean) -> Bundle = { enabled, current ->
            check(current())
            synchronized(snapshot) {
                snapshot.putBoolean("device_enabled", enabled); snapshot.putBoolean("enabled", enabled)
                snapshot.putBoolean("head_tracker_enabled", false)
                Bundle(snapshot).apply { putBoolean("applied", true) }
            }
        }
        val atmosphere = Bundle().apply {
            if (atmosAbsent) putString("reason", "backend_absent")
            putInt("version", 1); putBoolean("supported", atmosSupported); putBoolean("known", atmosSupported && atmosKnown)
            putBoolean("route_verified", atmosSupported)
            putBoolean("available", atmosSupported); putBoolean("desired_spatial", spatialInitiallyOn)
            putBoolean("desired_tracking", headInitiallyOn == true); putLong("accepted_generation", 0)
            putBoolean("actual_audio", false); putBoolean("output_committed", false)
        }
        var setAtmos: (Boolean, Boolean, Long, () -> Boolean) -> Bundle = { spatial, tracking, epoch, current ->
            check(current())
            synchronized(atmosphere) {
                if (atmosphere.getBoolean("supported") && atmosphere.getBoolean("route_verified")) {
                    atmosphere.putBoolean("known", true)
                    atmosphere.putBoolean("desired_spatial", spatial); atmosphere.putBoolean("desired_tracking", tracking)
                    atmosphere.putLong("accepted_generation", epoch)
                }
                Bundle(atmosphere).apply { putBoolean("applied", atmosphere.getBoolean("supported") && atmosphere.getBoolean("known")) }
            }
        }
        val bridge = mock(ShizukuAudioClient::class.java, withSettings().defaultAnswer { invocation ->
            when (invocation.method.name.substringBefore('$')) {
                "disableHeadTracking" -> disable(invocation.getArgument(1))
                "setFixedSpatialEnabled" -> fixed(invocation.getArgument(1), invocation.getArgument(2))
                "inspectAtmosSpatial" -> synchronized(atmosphere) { Bundle(atmosphere) }
                "setAtmosSpatial" -> setAtmos(invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4))
                "openHeadTracking" -> {
                    reporting.set(invocation.getArgument(4)); openNative?.invoke(invocation.getArgument(3))
                }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val profiles = mock(SpatialPoseProfileStore::class.java)
        val current = AtomicBoolean(true)
        val blocked = AtomicBoolean(false)
        val states = MutableStateFlow(AppleState(budRoleRaw = listOf(1, 0, 1, 0)))
        val metadata = MutableStateFlow(AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3,
            modelNumber = "A3064", version1 = "8-test"))
        val snapshot = Bundle().apply {
            putInt("version", 1)
            putBoolean("known", true); headInitiallyOn?.let { putBoolean("head_tracker_enabled", it) }
            putBoolean("device_enabled", spatialInitiallyOn); putBoolean("enabled", spatialInitiallyOn)
            putBoolean("route_selected", true); putBoolean("fixed_supported", true)
        }
        val controller: NativeHeadTrackingController
        init {
            if (clearSavedChoices) RuntimeEnvironment.getApplication().getSharedPreferences("spatial-controls", 0).edit().clear().commit()
            `when`(device.macAddress).thenReturn(MacAddress(MAC))
            `when`(device.context).thenReturn(RuntimeEnvironment.getApplication())
            `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
            `when`(device.aacp).thenReturn(aacp)
            `when`(device.state).thenReturn(states); `when`(device.metadata).thenReturn(metadata)
            `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings()))
            `when`(aacp.captureAudioSocket()).thenReturn(socket)
            `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
            `when`(aacp.motionSourceTimingSnapshot()).thenReturn(sourceTiming)
            `when`(bridge.hasRootPrivileges).thenAnswer { rootReady.get() }
            `when`(profiles.load(any<SpatialPoseProfileKey>() ?: key())).thenReturn(if (calibrated) identity else null)
            doAnswer { released += it.getArgument<Any>(0); true }.`when`(device).stopHeadTracking(any<Any>() ?: Any())
            runBlocking {
                `when`(bridge.inspectSpatialAudio(eq(MAC) ?: MAC, anyCurrent())).thenAnswer {
                    synchronized(snapshot) { Bundle(snapshot) }
                }
            }
            controller = NativeHeadTrackingController(device, parent, blocked::get, current::get,
                bridge, profiles, source, Dispatchers.Default, enableAtmos = nativeAtmos)
        }
        override fun close() { controller.close(); parent.cancel() }
    }

    @Test fun cachedSourceTimingIsIncludedWithoutStartingMotion() {
        Target(sourceTiming = mapOf("reads" to 12L, "feed_wait_max_nanos" to 30L)).use { f ->
            val source = requireNotNull(f.controller.timingSnapshot().getBundle("source"))
            assertEquals(12L, source.getLong("reads"))
            assertEquals(30L, source.getLong("feed_wait_max_nanos"))
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun sourceRejectionRetainsExactHostFreshnessBoundaryAndBlockedState() = runBlocking {
        Target().use { f ->
            ReflectionHelpers.getField<kotlinx.coroutines.Job>(f.controller, "runner").cancelAndJoin()
            val idle = ReflectionHelpers.getField<AtomicBoolean>(f.controller, "hostIdle")
            val checked = ReflectionHelpers.getField<AtomicLong>(f.controller, "hostCheckedAt")
            fun rejection() = ReflectionHelpers.callInstanceMethod<HeadTrackingSourceRejection?>(f.controller, "sourceRejection",
                ReflectionHelpers.ClassParameter.from(BluetoothSocket::class.java, f.socket),
                ReflectionHelpers.ClassParameter.from(SpatialPoseProfileKey::class.java, checkNotNull(spatialPoseProfileKey(f.device))))
            idle.set(true)
            val now = android.os.SystemClock.elapsedRealtime()
            checked.set(now - 500)
            assertNull(rejection())
            checked.set(now - 501)
            val stale = checkNotNull(rejection())
            assertEquals("host_check_stale", stale.reason)
            assertEquals(501L, stale.snapshot().getLong("host_age_ms"))
            assertEquals(true, stale.hostIdle)
            checked.set(now); idle.set(false)
            assertEquals("host_blocked", rejection()?.reason)
            f.current.set(false)
            assertEquals("device_not_current", rejection()?.reason)
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun sessionReplacementRetainsOnlyThePreviousSourceSummaryAndResetsCurrentCounters() {
        Target().use { f ->
            val old = HeadTrackingTiming().apply {
                begin(100); collectorFinished(110, 150); fallingBack(200, "attitude_stall", 120)
            }
            ReflectionHelpers.setField(f.controller, "timing", old)
            val fresh = ReflectionHelpers.callInstanceMethod<HeadTrackingTiming>(f.controller, "replaceSessionTiming")
            var value = f.controller.timingSnapshot()
            assertEquals(0L, value.getLong("source_epochs"))
            assertEquals(0L, value.getLong("collector_work_count"))
            var previous = requireNotNull(value.getBundle("previous_session"))
            assertEquals(40L, previous.getLong("collector_work_max_nanos"))
            assertEquals(150L, previous.getLong("collector_work_max_at_nanos"))
            assertEquals("attitude_stall", previous.getString("first_fallback_reason"))
            assertFalse(previous.containsKey("previous_session"))
            previous.putLong("collector_work_max_nanos", 999) // Dump callers cannot mutate the retained snapshot.
            assertEquals(40L, f.controller.timingSnapshot().getBundle("previous_session")!!.getLong("collector_work_max_nanos"))
            fresh.begin(300); fresh.collectorFinished(310, 315)
            ReflectionHelpers.callInstanceMethod<HeadTrackingTiming>(f.controller, "replaceSessionTiming")
            value = f.controller.timingSnapshot()
            previous = requireNotNull(value.getBundle("previous_session"))
            assertEquals(300L, previous.getLong("latest_epoch_request_at_nanos"))
            assertEquals(5L, previous.getLong("collector_work_max_nanos"))
            assertNull(previous.getString("first_fallback_reason"))
            assertFalse(previous.containsKey("previous_session"))
        }
    }

    @Test fun calibratedNativeOffIsReadWithoutStartingASensorOrRequestingPermission() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            assertEquals(false, f.controller.state.value.enabled)
            assertTrue(f.controller.state.value.calibrated)
            assertFalse(f.controller.state.value.active)
            assertTrue(f.started.isEmpty())
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("openHeadTracking", "requestPermission", "setFixedSpatialEnabled", "disableHeadTracking") })
        }
    }

    @Test fun spatialOffStillOffersHeadOnWithoutChangingThePreferenceOnInspection() = runBlocking {
        Target(spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.available }
            assertEquals(false, f.controller.state.value.enabled)
            assertEquals(false, f.controller.state.value.spatialEnabled)
            assertTrue(f.controller.state.value.spatialAvailable)
            assertTrue(f.started.isEmpty())
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("openHeadTracking", "setFixedSpatialEnabled", "disableHeadTracking", "requestPermission") })
        }
    }

    @Test fun unresolvedNativeAtmosMasterNeverClaimsAndroidReadbackControlsItsRenderer() = runBlocking {
        Target(nativeAtmos = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.available }
            assertNull(f.controller.state.value.spatialEnabled)
            assertFalse(f.controller.state.value.spatialAvailable)
            f.controller.setSpatialEnabled(true)
            f.controller.setSpatialEnabled(false)
            delay(150)
            assertFalse(f.controller.state.value.spatialBusy)
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("setFixedSpatialEnabled", "disableHeadTracking") })
        }
    }

    @Test fun knownAtmosMasterIsVisibleWhilePausedWithoutClaimingRenderedAudio() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable && f.atmosphere.getLong("accepted_generation") > 0 }
            assertTrue(f.controller.state.value.spatialSupported)
            assertEquals(false, f.controller.state.value.spatialEnabled)
            assertFalse(f.controller.state.value.actualAudio)
            assertNull(f.controller.state.value.renderedSpatial)
            assertFalse(f.atmosphere.getBoolean("desired_spatial")); assertFalse(f.atmosphere.getBoolean("desired_tracking"))
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("setFixedSpatialEnabled", "disableHeadTracking") })
        }
    }

    @Test fun confirmedAbsentDolbyLeavesAndroidSpatialAndHeadTrackingUsable() = runBlocking {
        Target(nativeAtmos = true, atmosAbsent = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable }
            assertEquals(false, f.controller.state.value.spatialEnabled)
            f.controller.setSpatialEnabled(true)
            waitUntil { !f.controller.state.value.spatialBusy && f.controller.state.value.spatialEnabled == true }
            f.controller.setEnabled(true)
            waitUntil { mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "openHeadTracking" } }
        }
    }

    @Test fun unownedOptionalBackendCanAdoptObservedOffWithoutInventingAnOn() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, spatialInitiallyOn = false, atmosKnown = false).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 && f.controller.state.value.spatialAvailable }
            assertTrue(f.controller.state.value.spatialSupported)
            assertEquals(false, f.controller.state.value.spatialEnabled)
            assertFalse(f.atmosphere.getBoolean("desired_spatial")); assertFalse(f.atmosphere.getBoolean("desired_tracking"))
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("setFixedSpatialEnabled", "disableHeadTracking") })
        }
    }

    @Test fun observedNativeHeadPreferenceMigratesOnlyWithItsCurrentCalibration() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, headInitiallyOn = true).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 }
            assertTrue(f.atmosphere.getBoolean("desired_spatial")); assertTrue(f.atmosphere.getBoolean("desired_tracking"))
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("setFixedSpatialEnabled", "disableHeadTracking") })
        }
        Target(nativeAtmos = true, atmosSupported = true, headInitiallyOn = true, calibrated = false).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable }
            delay(200)
            assertEquals(0L, f.atmosphere.getLong("accepted_generation"))
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "setAtmosSpatial" })
        }
        Target(nativeAtmos = true, atmosSupported = true, headInitiallyOn = null).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable }
            delay(200)
            assertEquals(0L, f.atmosphere.getLong("accepted_generation"))
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "setAtmosSpatial" })
        }
    }

    @Test fun combinedSpatialOffDisablesBothAndSurvivesControllerReplacement() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable && f.atmosphere.getLong("accepted_generation") > 0 }
            f.controller.setSpatialEnabled(false)
            waitUntil { !f.controller.state.value.spatialBusy && f.controller.state.value.spatialEnabled == false }
            assertFalse(f.atmosphere.getBoolean("desired_spatial")); assertFalse(f.atmosphere.getBoolean("desired_tracking"))
            assertEquals(false, f.controller.state.value.enabled)
            val methods = mockingDetails(f.bridge).invocations.map { it.method.name.substringBefore('$') }
            assertTrue(methods.indexOf("disableHeadTracking") < methods.lastIndexOf("setFixedSpatialEnabled"))
        }
        Target(nativeAtmos = true, atmosSupported = true, clearSavedChoices = false).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 && !f.controller.state.value.spatialBusy }
            assertFalse(f.atmosphere.getBoolean("desired_spatial")); assertFalse(f.atmosphere.getBoolean("desired_tracking"))
        }
    }

    @Test fun newerOffFencesALateAtmosOnBeforeEitherPoseProviderCanStart() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable && f.atmosphere.getLong("accepted_generation") > 0 }
            val original = f.setAtmos
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.setAtmos = { spatial, tracking, epoch, current ->
                if (tracking) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                original(spatial, tracking, epoch, current)
            }
            f.controller.setEnabled(true)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.controller.setEnabled(false)
            assertEquals(false, f.controller.state.value.enabled)
            release.countDown()
            waitUntil { !f.controller.state.value.busy && !f.atmosphere.getBoolean("desired_tracking") }
            assertTrue("Head Off preserves the already selected fixed-spatial choice", f.atmosphere.getBoolean("desired_spatial"))
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "openHeadTracking" })
            assertEquals(false, f.controller.state.value.desiredTrackingEnabled)
        }
    }

    @Test fun acceptedPolicyRequiresExactGenerationAndNeverMeansRenderedAudio() {
        val value = Bundle().apply {
            putInt("version", 1); putBoolean("supported", true); putBoolean("known", true)
            putBoolean("desired_spatial", true); putBoolean("desired_tracking", false)
            putLong("accepted_generation", 5); putLong("applied_generation", 0); putBoolean("actual_audio", false)
        }
        assertTrue(acceptedAtmosSpatial(value, true, false, 5))
        assertFalse(acceptedAtmosSpatial(value, true, false, 6))
        assertFalse(acceptedAtmosSpatial(value, true, true, 5))
        assertFalse(acceptedAtmosSpatial(value, false, true, 5))
        for (field in listOf("supported", "known")) {
            assertFalse(acceptedAtmosSpatial(Bundle(value).apply { putBoolean(field, false) }, true, false, 5))
        }
    }

    @Test fun acceptedMasterOwnershipOutlivesPendingButNotANewerChoice() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable && f.atmosphere.getLong("accepted_generation") > 0 }
            val original = f.setAtmos
            val owner = AtomicReference<() -> Boolean>()
            f.setAtmos = { spatial, tracking, epoch, current -> owner.set(current); original(spatial, tracking, epoch, current) }
            f.controller.setSpatialEnabled(false)
            waitUntil { !f.controller.state.value.spatialBusy && f.controller.state.value.spatialEnabled == false }
            val acceptedOwner = requireNotNull(owner.get())
            assertTrue("Clearing the UI request must not revoke the root heartbeat", acceptedOwner())
            f.controller.setSpatialEnabled(true)
            assertFalse("A newer choice immediately fences the preceding root owner", acceptedOwner())
        }
    }

    @Test fun actualAudioNeedsCommittedOutputFromTheAcceptedGeneration() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 && f.controller.state.value.spatialAvailable }
            synchronized(f.atmosphere) {
                f.atmosphere.putBoolean("actual_audio", true); f.atmosphere.putBoolean("output_committed", true)
                f.atmosphere.putBoolean("actual_spatial", true); f.atmosphere.putBoolean("actual_tracking", false)
                f.atmosphere.putLong("applied_generation", f.atmosphere.getLong("accepted_generation"))
            }
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            waitUntil { f.controller.state.value.actualAudio }
            assertEquals(true, f.controller.state.value.renderedSpatial)
            synchronized(f.atmosphere) { f.atmosphere.putLong("applied_generation", 0) }
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            waitUntil { !f.controller.state.value.actualAudio }
            assertNull(f.controller.state.value.renderedSpatial)
        }
    }

    @Test fun savedOffWaitsForExistingRootAuthorizationAndRouteBeforeRearming() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 }
        }
        Target(nativeAtmos = true, atmosSupported = true, clearSavedChoices = false, rootInitiallyReady = false).use { f ->
            delay(200)
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in
                setOf("setAtmosSpatial", "setFixedSpatialEnabled", "disableHeadTracking") })
            f.rootReady.set(true)
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 }
            assertFalse(f.atmosphere.getBoolean("desired_spatial")); assertFalse(f.atmosphere.getBoolean("desired_tracking"))
        }
    }

    @Test fun freshSameEpochAcknowledgementRecoversAnUnconfirmedWriteWithoutChangingTheIntent() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 && f.controller.state.value.spatialAvailable }
            f.setAtmos = { _, _, _, _ -> Bundle(f.atmosphere).apply { putBoolean("applied", false) } }
            f.controller.setSpatialEnabled(true)
            waitUntil { !f.controller.state.value.spatialBusy && f.snapshot.getBoolean("device_enabled") &&
                f.atmosphere.getLong("accepted_generation") < f.controller.timingSnapshot().getLong("choice_epoch") }
            val epoch = f.controller.timingSnapshot().getLong("choice_epoch")
            synchronized(f.atmosphere) {
                f.atmosphere.putBoolean("desired_spatial", true); f.atmosphere.putBoolean("desired_tracking", false)
                f.atmosphere.putLong("accepted_generation", epoch)
            }
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            waitUntil { f.controller.state.value.spatialEnabled == true && !f.controller.state.value.spatialBusy }
            assertEquals(epoch, f.controller.timingSnapshot().getLong("choice_epoch"))
            assertFalse(f.controller.state.value.actualAudio)
        }
    }

    @Test fun sameEpochWithADifferentPairCannotSuppressSavedOffRecovery() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 && f.controller.state.value.spatialEnabled == false }
            f.setAtmos = { _, _, _, _ -> Bundle(f.atmosphere).apply { putBoolean("applied", false) } }
            clearInvocations(f.bridge)
            synchronized(f.atmosphere) { f.atmosphere.putBoolean("desired_spatial", true) }
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            waitUntil { f.controller.state.value.spatialEnabled == null && mockingDetails(f.bridge).invocations.any {
                it.method.name.substringBefore('$') == "setAtmosSpatial"
            } }
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(f.controller, "atmosPolicyCurrent"))
            assertEquals(false, f.controller.state.value.desiredSpatialEnabled)
            assertEquals(false, f.controller.state.value.desiredTrackingEnabled)
        }
    }

    @Test fun preservedIntentReacquiresOnlyAfterLostOwnershipHasAReadyRouteAgain() = runBlocking {
        Target(nativeAtmos = true, atmosSupported = true, spatialInitiallyOn = false).use { f ->
            waitUntil { f.atmosphere.getLong("accepted_generation") > 0 && f.controller.state.value.spatialAvailable }
            val epoch = f.atmosphere.getLong("accepted_generation")
            synchronized(f.atmosphere) {
                f.atmosphere.putBoolean("known", false); f.atmosphere.putBoolean("route_verified", false)
                f.atmosphere.putLong("accepted_generation", 0)
            }
            f.blocked.set(true)
            clearInvocations(f.bridge)
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            delay(200)
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "setAtmosSpatial" })
            synchronized(f.atmosphere) { f.atmosphere.putBoolean("route_verified", true) }
            f.blocked.set(false)
            ReflectionHelpers.getField<AtomicBoolean>(f.controller, "forceInspect").set(true)
            waitUntil { f.atmosphere.getLong("accepted_generation") == epoch }
            assertFalse(f.atmosphere.getBoolean("desired_spatial")); assertFalse(f.atmosphere.getBoolean("desired_tracking"))
        }
    }

    @Test fun explicitHeadOnEnablesAndVerifiesSpatialBeforeOpeningItsLease() = runBlocking {
        Target(spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.available }
            f.controller.setEnabled(true)
            waitUntil { mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "openHeadTracking" } }
            val writes = mockingDetails(f.bridge).invocations.map { it.method.name.substringBefore('$') }
            assertTrue(writes.indexOf("setFixedSpatialEnabled") < writes.indexOf("openHeadTracking"))
            assertTrue(f.snapshot.getBoolean("device_enabled"))
            assertTrue(f.snapshot.getBoolean("enabled"))
            assertEquals(1, writes.count { it == "setFixedSpatialEnabled" })
        }
    }

    @Test fun headOnCannotOpenALeaseFromAnUnconfirmedSpatialWrite() = runBlocking {
        Target(spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.available }
            f.fixed = { _, _ -> Bundle(f.snapshot).apply { putBoolean("applied", true) } }
            f.controller.setEnabled(true)
            waitUntil { !f.controller.state.value.busy }
            assertEquals(false, f.controller.state.value.enabled)
            assertEquals(false, f.controller.state.value.spatialEnabled)
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "openHeadTracking" })
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun lateSpatialPreparationCannotOverrideHeadOffOrOpenItsFormerLease() = runBlocking {
        Target(spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.available }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val owner = AtomicReference<() -> Boolean>()
            f.fixed = { _, current ->
                owner.set(current); entered.countDown(); release.await(2, TimeUnit.SECONDS)
                Bundle(f.snapshot).apply {
                    putBoolean("applied", true); putBoolean("enabled", true); putBoolean("device_enabled", true)
                }
            }
            try {
                f.controller.setEnabled(true)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.controller.setEnabled(false)
                assertFalse(owner.get().invoke())
                release.countDown()
                waitUntil { !f.controller.state.value.busy && f.controller.state.value.enabled == false }
                assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "openHeadTracking" })
            } finally { release.countDown() }
        }
    }

    @Test fun spatialPreparationReadbackLosesAuthorityWhenItsDeviceOwnerChanges() = runBlocking {
        Target(spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.available }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val owner = AtomicReference<() -> Boolean>()
            f.fixed = { _, current ->
                owner.set(current); entered.countDown(); release.await(2, TimeUnit.SECONDS)
                Bundle(f.snapshot).apply {
                    putBoolean("applied", true); putBoolean("enabled", true); putBoolean("device_enabled", true)
                }
            }
            try {
                f.controller.setEnabled(true)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.current.set(false)
                assertFalse(owner.get().invoke())
                release.countDown()
                waitUntil { !f.controller.state.value.available }
                assertEquals(false, f.controller.state.value.spatialEnabled)
                assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "openHeadTracking" })
            } finally { release.countDown() }
        }
    }

    @Test fun explicitSpatialChangesUseReadbackAndOffDisablesHeadTrackingFirst() = runBlocking {
        Target(spatialInitiallyOn = false).use { f ->
            waitUntil { f.controller.state.value.spatialAvailable }
            f.controller.setSpatialEnabled(true)
            waitUntil { !f.controller.state.value.spatialBusy && f.controller.state.value.spatialEnabled == true }
            assertEquals(false, f.controller.state.value.enabled)
            f.controller.setSpatialEnabled(false)
            waitUntil { !f.controller.state.value.spatialBusy && f.controller.state.value.spatialEnabled == false }
            assertEquals(false, f.controller.state.value.enabled)
            val writes = mockingDetails(f.bridge).invocations.map { it.method.name.substringBefore('$') }
                .filter { it in setOf("setFixedSpatialEnabled", "disableHeadTracking") }
            assertEquals(listOf("setFixedSpatialEnabled", "disableHeadTracking", "setFixedSpatialEnabled"), writes)
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun explicitOffWorksWithoutACurrentProfileAndCloseDoesNotChangeThePreference() = runBlocking {
        Target(calibrated = false).use { f ->
            f.controller.setEnabled(false)
            waitUntil { !f.controller.state.value.busy && f.controller.state.value.enabled == false }
            f.controller.close()
            waitUntil { mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') == "close" } }
            assertEquals(1, mockingDetails(f.bridge).invocations.count { it.method.name.substringBefore('$') == "disableHeadTracking" })
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun knownPro3ModelAliasesUseTheSameFullSourceIdentity() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            fun reads() = mockingDetails(f.profiles).invocations.filter { it.method.name == "load" }
            val before = reads().size
            f.metadata.value = f.metadata.value.copy(modelNumber = "A3063")
            waitUntil { reads().size > before }
            val keys = reads().map { it.getArgument<SpatialPoseProfileKey>(0) }
            assertTrue(keys.isNotEmpty())
            assertTrue(keys.all { it == key().copy(modelNumber = "AIRPODS_PRO_3") })
            val cached = f.controller.timingSnapshot()
            assertTrue(cached.getBoolean("calibrated")); assertTrue(cached.getBoolean("available"))
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun unknownRoleNeverStartsAnUncalibratedSourceOrChangesNativeChoice() = runBlocking {
        Target(calibrated = false).use { f ->
            f.states.value = f.states.value.copy(budRoleRaw = emptyList())
            delay(250)
            assertFalse(f.controller.state.value.calibrated)
            assertFalse(f.controller.state.value.available)
            assertTrue(f.started.isEmpty())
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in setOf("openHeadTracking", "disableHeadTracking", "requestPermission") })
        }
    }

    @Test fun nativeOffDoesNotWaitForABlockedBluetoothSourceCleanup() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            val cleanupStarted = CountDownLatch(1); val release = CountDownLatch(1)
            val stuckSource = f.parent.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { cleanupStarted.countDown(); release.await(3, TimeUnit.SECONDS) }
            }
            try {
                // Reproduce a source finally blocked inside the Bluetooth stack's synchronous write.
                ReflectionHelpers.setField(f.controller, "session", stuckSource)
                assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
                f.controller.setEnabled(false)
                waitUntil { !f.controller.state.value.busy && f.controller.state.value.enabled == false }
                assertFalse("Off must finish before Bluetooth cleanup returns", stuckSource.isCompleted)
                assertEquals(1, mockingDetails(f.bridge).invocations.count {
                    it.method.name.substringBefore('$') == "disableHeadTracking"
                })
            } finally { release.countDown(); stuckSource.cancelAndJoin() }
        }
    }

    @Test fun aLateOffReplyCannotClearANewerOnChoice() = runBlocking {
        Target(calibrated = false).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val owner = AtomicReference<() -> Boolean>()
            val returned = AtomicBoolean()
            f.disable = { current ->
                owner.set(current); entered.countDown()
                release.await(2, TimeUnit.SECONDS); returned.set(true)
                Bundle().apply { putBoolean("applied", true) }
            }
            try {
                f.controller.setEnabled(false)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.controller.setCalibrationActive(Any(), true)
                f.controller.setEnabled(true)
                assertFalse("A superseded root request must lose its owner", owner.get().invoke())
                release.countDown(); waitUntil { returned.get() }; delay(150)
                assertEquals(true, f.controller.state.value.enabled)
                assertTrue(f.controller.state.value.busy)
            } finally { release.countDown() }
        }
    }

    @Test fun ownedCalibrationPauseAndCallBlockDoNotWriteTheNativeChoice() = runBlocking {
        Target().use { f ->
            waitUntil { f.controller.state.value.available }
            val first = Any(); val second = Any()
            f.controller.setCalibrationActive(first, true); f.controller.setCalibrationActive(second, true)
            waitUntil { !f.controller.state.value.available }
            f.controller.setCalibrationActive(first, false)
            delay(150); assertFalse(f.controller.state.value.available)
            f.blocked.set(true); f.controller.setCalibrationActive(second, false)
            delay(250); assertFalse(f.controller.state.value.available)
            assertFalse(mockingDetails(f.bridge).invocations.any { it.method.name.substringBefore('$') in setOf("openHeadTracking", "disableHeadTracking") })
            assertTrue(f.started.isEmpty())
        }
    }

    @Test fun collectorDiagnosticsIncludeOwnerAndSubmitWorkWithoutCreditingInvalidStartupSamples() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> {
                emit(null)
                clock = HOST_START + 10_000_000
                emit(MotionSample(report(1), HOST_START)) // Before the request.
                clock = HOST_START + 300_000_000
                emit(MotionSample(report(1), HOST_START + 2_000_000)) // Stale.
                clock = HOST_START + 320_000_000
                emit(MotionSample(report(1).copy(attitudeQ15 = listOf(0, 0, 0)), clock))
                clock = HOST_START + 330_000_000
                emit(MotionSample(report(1), clock)) // Duplicate counter cannot establish the valid-attitude milestone.
                clock = HOST_START + 340_000_000
                emit(MotionSample(report(2), clock)) // Valid attitude primes only.
                clock = HOST_START + 360_000_000
                emit(MotionSample(report(3), clock))
            }
            val collectorThread = Thread.currentThread()
            val failure = runCatching {
                streamCalibratedHeadMotion(f.device, source, identity, 0,
                    { if (Thread.currentThread() === collectorThread) clock += 1_000_000; true },
                    timing, now = { clock }) { _, _ -> clock += 3_000_000 }
            }.exceptionOrNull()
            assertEquals("motion_ended", failure?.message)
            val value = timing.snapshot()
            assertEquals(6L, value.getLong("collector_work_count"))
            assertEquals(1_000_000L, value.getLong("collector_work_min_nanos"))
            assertEquals(4_000_000L, value.getLong("collector_work_max_nanos"))
            assertEquals(HOST_START + 364_000_000, value.getLong("collector_work_max_at_nanos"))
            assertEquals(HOST_START + 1_000_000, value.getLong("latest_epoch_request_at_nanos"))
            assertEquals(HOST_START + 321_000_000, value.getLong("latest_epoch_first_collection_at_nanos"))
            assertEquals(HOST_START + 341_000_000, value.getLong("latest_epoch_first_attitude_at_nanos"))
            assertEquals(HOST_START + 364_000_000, value.getLong("latest_epoch_first_submit_at_nanos"))
            assertEquals(3L, value.getLong("source_samples"))
            assertEquals(1L, value.getLong("stale_samples_dropped"))
        }
    }

    @Test fun savedCalibrationReferenceAndInvalidAttitudeCannotClaimFirstUsableAttitude() = runBlocking {
        for (savedReference in listOf(true, false)) Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val profile = checkNotNull(SpatialPoseProfile.fromBasis(identity.headFromRaw))
            val first = if (savedReference) report(1) else report(1).copy(attitudeQ15 = listOf(32767, 32767, 32767))
            if (savedReference) {
                ReflectionHelpers.setField(profile, "referenceTimestampNs", first.timestampNs)
                ReflectionHelpers.setField(profile, "referenceCounter", first.counter)
            }
            val source = flow<MotionSample?> { clock += 10_000_000; emit(MotionSample(first, clock)) }
            val failure = runCatching {
                streamCalibratedHeadMotion(f.device, source, profile, 0, { true }, timing, now = { clock }) { _, _ ->
                    fail("Neither input can emit a pose")
                }
            }.exceptionOrNull()
            assertEquals(if (savedReference) "motion_ended" else "unsupported_attitude", failure?.message)
            val value = timing.snapshot()
            assertEquals(1L, value.getLong("collector_work_count"))
            assertEquals(HOST_START + 10_000_000, value.getLong("latest_epoch_first_collection_at_nanos"))
            assertEquals(0L, value.getLong("latest_epoch_first_attitude_at_nanos"))
            assertEquals(0L, value.getLong("latest_epoch_first_submit_at_nanos"))
        }
    }

    @Test fun failedSubmissionIsTimedWithoutPublishingASuccessMilestone() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> {
                for (tick in 1..2) { clock += 10_000_000; emit(MotionSample(report(tick), clock)) }
            }
            val failure = runCatching {
                streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing, now = { clock }) { _, _ ->
                    clock += 5_000_000
                    error("submit_failed")
                }
            }.exceptionOrNull()
            assertEquals("submit_failed", failure?.message)
            val value = timing.snapshot()
            assertEquals(2L, value.getLong("collector_work_count"))
            assertEquals(5_000_000L, value.getLong("collector_work_max_nanos"))
            assertEquals(0L, value.getLong("latest_epoch_first_submit_at_nanos"))
        }
    }

    @Test fun reportingSourcePrimesFreshRatesAndReleasesOnlyItsOwnMotionOwner() = runBlocking {
        Target().use { f ->
            val poses = mutableListOf<SpatialPose>()
            val arrivals = mutableListOf<Long>()
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> {
                emit(MotionSample(report(1), HOST_START - 1)) // Cached before this reporting epoch.
                for (tick in listOf(2, 3, 3, 4)) {
                    clock += 20_000_000; emit(MotionSample(report(tick), clock))
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 7, { true }, timing,
                now = { clock }) { pose, arrival -> poses += pose; arrivals += arrival } }
            try {
                waitUntil { poses.size == 2 }
                assertEquals(listOf(7, 7), poses.map { it.discontinuity })
                assertEquals(listOf(60_000_000L, 80_000_000L), poses.map { it.timestampNs })
                assertTrue(poses.all { it.angularVelocity.all { n -> n == 0f } })
                assertEquals(listOf(HOST_START + 40_000_000, HOST_START + 80_000_000), arrivals)
                assertEquals(10_000, timing.snapshot().getLong("requested_interval_us"))
            } finally { job.cancelAndJoin() }
            assertEquals(1, f.started.size); assertEquals(f.started.toList(), f.released.toList())
            verify(f.device, never()).stopHeadTracking()
        }
    }

    @Test fun hundredHertzSourceContinuesAcrossTheCorroboratedSixteenBitBoundary() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for ((index, counter) in listOf(65530L, 65532L, 65534L, 0L, 2L, 4L).withIndex()) {
                    clock += 10_000_000
                    emit(MotionSample(report(index + 1).copy(timestampNs = (index + 1) * 10_000_000L,
                        counter = counter), clock))
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 3, { true }, timing,
                now = { clock }) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 5 }
                assertEquals((2..6).map { it * 10_000_000L }, poses.map { it.timestampNs })
                assertEquals(6L, timing.snapshot().getLong("source_samples"))
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals(1, f.started.size)
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun hundredHertzSourceKeepsOriginalTimesAndDoesNotFallBack() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming()
            val poses = mutableListOf<Pair<SpatialPose, Long>>()
            val source = flow<MotionSample?> {
                for (tick in 1..101) {
                    clock += 10_000_000
                    emit(MotionSample(report(tick).copy(timestampNs = tick * 10_000_000L,
                        counter = tick * 2L), clock))
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 3, { true }, timing,
                now = { clock }) { pose, arrival -> poses += pose to arrival } }
            try {
                waitUntil { poses.size == 100 }
                assertEquals(10_000L, timing.snapshot().getLong("requested_interval_us"))
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals(101L, timing.snapshot().getLong("source_samples"))
                assertEquals((2..101).map { it * 10_000_000L }, poses.map { it.first.timestampNs })
                assertEquals((2..101).map { HOST_START + it * 10_000_000L }, poses.map { it.second })
                assertTrue(poses.all { it.first.discontinuity == 3 })
                assertEquals(1, f.started.size)
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun sourceOwnerLossAndBadReferenceStopWithoutEmittingAnotherPose() = runBlocking {
        for (loseOwner in listOf(false, true)) Target().use { f ->
            val current = AtomicBoolean(true); val poses = mutableListOf<SpatialPose>()
            var clock = HOST_START
            val source = flow<MotionSample?> {
                emit(null)
                for (tick in listOf(1, 2)) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                if (loseOwner) current.set(false)
                clock += 20_000_000; emit(MotionSample(report(if (loseOwner) 3 else 1), clock))
            }
            val failure = runCatching { streamCalibratedHeadMotion(f.device, source, identity, 0, current::get,
                now = { clock }) { pose, _ -> poses += pose } }.exceptionOrNull()
            assertNotNull(failure); assertEquals(1, poses.size)
            assertEquals(if (loseOwner) "owner_changed" else "sensor_time_changed", failure?.message)
            assertEquals(1, f.started.size); assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun verifiedGapReacquiresAFreshRootLeaseOnceThenLatchesAnEarlyRepeat() = runBlocking {
        val source = flow<MotionSample?> { throw CalibratedHeadMotionFault("sensor_gap", verifiedSensorGap = true) }
        Target(source = source).use { f ->
            val opened = java.util.concurrent.atomic.AtomicInteger()
            val finished = java.util.concurrent.atomic.AtomicInteger()
            f.openNative = { current ->
                assertTrue(current())
                val attempt = opened.incrementAndGet()
                assertEquals("Old lease cleanup precedes any new root acquisition", attempt - 1, finished.get())
                synchronized(f.snapshot) { f.snapshot.putBoolean("head_tracker_enabled", true) }
                f.reporting.get().invoke(true, 10_000_000)
                ShizukuAudioClient.HeadTrackerLease(Bundle(), { Bundle() }, { finished.incrementAndGet(); Bundle() })
            }
            waitUntil { f.controller.state.value.available }
            f.controller.setEnabled(true)
            waitUntil { finished.get() == 1 }
            assertEquals(1, opened.get())
            delay(100); assertEquals("Retry waits for the Android elapsed-time deadline", 1, opened.get())
            ShadowSystemClock.advanceBy(Duration.ofMillis(2_001))
            waitUntil { finished.get() == 2 }
            assertEquals(2, opened.get()); assertEquals(2, finished.get())
            assertEquals("motion_changed", f.controller.state.value.reason)
            assertEquals("sensor_gap", f.controller.timingSnapshot().getString("pose_fault_reason"))
            assertTrue(f.controller.state.value.enabled == true)
            ShadowSystemClock.advanceBy(Duration.ofMillis(2_200))
            delay(250); assertEquals("An exhausted budget cannot become a third automatic lease", 2, opened.get())
            assertEquals(f.started.toSet(), f.released.toSet())
        }
    }

    @Test fun sharedRuntimeRetiresAVerifiedGapAndOnlyANewInvocationCanPrimeAgain() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val poses = mutableListOf<SpatialPose>(); val recovery = HeadMotionGapRecovery()
            val source = flow<MotionSample?> {
                for (tick in 1..2) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                clock += 30_000_000
                emit(MotionSample(report(2).copy(timestampNs = 40_000_000L + 288_887_000, counter = 8L + 58), clock))
            }
            val failure = runCatching { streamCalibratedHeadMotion(f.device, source, identity, 7, { true },
                now = { clock }, tryFast = false, recovery = recovery) { pose, _ -> poses += pose } }.exceptionOrNull()
            assertTrue(failure is CalibratedHeadMotionFault)
            assertTrue((failure as CalibratedHeadMotionFault).verifiedSensorGap)
            assertEquals(1, poses.size); assertEquals(7, poses.single().discontinuity)
            assertEquals(f.started.toSet(), f.released.toSet()); assertTrue(recovery.failed(failure.verifiedSensorGap))
            val nextSource = flow<MotionSample?> {
                clock += 20_000_000; emit(MotionSample(report(20), clock))
                assertEquals("The new epoch's first sample is only a reference", 1, poses.size)
                clock += 20_000_000; emit(MotionSample(report(21), clock))
            }
            runCatching { streamCalibratedHeadMotion(f.device, nextSource, identity, 8, { true },
                now = { clock }, tryFast = false, recovery = recovery) { pose, _ -> poses += pose } }
            assertEquals(listOf(7, 8), poses.map { it.discontinuity })
            assertEquals(420_000_000L, poses.last().timestampNs)
            assertNotSame(f.started[0], f.started[1]); assertEquals(f.started.toSet(), f.released.toSet())
        }
    }

    @Test fun androidPoseGateReceivesOriginalReceiptTimeOncePerRead() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<Pair<SpatialPose, Long>>()
            val gate = HeadTrackerPoseGate().apply { reporting(true, clock) }
            val source = flow<MotionSample?> {
                clock += 300_000_000
                emit(MotionSample(report(1), HOST_START + 1))
                clock += 20_000_000; emit(MotionSample(report(2), clock - 30_000_000))
                clock += 20_000_000
                val receipt = clock - 25_000_000
                emit(MotionSample(report(3), receipt))
                emit(MotionSample(report(4), receipt))
                clock += 20_000_000; emit(MotionSample(report(5), clock - 5_000_000))
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }) { pose, arrival ->
                check(gate.accept(HeadTrackerUhidProtocol.Companion.Pose(arrival, pose.discontinuity,
                    (pose.rotationVector + pose.angularVelocity).toFloatArray()), clock))
                poses += pose to arrival
            } }
            try {
                waitUntil { poses.size >= 2 }
                assertEquals(listOf(report(3).timestampNs, report(5).timestampNs), poses.map { it.first.timestampNs })
                assertEquals(listOf(HOST_START + 315_000_000, HOST_START + 355_000_000), poses.map { it.second })
                val stats = timing.snapshot()
                assertEquals(1, stats.getLong("stale_samples_dropped"))
                assertEquals(4, stats.getLong("source_samples"))
                assertEquals(21_250_000.0, stats.getDouble("app_collection_age_mean_nanos"), 0.0)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun refusedFastRateFallsBackToTheWorkingRateUsingTheSameOwner() = runBlocking {
        Target(rejectFirstStart = true).use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for (tick in 1..2) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(20_000, timing.snapshot().getLong("requested_interval_us"))
                assertTrue(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals("fast_request_refused", timing.snapshot().getString("first_fallback_reason"))
            } finally { job.cancelAndJoin() }
            assertEquals(2, f.started.size); assertSame(f.started[0], f.started[1])
            assertEquals(listOf(f.started[0]), f.released.toList())
        }
    }

    @Test fun anAcceptedFastRequestWithNoValidAttitudeFallsBackWithoutRestartingTheSource() = runBlocking {
        Target().use { f ->
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> { emit(null); awaitCancellation() }
            val startedAt = System.nanoTime()
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { HOST_START }) { _, _ -> fail("No measurements exist") } }
            try {
                withTimeout(4_500) { while (!timing.snapshot().getBoolean("fallback_rate")) delay(10) }
                assertTrue(System.nanoTime() - startedAt >= 3_000_000_000L)
                assertEquals("attitude_startup_timeout", timing.snapshot().getString("first_fallback_reason"))
                assertEquals(20_000, timing.snapshot().getLong("requested_interval_us"))
                assertEquals(2, f.started.size); assertSame(f.started[0], f.started[1])
                assertTrue(f.released.isEmpty())
            } finally { job.cancelAndJoin() }
            assertEquals(listOf(f.started[0]), f.released.toList())
        }
    }

    @Test fun aFirstAttitudeAfterColdStartupKeeps100HzAndOnlyEmitsMeasuredTimestamps() = runBlocking {
        Target().use { f ->
            val timing = HeadTrackingTiming(); val clock = AtomicLong(HOST_START)
            val poses = mutableListOf<Pair<SpatialPose, Long>>()
            val firstArrival = HOST_START + 1_800_000_000L
            val source = flow<MotionSample?> {
                delay(1_800)
                assertTrue(poses.isEmpty())
                clock.set(firstArrival)
                emit(MotionSample(report(1).copy(timestampNs = 10_000_000), firstArrival))
                assertTrue("The first attitude only primes the converter", poses.isEmpty())
                delay(10)
                clock.addAndGet(10_000_000)
                emit(MotionSample(report(2).copy(timestampNs = 20_000_000), clock.get()))
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = clock::get) { pose, arrival -> poses += pose to arrival } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(20_000_000L, poses.single().first.timestampNs)
                assertEquals(firstArrival + 10_000_000, poses.single().second)
                assertEquals(10_000L, timing.snapshot().getLong("requested_interval_us"))
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
                assertNull(timing.snapshot().getString("first_fallback_reason"))
                assertEquals(1, f.started.size)
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun cancellationDuringColdStartupReleasesTheOwnerWithoutAFallbackWrite() = runBlocking {
        Target().use { f ->
            val timing = HeadTrackingTiming()
            val source = flow<MotionSample?> { emit(null); awaitCancellation() }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { HOST_START }) { _, _ -> fail("No measurements exist") } }
            try {
                waitUntil { f.started.isNotEmpty() }
                delay(1_800)
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
            } finally { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, f.started.size)
            assertEquals(f.started.toList(), f.released.toList())
            assertNull(timing.snapshot().getString("first_fallback_reason"))
        }
    }

    @Test fun oneFreshPoseThenAStallFallsBackBeforeTheRootFreshnessWindow() = runBlocking {
        Target().use { f ->
            val clock = AtomicLong(HOST_START)
            val timing = HeadTrackingTiming(); val fallbackAt = AtomicLong()
            val firstArrival = HOST_START + 20_000_000
            val poseArrival = firstArrival + 20_000_000
            var poses = 0
            val source = flow<MotionSample?> {
                clock.set(firstArrival); emit(MotionSample(report(1), firstArrival))
                clock.set(poseArrival); emit(MotionSample(report(2), poseArrival))
                clock.set(poseArrival + 180_000_000)
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = clock::get, onFallback = { fallbackAt.set(clock.get()) }) { _, _ -> poses++ } }
            try {
                waitUntil { timing.snapshot().getBoolean("fallback_rate") }
                assertEquals(1, poses)
                assertTrue(fallbackAt.get() - poseArrival in 150_000_000L until 250_000_000L)
                assertEquals("attitude_stall", timing.snapshot().getString("first_fallback_reason"))
                assertEquals(fallbackAt.get(), timing.snapshot().getLong("first_fallback_at_nanos"))
                assertEquals(poseArrival, timing.snapshot().getLong("first_fallback_last_attitude_at_nanos"))
                assertEquals(20_000L, timing.snapshot().getLong("requested_interval_us"))
                assertEquals(2, f.started.size); assertSame(f.started[0], f.started[1])
                assertTrue(f.released.isEmpty())
            } finally { job.cancelAndJoin() }
            assertEquals(listOf(f.started[0]), f.released.toList())
        }
    }

    @Test fun aSocketAlreadyMarkedForFallbackStartsAtTheWorkingRateWithoutTryingFastAgain() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for (tick in 1..2) { clock += 20_000_000; emit(MotionSample(report(tick), clock)) }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }, tryFast = false) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(1, f.started.size)
                assertEquals(20_000L, timing.snapshot().getLong("requested_interval_us"))
                assertTrue(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals("socket_fallback", timing.snapshot().getString("first_fallback_reason"))
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun validIdentityReportsAfterStartupKeepTheFastSourceAlive() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                for (tick in 1..20) {
                    clock += 20_000_000
                    emit(MotionSample(if (tick <= 2) report(tick) else
                        report(tick).copy(attitudeQ15 = listOf(0, 0, 0)), clock))
                    delay(10)
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = { clock }) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 19 }
                assertEquals(1, f.started.size)
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun persistentStartupZerosCannotRefreshTheRootPoseStartupDeadline() = runBlocking {
        Target().use { f ->
            var clock = HOST_START
            val gate = HeadTrackerPoseGate().apply { reporting(true, HOST_START) }
            val source = flow<MotionSample?> {
                for (tick in 1..251) {
                    clock += 20_000_000
                    emit(MotionSample(report(tick).copy(attitudeQ15 = listOf(0, 0, 0)), clock))
                    assertFalse(gate.hasPose)
                    assertEquals(HOST_START + HeadTrackerUhidProtocol.STARTUP_NANOS, gate.deadline)
                    gate.checkFresh(clock)
                }
                fail("Persistent placeholders must not outlive the root startup budget")
            }
            val error = runCatching {
                streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, now = { clock },
                    tryFast = false) { pose, arrival ->
                    gate.accept(HeadTrackerUhidProtocol.Companion.Pose(arrival, pose.discontinuity,
                        (pose.rotationVector + pose.angularVelocity).toFloatArray()), arrival)
                    fail("A startup zero is not a usable pose")
                }
            }.exceptionOrNull()
            assertEquals("pose_startup_timeout", error?.message)
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun aRetainedStartupAttitudeAndZeroRunDoNotStartTheSteadyStallWatchdog() = runBlocking {
        Target().use { f ->
            val clock = AtomicLong(HOST_START)
            val timing = HeadTrackingTiming(); val poses = mutableListOf<SpatialPose>()
            val source = flow<MotionSample?> {
                clock.addAndGet(20_000_000)
                emit(MotionSample(report(1).copy(attitudeQ15 = listOf(-18261, 22093, 169)), clock.get()))
                clock.addAndGet(200_000_000)
                emit(MotionSample(report(2).copy(attitudeQ15 = listOf(0, 0, 0)), clock.get()))
                delay(100) // The old first-attitude gate would enter its 150 ms steady watchdog.
                assertTrue(poses.isEmpty()); assertEquals(1, f.started.size)
                assertFalse(timing.snapshot().getBoolean("fallback_rate"))
                for (tick in 3..4) {
                    clock.addAndGet(20_000_000)
                    emit(MotionSample(report(tick).copy(attitudeQ15 = listOf(-13479, 22145, 129)), clock.get()))
                }
                awaitCancellation()
            }
            val job = async { streamCalibratedHeadMotion(f.device, source, identity, 0, { true }, timing,
                now = clock::get) { pose, _ -> poses += pose } }
            try {
                waitUntil { poses.size == 1 }
                assertEquals(1, f.started.size); assertFalse(timing.snapshot().getBoolean("fallback_rate"))
                assertEquals(80_000_000L, poses.single().timestampNs)
            } finally { job.cancelAndJoin() }
            assertEquals(f.started.toList(), f.released.toList())
        }
    }

    @Test fun liveBurstHandoffPreservesOrderAndStillRejectsPreStartAndExpiredSamples() = runBlocking {
        HeadTracking.reset()
        Target().use { f ->
            val clock = AtomicLong(HOST_START)
            val timing = HeadTrackingTiming()
            val poses = mutableListOf<Pair<SpatialPose, Long>>()
            HeadTracking.addMotion(report(99), 0f, 0f, HOST_START - 1)
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                streamCalibratedHeadMotion(f.device, HeadTracking.motionSamples, identity, 0,
                    { true }, timing, now = clock::get, tryFast = false) { pose, arrival ->
                    poses += pose to arrival
                }
            }
            try {
                assertEquals(0L, timing.snapshot().getLong("source_samples"))
                // Publish both before yielding to the live collector, just like a parsed socket burst.
                for (tick in 1..2) {
                    HeadTracking.addMotion(report(tick), 0f, 0f, clock.addAndGet(20_000_000))
                }
                waitUntil { poses.size == 1 }
                assertEquals(40_000_000L, poses.single().first.timestampNs)
                assertEquals(HOST_START + 40_000_000L, poses.single().second)
                assertEquals(2L, timing.snapshot().getLong("source_samples"))
                HeadTracking.addMotion(report(3), 0f, 0f, clock.addAndGet(20_000_000))
                clock.addAndGet(300_000_000)
                HeadTracking.addMotion(report(4), 0f, 0f, clock.get())
                waitUntil { poses.size == 2 }
                assertEquals(80_000_000L, poses.last().first.timestampNs)
                assertEquals(clock.get(), poses.last().second)
                assertEquals(1L, timing.snapshot().getLong("stale_samples_dropped"))
                assertEquals(3L, timing.snapshot().getLong("source_samples"))
            } finally { job.cancelAndJoin(); HeadTracking.reset() }
        }
    }

    @Test fun bufferedDeliveryKeepsSensorVelocityAndHostFreshnessSeparate() = runBlocking {
        HeadTracking.reset()
        Target().use { f ->
            val clock = AtomicLong(HOST_START)
            val poses = mutableListOf<Pair<SpatialPose, Long>>()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                streamCalibratedHeadMotion(f.device, HeadTracking.motionSamples, identity, 0,
                    { true }, now = clock::get, tryFast = false) { pose, arrival -> poses += pose to arrival }
            }
            try {
                val sensorStart = 100_000_000_000L // Deliberately a different clock epoch from BOOTTIME.
                val arrivalStart = HOST_START + 100_000_000L
                // Two 10 ms physical samples arrive in separate reads 1 us apart.
                for (tick in 1..2) {
                    val sample = report(tick).copy(timestampNs = sensorStart + tick * 10_000_000L,
                        counter = tick * 2L, attitudeQ15 = listOf(0, 0, tick * 164))
                    clock.set(arrivalStart + (tick - 1) * 1_000L)
                    HeadTracking.addMotion(sample, 0f, 0f, clock.get())
                }
                waitUntil { poses.size == 1 }
                val (pose, arrival) = poses.single()
                assertEquals(sensorStart + 20_000_000L, pose.timestampNs)
                assertEquals(10_000_000L, pose.intervalNs)
                assertEquals(arrivalStart + 1_000L, arrival)
                assertEquals(1.0, pose.angularVelocity[2].toDouble(), 0.002)
                assertEquals(0f, pose.angularVelocity[0], 0f)
                assertEquals(0f, pose.angularVelocity[1], 0f)

                // Android's root gate uses BOOTTIME for freshness, never the foreign sensor epoch.
                val measured = HeadTrackerUhidProtocol.Companion.Pose(arrival, pose.discontinuity,
                    (pose.rotationVector + pose.angularVelocity).toFloatArray())
                val gate = HeadTrackerPoseGate().apply { reporting(true, HOST_START) }
                assertTrue(gate.accept(measured, arrival))
                val emitted = checkNotNull(HeadTrackerUhidProtocol.poseAtHostTime(
                    checkNotNull(gate.pose(arrival + 5_000_000L)), arrival + 5_000_000L))
                assertEquals(pose.rotationVector[2] + pose.angularVelocity[2] * 0.005f, emitted.values[2], 0.000001f)
                assertEquals(pose.angularVelocity[2], emitted.values[5], 0f)
                assertEquals(arrival, emitted.timestampNanos)
                assertNull(gate.pose(arrival + HeadTrackerUhidProtocol.FRESH_NANOS + 1))
                assertEquals(arrival + HeadTrackerPoseGate.ACTIVE_GAP_NANOS, gate.deadline)
                assertNull(HeadTrackerUhidProtocol.poseAtHostTime(measured,
                    arrival + HeadTrackerUhidProtocol.MAX_EXTRAPOLATION_NANOS + 1))
                assertThrows(IllegalStateException::class.java) {
                    gate.checkFresh(gate.deadline + 1)
                }
                Unit
            } finally { job.cancelAndJoin(); HeadTracking.reset() }
        }
    }

    @Test fun queuedReportsCannotOutliveTheirOwnerEvenWhenResetIsBehindThem() = runBlocking {
        HeadTracking.reset()
        Target().use { f ->
            val current = AtomicBoolean(true)
            val clock = AtomicLong(HOST_START)
            val timing = HeadTrackingTiming()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    streamCalibratedHeadMotion(f.device, HeadTracking.motionSamples, identity, 0,
                        current::get, timing, now = clock::get, tryFast = false) { _, _ ->
                        fail("An old owner cannot submit queued reports")
                    }
                }.exceptionOrNull()
            }
            try {
                for (tick in 1..2) {
                    HeadTracking.addMotion(report(tick), 0f, 0f, clock.addAndGet(20_000_000))
                }
                current.set(false)
                HeadTracking.reset()
                assertEquals("owner_changed", withTimeout(2_000) { job.await() }?.message)
                assertEquals(0L, timing.snapshot().getLong("source_samples"))
                assertEquals(f.started.toList(), f.released.toList())
            } finally { job.cancelAndJoin(); HeadTracking.reset() }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }
    private fun key() = SpatialPoseProfileKey(MAC, listOf(1, 0, 1, 0), "A3064", "8-test")
    private fun report(tick: Int) = MotionReport(tick * 20_000_000L, listOf(1, 0, 0),
        listOf(0, 0, 0), listOf(0, 0, 0), listOf(0, 0, -1000), tick * 4L)
    companion object {
        private const val MAC = "00:AA:22:33:44:55"
        private const val HOST_START = 1_000_000_000L
        private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
    }
}
