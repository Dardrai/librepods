/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import me.kavishdevar.librepods.BuildConfig
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A fixed-operation bridge into the already loaded Bluetooth native library. */
@android.annotation.SuppressLint("NewApi")
internal class NativeGameTransportServer private constructor(
    private val context: Context,
    private val gateway: GameNativeGateway,
    private val moduleUid: Int,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val queued = AtomicInteger()
    private val diagnosticLines = AtomicInteger()
    private val thread = HandlerThread("LibrePodsGameControl").apply { start() }
    private val handler = Handler(thread.looper)
    private val nonce = UUID.randomUUID().toString()
    private val session = NativeGameSession(nonce)
    private var lease: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var ownerSerial = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uid = getSentFromUid()
            val sender = getSentFromPackage()
            val allowed = NativeGameTransportRules.requestSender(uid, sender, moduleUid)
            diagnostic("receive uid=$uid package=${sender?.take(96)} expectedUid=$moduleUid allowed=$allowed closed=${closed.get()}")
            if (closed.get() || intent.action != NativeGameTransportRules.REQUEST || !allowed) return
            val request = try { decode(intent) } catch (_: RuntimeException) { null }
            if (request == null) { diagnostic("decode-rejected"); return }
            if (queued.incrementAndGet() > NativeGameTransportRules.MAX_PENDING) {
                queued.decrementAndGet()
                diagnostic("queue-full")
                return
            }
            if (!handler.post {
                try { if (!closed.get()) handle(request) } finally { queued.decrementAndGet() }
            }) queued.decrementAndGet()
        }
    }

    private fun diagnostic(stage: String) {
        if (BuildConfig.DEBUG && diagnosticLines.getAndIncrement() < 64)
            Log.i("LibrePodsGameTransport", "server $stage")
    }

    private data class Request(val token: IBinder, val client: String, val id: Long,
        val operation: Int, val server: String?, val peer: ByteArray?, val generation: Long,
        val nativeSerial: Long, val encoderGeneration: Long, val aclHandle: Int,
        val holdSerial: Long, val interval: Int, val drift: Int)

    private fun decode(intent: Intent): Request? {
        val extras = intent.extras ?: return null
        if (extras.keySet().any { it !in KEYS } || extras.getInt("version", -1) != NativeGameTransportRules.VERSION)
            return null
        val token = extras.getBinder("lease") ?: return null
        val client = extras.getString("client") ?: return null
        val id = extras.getLong("request", 0)
        val operation = extras.getInt("operation", -1)
        val server = extras.getString("server")
        val peer = extras.getByteArray("peer")
        val generation = extras.getLong("generation", 0)
        val serial = extras.getLong("serial", 0)
        val encoderGeneration = extras.getLong("encoder", 0)
        val aclHandle = extras.getInt("acl", 0)
        val holdSerial = extras.getLong("hold", 0)
        val interval = extras.getInt("interval", 0)
        val drift = extras.getInt("drift", 0)
        if (operation in setOf(NativeGameTransportRules.PROBE_FLUSH, NativeGameTransportRules.READ_FLUSH,
                NativeGameTransportRules.PREPARE, NativeGameTransportRules.BEGIN_CLOCK) &&
            (!extras.containsKey("encoder") || !extras.containsKey("acl"))) return null
        if (!NativeGameTransportRules.validNonce(client) || id <= 0 ||
            !NativeGameTransportRules.validPayload(operation, peer, generation, serial,
                encoderGeneration, aclHandle, holdSerial, interval, drift)) return null
        return Request(token, client, id, operation, server, peer, generation, serial, encoderGeneration, aclHandle,
            holdSerial, interval, drift)
    }

    private fun handle(request: Request) {
        diagnostic("dispatch operation=${request.operation}")
        if (lease == request.token && !request.token.isBinderAlive) { retire(request.token); return }
        val admission = session.accept(request.token, request.client, request.id, request.operation, request.server)
        if (admission != NativeGameTransportRules.OK) {
            diagnostic("session-rejected status=$admission")
            reply(request, admission); return
        }
        if (lease == null) {
            val recipient = IBinder.DeathRecipient { handler.post { retire(request.token) } }
            try { request.token.linkToDeath(recipient, 0) } catch (_: android.os.RemoteException) {
                session.release(request.token)
                reply(request, NativeGameTransportRules.CLOSED)
                return
            }
            lease = request.token
            death = recipient
            do { ownerSerial = SecureRandom().nextLong() and Long.MAX_VALUE } while (ownerSerial == 0L)
            if (!request.token.isBinderAlive) { retire(request.token); return }
        }
        try {
            val values = when (request.operation) {
                NativeGameTransportRules.QUERY -> gateway.query()
                NativeGameTransportRules.PROBE_FLUSH, NativeGameTransportRules.READ_FLUSH,
                NativeGameTransportRules.PREPARE -> gateway.submit(
                    when (request.operation) { NativeGameTransportRules.READ_FLUSH -> 0
                        NativeGameTransportRules.PREPARE -> 2; else -> 1 }, ownerSerial,
                    request.peer!!.copyOf(), request.generation,
                    longArrayOf(request.encoderGeneration, request.aclHandle.toLong()))
                NativeGameTransportRules.POLL -> gateway.poll(ownerSerial, request.nativeSerial)
                NativeGameTransportRules.RESTORE -> gateway.restore(ownerSerial, request.holdSerial)
                NativeGameTransportRules.CANCEL_CLOCK -> gateway.cancelClock(ownerSerial, request.holdSerial)
                NativeGameTransportRules.BEGIN_CLOCK -> gateway.beginClock(ownerSerial, request.holdSerial,
                    request.peer!!.copyOf(), request.generation, longArrayOf(request.encoderGeneration,
                        request.aclHandle.toLong(), request.interval.toLong(), request.drift.toLong()))
                NativeGameTransportRules.CANCEL, NativeGameTransportRules.RELEASE -> {
                    gateway.cancelOwner(ownerSerial)
                    longArrayOf()
                }
                else -> return
            }
            diagnostic("native-return operation=${request.operation} values=${values.size}")
            if (values.size > NativeGameTransportRules.MAX_VALUES) {
                reply(request, NativeGameTransportRules.UNAVAILABLE)
                retire(request.token)
                return
            }
            reply(request, NativeGameTransportRules.OK, values)
            if (request.operation == NativeGameTransportRules.RELEASE) retire(request.token)
        } catch (failure: RuntimeException) {
            diagnostic("native-exception type=${failure.javaClass.simpleName}")
            reply(request, NativeGameTransportRules.UNAVAILABLE)
            retire(request.token)
        } catch (failure: LinkageError) {
            diagnostic("native-linkage type=${failure.javaClass.simpleName}")
            reply(request, NativeGameTransportRules.UNAVAILABLE)
            retire(request.token)
        }
    }

    private fun reply(request: Request, status: Int, values: LongArray = longArrayOf()) {
        if (closed.get()) return
        val reply = Intent(NativeGameTransportRules.REPLY).setPackage(NativeGameTransportRules.APP)
            .putExtra("version", NativeGameTransportRules.VERSION).putExtra("client", request.client)
            .putExtra("request", request.id).putExtra("server", nonce)
            .putExtra("status", status).putExtra("values", values)
        try {
            diagnostic("reply-send status=$status from=${context.packageName}")
            context.sendBroadcast(reply, null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } catch (failure: RuntimeException) {
            diagnostic("reply-exception type=${failure.javaClass.simpleName}")
            retire(request.token)
        }
    }

    private fun retire(token: IBinder) {
        if (lease != token) return
        val serial = ownerSerial
        lease = null
        ownerSerial = 0
        session.release(token)
        death?.let { try { token.unlinkToDeath(it, 0) } catch (_: java.util.NoSuchElementException) {} }
        death = null
        try { gateway.cancelOwner(serial) } catch (_: RuntimeException) {} catch (_: LinkageError) {}
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        handler.post {
            lease?.let(::retire)
            thread.quitSafely()
        }
    }

    companion object {
        private val KEYS = setOf("version", "client", "request", "operation", "server", "lease",
            "peer", "generation", "serial", "encoder", "acl", "hold", "interval", "drift")

        fun install(context: Context, gateway: GameNativeGateway, expectedModuleUid: Int): NativeGameTransportServer? {
            if (Build.VERSION.SDK_INT < 34 || context.packageName !in NativeGameTransportRules.BLUETOOTH_PACKAGES)
                return null
            val packageManager = context.packageManager
            val valid = try {
                val app = packageManager.getApplicationInfo(NativeGameTransportRules.APP, 0)
                val permission = packageManager.getPermissionInfo(NativeGameTransportRules.PERMISSION, 0)
                val bluetooth = packageManager.getApplicationInfo(context.packageName, 0)
                bluetooth.uid == 1002 && bluetooth.enabled && expectedModuleUid >= 10000 && app.uid == expectedModuleUid &&
                    permission.packageName == NativeGameTransportRules.APP &&
                    permission.protection == PermissionInfo.PROTECTION_SIGNATURE &&
                    packageManager.checkPermission(NativeGameTransportRules.PERMISSION,
                        NativeGameTransportRules.APP) == PackageManager.PERMISSION_GRANTED
            } catch (_: PackageManager.NameNotFoundException) { false } catch (_: RuntimeException) { false }
            if (!valid) return null
            val server = NativeGameTransportServer(context, gateway, expectedModuleUid)
            try {
                context.registerReceiver(server.receiver, IntentFilter(NativeGameTransportRules.REQUEST),
                    NativeGameTransportRules.PERMISSION, Handler(context.mainLooper), Context.RECEIVER_EXPORTED)
                server.diagnostic("registered package=${context.packageName} opPackage=${context.opPackageName} " +
                    "uid=${android.os.Process.myUid()} expectedUid=$expectedModuleUid")
            } catch (_: RuntimeException) {
                server.close()
                return null
            }
            return server
        }
    }
}
