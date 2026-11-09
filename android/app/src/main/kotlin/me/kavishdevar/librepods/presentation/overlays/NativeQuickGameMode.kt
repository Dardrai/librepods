/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource

/** Game Mode uses the usual settings observer; this only keeps preference writes off Main. */
internal class NativeQuickGameMode(private val device: AppleDevice, private val scope: CoroutineScope,
    private val current: () -> Boolean, private val failed: () -> Unit) {
    private data class Owner(val connection: Int, val dispatch: Long)
    private val dispatch = AtomicLong()
    private fun owner(): Owner? = Owner(device.connectionNumber.value, dispatch.get()).takeIf {
        scope.isActive && current() && device.connectionState.value == ConnectionState.CONNECTED
    }
    private val editor = LiveValueEditor(scope, {
        val owner = owner()
        LiveValueSource(owner, device.settings.value.nativeGameModeEnabled, owner != null)
    }, { _: Boolean -> true }, { captured, enabled ->
        val success = withContext(Dispatchers.IO) {
            var applied = false
            device.updateSettings { settings ->
                if (owner() != captured) settings else {
                    applied = true
                    settings.copy(nativeGameModeEnabled = enabled)
                }
            }
            applied && owner() == captured && device.settings.value.nativeGameModeEnabled == enabled
        }
        if (!success && owner() == captured) failed()
        success
    }, intervalMs = 0)

    val state = editor.state
    fun observe() = editor.observe()
    fun cancelPending() { dispatch.incrementAndGet(); editor.cancel(editor.state.value.revision); observe() }
    fun edit(enabled: Boolean): Boolean {
        observe()
        return editor.edit(editor.state.value.revision, enabled)
    }
    fun displaySettings(actual: AppleSettings): AppleSettings =
        actual.copy(nativeGameModeEnabled = state.value.displayed ?: actual.nativeGameModeEnabled)
}
