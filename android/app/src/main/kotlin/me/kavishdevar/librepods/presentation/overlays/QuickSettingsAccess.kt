/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.utils.BluetoothIdentityState

/** Root authorization alone does not prove that the Bluetooth identity hook is active. */
internal fun supportsProtectedQuickSettings(identity: BluetoothIdentityState, nativeIdentity: Boolean,
    xposedAvailable: Boolean, bluetoothScopeEnabled: Boolean): Boolean =
    identity.known && identity.enabled && (nativeIdentity || xposedAvailable && bluetoothScopeEnabled)
