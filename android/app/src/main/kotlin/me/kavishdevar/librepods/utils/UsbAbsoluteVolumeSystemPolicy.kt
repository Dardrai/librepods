/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumeEvents
import me.kavishdevar.librepods.shizuku.IUsbAbsoluteVolumePolicy
import me.kavishdevar.librepods.shizuku.UsbAbsoluteVolumeBroker
import me.kavishdevar.librepods.shizuku.UsbAbsoluteVolumeInspection
import me.kavishdevar.librepods.shizuku.UsbAudioDevices
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** This object and every native-policy cleanup obligation live in system_server. */
@android.annotation.SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal class UsbAbsoluteVolumeSystemPolicy(
    private val context: Context,
    private val service: Any,
    private val methods: UsbAbsoluteVolumePolicyHooks.Methods,
) : IUsbAbsoluteVolumePolicy.Stub(), AutoCloseable {
    private val audioServiceType = methods.ready.declaringClass
    private val gate = ReentrantLock()
    private val handler = Handler(context.mainLooper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = Class.forName("android.media.AudioDeviceAttributes")
    private val volumes = Class.forName("android.media.VolumeInfo")
    private val volumeBuilder = Class.forName("android.media.VolumeInfo\$Builder")
    private val integer = Int::class.javaPrimitiveType!!
    private val createVolume = volumeBuilder.getConstructor(integer)
    private val minimumVolume = volumeBuilder.getMethod("setMinVolumeIndex", integer)
    private val maximumVolume = volumeBuilder.getMethod("setMaxVolumeIndex", integer)
    private val volumeIndex = volumeBuilder.getMethod("setVolumeIndex", integer)
    private val mutedVolume = volumeBuilder.getMethod("setMuted", Boolean::class.javaPrimitiveType)
    private val buildVolume = volumeBuilder.getMethod("build")
    private val getVolumeIndex = volumes.getMethod("getVolumeIndex")
    private val hasMuteState = volumes.getMethod("hasMuteState")
    private val isMuted = volumes.getMethod("isMuted")
    private val managerType = Class.forName("android.media.AudioDeviceVolumeManager")
    private val listenerType = Class.forName("android.media.AudioDeviceVolumeManager\$OnAudioDeviceVolumeChangedListener")
    private val manager = managerType.getConstructor(Context::class.java).newInstance(context)
    private val dispatcherField = managerType.getDeclaredField("mDeviceVolumeDispatcherStub").apply {
        isAccessible = true
        check(Class.forName("android.media.IAudioDeviceVolumeDispatcher").isAssignableFrom(type))
    }
    private val volumeLock = field("mVolumeStateLock")
    private val nativeLock = field("mCachedAbsVolDrivingStreamsLock")
    @Suppress("UNCHECKED_CAST")
    private val nativeStreams = field("mCachedAbsVolDrivingStreams") as MutableMap<Int, Int>
    private val native = field("mAudioSystem")
    private val getBehavior = audioServiceType.getMethod("getDeviceVolumeBehavior", attributes)
    private val getInfo = audioServiceType.getDeclaredMethod("getAbsoluteVolumeDeviceInfo", attributes).apply { isAccessible = true }
    private val getCallback = usbPolicyCallbackField(getInfo, Class.forName("android.media.IAudioDeviceVolumeDispatcher"))
    private val register = managerType.getMethod("setDeviceAbsoluteVolumeBehavior", attributes, volumes,
        Executor::class.java, listenerType)
    private val reset = managerType.getMethod("resetDeviceAbsoluteVolumeBehavior", attributes)
    private val notify = managerType.getMethod("notifyAbsoluteVolumeChanged", volumes, attributes)
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var target: UsbAbsoluteVolumeBroker.Target? = null
    private var authority: UsbAbsoluteVolumeBroker.Authority? = null
    private var events: IUsbAbsoluteVolumeEvents? = null
    private var preparedLevel = -1
    private var activationFailure: Bundle? = null
    @Volatile private var nativeOwned: NativeOwned? = null
    private var closed = false
    private val gateway: UsbAbsoluteVolumeBroker.Policy = object : UsbAbsoluteVolumeBroker.Policy {
        override val lifecycleSafe = true
        override fun route(target: UsbAbsoluteVolumeBroker.Target): UsbAbsoluteVolumeBroker.Route? {
            if (!UsbAbsoluteVolumeInspection.routeEligible(context, target.deviceId, target.boardSerial, target.address)) return null
            val device = device(target.deviceId) ?: return null
            if (device.type != target.type || device.address != target.address) return null
            val attr = attributes.getConstructor(AudioDeviceInfo::class.java).newInstance(device)
            return UsbAbsoluteVolumeBroker.Route(target, true, true, audio.mode == AudioManager.MODE_NORMAL,
                getBehavior.invoke(service, attr) == 0)
        }
        override fun acquire(target: UsbAbsoluteVolumeBroker.Target, deadline: Long): UsbAbsoluteVolumeBroker.OwnedPolicy? {
            val device = device(target.deviceId) ?: return null
            val attr = attributes.getConstructor(AudioDeviceInfo::class.java).newInstance(device)
            val type = attributes.getMethod("getInternalType").invoke(attr) as Int
            val action = NativeOwned(target, attr, type)
            nativeOwned = action
            action.begin()
            return action
        }
    }
    private val broker: UsbAbsoluteVolumeBroker = UsbAbsoluteVolumeBroker(gateway, SystemClock::elapsedRealtime, firmwareContractVersion = 1)
    private val watch: Runnable = object : Runnable {
        override fun run() = gate.withLock {
            if (owner == null) return@withLock
            val result = broker.poll()
            if (!result.active) end(result.reason)
            if (owner != null) handler.postDelayed(this, if (result.reason == "cleanup_failed") 1_000 else 200)
        }
    }

    private fun field(name: String) = audioServiceType.getDeclaredField(name).apply { isAccessible = true }.get(service)!!
    private fun device(id: Int) = UsbAudioDevices.outputs().singleOrNull { it.id == id }
    private fun response(active: Boolean, reason: String, cleanup: Boolean = true) = Bundle().apply {
        putInt("version", 1); putBoolean("active", active); putString("reason", reason)
        putBoolean("native_policy_owned", active)
        putBoolean("cleanup_complete", cleanup)
    }
    private fun <T> root(action: () -> T): T {
        check(Binder.getCallingUid() == 0) { "USB volume policy requires the root helper" }
        val identity = Binder.clearCallingIdentity()
        return try { gate.withLock { action() } } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun inspect(audioDeviceId: Int, boardSerial: String, verifiedAddress: String): Bundle = root {
        UsbAbsoluteVolumeInspection.inspect(context, audioDeviceId, boardSerial).apply {
            val route = UsbAbsoluteVolumeInspection.routeEligible(context, audioDeviceId, boardSerial, verifiedAddress)
            val current = device(audioDeviceId)
            val attr = current?.let { attributes.getConstructor(AudioDeviceInfo::class.java).newInstance(it) }
            val type = attr?.let { attributes.getMethod("getInternalType").invoke(it) as Int }
            val eligible = route && attr != null && type != null && getBehavior.invoke(service, attr) == 0 &&
                synchronized(nativeLock) { !nativeStreams.containsKey(type) }
            putBoolean("route_eligible", eligible)
            putBoolean("root", true)
            putBoolean("registration_api", true)
            putBoolean("native_policy_supported", !closed)
            putBoolean("native_policy_owned", runCatching { nativeOwned?.stillOwned() == true }.getOrDefault(false))
            putBoolean("available", !closed && owner == null && nativeOwned == null && eligible)
            putString("reason", if (closed) "closed" else if (owner != null) "busy" else if (eligible) "ready" else "route_unavailable")
        }
    }

    override fun acquire(token: IBinder, audioDeviceId: Int, boardSerial: String, verifiedAddress: String, connection: Long,
        expiresAt: Long, physicalLevel: Int, listener: IUsbAbsoluteVolumeEvents): Bundle = root {
        if (closed || owner != null || nativeOwned != null) return@root response(false, "busy")
        if (!token.isBinderAlive || physicalLevel !in 0..127) return@root response(false, "stale")
        val device = device(audioDeviceId) ?: return@root response(false, "route_unavailable")
        if (!UsbAbsoluteVolumeInspection.validAddress(verifiedAddress) || device.address != verifiedAddress)
            return@root response(false, "root_identity_unavailable")
        val selected = UsbAbsoluteVolumeBroker.Target(audioDeviceId, device.type, device.address, boardSerial, connection)
        val recipient = IBinder.DeathRecipient { handler.post { gate.withLock { if (owner === token) end("owner_died") } } }
        try { token.linkToDeath(recipient, 0) } catch (_: android.os.RemoteException) { return@root response(false, "stale") }
        owner = token; death = recipient; target = selected; events = listener; preparedLevel = physicalLevel
        val proof = UsbAbsoluteVolumeBroker.Authority(1, true, connection, expiresAt, true, true)
        authority = proof
        activationFailure = null
        val result = broker.acquire(token, selected, proof, true, 37, 0)
        if (!result.active || !token.isBinderAlive) {
            // begin() may have failed after registering; retain and clean that obligation too.
            val clean = end(if (result.active) "stale" else result.reason)
            return@root response(false, if (clean) result.reason else "cleanup_failed", clean).apply {
                activationFailure?.let(::putAll)
            }
        }
        handler.postDelayed(watch, 200)
        response(true, "active")
    }

    override fun renew(token: IBinder, connection: Long, expiresAt: Long, verifiedAddress: String): Bundle = root {
        if (owner !== token) return@root response(false, "wrong_owner")
        if (target?.address != verifiedAddress) {
            val clean = end("root_identity_unavailable")
            return@root response(false, "root_identity_unavailable", clean)
        }
        val selected = target ?: return@root response(false, "stale")
        val proof = authority?.copy(connection = connection, expiresAt = expiresAt) ?: return@root response(false, "stale")
        val result = broker.renew(token, selected, proof)
        if (!result.active) end(result.reason) else authority = proof
        response(result.active, result.reason, nativeOwned == null || result.active)
    }

    override fun notifyPhysicalVolume(token: IBinder, connection: Long, level: Int, verifiedAddress: String,
        acknowledgedRequestSequence: Long): Bundle = root {
        if (owner !== token || target?.connection != connection || level !in 0..127) return@root response(false, "stale")
        if (target?.address != verifiedAddress) {
            val clean = end("root_identity_unavailable")
            return@root response(false, "root_identity_unavailable", clean)
        }
        if (!broker.poll().active) { end("stale"); return@root response(false, "stale", nativeOwned == null) }
        val action = nativeOwned ?: return@root response(false, "stale")
        if (acknowledgedRequestSequence !in 0..action.volumeState.requestSequence)
            return@root response(false, "invalid_host_request_sequence")
        if (action.volumeState.acknowledgementSuperseded(acknowledgedRequestSequence, level))
            return@root response(true, "host_request_superseded")
        publishPhysicalVolume(level, action.attr)
        response(true, "active")
    }

    override fun release(token: IBinder): Bundle = root {
        if (owner !== token) return@root response(false, "wrong_owner")
        val clean = end("released")
        response(false, if (clean) "released" else "cleanup_failed", clean)
    }

    /** Hooks call this before foreign registrations, USB topology changes and audio-server recovery. */
    fun invalidate(reason: String): Boolean {
        if (ownOperation.get() == true) return true
        return beforeForeignUsbPolicy(gate, { work -> handler.post { work() } }) { end(reason) }
    }

    private fun end(reason: String): Boolean {
        val token = owner ?: return nativeOwned == null
        broker.release(token)
        val remaining = nativeOwned
        // begin() can clean its own failed attempt before the broker ever records a lease.
        val clean = remaining == null || runCatching { remaining.close() }.isSuccess
        if (!clean) {
            runCatching { events?.onPolicyEnded("cleanup_failed", false) }
            handler.removeCallbacks(watch); handler.postDelayed(watch, 1_000)
            return false
        }
        broker.release(token)
        nativeOwned = null
        handler.removeCallbacks(watch)
        death?.let { runCatching { token.unlinkToDeath(it, 0) } }
        runCatching { events?.onPolicyEnded(reason, true) }
        owner = null; death = null; target = null; authority = null; events = null; preparedLevel = -1
        return true
    }

    private fun volume(index: Int? = null, muted: Boolean? = null): Any {
        return volume(index, muted, 127)
    }

    private fun volume(index: Int?, muted: Boolean?, maximum: Int): Any {
        val builder = createVolume.newInstance(AudioManager.STREAM_MUSIC)
        minimumVolume.invoke(builder, 0); maximumVolume.invoke(builder, maximum)
        if (index != null) volumeIndex.invoke(builder, index)
        if (muted != null) mutedVolume.invoke(builder, muted)
        return checkNotNull(buildVolume.invoke(builder))
    }

    private fun publishPhysicalVolume(level: Int, attr: Any, preserveMute: Boolean = false) {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) && (preserveMute || level == 0)
        // Keep the exact physical level in the lease; only the host display is rescaled.
        val index = if (muted && level == 0) null else usbHostVolumeIndex(level, maximum)
        ownCall { notify.invoke(manager, volume(index, muted, maximum), attr) }
        nativeOwned?.volumeState?.observe(level, index, muted || index == 0)
    }

    private inner class NativeOwned(val selected: UsbAbsoluteVolumeBroker.Target, val attr: Any, val type: Int) : UsbAbsoluteVolumeBroker.OwnedPolicy {
        private val debt = UsbVolumePolicyDebt()
        val volumeState = UsbPhysicalVolumeState(preparedLevel, audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.isStreamMute(AudioManager.STREAM_MUSIC))
        private var callback: Any? = null
        private val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "onAudioDeviceVolumeChanged" -> {
                    runCatching { gate.withLock {
                        if (nativeOwned === this && owner != null && broker.poll().active) {
                            val value = args!![1]
                            val index = getVolumeIndex.invoke(value) as Int
                            val hostIndex = if (index in 0..127) usbHostVolumeIndex(index,
                                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) else null
                            val level = volumeState.request(index, hostIndex,
                                hasMuteState.invoke(value) as Boolean,
                                isMuted.invoke(value) as Boolean,
                                audio.isStreamMute(AudioManager.STREAM_MUSIC))
                            if (level != null) runCatching {
                                events?.onVolumeChanged(selected.connection, level, volumeState.requestSequence)
                            }
                                .onFailure { end("callback_failed") }
                        }
                    } }.onFailure { handler.post { gate.withLock { end("callback_failed") } } }; null
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "LibrePods USB volume listener"
                else -> null
            }
        }
        fun begin() = synchronized(volumeLock) {
            var stage = UsbVolumeActivationStage.PREFLIGHT
            try {
                check(!audio.isMusicActive && audio.activeRecordingConfigurations.isEmpty()) { "Pause playback and recording before changing USB volume authority" }
                check(preparedLevel in 0..127) { "Physical volume is unavailable" }
                check(getBehavior.invoke(service, attr) == 0)
                synchronized(nativeLock) { check(!nativeStreams.containsKey(type)) { "Another native volume policy owns USB" } }
                stage = UsbVolumeActivationStage.FRAMEWORK_REGISTRATION
                // An exception can follow partial registration; cleanup still owns the callback.
                debt.registration = true
                try { ownCall { register.invoke(manager, attr, volume(), Executor { command -> handler.post(command) }, listener) } }
                finally { callback = dispatcherField.get(manager) }
                stage = UsbVolumeActivationStage.CALLBACK_OWNERSHIP
                check(registeredByUs())
                stage = UsbVolumeActivationStage.NATIVE_UNITY
                synchronized(nativeLock) { check(!nativeStreams.containsKey(type)); nativeStreams[type] = AudioManager.STREAM_MUSIC; debt.mapping = true }
                debt.native = true
                check(ownCall { methods.nativePolicy.invoke(native, type, "", true, AudioManager.STREAM_MUSIC) } == 0)
                publishPhysicalVolume(preparedLevel, attr, preserveMute = true)
            } catch (error: Throwable) {
                activationFailure = usbVolumeActivationFailure(stage, error)
                runCatching { close() }
                throw error
            }
        }
        private fun registeredByUs(): Boolean = callback != null &&
            getInfo.invoke(service, attr)?.let { getCallback.get(it) === callback } == true
        override fun stillOwned(): Boolean = nativeOwned === this && registeredByUs() && debt.mapping &&
            synchronized(nativeLock) { nativeStreams[type] == AudioManager.STREAM_MUSIC }
        override fun close() = synchronized(volumeLock) {
            if (nativeOwned !== this) return@synchronized
            val current = if (debt.registration) getInfo.invoke(service, attr) else null
            val registry = when { current == null -> 0; getCallback.get(current) === callback -> 1; else -> 2 }
            debt.clear(registry, removeCache = {
                synchronized(nativeLock) {
                    // The framework may change the value after a rejected native update.
                    // Callback ownership, rather than that numeric value, owns this entry.
                    nativeStreams.remove(type)
                }
            }, disableNative = {
                ownCall { methods.nativePolicy.invoke(native, type, "", false, AudioManager.STREAM_MUSIC) } == 0
            }, reset = {
                ownCall { reset.invoke(manager, attr) }
                getBehavior.invoke(service, attr) == 0
            })
            nativeOwned = null
        }
    }

    override fun close() = gate.withLock { closed = true; end("closed"); Unit }
    fun ownsNativeType(type: Int) = nativeOwned?.type == type
    fun foreignNativePolicy(): Int = beforeForeignUsbNativePolicy({ work -> handler.post { work() } }) {
        gate.withLock { end("foreign_native_policy") }
    }
    fun mayMutate() = android.os.Binder.getCallingUid() in setOf(0, 1000) ||
        listOf("android.permission.MODIFY_AUDIO_ROUTING", "android.permission.BLUETOOTH_PRIVILEGED",
            "android.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED").any {
            context.checkCallingOrSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    companion object {
        private val ownOperation = ThreadLocal.withInitial { false }
        fun isOwnOperation() = ownOperation.get() == true
        private fun <T> ownCall(action: () -> T): T {
            val previous = ownOperation.get(); ownOperation.set(true)
            return try { action() } finally { ownOperation.set(previous) }
        }
    }
}
