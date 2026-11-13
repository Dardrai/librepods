/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.utils.BluetoothIdentityState
import me.kavishdevar.librepods.utils.XposedState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSettingsAccessTest {
    @Test fun nativeIdentityRequiresObservedActiveModuleRatherThanSavedPreferenceOrRootAlone() {
        for (known in listOf(false, true)) for (enabled in listOf(false, true)) {
            assertEquals(known && enabled, supportsProtectedQuickSettings(
                BluetoothIdentityState(enabled, known), nativeIdentity = true,
                xposedAvailable = false, bluetoothScopeEnabled = false))
        }
    }

    @Test fun legacyIdentityAlsoRequiresTheLoadedBluetoothScopedHook() {
        for (known in listOf(false, true)) for (enabled in listOf(false, true))
            for (loaded in listOf(false, true)) for (scoped in listOf(false, true)) {
                assertEquals(known && enabled && loaded && scoped, supportsProtectedQuickSettings(
                    BluetoothIdentityState(enabled, known), nativeIdentity = false,
                    xposedAvailable = loaded, bluetoothScopeEnabled = scoped))
            }
    }

    @Test fun refreshingKnownIdentityDoesNotTemporarilyHideSupportedControls() {
        assertEquals(true, supportsProtectedQuickSettings(
            BluetoothIdentityState(enabled = true, known = true, busy = true), nativeIdentity = true,
            xposedAvailable = false, bluetoothScopeEnabled = false))
    }

    @Test fun legacyScopeChangesRemainObservableWhenTheIdentityPreferenceDoesNotChange() = runTest {
        val previous = XposedState.state.value
        val observed = mutableListOf<Boolean>()
        XposedState.isAvailable = true
        XposedState.bluetoothScopeEnabled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(flowOf(BluetoothIdentityState(enabled = true, known = true)), XposedState.state) { identity, hook ->
                supportsProtectedQuickSettings(identity, nativeIdentity = false,
                    xposedAvailable = hook.available, bluetoothScopeEnabled = hook.bluetoothScopeEnabled)
            }.distinctUntilChanged().collect { observed += it }
        }
        try {
            runCurrent()
            XposedState.bluetoothScopeEnabled = true
            runCurrent()
            XposedState.isAvailable = false
            runCurrent()
            assertEquals(listOf(false, true, false), observed)
        } finally {
            job.cancel()
            XposedState.isAvailable = previous.available
            XposedState.bluetoothScopeEnabled = previous.bluetoothScopeEnabled
        }
    }
}
