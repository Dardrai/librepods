/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import rikka.shizuku.SystemServiceHelper

/** Selected native A2DP settings only. This helper never creates audio or changes global spatial audio. */
internal class SpatialAudioAccess(private val platform: Platform,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }) {
    constructor(context: Context) : this(Framework(context))

    internal data class DeviceState(val enabled: Boolean, val headTracking: Boolean)
    // enabled is the routed engine state, which also depends on this device's preference.
    internal data class State(val supported: Boolean, val enabled: Boolean, val available: Boolean,
        val routeSelected: Boolean, val nativePair: Boolean, val canSpatialize51: Boolean,
        val compatible: Boolean?, val deviceEnabled: Boolean?, val headTrackerEnabled: Boolean?,
        val headTrackerPresent: Boolean?, val headTrackerAvailable: Boolean?, val actualHeadTrackingMode: Int?,
        val mutationApi: Boolean) {
        fun device() = if (deviceEnabled != null && headTrackerEnabled != null)
            DeviceState(deviceEnabled, headTrackerEnabled) else null
        fun reason() = when {
            !supported -> "unsupported"
            !nativePair -> "device_unavailable"
            !routeSelected -> "route_unavailable"
            compatible == null || device() == null -> "device_state_unavailable"
            !compatible -> "device_incompatible"
            !mutationApi -> "method_unavailable"
            !canSpatialize51 -> "format_unavailable"
            deviceEnabled == true && !enabled -> "global_disabled"
            else -> "ok"
        }
        fun bundle(reason: String = reason()) = Bundle().apply {
            putInt("version", 1); putBoolean("known", true)
            putBoolean("supported", supported); putBoolean("enabled", enabled); putBoolean("available", available)
            putBoolean("route_selected", routeSelected); putBoolean("can_spatialize_51", canSpatialize51)
            putBoolean("device_state_known", compatible != null && device() != null)
            compatible?.let { putBoolean("device_compatible", it) }
            deviceEnabled?.let { putBoolean("device_enabled", it) }
            headTrackerEnabled?.let { putBoolean("head_tracker_enabled", it) }
            headTrackerPresent?.let { putBoolean("head_tracker_present", it) }
            headTrackerAvailable?.let { putBoolean("head_tracker_available", it) }
            actualHeadTrackingMode?.let { putInt("actual_head_tracking_mode", it) }
            putBoolean("fixed_supported", reason() == "ok")
            putBoolean("applied", false); putBoolean("restored", false); putString("reason", reason)
        }
    }

    internal interface Platform {
        fun read(address: String): State
        fun setDeviceEnabled(address: String, enabled: Boolean)
        fun setHeadTrackerEnabled(address: String, enabled: Boolean)
    }
    private data class Change(val owner: IBinder, val address: String,
        val before: DeviceState, val expected: DeviceState)
    private var change: Change? = null

    @Synchronized fun inspect(address: String, owner: IPlaybackRecoveryOwner): Bundle = guarded(address) { selected ->
        val deadline = now() + 3_000
        requireCurrent(owner, deadline)
        val state = platform.read(selected)
        requireCurrent(owner, deadline)
        state.bundle()
    }

    @Synchronized fun setFixedEnabled(address: String, owner: IPlaybackRecoveryOwner, enabled: Boolean): Bundle =
        guarded(address) { selected ->
            val deadline = now() + 3_000
            requireCurrent(owner, deadline)
            val before = platform.read(selected)
            requireCurrent(owner, deadline)
            val blocked = when {
                !before.supported -> "unsupported"
                !before.nativePair -> "device_unavailable"
                !before.routeSelected -> "route_unavailable"
                before.compatible == null || before.device() == null -> "device_state_unavailable"
                !before.compatible -> "device_incompatible"
                !before.mutationApi -> "method_unavailable"
                enabled && !before.canSpatialize51 -> "format_unavailable"
                // An off device hides the global choice; enabling its preference must remain possible.
                enabled && before.deviceEnabled == true && !before.enabled -> "global_disabled"
                else -> null
            }
            if (blocked != null) return@guarded before.bundle(blocked)
            val initial = checkNotNull(before.device())
            var expected = initial
            var verified = initial
            fun revalidate(): State {
                requireCurrent(owner, deadline)
                val state = platform.read(selected)
                requireCurrent(owner, deadline)
                check(state.nativePair && state.routeSelected && state.compatible == true &&
                    state.device() == expected) { "state_changed" }
                verified = expected
                return state
            }
            try {
                if (enabled && expected.headTracking) {
                    revalidate()
                    expected = expected.copy(headTracking = false)
                    platform.setHeadTrackerEnabled(selected, false)
                }
                if (expected.enabled != enabled) {
                    revalidate()
                    expected = expected.copy(enabled = enabled)
                    platform.setDeviceEnabled(selected, enabled)
                }
                val after = revalidate()
                change = Change(owner.asBinder(), selected, initial, expected)
                after.bundle().apply { putBoolean("applied", true) }
            } catch (error: Exception) {
                val restored = runCatching {
                    val current = platform.read(selected)
                    current.nativePair && (current.device() == initial ||
                        (current.device() in setOf(expected, verified) &&
                            restoreCaptured(selected, initial, checkNotNull(current.device()))))
                }.getOrDefault(false)
                failure(if (restored) failureReason(error) else "restore_failed")
            }
        }

    /** A probe can undo only its last accepted change, while the captured values still match. */
    @Synchronized fun restore(address: String, owner: IPlaybackRecoveryOwner): Bundle = guarded(address) { selected ->
        val deadline = now() + 3_000
        requireCurrent(owner, deadline)
        val captured = change?.takeIf { it.owner == owner.asBinder() && it.address == selected }
            ?: return@guarded failure("no_snapshot")
        val state = platform.read(selected)
        requireCurrent(owner, deadline)
        if (!state.nativePair || state.device() != captured.expected) return@guarded state.bundle("state_changed")
        if (!restoreCaptured(selected, captured.before, captured.expected)) return@guarded failure("restore_failed")
        change = null
        platform.read(selected).bundle("ok").apply { putBoolean("restored", true) }
    }

    @Synchronized fun retire(owner: IPlaybackRecoveryOwner) {
        if (change?.owner == owner.asBinder()) change = null
    }

    private fun restoreCaptured(address: String, before: DeviceState, expected: DeviceState): Boolean = try {
        var current = platform.read(address)
        if (!current.nativePair || current.device() != expected) false else {
            var restoring = expected
            if (restoring.enabled != before.enabled) {
                platform.setDeviceEnabled(address, before.enabled)
                restoring = restoring.copy(enabled = before.enabled)
                current = platform.read(address)
                check(current.nativePair && current.device() == restoring)
            }
            if (restoring.headTracking != before.headTracking) {
                platform.setHeadTrackerEnabled(address, before.headTracking)
            }
            platform.read(address).device() == before
        }
    } catch (_: Exception) { false }

    private fun requireCurrent(owner: IPlaybackRecoveryOwner, deadline: Long) {
        check(now() < deadline && owner.asBinder().isBinderAlive && owner.isCurrent && now() < deadline) { "owner_changed" }
    }

    private inline fun guarded(address: String, action: (String) -> Bundle): Bundle = try {
        action(A2dpRecoveryGuard(address).address)
    } catch (error: Exception) { failure(failureReason(error)) }

    companion object {
        fun failure(reason: String) = Bundle().apply {
            putInt("version", 1); putBoolean("known", false); putBoolean("fixed_supported", false)
            putBoolean("applied", false); putBoolean("restored", false); putString("reason", reason)
        }
        private fun failureReason(error: Throwable): String = when (error) {
            is InvocationTargetException -> failureReason(error.targetException)
            is SecurityException -> "permission_denied"
            is NoSuchMethodException, is ClassNotFoundException -> "method_unavailable"
            is IllegalArgumentException -> "invalid_address"
            is IllegalStateException -> error.message?.takeIf { it in setOf("owner_changed", "state_changed") } ?: "unavailable"
            else -> "unavailable"
        }
    }

    @android.annotation.SuppressLint("MissingPermission", "PrivateApi", "SoonBlockedPrivateApi")
    internal class Framework(private val context: Context) : Platform {
        private val type = Class.forName("android.media.IAudioService")
        private val attributesType = Class.forName("android.media.AudioDeviceAttributes")
        private val binder = checkNotNull(SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE))
        private val service = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        private val audio = context.getSystemService(AudioManager::class.java)
        private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        private val format = AudioFormat.Builder().setSampleRate(48_000)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
        private fun method(name: String, result: Class<*>, vararg args: Class<*>) = type.getMethod(name, *args).also {
            check(it.returnType == result) { "Unsupported spatial audio signature" }
        }
        private fun readBoolean(name: String) = method(name, Boolean::class.javaPrimitiveType!!).invoke(service) as Boolean
        private fun device(address: String) = attributesType.getConstructor(Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java).newInstance(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, address)
        private fun matches(value: Any, address: String) = attributesType.getMethod("getRole").invoke(value) == 2 &&
            attributesType.getMethod("getType").invoke(value) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            (attributesType.getMethod("getAddress").invoke(value) as? String).equals(address, true)
        private fun <T> optional(action: () -> T): T? = try { action() }
            catch (_: NoSuchMethodException) { null }

        override fun read(address: String): State {
            check(binder.isBinderAlive && binder.pingBinder())
            val selected = device(address)
            val routes = method("getDevicesForAttributes", List::class.java, AudioAttributes::class.java)
                .invoke(service, media) as List<*>
            val route = routes.singleOrNull()?.let { matches(it, address) } == true &&
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).count {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(address, true)
                } == 1 && audio.mode == AudioManager.MODE_NORMAL
            val adapter = ShellBluetooth.adapter(context)
            val pair = adapter.isEnabled && adapter.bondedDevices.singleOrNull { it.address.equals(address, true) }?.let {
                A2dpRecoveryGuard.isAirPodsCandidate(it.bondState == BluetoothDevice.BOND_BONDED,
                    it.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL),
                    it.uuids?.map { uuid -> uuid.uuid.toString() }.orEmpty())
            } == true
            val compatible = optional { method("isSpatializerAvailableForDevice", Boolean::class.javaPrimitiveType!!,
                attributesType).invoke(service, selected) as Boolean }
            val deviceEnabled = optional { (method("getSpatializerCompatibleAudioDevices", List::class.java)
                .invoke(service) as List<*>).any { it != null && matches(it, address) } }
            val headEnabled = optional { method("isHeadTrackerEnabled", Boolean::class.javaPrimitiveType!!,
                attributesType).invoke(service, selected) as Boolean }
            val headPresent = optional { method("hasHeadTracker", Boolean::class.javaPrimitiveType!!,
                attributesType).invoke(service, selected) as Boolean }
            val mutations = optional {
                method("addSpatializerCompatibleAudioDevice", Void.TYPE, attributesType)
                method("removeSpatializerCompatibleAudioDevice", Void.TYPE, attributesType)
                method("setHeadTrackerEnabled", Void.TYPE, Boolean::class.javaPrimitiveType!!, attributesType)
                true
            } == true
            return State((method("getSpatializerImmersiveAudioLevel", Int::class.javaPrimitiveType!!)
                .invoke(service) as Int) > 0, readBoolean("isSpatializerEnabled"), readBoolean("isSpatializerAvailable"),
                route, pair, route && (method("canBeSpatialized", Boolean::class.javaPrimitiveType!!,
                    AudioAttributes::class.java, AudioFormat::class.java).invoke(service, media, format) as Boolean),
                compatible, deviceEnabled, headEnabled, headPresent, optional { readBoolean("isHeadTrackerAvailable") },
                optional { method("getActualHeadTrackingMode", Int::class.javaPrimitiveType!!).invoke(service) as Int }, mutations)
        }

        override fun setDeviceEnabled(address: String, enabled: Boolean) {
            method(if (enabled) "addSpatializerCompatibleAudioDevice" else "removeSpatializerCompatibleAudioDevice",
                Void.TYPE, attributesType).invoke(service, device(address))
        }
        override fun setHeadTrackerEnabled(address: String, enabled: Boolean) {
            method("setHeadTrackerEnabled", Void.TYPE, Boolean::class.javaPrimitiveType!!, attributesType)
                .invoke(service, enabled, device(address))
        }
    }
}
