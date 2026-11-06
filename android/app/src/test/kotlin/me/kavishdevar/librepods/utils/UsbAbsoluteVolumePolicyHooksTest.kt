/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class UsbAbsoluteVolumePolicyHooksTest {
    class Attributes
    interface Dispatcher
    class Adapter {
        @Suppress("UNUSED_PARAMETER")
        fun setDeviceAbsoluteVolumeEnabled(type: Int, address: String, enabled: Boolean, stream: Int) = 0
    }
    @Suppress("UNUSED_PARAMETER")
    class Audio {
        fun onSystemReady() {}
        fun onAudioServerDied() {}
        fun setWiredDeviceConnectionState(device: Attributes, state: Int, caller: String) {}
        fun registerDeviceVolumeDispatcherForAbsoluteVolume(register: Boolean, callback: Dispatcher,
            caller: String, device: Attributes, volumes: List<Any>, adjustments: Boolean, behavior: Int) {}
        fun unregisterAbsoluteVolumeDevice(device: Attributes) {}
        fun setDeviceVolumeBehavior(device: Attributes, behavior: Int, caller: String) {}
    }
    class ChangedAdapter {
        @Suppress("UNUSED_PARAMETER")
        fun setDeviceAbsoluteVolumeEnabled(type: Int, address: String, enabled: Boolean, stream: Int) = false
    }
    @Test fun discoversTheExactReviewedMethodSetWithoutInstallingHooksOrNativePolicy() {
        val found = UsbAbsoluteVolumePolicyHooks.platformMethods(Audio::class.java, Adapter::class.java,
            Attributes::class.java, Dispatcher::class.java)
        assertEquals(7, listOf(found.ready, found.audioServerDied, found.wiredDevice, found.register,
            found.unregister, found.behavior, found.nativePolicy).toSet().size)
        assertFalse(UsbAbsoluteVolumePolicyHooks.OWNED_NATIVE_POLICY_AVAILABLE)
    }
    @Test fun changedPlatformSignatureFailsClosedAndOtherSdksOrProcessesAreNotProbed() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbAbsoluteVolumePolicyHooks.platformMethods(Audio::class.java, ChangedAdapter::class.java,
                Attributes::class.java, Dispatcher::class.java)
        }
        val loader = javaClass.classLoader!!
        assertNull(UsbAbsoluteVolumePolicyHooks.discover(36, "system_server", loader))
        assertNull(UsbAbsoluteVolumePolicyHooks.discover(38, "system_server", loader))
        assertNull(UsbAbsoluteVolumePolicyHooks.discover(37, "com.android.bluetooth", loader))
    }
    @Test fun nativePolicyCleanupNeverMutatesTheFrameworkHashMapDuringReplaceAll() {
        for (ownerBusy in listOf(false, true)) {
            val driving = hashMapOf(22 to 3, 8 to 3)
            val deferred = mutableListOf<() -> Unit>()
            val gate = ReentrantLock()
            if (ownerBusy) gate.lock()
            try {
                driving.replaceAll { device, stream ->
                    if (device == 22) {
                        assertEquals(-1, beforeForeignUsbNativePolicy({ deferred += it }) {
                            gate.lock()
                            try { driving.remove(22) } finally { gate.unlock() }
                        })
                        0
                    } else stream
                }
                assertEquals(0, driving[22])
                assertEquals(1, deferred.size)
            } finally { if (ownerBusy) gate.unlock() }
            deferred.single().invoke()
            assertFalse(driving.containsKey(22))
            assertEquals(3, driving[8])
        }
    }
    @Test fun aFrameworkThreadHoldingItsNativeLockNeverWaitsForTheBrokerOwnerLock() {
        val gate = ReentrantLock()
        val native = Any()
        var deferred: (() -> Unit)? = null
        var cleanups = 0
        val result = CompletableFuture<Boolean>()
        gate.lock()
        try {
            Thread {
                synchronized(native) {
                    result.complete(beforeForeignUsbPolicy(gate, { deferred = it }) { cleanups++; true })
                }
            }.apply { isDaemon = true; start() }
            assertFalse(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, cleanups)
            // The owner can still take native, then release gate; no ABBA wait exists.
            synchronized(native) { assertNotNull(deferred) }
        } finally { gate.unlock() }
        deferred!!.invoke()
        assertEquals(1, cleanups)
    }
}
