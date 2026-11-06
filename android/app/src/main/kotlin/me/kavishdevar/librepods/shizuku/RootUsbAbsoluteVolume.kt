/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.content.Context

/** The signed app supplies firmware receipts; only system_server can prove native-policy ownership. */
internal class RootUsbAbsoluteVolume(private val verifyAddress: (Int, String) -> String?) : AutoCloseable {
    constructor(context: Context) : this({ device, serial -> UsbAbsoluteVolumeInspection.verifiedAddress(context, device, serial) })
    private data class Proof(val device: Int, val serial: String, val address: String, val control: Long, val generation: Long,
        val usb: Long, val token: Long, val level: Int, val expires: Long)
    private data class Held(val owner: IPlaybackRecoveryOwner, val token: IBinder,
        val policy: IUsbAbsoluteVolumePolicy, val proof: Proof, val death: IBinder.DeathRecipient)
    private var held: Held? = null
    private var closed = false
    private fun result(reason: String, clean: Boolean = true) = Bundle().apply {
        putInt("version", 1); putBoolean("active", false); putString("reason", reason); putBoolean("cleanup_complete", clean)
    }
    private fun proof(value: Bundle, requireConfirmed: Boolean = true): Proof {
        check(Process.myUid() == 0 && value.getBoolean("enabled", false))
        check(value.keySet() == KEYS)
        check(value.getInt("version", 0) == 1 && value.getInt("capabilities", 0) in 0..65535 &&
            value.getInt("capabilities", 0) and 128 != 0)
        val flags = value.getInt("flags", 0)
        check(flags and 24 == 24 && flags and 31.inv() == 0)
        if (requireConfirmed) check(flags and 1 != 0 && flags and 6 == 0)
        val now = SystemClock.elapsedRealtime()
        val observed = value.getLong("observed_at", -1)
        val ttl = value.getLong("ttl_ms", 0)
        check(observed in 0..now && ttl in 251..5_000 && now - observed < ttl - 250)
        val level = value.getInt("level", -1)
        check((if (flags and 1 != 0) level in 0..127 else level == 255) &&
            value.getLong("observation_revision", 0) in 1..0xffff_ffffL)
        return Proof(value.getInt("device_id", 0), value.getString("board_serial") ?: "", value.getString("device_address") ?: "",
            value.getLong("control_session", 0), value.getLong("connection_generation", 0),
            value.getLong("usb_generation", 0), value.getLong("firmware_token", 0), level, observed + ttl - 250).also {
            check(it.device > 0 && it.serial.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                UsbAbsoluteVolumeInspection.validAddress(it.address) && it.control > 0 &&
                it.generation in 1..0xffff_ffffL && it.usb in 1..0xffff_ffffL && it.token in 1..0xffff_ffffL)
        }
    }
    private fun same(a: Proof, b: Proof) = a.copy(level = b.level, expires = b.expires) == b
    private fun verified(proof: Proof) = runCatching { verifyAddress(proof.device, proof.serial) == proof.address }.getOrDefault(false)
    @Synchronized fun inspect(policy: IUsbAbsoluteVolumePolicy, device: Int, serial: String): Bundle {
        if (closed || Process.myUid() != 0) return result("root_identity_unavailable")
        val address = runCatching { verifyAddress(device, serial) }.getOrNull()
            ?.takeIf(UsbAbsoluteVolumeInspection::validAddress) ?: return result("root_identity_unavailable")
        return policy.inspect(device, serial, address).apply {
            putInt("device_id", device); putString("device_address", address)
        }
    }
    @Synchronized fun start(owner: IPlaybackRecoveryOwner, policy: IUsbAbsoluteVolumePolicy,
        events: IUsbAbsoluteVolumeEvents, value: Bundle): Bundle {
        if (closed || held != null) return result("busy")
        val proof = runCatching { proof(value) }.getOrNull() ?: return result("firmware_authority_unavailable")
        if (!verified(proof)) return result("root_identity_unavailable")
        if (!runCatching { owner.isCurrent }.getOrDefault(false)) return result("stale")
        val token = Binder()
        val death = IBinder.DeathRecipient { synchronized(this) { release(owner) } }
        try { owner.asBinder().linkToDeath(death, 0) } catch (_: android.os.RemoteException) { return result("stale") }
        val lease = Held(owner, token, policy, proof, death)
        held = lease
        return try {
            val response = policy.acquire(token, proof.device, proof.serial, proof.address, proof.control, proof.expires, proof.level, events)
            if (!response.getBoolean("active", false) || !owner.isCurrent) {
                release(owner)
                if (held != null) result("cleanup_failed", false)
                else if (response.getBoolean("active", false)) result("stale") else response
            } else response
        } catch (_: Exception) { release(owner); result("native_policy_unavailable", held == null) }
    }
    @Synchronized fun renew(owner: IPlaybackRecoveryOwner, value: Bundle): Bundle {
        val current = held?.takeIf { it.owner.asBinder() == owner.asBinder() } ?: return result("wrong_owner")
        val next = runCatching { proof(value, requireConfirmed = false) }.getOrNull()
        if (next == null || !same(current.proof, next) || !runCatching { owner.isCurrent }.getOrDefault(false)) {
            release(owner); return result("stale", held == null)
        }
        if (!verified(next)) { release(owner); return result("root_identity_unavailable", held == null) }
        return try {
            val response = current.policy.renew(current.token, next.control, next.expires, next.address)
            if (response.getBoolean("active", false)) { held = current.copy(proof = next); response }
            else { release(owner); if (held != null) result("cleanup_failed", false) else response }
        } catch (_: Exception) { release(owner); result("native_policy_unavailable", held == null) }
    }
    @Synchronized fun notify(owner: IPlaybackRecoveryOwner, value: Bundle, acknowledgedRequestSequence: Long = 0): Bundle {
        val current = held?.takeIf { it.owner.asBinder() == owner.asBinder() } ?: return result("wrong_owner")
        if (acknowledgedRequestSequence < 0) return result("invalid_host_request")
        val next = runCatching { proof(value) }.getOrNull()
        if (next == null || !same(current.proof, next) || !runCatching { owner.isCurrent }.getOrDefault(false)) {
            release(owner); return result("stale", held == null)
        }
        if (!verified(next)) { release(owner); return result("root_identity_unavailable", held == null) }
        return try {
            val response = current.policy.notifyPhysicalVolume(current.token, next.control, next.level, next.address, acknowledgedRequestSequence)
            if (response.getBoolean("active", false)) response
            else { release(owner); if (held != null) result("cleanup_failed", false) else response }
        }
        catch (_: Exception) { release(owner); result("native_policy_unavailable", held == null) }
    }
    @Synchronized fun release(owner: IPlaybackRecoveryOwner): Bundle {
        val current = held?.takeIf { it.owner.asBinder() == owner.asBinder() } ?: return result("inactive")
        val response = runCatching { current.policy.release(current.token) }.getOrElse { return result("cleanup_failed", false) }
        if (response.getBoolean("cleanup_complete", false)) {
            held = null; runCatching { owner.asBinder().unlinkToDeath(current.death, 0) }
        }
        return response
    }
    @Synchronized override fun close() { closed = true; held?.owner?.let(::release) }
    companion object {
        private val KEYS = setOf("enabled", "device_id", "board_serial", "device_address", "control_session", "connection_generation",
            "capabilities", "version", "flags", "level", "observation_revision", "firmware_token", "ttl_ms", "usb_generation", "observed_at")
    }
}
