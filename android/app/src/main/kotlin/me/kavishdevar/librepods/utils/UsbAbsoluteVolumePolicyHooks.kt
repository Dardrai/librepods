/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import java.lang.reflect.Method
import java.lang.reflect.Field
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A framework call can already own the native volume lock; never wait for our owner lock there. */
internal fun beforeForeignUsbPolicy(gate: ReentrantLock, defer: (() -> Unit) -> Unit,
    cleanup: () -> Boolean): Boolean {
    if (!gate.tryLock()) { defer { gate.withLock { cleanup() } }; return false }
    return try { cleanup() } finally { gate.unlock() }
}

/** AudioService can be iterating its driving-stream map inside this callback. */
internal fun beforeForeignUsbNativePolicy(defer: (() -> Unit) -> Unit, cleanup: () -> Unit): Int {
    defer(cleanup)
    return -1
}

/** Host-private return types belong to the host loader, not the isolated Xposed module loader. */
internal fun usbPolicyCallbackField(info: Method, dispatcher: Class<*>): Field =
    info.returnType.getDeclaredField("mCallback").apply {
        require(dispatcher.isAssignableFrom(type)) { "Unknown USB volume callback type" }
        isAccessible = true
    }

/** Discover every lifecycle hook before allowing a future system-server-owned USB policy. */
internal object UsbAbsoluteVolumePolicyHooks {
    data class Methods(val ready: Method, val audioServerDied: Method, val wiredDevice: Method,
        val register: Method, val unregister: Method, val behavior: Method, val nativePolicy: Method)

    fun discover(sdk: Int, process: String, loader: ClassLoader,
        failed: (String, Throwable) -> Unit = { _, _ -> }): Methods? {
        if (sdk != 37 || process != "system_server") return null
        var stage = "AudioService class"
        return runCatching {
            val audio = Class.forName("com.android.server.audio.AudioService", false, loader)
            stage = "AudioSystemAdapter class"
            val adapter = Class.forName("com.android.server.audio.AudioSystemAdapter", false, loader)
            stage = "framework volume classes"
            val attributes = Class.forName("android.media.AudioDeviceAttributes", false, loader)
            val dispatcher = Class.forName("android.media.IAudioDeviceVolumeDispatcher", false, loader)
            stage = "lifecycle method signatures"
            platformMethods(audio, adapter, attributes, dispatcher)
        }.onFailure { failed(stage, it) }.getOrNull()
    }

    internal fun platformMethods(audio: Class<*>, adapter: Class<*>, attributes: Class<*>,
        dispatcher: Class<*>): Methods {
        val integer = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        fun method(name: String, result: Class<*>, vararg arguments: Class<*>) =
            exactEldMethod(audio, name, result, arguments.toList())
        return Methods(
            method("onSystemReady", Void.TYPE),
            method("onAudioServerDied", Void.TYPE),
            method("setWiredDeviceConnectionState", Void.TYPE, attributes, integer, String::class.java),
            method("registerDeviceVolumeDispatcherForAbsoluteVolume", Void.TYPE, bool, dispatcher,
                String::class.java, attributes, List::class.java, bool, integer),
            method("unregisterAbsoluteVolumeDevice", Void.TYPE, attributes),
            method("setDeviceVolumeBehavior", Void.TYPE, attributes, integer, String::class.java),
            exactEldMethod(adapter, "setDeviceAbsoluteVolumeEnabled", integer,
                listOf(integer, String::class.java, bool, integer)))
    }

    // Callback registration alone does not install native USB unity policy on SDK37. Do not
    // enable a root-process-owned native toggle: it survives root Binder death and ignores address.
    // Readiness comes from the live system-server Binder after every hook has installed.
    const val OWNED_NATIVE_POLICY_AVAILABLE = false
}
