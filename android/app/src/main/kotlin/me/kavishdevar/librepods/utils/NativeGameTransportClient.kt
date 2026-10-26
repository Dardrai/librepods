/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import me.kavishdevar.librepods.BuildConfig
import kotlin.coroutines.resume

/** One lease per app process, shared by all callers. It never outlives that process. */
@android.annotation.SuppressLint("NewApi")
internal class NativeGameTransportClient private constructor(private val context: Context,
    private val bluetoothPackage: String) : AutoCloseable {
    data class Reply(val status: Int, val values: LongArray = longArrayOf()) {
        val successful get() = status == NativeGameTransportRules.OK
    }
    private data class Pending(val continuation: CancellableContinuation<Reply>, val operation: Int,
        val server: String?, val timeout: Runnable, val restoreSerial: Long)
    private val guard = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val token = Binder()
    private val clientNonce = UUID.randomUUID().toString()
    private val pending = mutableMapOf<Long, Pending>()
    private val diagnosticLines = AtomicInteger()
    private var serverNonce: String? = null
    private var requestId = 0L
    private var lastQueryReplyId = 0L
    private var closed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uid = getSentFromUid()
            val sender = getSentFromPackage()
            val allowed = NativeGameTransportRules.replySender(uid, sender, bluetoothPackage)
            diagnostic("receive uid=$uid package=${sender?.take(96)} expectedPackage=$bluetoothPackage allowed=$allowed")
            if (intent.action != NativeGameTransportRules.REPLY || !allowed) return
            try { accept(intent) } catch (failure: RuntimeException) {
                diagnostic("reply-exception type=${failure.javaClass.simpleName}")
            }
        }
    }

    init {
        context.registerReceiver(receiver, IntentFilter(NativeGameTransportRules.REPLY),
            "android.permission.BLUETOOTH_PRIVILEGED", handler, Context.RECEIVER_EXPORTED)
        diagnostic("registered package=${context.packageName} opPackage=${context.opPackageName} " +
            "target=$bluetoothPackage uid=${android.os.Process.myUid()}")
    }

    private fun diagnostic(stage: String) {
        if (BuildConfig.DEBUG && diagnosticLines.getAndIncrement() < 64)
            Log.i("LibrePodsGameTransport", "client $stage")
    }

    suspend fun query() = request(NativeGameTransportRules.QUERY)
    suspend fun readFlush(peer: ByteArray, mediaGeneration: Long, encoderGeneration: Long, aclHandle: Int) =
        request(NativeGameTransportRules.READ_FLUSH, peer, mediaGeneration, encoderGeneration, aclHandle)
    suspend fun probeFlush(peer: ByteArray, mediaGeneration: Long, encoderGeneration: Long, aclHandle: Int) =
        request(NativeGameTransportRules.PROBE_FLUSH, peer, mediaGeneration, encoderGeneration, aclHandle)
    suspend fun poll(nativeSerial: Long) = request(NativeGameTransportRules.POLL, nativeSerial = nativeSerial)
    suspend fun cancel() = request(NativeGameTransportRules.CANCEL)
    suspend fun prepare(peer: ByteArray, mediaGeneration: Long, encoderGeneration: Long, aclHandle: Int) =
        request(NativeGameTransportRules.PREPARE, peer, mediaGeneration, encoderGeneration, aclHandle)
    suspend fun restore(holdSerial: Long) = request(NativeGameTransportRules.RESTORE, holdSerial = holdSerial)
    suspend fun cancelClock(holdSerial: Long) = request(NativeGameTransportRules.CANCEL_CLOCK, holdSerial = holdSerial)
    suspend fun beginClock(holdSerial: Long, peer: ByteArray, mediaGeneration: Long, encoderGeneration: Long,
        aclHandle: Int, intervalTenths: Int, driftMs: Int) = request(NativeGameTransportRules.BEGIN_CLOCK,
            peer, mediaGeneration, encoderGeneration, aclHandle, holdSerial = holdSerial,
            interval = intervalTenths, drift = driftMs)

    private suspend fun request(operation: Int, peer: ByteArray? = null, generation: Long = 0,
        encoderGeneration: Long = 0, aclHandle: Int = 0, nativeSerial: Long = 0,
        holdSerial: Long = 0, interval: Int = 0, drift: Int = 0): Reply {
        if (!NativeGameTransportRules.validPayload(operation, peer, generation, nativeSerial,
                encoderGeneration, aclHandle, holdSerial, interval, drift)) return Reply(NativeGameTransportRules.INVALID)
        return suspendCancellableCoroutine { continuation ->
            synchronized(guard) {
                if (closed || requestId == Long.MAX_VALUE) {
                    continuation.resume(Reply(NativeGameTransportRules.CLOSED))
                    return@synchronized
                }
                if (pending.size >= NativeGameTransportRules.MAX_PENDING) {
                    continuation.resume(Reply(NativeGameTransportRules.BUSY))
                    return@synchronized
                }
                if (operation != NativeGameTransportRules.QUERY && serverNonce == null) {
                    continuation.resume(Reply(NativeGameTransportRules.STALE))
                    return@synchronized
                }
                val id = ++requestId
                val timeout = Runnable {
                    val expired = synchronized(guard) { pending.remove(id) } ?: return@Runnable
                    diagnostic("timeout operation=${expired.operation}")
                    if (expired.continuation.isActive)
                        expired.continuation.resume(Reply(NativeGameTransportRules.UNAVAILABLE))
                    sendOwnedRestore(expired.restoreSerial)
                }
                pending[id] = Pending(continuation, operation, serverNonce, timeout,
                    NativeGameTransportRules.timeoutRestoreSerial(operation, nativeSerial, holdSerial))
                handler.postDelayed(timeout, RESPONSE_TIMEOUT_MS)
                continuation.invokeOnCancellation {
                    val cancelled = synchronized(guard) { pending.remove(id) }
                    cancelled?.let {
                        handler.removeCallbacks(it.timeout)
                        sendOwnedRestore(it.restoreSerial)
                    }
                }
                if (!continuation.isActive || pending[id] == null) return@synchronized
                try {
                    send(envelope(operation, id, peer, generation, encoderGeneration, aclHandle, nativeSerial,
                        holdSerial, interval, drift))
                } catch (failure: RuntimeException) {
                    diagnostic("send-exception type=${failure.javaClass.simpleName}")
                    pending.remove(id)
                    handler.removeCallbacks(timeout)
                    if (continuation.isActive) continuation.resume(Reply(NativeGameTransportRules.UNAVAILABLE))
                }
            }
        }
    }

    private fun envelope(operation: Int, id: Long, peer: ByteArray? = null, generation: Long = 0,
        encoderGeneration: Long = 0, aclHandle: Int = 0, nativeSerial: Long = 0,
        holdSerial: Long = 0, interval: Int = 0, drift: Int = 0): Intent {
        val extras = Bundle().apply {
            putInt("version", NativeGameTransportRules.VERSION)
            putString("client", clientNonce)
            putLong("request", id)
            putInt("operation", operation)
            putBinder("lease", token)
            serverNonce?.let { putString("server", it) }
            if (peer != null) {
                putByteArray("peer", peer.copyOf())
                putLong("generation", generation)
                putLong("encoder", encoderGeneration)
                putInt("acl", aclHandle)
            }
            if (nativeSerial != 0L) putLong("serial", nativeSerial)
            if (holdSerial != 0L) putLong("hold", holdSerial)
            if (interval != 0) putInt("interval", interval)
            if (drift != 0) putInt("drift", drift)
        }
        return Intent(NativeGameTransportRules.REQUEST).setPackage(bluetoothPackage)
            .putExtras(extras)
    }

    private fun send(intent: Intent) {
        diagnostic("send operation=${intent.getIntExtra("operation", -1)} target=$bluetoothPackage")
        context.sendBroadcast(intent, null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    private fun accept(intent: Intent) {
        val extras = intent.extras ?: return
        if (extras.keySet().any { it !in REPLY_KEYS } ||
            extras.getInt("version", -1) != NativeGameTransportRules.VERSION ||
            extras.getString("client") != clientNonce) return
        val id = extras.getLong("request", 0)
        val nonce = extras.getString("server")
        val status = extras.getInt("status", -1)
        val values = extras.getLongArray("values") ?: return
        if (!NativeGameTransportRules.validNonce(nonce) || status !in 0..NativeGameTransportRules.CLOSED ||
            values.size > NativeGameTransportRules.MAX_VALUES) return
        val obsolete = mutableListOf<Pending>()
        var acceptedStatus = status
        val accepted = synchronized(guard) {
            if (closed) return@synchronized null
            val current = pending[id] ?: return@synchronized null
            if (current.operation != NativeGameTransportRules.QUERY &&
                (nonce != current.server || nonce != serverNonce)) return@synchronized null
            if (current.operation == NativeGameTransportRules.QUERY && status == NativeGameTransportRules.OK) {
                if (id < lastQueryReplyId) {
                    acceptedStatus = NativeGameTransportRules.STALE
                    return@synchronized pending.remove(id)
                }
                lastQueryReplyId = id
                if (serverNonce != null && serverNonce != nonce) {
                    pending.filterKeys { it != id }.values.forEach(obsolete::add)
                    pending.keys.retainAll(setOf(id))
                }
                serverNonce = nonce
            }
            pending.remove(id)
        } ?: run { diagnostic("reply-unmatched"); return }
        (obsolete + accepted).forEach { handler.removeCallbacks(it.timeout) }
        obsolete.forEach { if (it.continuation.isActive) it.continuation.resume(Reply(NativeGameTransportRules.STALE)) }
        if (accepted.continuation.isActive) accepted.continuation.resume(Reply(acceptedStatus,
            if (acceptedStatus == NativeGameTransportRules.OK) values.copyOf() else longArrayOf()))
        diagnostic("reply-accepted status=$acceptedStatus values=${values.size}")
    }

    private fun sendOwnedRestore(serial: Long) = synchronized(guard) {
        if (serial <= 0 || closed || serverNonce == null || requestId == Long.MAX_VALUE) return@synchronized
        try { send(envelope(NativeGameTransportRules.RESTORE, ++requestId, holdSerial = serial)) }
        catch (_: RuntimeException) {}
    }

    override fun close() {
        val waiting = synchronized(guard) {
            if (closed) return
            if (serverNonce != null && requestId < Long.MAX_VALUE) {
                try { send(envelope(NativeGameTransportRules.RELEASE, ++requestId)) } catch (_: RuntimeException) {}
            }
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        waiting.forEach {
            handler.removeCallbacks(it.timeout)
            if (it.continuation.isActive) it.continuation.resume(Reply(NativeGameTransportRules.CLOSED))
        }
        synchronized(Companion) { if (instance === this) instance = null }
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 3000L
        private val REPLY_KEYS = setOf("version", "client", "request", "server", "status", "values")
        private var instance: NativeGameTransportClient? = null

        @Synchronized fun get(context: Context): NativeGameTransportClient {
            require(Build.VERSION.SDK_INT >= 34 && context.packageName == NativeGameTransportRules.APP)
            return instance ?: run {
                val bluetooth = NativeGameTransportRules.BLUETOOTH_PACKAGES.firstOrNull { name ->
                    try {
                        val info = context.packageManager.getApplicationInfo(name, 0)
                        info.uid == 1002 && info.enabled
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
                    catch (_: RuntimeException) { false }
                } ?: throw IllegalStateException("Bluetooth control package unavailable")
                NativeGameTransportClient(context.applicationContext, bluetooth).also { instance = it }
            }
        }
    }
}
