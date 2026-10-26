// SPDX-License-Identifier: GPL-3.0-or-later
package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import me.kavishdevar.librepods.data.xposed.XposedRemotePrefProvider

internal enum class IdentityAction { STATUS, ENABLE, DISABLE }
internal data class IdentityHelperStatus(val configured: Boolean, val active: Boolean)
data class BluetoothIdentityState(val enabled: Boolean = false, val known: Boolean = false, val busy: Boolean = false)
enum class IdentityUpdateResult { UPDATED, FAILED, BUSY }

internal fun parseIdentityStatus(output: String): IdentityHelperStatus? {
    if (output.length > 128) return null
    val lines = output.trimEnd('\n', '\r').lines()
    if (lines.size != 3 || lines[0] != "version=1") return null
    fun flag(line: String, key: String): Boolean? = when (line) {
        "$key=0" -> false
        "$key=1" -> true
        else -> null
    }
    return IdentityHelperStatus(flag(lines[1], "configured") ?: return null,
        flag(lines[2], "active") ?: return null)
}

internal class IdentityPreferenceController(
    private val modern: Boolean,
    initialEnabled: Boolean = false,
    private val readLegacy: () -> Boolean?,
    private val writeLegacy: (Boolean) -> Boolean,
    private val rootWasApproved: () -> Boolean,
    private val rememberApproval: (Boolean) -> Unit,
    private val root: suspend (IdentityAction) -> IdentityHelperStatus?,
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(BluetoothIdentityState(initialEnabled))
    val state = mutableState.asStateFlow()

    fun restoreHint(enabled: Boolean) {
        if (!mutableState.value.known && !mutableState.value.busy)
            mutableState.value = mutableState.value.copy(enabled = enabled)
    }

    suspend fun refresh() {
        if (!operation.tryLock()) return
        try {
            if (modern) {
                // Opening settings must not request root before the owner's first explicit change.
                if (!rootWasApproved()) return
                mutableState.value = mutableState.value.copy(busy = true)
                val observed = root(IdentityAction.STATUS)
                mutableState.value = if (observed == null) mutableState.value.copy(known = false)
                    else mutableState.value.copy(enabled = observed.active, known = true)
            } else {
                val observed = readLegacy()
                mutableState.value = if (observed == null) mutableState.value.copy(known = false)
                    else mutableState.value.copy(enabled = observed, known = true)
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(known = false)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(known = false)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operation.unlock()
        }
    }

    suspend fun setEnabled(enabled: Boolean): IdentityUpdateResult {
        if (!operation.tryLock()) return IdentityUpdateResult.BUSY
        mutableState.value = mutableState.value.copy(busy = true)
        return try {
            val applied = if (modern) {
                root(if (enabled) IdentityAction.ENABLE else IdentityAction.DISABLE)?.let {
                    it.configured == enabled && it.active == enabled
                } == true
            } else writeLegacy(enabled)
            if (!applied) {
                mutableState.value = mutableState.value.copy(known = false)
                IdentityUpdateResult.FAILED
            } else {
                if (modern) runCatching { rememberApproval(enabled) }
                mutableState.value = BluetoothIdentityState(enabled, known = true, busy = true)
                IdentityUpdateResult.UPDATED
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(known = false)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(known = false)
            IdentityUpdateResult.FAILED
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operation.unlock()
        }
    }
}

object BluetoothIdentitySettings {
    val usesNativeProperties: Boolean get() = Build.VERSION.SDK_INT >= 37
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var preferences: SharedPreferences? = null
    private val controller by lazy {
        IdentityPreferenceController(usesNativeProperties,
            initialEnabled = preferences?.getBoolean("last-enabled", false) ?: false,
            readLegacy = { XposedServiceHolder.service?.getRemotePreferences("me.kavishdevar.librepods")
                ?.getBoolean("vendor_id_hook", false) },
            writeLegacy = { enabled ->
                XposedServiceHolder.service?.getRemotePreferences("me.kavishdevar.librepods")?.let { prefs ->
                    prefs.edit().putBoolean("vendor_id_hook", enabled).commit() &&
                        prefs.getBoolean("vendor_id_hook", !enabled) == enabled
                } == true
            },
            rootWasApproved = { preferences?.getBoolean("root-approved", false) == true },
            rememberApproval = { enabled -> preferences?.edit()?.putBoolean("root-approved", true)
                ?.putBoolean("last-enabled", enabled)?.commit(); Unit },
            root = ::runRootAction)
    }
    val state get() = controller.state

    @Synchronized fun initialize(context: Context) {
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences("bluetooth-identity", Context.MODE_PRIVATE)
            if (usesNativeProperties) controller.restoreHint(preferences?.getBoolean("last-enabled", false) ?: false)
        }
        refresh()
    }
    fun refresh() { scope.launch { controller.refresh() } }
    suspend fun setEnabled(enabled: Boolean): IdentityUpdateResult = controller.setEnabled(enabled)

    fun allowsRootlessCompanion(): Boolean = if (usesNativeProperties)
        !state.value.enabled && (preferences?.getBoolean("root-approved", false) != true || state.value.known)
    else !XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false)

    private suspend fun runRootAction(action: IdentityAction): IdentityHelperStatus? = runInterruptible(Dispatchers.IO) {
        // Commands are fixed literals; no device, setting value or user text becomes shell code.
        val command = when (action) {
            IdentityAction.STATUS -> "exec /system/bin/sh /data/adb/modules/librepods_bluetooth_identity/identity.sh status --stdin-lease"
            IdentityAction.ENABLE -> "exec /system/bin/sh /data/adb/modules/librepods_bluetooth_identity/identity.sh enable --stdin-lease"
            IdentityAction.DISABLE -> "exec /system/bin/sh /data/adb/modules/librepods_bluetooth_identity/identity.sh disable --stdin-lease"
        }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val captured = ByteArrayOutputStream()
        val reader = Thread({
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(256)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(captured) {
                            if (captured.size() < 2048) captured.write(buffer, 0, minOf(count, 2048 - captured.size()))
                        }
                    }
                }
            }
        }, "LibrePods-identity-status").apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS) || process.exitValue() != 0) null
            else {
                reader.join(1000)
                synchronized(captured) { parseIdentityStatus(captured.toString(Charsets.UTF_8.name())) }
            }
        } finally {
            // EOF on the helper's lease triggers its TERM/rollback trap before su is retired.
            runCatching { process.outputStream.close() }
            runCatching { if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroy() }
            runCatching { process.inputStream.close() }
            if (process.isAlive) process.destroyForcibly()
            reader.interrupt()
        }
    }
}
