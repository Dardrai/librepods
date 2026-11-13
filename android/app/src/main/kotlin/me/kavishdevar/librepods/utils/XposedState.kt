package me.kavishdevar.librepods.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class XposedStatus(val available: Boolean = false, val bluetoothScopeEnabled: Boolean = false)

object XposedState {
    private val current = MutableStateFlow(XposedStatus())
    val state = current.asStateFlow()
    var isAvailable: Boolean
        get() = current.value.available
        set(value) { current.update { it.copy(available = value) } }
    var bluetoothScopeEnabled: Boolean
        get() = current.value.bluetoothScopeEnabled
        set(value) { current.update { it.copy(bluetoothScopeEnabled = value) } }
}
