/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Process
import me.kavishdevar.librepods.shizuku.IHfpCallControl
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class HfpCallHookMethods(val create: Method, val destroy: Method, val connect: Method,
    val incoming: Method, val cleanup: Method, val current: Method) {
    companion object {
        fun discover(adapter: Class<*>, headset: Class<*>): HfpCallHookMethods {
            val device = BluetoothDevice::class.java
            return HfpCallHookMethods(
                exactEldMethod(adapter, "onCreate", Void.TYPE, emptyList()),
                exactEldMethod(adapter, "onDestroy", Void.TYPE, emptyList()),
                exactEldMethod(headset, "connect", Boolean::class.javaPrimitiveType!!, listOf(device)),
                exactEldMethod(headset, "okToAcceptConnection", Boolean::class.javaPrimitiveType!!,
                    listOf(device, Boolean::class.javaPrimitiveType!!)),
                exactEldMethod(headset, "cleanup", Void.TYPE, emptyList()),
                exactEldMethod(headset, "getHeadsetService", headset, emptyList(), static = true))
        }
    }
}

/** Only cached local ownership is consulted by the Bluetooth connection hooks. */
internal class HfpSuppressionState(private val retired: (Lease) -> Unit = {}) {
    class Lease(val peer: String, val root: Any, val app: Any, val service: Any, val generation: Long) {
        val alive = AtomicBoolean(true)
    }
    data class Receipt(val active: Boolean, val generation: Long = 0, val peer: String = "",
        val reason: String, val owned: Boolean = false, val released: Boolean = false)
    private data class State(val active: Lease? = null, val previous: Lease? = null,
        val reason: String = "idle", val closed: Boolean = false)
    private val value = AtomicReference(State())

    fun active(): Lease? = value.get().active
    fun state(root: Any): Receipt {
        val current = value.get()
        val lease = current.active ?: current.previous
        return if (lease == null) Receipt(false, reason = if (current.closed) "closed" else "idle")
        else Receipt(current.active != null, lease.generation, lease.peer,
            if (lease.root == root) current.reason else "different-owner", lease.root == root)
    }
    fun publish(lease: Lease): Receipt {
        while (true) {
            val old = value.get()
            if (old.closed) return Receipt(false, reason = "closed")
            old.active?.let {
                return if (it.root == lease.root && it.app == lease.app && it.peer == lease.peer && it.service === lease.service)
                    Receipt(true, it.generation, it.peer, old.reason, true)
                else Receipt(true, it.generation, it.peer, "busy")
            }
            if (old.previous?.root == lease.root) return state(lease.root)
            val next = if (lease.alive.get()) State(lease, old.previous, "active")
                else State(previous = lease, reason = "owner-died")
            if (!value.compareAndSet(old, next)) continue
            if (!lease.alive.get()) retire(lease, "owner-died")
            return state(lease.root)
        }
    }
    fun retire(lease: Lease, reason: String): Boolean {
        while (true) {
            val old = value.get()
            if (old.active !== lease) return false
            if (value.compareAndSet(old, State(previous = lease, reason = reason, closed = old.closed))) {
                retired(lease)
                return true
            }
        }
    }
    fun release(root: Any): Receipt {
        while (true) {
            val old = value.get()
            old.active?.let {
                // A stale caller must not interpret its release as permission to reconnect
                // over a replacement call's lease.
                if (it.root != root) return Receipt(true, it.generation, it.peer, "different-owner")
                if (!retire(it, "released")) return@let
            }
            val now = value.get()
            if (now.active != null) continue
            val previous = now.previous
            return if (previous?.root == root) Receipt(false, previous.generation, previous.peer,
                now.reason, true, true) else Receipt(false, reason = "unknown-owner")
        }
    }
    fun blockConnect(service: Any?, peer: String?, internalCaller: Boolean): Boolean {
        val lease = active() ?: return false
        if (lease.service !== service || !lease.peer.equals(peer, ignoreCase = true)) return false
        if (!lease.alive.get()) { retire(lease, "owner-died"); return false }
        if (!internalCaller) { retire(lease, "manual-connect"); return false }
        return value.get().active === lease
    }
    fun blockIncoming(service: Any?, peer: String?): Boolean {
        val lease = active() ?: return false
        if (lease.service !== service || !lease.peer.equals(peer, ignoreCase = true)) return false
        if (!lease.alive.get()) { retire(lease, "owner-died"); return false }
        return value.get().active === lease
    }
    fun serviceStopped(service: Any?) { active()?.takeIf { it.service === service }?.let { retire(it, "service-stopped") } }
    fun close() {
        while (true) {
            val old = value.get()
            if (old.closed) return
            val lease = old.active
            if (!value.compareAndSet(old, State(previous = lease ?: old.previous, reason = "closed", closed = true))) continue
            if (lease != null) retired(lease)
            return
        }
    }
}

/** Root owns the temporary exclusion; no saved Bluetooth preference is changed. */
@SuppressLint("MissingPermission")
internal class HfpCallSuppression(private val context: Context, private val currentService: () -> Any?) :
    IHfpCallControl.Stub(), AutoCloseable {
    private data class Links(val root: IBinder, val app: IBinder, val death: IBinder.DeathRecipient)
    private val handler = Handler(context.mainLooper)
    private val links = ConcurrentHashMap<HfpSuppressionState.Lease, Links>()
    private val closed = AtomicBoolean()
    internal val leases = HfpSuppressionState { lease -> handler.post { unlink(lease) } }

    private fun enforceRoot() { check(rootCaller(Binder.getCallingUid())) { "Root call owner required" } }
    private fun HfpSuppressionState.Receipt.bundle() = Bundle().apply {
        putBoolean("active", active); putLong("generation", generation); putString("peer", peer)
        putString("reason", reason); putBoolean("owned", owned); putBoolean("released", released)
    }
    @Synchronized override fun acquire(address: String, rootOwner: IBinder, appOwner: IBinder): Bundle {
        enforceRoot()
        val peer = address.uppercase(Locale.ROOT)
        val existing = leases.state(rootOwner)
        if (existing.owned || existing.active) return existing.bundle()
        if (closed.get() || !validPeer(peer) || rootOwner == appOwner)
            return HfpSuppressionState.Receipt(false, reason = "invalid-owner").bundle()
        val service = currentService() ?: return HfpSuppressionState.Receipt(false, reason = "service-unavailable").bundle()
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val device = adapter?.bondedDevices?.singleOrNull { it.address.equals(peer, ignoreCase = true) }
        if (adapter?.isEnabled != true || device?.bondState != BluetoothDevice.BOND_BONDED)
            return HfpSuppressionState.Receipt(false, reason = "selected-pair-unavailable").bundle()
        val generation = generations.incrementAndGet()
        if (generation <= 0) return HfpSuppressionState.Receipt(false, reason = "generation-exhausted").bundle()
        val lease = HfpSuppressionState.Lease(peer, rootOwner, appOwner, service, generation)
        val death = IBinder.DeathRecipient {
            lease.alive.set(false)
            leases.retire(lease, "owner-died")
        }
        links[lease] = Links(rootOwner, appOwner, death)
        try {
            rootOwner.linkToDeath(death, 0)
            appOwner.linkToDeath(death, 0)
            if (!rootOwner.isBinderAlive || !appOwner.isBinderAlive || currentService() !== service || closed.get())
                lease.alive.set(false)
            return leases.publish(lease).bundle()
        } catch (_: android.os.RemoteException) {
            lease.alive.set(false)
            return leases.publish(lease).bundle()
        } finally {
            if (leases.active() !== lease) unlink(lease)
        }
    }
    override fun state(rootOwner: IBinder): Bundle {
        enforceRoot()
        leases.active()?.let { lease ->
            if (!(lease.root as IBinder).isBinderAlive || !(lease.app as IBinder).isBinderAlive) {
                lease.alive.set(false); leases.retire(lease, "owner-died")
            } else if (runCatching(currentService).getOrNull() !== lease.service) leases.retire(lease, "service-changed")
        }
        return leases.state(rootOwner).bundle()
    }
    override fun release(rootOwner: IBinder): Bundle { enforceRoot(); return leases.release(rootOwner).bundle() }
    fun blockConnect(service: Any?, peer: String?) = leases.blockConnect(service, peer, Binder.getCallingUid() == Process.myUid())
    fun blockIncoming(service: Any?, peer: String?) = leases.blockIncoming(service, peer)
    fun serviceStopped(service: Any?) = leases.serviceStopped(service)
    private fun unlink(lease: HfpSuppressionState.Lease) {
        links.remove(lease)?.let { link ->
            runCatching { link.root.unlinkToDeath(link.death, 0) }
            runCatching { link.app.unlinkToDeath(link.death, 0) }
        }
    }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        leases.close()
        links.keys.forEach(::unlink)
    }
    companion object {
        private val generations = AtomicLong()
        internal fun rootCaller(uid: Int) = uid == 0
        internal fun validPeer(peer: String) = peer.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) &&
            peer != "00:00:00:00:00:00" && peer != "FF:FF:FF:FF:FF:FF"
    }
}
