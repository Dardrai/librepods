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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocol
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient

data class NativeHeadTrackingState(val enabled: Boolean? = null, val calibrated: Boolean = false,
    val available: Boolean = false, val active: Boolean = false, val busy: Boolean = false,
    val reason: String = "loading")

/** Native preference is the user's choice; reporting owns only this controller's motion source. */
class NativeHeadTrackingController internal constructor(
    private val device: AppleDevice,
    parentScope: CoroutineScope,
    private val hostBlocked: () -> Boolean,
    private val deviceCurrent: () -> Boolean,
    private val access: ShizukuAudioClient,
    private val profiles: SpatialPoseProfileStore,
    private val motion: Flow<MotionReport?>,
    dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    constructor(device: AppleDevice, parentScope: CoroutineScope, hostBlocked: () -> Boolean,
        deviceCurrent: () -> Boolean) : this(device, parentScope, hostBlocked, deviceCurrent,
        ShizukuAudioClient(device.context, requireRoot = true), SpatialPoseProfileStore(device.context),
        HeadTracking.motion, Dispatchers.Default)

    private data class Choice(val epoch: Long, val enabled: Boolean, val started: Long)
    private val mutableState = MutableStateFlow(NativeHeadTrackingState())
    val state = mutableState.asStateFlow()
    private val closed = AtomicBoolean()
    private val hostIdle = AtomicBoolean()
    private val hostCheckedAt = AtomicLong(Long.MIN_VALUE)
    private val choiceEpoch = AtomicLong()
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
    @Volatile private var nativeOn: Boolean? = null
    private val discontinuity = AtomicInteger()
    private val forceInspect = AtomicBoolean(true)
    private val retryAfter = AtomicLong()
    // Bluetooth source cleanup can block in a socket write; it must not delay the user's native Off.
    private val offWriter = scope.launch {
        for (choice in offRequests) {
            try {
                if (pending.get() !== choice || !current()) continue
                access.connect()
                while (!access.hasRootPrivileges && current() && pending.get() === choice &&
                    SystemClock.elapsedRealtime() - choice.started < 5_000) delay(100)
                val result = access.disableHeadTracking(device.macAddress.value) {
                    current() && pending.get() === choice && choiceEpoch.get() == choice.epoch
                }
                withChoice(choice.epoch) {
                    if (pending.compareAndSet(choice, null)) {
                        nativeOn = if (result.getBoolean("applied")) false else null
                        if (nativeOn == false) { failedSocket = null; failedKey = null }
                        mutableState.update { it.copy(enabled = nativeOn, busy = false, active = false,
                            reason = if (nativeOn == false) "off" else "request_failed") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withChoice(choice.epoch) {
                    if (pending.compareAndSet(choice, null)) mutableState.update {
                        it.copy(enabled = nativeOn, busy = false, reason = "request_failed")
                    }
                }
            } finally { forceInspect.set(true) }
        }
    }

    private fun calibrating() = synchronized(calibrationOwners) { calibrationOwners.isNotEmpty() }

    private inline fun withChoice(epoch: Long, action: () -> Unit) = synchronized(choiceLock) {
        if (choiceEpoch.get() == epoch) action()
    }

    fun setCalibrationActive(owner: Any, active: Boolean) {
        synchronized(calibrationOwners) { if (active) calibrationOwners += owner else calibrationOwners -= owner }
        if (active) sessionLive?.set(false)
        else { failedSocket = null; failedKey = null }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(choiceLock) {
            if (closed.get()) return
            val choice = Choice(choiceEpoch.incrementAndGet(), enabled, SystemClock.elapsedRealtime())
            pending.set(choice)
            // Retire the old owner immediately; its late startup cannot override a newer choice.
            sessionLive?.set(false)
            mutableState.update { it.copy(enabled = enabled, active = false, busy = true) }
            if (!enabled) offRequests.trySend(choice)
        }
    }

    private fun current() = !closed.get() && runCatching(deviceCurrent).getOrDefault(false) &&
        device.connectionState.value == ConnectionState.CONNECTED

    private fun key(): SpatialPoseProfileKey? {
        val metadata = device.metadata.value
        val role = device.state.value.budRoleRaw.toList()
        if (metadata.model != AirPodsModel.AIRPODS_PRO_3 || role.firstOrNull() !in listOf(1, 2) ||
            metadata.modelNumber.isBlank() || metadata.version1.isBlank()) return null
        return SpatialPoseProfileKey(device.macAddress.value, role, metadata.modelNumber, metadata.version1)
    }

    private fun sourceCurrent(socket: BluetoothSocket, key: SpatialPoseProfileKey) = current() && access.hasRootPrivileges && hostIdle.get() &&
        hostCheckedAt.get().let { it != Long.MIN_VALUE && SystemClock.elapsedRealtime() - it in 0..500 } &&
        !calibrating() && device.aacp.ownsAudioSocket(socket) && key() == key &&
        !listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT).all { side ->
            device.state.value.componentState.any { it.component == side && it.status == ComponentStatus.IN_CASE }
        }

    private suspend fun stopSession() {
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
        try {
            while (isActive) {
                try {
                    val key = if (current()) key() else null
                    val profile = key?.let(profiles::load)
                    val socket = device.aacp.captureAudioSocket()
                    val eligible = BuildConfig.DEBUG && profile != null && socket != null &&
                        sourceCurrent(socket, checkNotNull(key)) &&
                        (!device.settings.value.headTrackingIntervalConfigured ||
                            device.settings.value.headTrackingInterval.inWholeMilliseconds <= 40)
                    mutableState.update { it.copy(calibrated = profile != null) }
                    if (session != null && (!eligible || sessionSocket !== socket || sessionKey != key ||
                            sessionLive?.get() != true || session?.isActive != true)) stopSession()
                    if (failedSocket !== socket || failedKey != key) { failedSocket = null; failedKey = null }
                    val epoch = choiceEpoch.get()
                    val choice = pending.get()
                    if (choice != null && choice.epoch != epoch) { delay(100); continue }
                    if (choice != null && SystemClock.elapsedRealtime() - choice.started >= 5_000) {
                        withChoice(epoch) {
                            if (pending.compareAndSet(choice, null)) mutableState.update {
                                it.copy(enabled = nativeOn, busy = false, reason = "request_failed")
                            }
                        }
                        delay(100)
                        continue
                    }
                    if (choice?.enabled != false && !calibrating() && current() && BuildConfig.DEBUG &&
                        device.metadata.value.model == AirPodsModel.AIRPODS_PRO_3) {
                        access.connect() // Reconnects existing authorization; it never requests a root grant.
                        if (access.hasRootPrivileges) {
                            val now = SystemClock.elapsedRealtime()
                            if (forceInspect.getAndSet(false) || inspectedAt == Long.MIN_VALUE || now - inspectedAt >= 1_000) {
                                val snapshot = access.inspectSpatialAudio(device.macAddress.value, ::current)
                                if (choiceEpoch.get() != epoch) { forceInspect.set(true); delay(100); continue }
                                inspectedAt = now
                                spatialEligible = false
                                if (snapshot.getInt("version", 0) == 1 && snapshot.flag("known") == true) {
                                    @Suppress("DEPRECATION")
                                    val headPreference = snapshot.flag("head_tracker_enabled")
                                    withChoice(epoch) {
                                        if (nativeOn == false && headPreference == true) { failedSocket = null; failedKey = null }
                                        nativeOn = headPreference
                                        spatialEligible = headPreference != null && snapshot.flag("device_enabled") == true &&
                                            snapshot.flag("enabled") == true && snapshot.flag("route_selected") == true
                                        mutableState.update { it.copy(available = eligible && spatialEligible,
                                            enabled = pending.get()?.enabled ?: nativeOn,
                                            reason = snapshot.getString("reason") ?: "unavailable") }
                                    }
                                    if (nativeOn == false && choice == null) stopSession()
                                }
                            }
                            val poseFault = failedSocket != null && failedSocket === socket && failedKey == key
                            mutableState.update { it.copy(available = eligible && spatialEligible && !poseFault,
                                reason = if (poseFault) "motion_changed" else it.reason) }
                            val explicitOn = choice?.enabled == true
                            if (session == null && eligible && mutableState.value.available &&
                                (explicitOn || nativeOn == true && SystemClock.elapsedRealtime() >= retryAfter.get() &&
                                    !(failedSocket === socket && failedKey == key))) {
                                if (explicitOn) { failedSocket = null; failedKey = null }
                                val live = AtomicBoolean(true)
                                sessionLive = live; sessionSocket = socket; sessionKey = key
                                session = launch { runSensor(checkNotNull(socket), checkNotNull(key),
                                    checkNotNull(profile), live, choice.takeIf { explicitOn }, epoch) }
                            } else if (explicitOn && (!eligible || !mutableState.value.available)) {
                                withChoice(epoch) {
                                    if (pending.compareAndSet(choice, null)) mutableState.update {
                                        it.copy(enabled = nativeOn, busy = false, reason = "unavailable")
                                    }
                                }
                            }
                        } else { forceInspect.set(true); mutableState.update { it.copy(available = false) } }
                    } else { forceInspect.set(true); mutableState.update { it.copy(available = false) } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(available = false, active = false, reason = "unavailable") } }
                delay(100)
            }
        } finally {
            hostIdle.set(false); guard.cancel()
            withContext(NonCancellable) { stopSession(); access.close() }
            mutableState.update { it.copy(active = false, available = false, busy = false) }
            scope.cancel()
        }
    }
    private val parentCompletion = parentScope.coroutineContext[Job]?.invokeOnCompletion { close() }

    private suspend fun runSensor(socket: BluetoothSocket, key: SpatialPoseProfileKey, profile: SpatialPoseProfile,
        live: AtomicBoolean, choice: Choice?, epoch: Long) {
        var pipe: Array<ParcelFileDescriptor>? = null
        var lease: ShizukuAudioClient.HeadTrackerLease? = null
        val reporting = MutableStateFlow(false)
        val stopped = kotlinx.coroutines.CompletableDeferred<String>()
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
            withChoice(epoch) {
                nativeOn = true
                if (choice == null || pending.compareAndSet(choice, null))
                    mutableState.update { it.copy(enabled = pending.get()?.enabled ?: true, busy = pending.get() != null) }
            }
            kotlinx.coroutines.coroutineScope {
                val sender = launch(Dispatchers.IO) {
                    reporting.collectLatest { enabled ->
                        withChoice(epoch) { mutableState.update { it.copy(active = false) } }
                        if (enabled) {
                            streamNativeHeadTracking(device, motion, profile,
                                discontinuity.getAndIncrement() and 255, ::owns) { pose ->
                                val frame = HeadTrackerUhidProtocol.little(ByteArray(HeadTrackerUhidProtocol.FRAME_BYTES))
                                    .putInt(HeadTrackerUhidProtocol.FRAME_MAGIC).putInt(pose.discontinuity)
                                    .putLong(SystemClock.elapsedRealtimeNanos())
                                (pose.rotationVector + pose.angularVelocity).forEach { frame.putFloat(it) }
                                check(Os.write(output, frame.array(), 0, frame.capacity()) == frame.capacity()) { "pose_pipe_stalled" }
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
                if (error is NativeHeadTrackingPoseFault && owns()) { failedSocket = socket; failedKey = key }
                else retryAfter.set(SystemClock.elapsedRealtime() + 2_000)
                if (choice != null) pending.compareAndSet(choice, null)
                mutableState.update { it.copy(enabled = pending.get()?.enabled ?: nativeOn,
                    active = false, busy = pending.get() != null, available = false,
                    reason = if (failedSocket === socket && failedKey == key) "motion_changed" else "sensor_unavailable") }
            }
        } finally {
            live.set(false)
            forceInspect.set(true)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { lease?.finish() }
                pipe?.forEach { runCatching { it.close() } }
            }
            withChoice(epoch) { mutableState.update { it.copy(active = false) } }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionLive?.set(false)
        offRequests.close(); offWriter.cancel()
        parentCompletion?.dispose()
        runner.cancel()
    }
}

@Suppress("DEPRECATION")
private fun Bundle.flag(name: String) = get(name) as? Boolean

private class NativeHeadTrackingPoseFault(reason: String) : IllegalStateException(reason)

/** Each reporting interval owns its source and a new clock/rate history, never another reader's. */
internal suspend fun streamNativeHeadTracking(device: AppleDevice, motion: Flow<MotionReport?>,
    profile: SpatialPoseProfile, discontinuity: Int, current: () -> Boolean, submit: (SpatialPose) -> Unit) {
    val owner = Any()
    try {
        check(current() && device.startHeadTracking(owner)) { "motion_unavailable" }
        val converter = SpatialPoseConverter(profile, discontinuity)
        var previousCounter: Long? = null
        motion.drop(1).filterNotNull().collect { report ->
            check(current()) { "owner_changed" }
            if (report.counter != null && report.counter == previousCounter) return@collect
            previousCounter = report.counter
            converter.accept(report)?.let(submit)
            if (converter.invalidated) throw NativeHeadTrackingPoseFault(converter.invalidReason ?: "motion_changed")
        }
        error("motion_ended")
    } finally { device.stopHeadTracking(owner) }
}
