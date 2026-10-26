/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import me.kavishdevar.librepods.shizuku.IHfpCallControl
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Discovery only: every resulting Binder operation separately requires the root UID. */
@SuppressLint("NewApi")
internal object HfpCallTransport {
    private const val REQUEST = "${NativeGameTransportRules.APP}.action.HFP_CALL_DISCOVER"
    private const val REPLY = "${NativeGameTransportRules.APP}.action.HFP_CALL_DISCOVERED"
    private const val VERSION = 1
    private const val TIMEOUT_MS = 3000L
    internal fun validRequest(version: Int, nonce: String?, keys: Set<String>) =
        version == VERSION && NativeGameTransportRules.validNonce(nonce) && keys == setOf("version", "nonce")
    internal fun validReply(version: Int, nonce: String?, expected: String, keys: Set<String>) =
        version == VERSION && nonce == expected && NativeGameTransportRules.validNonce(nonce) &&
            keys == setOf("version", "nonce", "control")

    fun install(context: Context, control: HfpCallSuppression, moduleUid: Int): AutoCloseable? {
        if (Build.VERSION.SDK_INT != 37 || context.packageName !in NativeGameTransportRules.BLUETOOTH_PACKAGES) return null
        val valid = runCatching {
            val pm = context.packageManager
            val app = pm.getApplicationInfo(NativeGameTransportRules.APP, 0)
            val permission = pm.getPermissionInfo(NativeGameTransportRules.PERMISSION, 0)
            pm.getApplicationInfo(context.packageName, 0).uid == 1002 && moduleUid >= 10000 && app.uid == moduleUid &&
                permission.packageName == NativeGameTransportRules.APP && permission.protection == PermissionInfo.PROTECTION_SIGNATURE &&
                pm.checkPermission(NativeGameTransportRules.PERMISSION, NativeGameTransportRules.APP) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!valid) return null
        val closed = AtomicBoolean()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (closed.get() || intent.action != REQUEST ||
                    !NativeGameTransportRules.requestSender(sentFromUid, sentFromPackage, moduleUid)) return
                runCatching {
                    val extras = intent.extras ?: return
                    val nonce = extras.getString("nonce")
                    if (!validRequest(extras.getInt("version", -1), nonce, extras.keySet())) return
                    val reply = Intent(REPLY).setPackage(NativeGameTransportRules.APP).putExtras(Bundle().apply {
                        putInt("version", VERSION); putString("nonce", nonce); putBinder("control", control)
                    })
                    context.sendBroadcast(reply, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                }
            }
        }
        return try {
            context.registerReceiver(receiver, IntentFilter(REQUEST), NativeGameTransportRules.PERMISSION,
                Handler(context.mainLooper), Context.RECEIVER_EXPORTED)
            AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    runCatching { context.unregisterReceiver(receiver) }
                    control.close()
                }
            }
        } catch (_: RuntimeException) { control.close(); null }
    }

    suspend fun discover(context: Context): IHfpCallControl? {
        if (Build.VERSION.SDK_INT != 37 || context.packageName != NativeGameTransportRules.APP) return null
        val app = context.applicationContext
        val bluetooth = NativeGameTransportRules.BLUETOOTH_PACKAGES.firstOrNull { name ->
            runCatching { app.packageManager.getApplicationInfo(name, 0).let { it.uid == 1002 && it.enabled } }.getOrDefault(false)
        } ?: return null
        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val nonce = UUID.randomUUID().toString()
            val finished = AtomicBoolean()
            val registered = AtomicBoolean()
            lateinit var receiver: BroadcastReceiver
            lateinit var timeout: Runnable
            fun finish(value: IHfpCallControl?) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacks(timeout)
                if (registered.compareAndSet(true, false)) runCatching { app.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(value)
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != REPLY || !NativeGameTransportRules.replySender(sentFromUid, sentFromPackage, bluetooth)) return
                    runCatching {
                        val extras = intent.extras ?: return
                        if (!validReply(extras.getInt("version", -1), extras.getString("nonce"), nonce, extras.keySet())) return
                        val binder = extras.getBinder("control") ?: return
                        if (!binder.isBinderAlive) return
                        finish(IHfpCallControl.Stub.asInterface(binder))
                    }
                }
            }
            timeout = Runnable { finish(null) }
            continuation.invokeOnCancellation { finish(null) }
            // Serialize registration against cancellation; no receiver may survive a cancelled discovery.
            if (!handler.post {
                if (finished.get()) return@post
                try {
                    app.registerReceiver(receiver, IntentFilter(REPLY), "android.permission.BLUETOOTH_PRIVILEGED",
                        handler, Context.RECEIVER_EXPORTED)
                    registered.set(true)
                    if (finished.get()) {
                        if (registered.compareAndSet(true, false)) runCatching { app.unregisterReceiver(receiver) }
                        return@post
                    }
                    if (!handler.postDelayed(timeout, TIMEOUT_MS)) {
                        finish(null)
                        return@post
                    }
                    app.sendBroadcast(Intent(REQUEST).setPackage(bluetooth).putExtra("version", VERSION).putExtra("nonce", nonce),
                        null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                } catch (_: RuntimeException) { finish(null) }
            }) finish(null)
        }
    }
}
