/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocol
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.SpatialBackendSnapshot

data class NativeHeadTrackingState(val enabled: Boolean? = null, val calibrated: Boolean = false,
    val available: Boolean = false, val active: Boolean = false, val busy: Boolean = false,
    val reason: String = "loading", val spatialEnabled: Boolean? = null,
    val spatialAvailable: Boolean = false, val spatialBusy: Boolean = false,
    val spatialSupported: Boolean = false, val actualAudio: Boolean = false,
    val renderedSpatial: Boolean? = null, val renderedTracking: Boolean? = null,
    val appliedGeneration: Long = 0, val desiredSpatialEnabled: Boolean? = null,
    val desiredTrackingEnabled: Boolean? = null)

/** Native preference is the user's choice; reporting owns only this controller's motion source. */
class NativeHeadTrackingController internal constructor(
    private val device: AppleDevice,
    parentScope: CoroutineScope,
    private val hostBlocked: () -> Boolean,
    private val deviceCurrent: () -> Boolean,
    private val access: ShizukuAudioClient,
    private val profiles: SpatialPoseProfileStore,
    private val motion: Flow<MotionSample?>,
    dispatcher: CoroutineDispatcher,
    enableAtmos: Boolean = false,
) : AutoCloseable {
    constructor(device: AppleDevice, parentScope: CoroutineScope, hostBlocked: () -> Boolean,
        deviceCurrent: () -> Boolean) : this(device, parentScope, hostBlocked, deviceCurrent,
        ShizukuAudioClient(device.context, requireRoot = true), SpatialPoseProfileStore(device.context),
        HeadTracking.motionSamples, Dispatchers.Default, enableAtmos = true)

    private data class Choice(val epoch: Long, val enabled: Boolean, val started: Long, val spatial: Boolean = false)
    private val preferences = device.context.getSharedPreferences("spatial-controls", android.content.Context.MODE_PRIVATE)
    private val preferencePrefix = device.macAddress.value + ":"
    @Volatile private var desiredSpatial: Boolean? = preferences.getBoolean(preferencePrefix + "spatial", false)
        .takeIf { preferences.contains(preferencePrefix + "spatial") }
    @Volatile private var desiredTracking: Boolean? = preferences.getBoolean(preferencePrefix + "tracking", false)
        .takeIf { preferences.contains(preferencePrefix + "tracking") }
    private val mutableState = MutableStateFlow(NativeHeadTrackingState())
    val state = mutableState.asStateFlow()
    private val closed = AtomicBoolean()
    private val hostIdle = AtomicBoolean()
    private val hostCheckedAt = AtomicLong(Long.MIN_VALUE)
    private val choiceEpoch = AtomicLong(preferences.getLong(preferencePrefix + "epoch", 0).coerceAtLeast(0))
    private val acceptedAtmosEpoch = AtomicLong(-1)
    private val acceptedNativeEpoch = AtomicLong(-1)
    @Volatile private var atmosControls: Bundle? = null
    @Volatile private var inspectedSpatial: Bundle? = null
    private val choiceLock = Any()
    private val pending = AtomicReference<Choice?>()
    private val offRequests = Channel<Choice>(Channel.CONFLATED)
    private val calibrationOwners = mutableSetOf<Any>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var session: Job? = null
    @Volatile private var sessionLive: AtomicBoolean? = null
    private var sessionSocket: BluetoothSocket? = null
    private var sessionKey: SpatialPoseProfileKey? = null
    @Volatile private var failedSocket: BluetoothSocket? = null
    @Volatile private var failedKey: SpatialPoseProfileKey? = null
    @Volatile private var poseFaultReason: String? = null
    @Volatile private var nativeOn: Boolean? = null
    @Volatile private var nativeSpatialOn: Boolean? = null
    private val discontinuity = AtomicInteger()
    private val forceInspect = AtomicBoolean(true)
    private val retryAfter = AtomicLong()
    private val gapRecoveries = HeadMotionGapRecovery.Scopes()
    @Volatile private var fallbackSocket: BluetoothSocket? = null
    private val atmos = if (enableAtmos) AtmosHeadTrackingCoordinator(device, scope, access,
        { fallbackSocket !== it }, { fallbackSocket = it }, { socket ->
            val profileKey = key()
            if (profileKey == null) sourceRejection("profile_key_missing")
            else sourceRejection(socket, profileKey) ?: if (headTrackingRequested()) null
                else sourceRejection("head_tracking_disabled")
        }) else null
    @Volatile private var timing = HeadTrackingTiming()
    @Volatile private var previousSessionTiming: Bundle? = null
    @Volatile private var activeLease: ShizukuAudioClient.HeadTrackerLease? = null
    @Volatile private var sinkTiming: Bundle? = null
    @Volatile private var sinkTimingAt = 0L

    internal fun timingSnapshot(): Bundle = timing.snapshot().apply {
        putString("reason", state.value.reason)
        putBoolean("enabled", state.value.enabled == true); putBoolean("active", state.value.active)
        putBoolean("calibrated", state.value.calibrated); putBoolean("available", state.value.available)
        putBoolean("busy", state.value.busy)
        val controls = state.value
        fun choice(name: String, value: Boolean?) { if (value == null) putString(name, null) else putBoolean(name, value) }
        choice("spatial_enabled", controls.spatialEnabled); choice("head_tracking_enabled", controls.enabled)
        choice("desired_spatial", desiredSpatial); choice("desired_tracking", desiredTracking)
        putBoolean("spatial_supported", controls.spatialSupported); putBoolean("spatial_available", controls.spatialAvailable)
        putBoolean("spatial_busy", controls.spatialBusy); putBoolean("actual_audio", controls.actualAudio)
        choice("rendered_spatial", controls.renderedSpatial); choice("rendered_tracking", controls.renderedTracking)
        putLong("applied_generation", controls.appliedGeneration); putLong("choice_epoch", choiceEpoch.get())
        putString("pose_fault_reason", poseFaultReason)
        putBundle("source", Bundle().apply {
            device.aacp.motionSourceTimingSnapshot().forEach { (key, value) -> putLong(key, value) }
        })
        sinkTiming?.let { putBundle("sink", Bundle(it)) }
        putLong("sink_snapshot_age_ms", if (sinkTimingAt == 0L) -1 else SystemClock.elapsedRealtime() - sinkTimingAt)
        previousSessionTiming?.let { putBundle("previous_session", Bundle(it)) }
        atmos?.let { putBundle("atmos", it.snapshot()) }
    }

    private fun replaceSessionTiming(): HeadTrackingTiming {
        // The runner joins the old session before replacement; retain one scalar summary, never nested history.
        timing.snapshot().takeIf { it.getLong("source_epochs") > 0 }?.let {
            it.putLong("retained_at_nanos", SystemClock.elapsedRealtimeNanos())
            previousSessionTiming = it
        }
        return HeadTrackingTiming().also { timing = it; sinkTiming = null; sinkTimingAt = 0 }
    }
    // Bluetooth source cleanup can block in a socket write; it must not delay the user's native Off.
    private val offWriter = scope.launch {
        for (choice in offRequests) {
            try {
                if (pending.get() !== choice || !current()) continue
                access.connect()
                while (!access.hasRootPrivileges && current() && pending.get() === choice &&
                    SystemClock.elapsedRealtime() - choice.started < 5_000) delay(100)
                val owns = {
                    current() && pending.get() === choice && choiceEpoch.get() == choice.epoch
                }
                val atmosApplied = applyAtmosChoice(choice, owns)
                if (choice.enabled && !choice.spatial) {
                    if (!atmosApplied) withChoice(choice.epoch) {
                        if (pending.compareAndSet(choice, null)) mutableState.update {
                            it.copy(enabled = displayedTracking(), busy = false, reason = "request_failed")
                        }
                    }
                    continue // The existing native runner owns preparation and the head-tracker lease.
                }
                val result = if (choice.spatial) {
                    if (!choice.enabled) access.disableHeadTracking(device.macAddress.value, owns)
                    access.setFixedSpatialEnabled(device.macAddress.value, choice.enabled, owns)
                } else access.disableHeadTracking(device.macAddress.value, owns)
                withChoice(choice.epoch) {
                    if (pending.compareAndSet(choice, null)) {
                        val nativeApplied = if (choice.spatial) result.getBoolean("applied") && result.spatialEnabled() == choice.enabled &&
                            result.flag("head_tracker_enabled") != null && (choice.enabled || result.flag("head_tracker_enabled") == false)
                            else result.getBoolean("applied")
                        if (nativeApplied) acceptedNativeEpoch.set(choice.epoch)
                        val applied = atmosApplied && nativeApplied
                        nativeOn = if (choice.spatial) result.flag("head_tracker_enabled") else if (applied) false else null
                        if (choice.spatial) publishSpatial(result)
                        if (nativeOn == false) { failedSocket = null; failedKey = null; poseFaultReason = null }
                        mutableState.update { it.copy(enabled = displayedTracking(), busy = false, spatialBusy = false, active = false,
                            reason = if (applied) { if (choice.enabled) "ok" else "off" } else "request_failed") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withChoice(choice.epoch) {
                    if (pending.compareAndSet(choice, null)) mutableState.update {
                        it.copy(enabled = nativeOn, busy = false, spatialEnabled = nativeSpatialOn,
                            spatialBusy = false, reason = "request_failed")
                    }
                }
            } finally { forceInspect.set(true) }
        }
    }

    private fun needsAtmos(backend: Bundle? = atmosControls) =
        atmos != null && !(backend?.getInt("version") == 1 &&
            backend.getString("reason") == SpatialBackendSnapshot.ABSENT)

    private fun atmosPolicyCurrent(): Boolean = !needsAtmos() || atmosControls?.let {
        it.getInt("version", 0) == 1 && it.flag("supported") == true && it.flag("known") == true &&
            (desiredSpatial == null || it.getLong("accepted_generation", -1) == choiceEpoch.get() &&
                it.flag("desired_spatial") == desiredSpatial && it.flag("desired_tracking") == desiredTracking)
    } == true
    private fun displayedTracking() = pending.get()?.let { !it.spatial && it.enabled } ?: nativeOn.takeIf {
        atmosPolicyCurrent() && (!needsAtmos() || atmosControls?.flag("desired_tracking") == it)
    }
    private fun displayedSpatial() = pending.get()?.takeIf { it.spatial }?.enabled ?: nativeSpatialOn.takeIf {
        atmosPolicyCurrent() && (!needsAtmos() || atmosControls?.flag("desired_spatial") == it)
    }

    private fun saveChoice(spatial: Boolean, tracking: Boolean, epoch: Long) {
        desiredSpatial = spatial; desiredTracking = tracking
        savedRearmed = true
        mutableState.update { it.copy(desiredSpatialEnabled = spatial, desiredTrackingEnabled = tracking) }
        preferences.edit().putBoolean(preferencePrefix + "spatial", spatial)
            .putBoolean(preferencePrefix + "tracking", tracking).putLong(preferencePrefix + "epoch", epoch).apply()
        atmos?.androidProviderReady(false)
        atmos?.setControls(spatial, tracking, epoch)
    }

    private suspend fun applyAtmosChoice(choice: Choice, owns: () -> Boolean): Boolean {
        if (atmos == null) return true
        val pair = synchronized(choiceLock) { (desiredSpatial == true) to (desiredTracking == true) }
        val ownerCurrent = { current() && choiceEpoch.get() == choice.epoch }
        if (acceptedAtmosEpoch.get() == choice.epoch && atmosControls?.let {
                acceptedAtmosSpatial(it, pair.first, pair.second, choice.epoch)
            } == true && ownerCurrent()) return true
        val result = try {
            access.setAtmosSpatial(device.macAddress.value, pair.first, pair.second, choice.epoch, ownerCurrent)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        if (!owns()) return false
        atmosControls = result
        if (!needsAtmos(result)) return true
        val accepted = result.getBoolean("applied") && acceptedAtmosSpatial(result, pair.first, pair.second, choice.epoch)
        if (accepted) {
            acceptedAtmosEpoch.set(choice.epoch)
            atmos.setControls(pair.first, pair.second, choice.epoch, accepted = true)
            inspectedSpatial?.let { snapshot -> withChoice(choice.epoch) { publishSpatial(snapshot) } }
        }
        return accepted
    }

    internal fun isCalibrationActive() = synchronized(calibrationOwners) { calibrationOwners.isNotEmpty() }

    private inline fun withChoice(epoch: Long, action: () -> Unit) = synchronized(choiceLock) {
        if (choiceEpoch.get() == epoch) action()
    }

    fun setCalibrationActive(owner: Any, active: Boolean) {
        synchronized(calibrationOwners) { if (active) calibrationOwners += owner else calibrationOwners -= owner }
        if (active) { sessionLive?.set(false); atmos?.retireCurrent() }
        else { failedSocket = null; failedKey = null; poseFaultReason = null }
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) atmos?.retireCurrent()
        synchronized(choiceLock) {
            if (closed.get()) return
            val choice = Choice(choiceEpoch.incrementAndGet(), enabled, SystemClock.elapsedRealtime())
            saveChoice(if (enabled) true else desiredSpatial ?: nativeSpatialOn ?: false, enabled, choice.epoch)
            pending.set(choice)
            // Retire the old owner immediately; its late startup cannot override a newer choice.
            sessionLive?.set(false)
            mutableState.update { it.copy(enabled = enabled, active = false, busy = true,
                spatialEnabled = nativeSpatialOn, spatialBusy = false) }
            if (!enabled || atmos != null) offRequests.trySend(choice)
        }
    }

    fun setSpatialEnabled(enabled: Boolean) {
        synchronized(choiceLock) {
            val currentState = mutableState.value
            if (!current() || !currentState.spatialSupported ||
                enabled && (!currentState.spatialAvailable || currentState.spatialEnabled == null) || currentState.spatialEnabled == enabled) return
            val choice = Choice(choiceEpoch.incrementAndGet(), enabled, SystemClock.elapsedRealtime(), spatial = true)
            saveChoice(enabled, false, choice.epoch)
            pending.set(choice)
            sessionLive?.set(false)
            if (!enabled) atmos?.retireCurrent()
            mutableState.update { it.copy(spatialEnabled = enabled, spatialBusy = true,
                enabled = false, busy = false, active = false) }
            offRequests.trySend(choice)
        }
    }

    private fun headTrackingRequested(): Boolean = if (desiredSpatial == false || desiredTracking == false) false else pending.get()?.let {
        if (it.spatial) it.enabled && nativeOn == true else it.enabled
    } ?: (desiredTracking ?: (nativeOn == true))

    private fun publishSpatial(snapshot: Bundle) {
        val backend = atmosControls
        val optionalReady = !needsAtmos(backend) || backend?.let { it.getInt("version") == 1 &&
            it.flag("supported") == true && it.flag("available") == true && it.flag("route_verified") == true } == true
        val writable = optionalReady && snapshot.getInt("version", 0) == 1 && snapshot.flag("known") == true &&
            snapshot.flag("fixed_supported") == true && snapshot.flag("route_selected") == true
        nativeSpatialOn = snapshot.spatialEnabled()
        mutableState.update {
            val supported = it.spatialSupported || writable
            val actual = backend?.let { value -> value.flag("actual_audio") == true && value.flag("output_committed") == true &&
                value.getLong("applied_generation", 0) > 0 && value.getLong("applied_generation") == value.getLong("accepted_generation", -1) &&
                (desiredSpatial == null || value.getLong("accepted_generation") == choiceEpoch.get()) } == true
            it.copy(spatialSupported = supported, spatialEnabled = displayedSpatial().takeIf { supported },
                spatialAvailable = writable && nativeSpatialOn != null,
                actualAudio = actual, renderedSpatial = if (actual) backend?.flag("actual_spatial") else null,
                renderedTracking = if (actual) backend?.flag("actual_tracking") else null,
                appliedGeneration = backend?.getLong("applied_generation", 0) ?: 0,
                desiredSpatialEnabled = desiredSpatial, desiredTrackingEnabled = desiredTracking)
        }
    }

    private suspend fun migrateNativeChoice(snapshot: Bundle, eligible: Boolean): Boolean {
        val backend = atmosControls ?: return false
        if (atmos == null || !eligible || backend.getInt("version") != 1 || backend.flag("supported") != true ||
            backend.flag("available") != true || backend.flag("route_verified") != true ||
            snapshot.flag("route_selected") != true || snapshot.flag("fixed_supported") != true)
            return false
        val spatial = snapshot.spatialEnabled() ?: return false
        val head = snapshot.flag("head_tracker_enabled") ?: return false
        val choice = synchronized(choiceLock) {
            if (desiredSpatial != null || desiredTracking != null || pending.get() != null || !current()) return false
            val epoch = choiceEpoch.incrementAndGet()
            saveChoice(spatial, spatial && head, epoch)
            acceptedNativeEpoch.set(epoch) // The migration carries an observed preference; it never writes Android.
            savedRearmed = true
            Choice(epoch, spatial && head, SystemClock.elapsedRealtime())
        }
        applyAtmosChoice(choice) { current() && choiceEpoch.get() == choice.epoch && pending.get() == null }
        return true
    }

    private fun current() = !closed.get() && runCatching(deviceCurrent).getOrDefault(false) &&
        device.connectionState.value == ConnectionState.CONNECTED

    private var savedRearmed = false
    private var lastRearmMs = -1L
    private fun rearmSavedChoice(eligible: Boolean) = synchronized(choiceLock) {
        if (!current() || !access.hasRootPrivileges || !mutableState.value.spatialAvailable || !hostIdle.get()) return@synchronized
        if (desiredSpatial == null || desiredTracking == null || pending.get() != null) return@synchronized
        if (desiredTracking == true && !eligible) return@synchronized
        if (savedRearmed && atmosPolicyCurrent()) return@synchronized
        val now = SystemClock.elapsedRealtime()
        if (lastRearmMs >= 0 && now - lastRearmMs < 1000) return@synchronized
        lastRearmMs = now
        savedRearmed = true
        val tracking = desiredTracking == true
        val choice = Choice(choiceEpoch.get(), if (tracking) true else desiredSpatial == true,
            now, spatial = !tracking)
        pending.set(choice)
        mutableState.update { it.copy(enabled = tracking, busy = tracking, spatialBusy = !tracking) }
        sessionLive?.set(false)
        atmos?.setControls(desiredSpatial == true, tracking, choice.epoch)
        offRequests.trySend(choice)
    }

    private fun reconcileAtmosChoice(eligible: Boolean) = synchronized(choiceLock) {
        val backend = atmosControls ?: return@synchronized
        val spatial = desiredSpatial ?: return@synchronized
        val tracking = desiredTracking ?: return@synchronized
        val epoch = choiceEpoch.get()
        if (!current() || !acceptedAtmosSpatial(backend, spatial, tracking, epoch)) return@synchronized
        acceptedAtmosEpoch.set(epoch)
        atmos?.setControls(spatial, tracking, epoch, accepted = true)
        if (acceptedNativeEpoch.get() == epoch || pending.get() != null || !savedRearmed || tracking && !eligible) return@synchronized
        val choice = Choice(epoch, if (tracking) true else spatial, SystemClock.elapsedRealtime(), spatial = !tracking)
        pending.set(choice)
        mutableState.update { it.copy(enabled = tracking, busy = tracking, spatialBusy = !tracking) }
        atmos?.androidProviderReady(false)
        offRequests.trySend(choice)
    }

    private fun key() = spatialPoseProfileKey(device)

    private fun sourceCurrent(socket: BluetoothSocket, key: SpatialPoseProfileKey) = sourceRejection(socket, key) == null

    private fun sourceRejection(reason: String, checkedAt: Long = hostCheckedAt.get(),
        now: Long = SystemClock.elapsedRealtime(), idle: Boolean = hostIdle.get()) =
        AtmosSourceRejection(reason, now, checkedAt, idle)

    private fun sourceRejection(socket: BluetoothSocket, key: SpatialPoseProfileKey): AtmosSourceRejection? {
        if (!current()) return sourceRejection("device_not_current")
        if (!access.hasRootPrivileges) return sourceRejection("root_unavailable")
        val idle = hostIdle.get()
        if (!idle) return sourceRejection("host_blocked", idle = idle)
        val checkedAt = hostCheckedAt.get(); val now = SystemClock.elapsedRealtime()
        if (checkedAt == Long.MIN_VALUE || now - checkedAt !in 0..500)
            return sourceRejection("host_check_stale", checkedAt, now, idle)
        if (isCalibrationActive()) return sourceRejection("calibration_active")
        if (!device.aacp.ownsAudioSocket(socket)) return sourceRejection("socket_changed")
        if (key() != key) return sourceRejection("profile_key_changed")
        if (listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT).all { side ->
            device.state.value.componentState.any { it.component == side && it.status == ComponentStatus.IN_CASE }
        }) return sourceRejection("buds_in_case")
        return null
    }

    private suspend fun stopSession() {
        atmos?.androidProviderReady(false)
        sessionLive?.set(false)
        session?.cancelAndJoin()
        session = null; sessionLive = null; sessionSocket = null; sessionKey = null
        mutableState.update { it.copy(active = false) }
    }

    private val runner = scope.launch {
        // Audio/telephony Binder queries stay outside root ownership and per-pose callbacks.
        val guard = launch(Dispatchers.IO) {
            while (isActive) {
                hostIdle.set(runCatching { !hostBlocked() }.getOrDefault(false))
                hostCheckedAt.set(SystemClock.elapsedRealtime())
                delay(100)
            }
        }
        var inspectedAt = Long.MIN_VALUE
        var spatialEligible = false
        var spatialReady = false
        try {
            while (isActive) {
                try {
                    val key = if (current()) key() else null
                    val profile = key?.let(profiles::load)
                    val socket = device.aacp.captureAudioSocket()
                    val eligible = BuildConfig.DEBUG && profile != null && socket != null &&
                        sourceCurrent(socket, checkNotNull(key))
                    atmos?.update(eligible && headTrackingRequested(), profile, socket)
                    mutableState.update { it.copy(calibrated = profile != null) }
                    if (session != null && (!eligible || !headTrackingRequested() || sessionSocket !== socket || sessionKey != key ||
                            sessionLive?.get() != true || session?.isActive != true)) stopSession()
                    if (failedSocket !== socket || failedKey != key) { failedSocket = null; failedKey = null; poseFaultReason = null }
                    val epoch = choiceEpoch.get()
                    val choice = pending.get()
                    if (choice != null && choice.epoch != epoch) { delay(100); continue }
                    if (choice != null && SystemClock.elapsedRealtime() - choice.started >= 5_000) {
                        withChoice(epoch) {
                            if (pending.compareAndSet(choice, null)) mutableState.update {
                                it.copy(enabled = nativeOn, busy = false, spatialEnabled = nativeSpatialOn,
                                    spatialBusy = false, reason = "request_failed")
                            }
                        }
                        delay(100)
                        continue
                    }
                    if (choice?.spatial == true) { delay(100); continue }
                    if (choice?.enabled == true && needsAtmos() && acceptedAtmosEpoch.get() != epoch) { delay(100); continue }
                    if (choice?.enabled != false && !isCalibrationActive() && current() && BuildConfig.DEBUG &&
                        device.metadata.value.model == AirPodsModel.AIRPODS_PRO_3) {
                        access.connect() // Reconnects existing authorization; it never requests a root grant.
                        if (access.hasRootPrivileges) {
                            val now = SystemClock.elapsedRealtime()
                            if (forceInspect.getAndSet(false) || inspectedAt == Long.MIN_VALUE || now - inspectedAt >= 1_000) {
                                val snapshot = access.inspectSpatialAudio(device.macAddress.value, ::current)
                                inspectedSpatial = Bundle(snapshot)
                                if (atmos != null) atmosControls = access.inspectAtmosSpatial(device.macAddress.value, ::current)
                                activeLease?.let { lease ->
                                    lease.state().getBundle("sink_stats")?.let { stats ->
                                        if (activeLease === lease) { sinkTiming = stats; sinkTimingAt = SystemClock.elapsedRealtime() }
                                    }
                                }
                                if (choiceEpoch.get() != epoch) { forceInspect.set(true); delay(100); continue }
                                inspectedAt = now
                                spatialEligible = false
                                spatialReady = false
                                withChoice(epoch) { publishSpatial(snapshot) }
                                if (snapshot.getInt("version", 0) == 1 && snapshot.flag("known") == true) {
                                    @Suppress("DEPRECATION")
                                    val headPreference = snapshot.flag("head_tracker_enabled")
                                    withChoice(epoch) {
                                        if (nativeOn == false && headPreference == true) { failedSocket = null; failedKey = null; poseFaultReason = null }
                                        nativeOn = headPreference
                                        spatialEligible = headPreference != null && snapshot.flag("device_enabled") == true &&
                                            snapshot.flag("enabled") == true && snapshot.flag("route_selected") == true
                                        spatialReady = headPreference != null && snapshot.flag("fixed_supported") == true &&
                                            snapshot.flag("route_selected") == true
                                        mutableState.update { it.copy(available = eligible && spatialReady,
                                            enabled = displayedTracking(),
                                            reason = snapshot.getString("reason") ?: "unavailable") }
                                    }
                                    if (nativeOn == false && choice == null) stopSession()
                                }
                            }
                            inspectedSpatial?.let { snapshot ->
                                if (migrateNativeChoice(snapshot, eligible)) forceInspect.set(true)
                            }
                            if (choiceEpoch.get() != epoch) { delay(100); continue }
                            rearmSavedChoice(eligible)
                            reconcileAtmosChoice(eligible)
                            val poseFault = failedSocket != null && failedSocket === socket && failedKey == key
                            mutableState.update { it.copy(available = eligible && spatialReady && !poseFault,
                                reason = if (poseFault) "motion_changed" else it.reason) }
                            val explicitOn = choice?.enabled == true
                            if (explicitOn && eligible && spatialReady && !spatialEligible) {
                                val ownsPreparation = {
                                    current() && pending.get() === choice && choiceEpoch.get() == epoch &&
                                        sourceCurrent(checkNotNull(socket), checkNotNull(key))
                                }
                                val result = access.setFixedSpatialEnabled(device.macAddress.value, true, ownsPreparation)
                                if (!ownsPreparation()) { forceInspect.set(true); delay(100); continue }
                                spatialEligible = result.getBoolean("applied") && result.spatialEnabled() == true &&
                                    result.flag("route_selected") == true && result.flag("fixed_supported") == true &&
                                    result.flag("head_tracker_enabled") == false
                                withChoice(epoch) {
                                    publishSpatial(result)
                                    if (spatialEligible) nativeOn = result.flag("head_tracker_enabled")
                                    else if (pending.compareAndSet(choice, null)) mutableState.update {
                                        it.copy(enabled = nativeOn, busy = false, available = false, reason = "request_failed")
                                    }
                                }
                                if (!spatialEligible) { forceInspect.set(true); delay(100); continue }
                            }
                            val combinedReady = !needsAtmos() || desiredTracking != true || acceptedAtmosEpoch.get() == choiceEpoch.get()
                            if (session == null && eligible && combinedReady && headTrackingRequested() && mutableState.value.available &&
                                spatialEligible &&
                                (explicitOn || nativeOn == true && SystemClock.elapsedRealtime() >= retryAfter.get() &&
                                    !(failedSocket === socket && failedKey == key))) {
                                if (explicitOn) { failedSocket = null; failedKey = null; poseFaultReason = null }
                                val live = AtomicBoolean(true)
                                val recovery = gapRecoveries.forSource(checkNotNull(socket), checkNotNull(key),
                                    checkNotNull(profile), epoch)
                                sessionLive = live; sessionSocket = socket; sessionKey = key
                                session = launch { runSensor(checkNotNull(socket), checkNotNull(key),
                                    checkNotNull(profile), live, choice.takeIf { explicitOn }, epoch, recovery) }
                            } else if (explicitOn && (!eligible || !mutableState.value.available)) {
                                withChoice(epoch) {
                                    if (pending.compareAndSet(choice, null)) mutableState.update {
                                        it.copy(enabled = nativeOn, busy = false, reason = "unavailable")
                                    }
                                }
                            }
                        } else { forceInspect.set(true); mutableState.update { it.copy(available = false, spatialAvailable = false) } }
                    } else { forceInspect.set(true); mutableState.update { it.copy(available = false, spatialAvailable = false) } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(available = false, spatialAvailable = false, active = false, reason = "unavailable") } }
                delay(100)
            }
        } finally {
            atmos?.close()
            hostIdle.set(false); guard.cancel()
            withContext(NonCancellable) { stopSession(); access.close() }
            mutableState.update { it.copy(active = false, available = false, busy = false, spatialAvailable = false, spatialBusy = false) }
            scope.cancel()
        }
    }
    private val parentCompletion = parentScope.coroutineContext[Job]?.invokeOnCompletion { close() }

    private suspend fun runSensor(socket: BluetoothSocket, key: SpatialPoseProfileKey, profile: SpatialPoseProfile,
        live: AtomicBoolean, choice: Choice?, epoch: Long, recovery: HeadMotionGapRecovery) {
        var pipe: Array<ParcelFileDescriptor>? = null
        var lease: ShizukuAudioClient.HeadTrackerLease? = null
        val reporting = MutableStateFlow(false)
        val stopped = kotlinx.coroutines.CompletableDeferred<String>()
        val sessionTiming = replaceSessionTiming()
        fun owns() = live.get() && choiceEpoch.get() == epoch &&
            (choice != null || pending.get() == null) && sourceCurrent(socket, key)
        try {
            val descriptors = ParcelFileDescriptor.createPipe().also { pipe = it }
            val output = descriptors[1].fileDescriptor
            withContext(Dispatchers.IO) {
                Os.fcntlInt(output, OsConstants.F_SETFL, Os.fcntlInt(output, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            }
            lease = access.openHeadTracking(device.macAddress.value, descriptors[0], choice != null, ::owns,
                { enabled, _ -> reporting.value = enabled }, { stopped.complete(it) })
            activeLease = lease
            withChoice(epoch) {
                nativeOn = true
                acceptedNativeEpoch.set(epoch)
                if (choice == null || pending.compareAndSet(choice, null))
                    mutableState.update { it.copy(enabled = pending.get()?.enabled ?: true, busy = pending.get() != null) }
            }
            if (owns() && nativeOn == true) atmos?.androidProviderReady(true)
            kotlinx.coroutines.coroutineScope {
                val sender = launch(Dispatchers.IO) {
                    reporting.collectLatest { enabled ->
                        recovery.withheld()
                        withChoice(epoch) { mutableState.update { it.copy(active = false) } }
                        if (enabled) {
                            streamCalibratedHeadMotion(device, motion, profile,
                                discontinuity.getAndIncrement() and 255, ::owns, sessionTiming,
                                tryFast = fallbackSocket !== socket, onFallback = { fallbackSocket = socket },
                                recovery = recovery, recoveryActive = { reporting.value && owns() }) { pose, arrivalNs ->
                                val frame = HeadTrackerUhidProtocol.little(ByteArray(HeadTrackerUhidProtocol.FRAME_BYTES))
                                    .putInt(HeadTrackerUhidProtocol.FRAME_MAGIC).putInt(pose.discontinuity)
                                    .putLong(arrivalNs)
                                (pose.rotationVector + pose.angularVelocity).forEach { frame.putFloat(it) }
                                check(Os.write(output, frame.array(), 0, frame.capacity()) == frame.capacity()) { "pose_pipe_stalled" }
                                sessionTiming.submitted()
                                withChoice(epoch) { mutableState.update { it.copy(active = true) } }
                            }
                        }
                    }
                }
                try {
                    val reason = stopped.await()
                    check(reason in setOf("finished", "owner_changed", "owner_died", "native_disabled",
                        "route_changed", "spatial_disabled", "state_changed", "permission_denied", "cancelled")) { "sensor_stopped" }
                    withChoice(epoch) {
                        if (reason == "native_disabled") nativeOn = false
                        mutableState.update { it.copy(enabled = pending.get()?.enabled ?: nativeOn,
                            active = false, available = false, reason = reason) }
                    }
                }
                finally { sender.cancelAndJoin() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            withChoice(epoch) {
                if (error is CalibratedHeadMotionFault && owns()) {
                    if (recovery.failed(error.verifiedSensorGap)) retryAfter.set(SystemClock.elapsedRealtime() + 2_000)
                    else { failedSocket = socket; failedKey = key; poseFaultReason = error.message }
                }
                else retryAfter.set(SystemClock.elapsedRealtime() + 2_000)
                if (choice != null) pending.compareAndSet(choice, null)
                mutableState.update { it.copy(enabled = pending.get()?.enabled ?: nativeOn,
                    active = false, busy = pending.get() != null, available = false,
                    reason = if (failedSocket === socket && failedKey == key) "motion_changed" else "sensor_unavailable") }
            }
        } finally {
            recovery.withheld()
            atmos?.androidProviderReady(false)
            live.set(false)
            forceInspect.set(true)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { lease?.finish()?.getBundle("sink_stats") }.getOrNull()?.let {
                    if (activeLease === lease) { sinkTiming = it; sinkTimingAt = SystemClock.elapsedRealtime() }
                }
                if (activeLease === lease) activeLease = null
                pipe?.forEach { runCatching { it.close() } }
            }
            withChoice(epoch) { mutableState.update { it.copy(active = false) } }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionLive?.set(false)
        atmos?.close()
        offRequests.close(); offWriter.cancel()
        parentCompletion?.dispose()
        runner.cancel()
    }
}

@Suppress("DEPRECATION")
private fun Bundle.flag(name: String) = get(name) as? Boolean

private fun Bundle.spatialEnabled(): Boolean? = if (getInt("version", 0) == 1 && flag("known") == true &&
    flag("enabled") != null && flag("device_enabled") != null) flag("enabled") == true && flag("device_enabled") == true else null

internal fun acceptedAtmosSpatial(result: Bundle, spatial: Boolean, tracking: Boolean, epoch: Long): Boolean =
    epoch > 0 && (!tracking || spatial) && result.getInt("version", 0) == 1 &&
        result.flag("supported") == true && result.flag("known") == true &&
        result.flag("desired_spatial") == spatial && result.flag("desired_tracking") == tracking &&
        result.getLong("accepted_generation", -1) == epoch
