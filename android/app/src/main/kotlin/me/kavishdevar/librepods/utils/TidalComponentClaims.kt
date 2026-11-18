/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Posts must enqueue on the captured player's Looper, never execute inline. */
internal class TidalComponentClaims<T : Any>(private val post: (T, () -> Unit) -> Boolean,
    private val valid: (T, TidalComponentClaimPermit) -> Boolean,
    private val send: (T, String) -> Unit, private val report: (String) -> Unit,
    private val accepted: (T, TidalComponentClaimPermit) -> Unit = { _, _ -> }) {
    private class Attempt<T : Any>(val owner: T, val permit: TidalComponentClaimPermit,
        val relay: TidalComponentClaimRelay<T>) { @Volatile var accepted = false }
    private val lock = Any()
    private var owner: T? = null
    private var permit: TidalComponentClaimPermit? = null
    private var attempt: Attempt<T>? = null
    private var ended = false
    private val used = mutableSetOf<String>()

    fun owner(value: T?) = synchronized(lock) {
        if (ended || owner === value) return@synchronized
        clearLocked(); owner = value; scheduleLocked()
    }
    fun selectOwner(value: T, sameOwnership: (T, T) -> Boolean): T = synchronized(lock) {
        owner?.takeIf { sameOwnership(it, value) }?.let { return@synchronized it }
        owner(value)
        value
    }
    /** A seek can flush only the track; restoring its verified codec never reuses a spent nonce. */
    fun restoreOwner(value: T, current: () -> Boolean): Boolean = synchronized(lock) {
        if (ended || owner != null || !current()) false
        else { owner(value); true }
    }
    fun retryOwner(value: T) = synchronized(lock) {
        if (!ended && owner === value && attempt == null) scheduleLocked()
    }
    fun permit(value: TidalComponentClaimPermit?) = synchronized(lock) {
        if (ended || permit == value) return@synchronized
        clearLocked(); permit = value; scheduleLocked()
    }
    fun granted(value: T): TidalComponentClaimPermit? = synchronized(lock) {
        attempt?.takeIf { it.owner === value && it.accepted }?.permit
    }
    fun end() = synchronized(lock) { ended = true; owner = null; permit = null; clearLocked() }

    /** Only a fresh root-issued grant starts a new bounded nonce budget. */
    fun renew(value: T?) = synchronized(lock) {
        clearLocked(); owner = value; permit = null; used.clear(); ended = false
    }

    private fun clearLocked() {
        val old = attempt ?: return
        attempt = null
        old.relay.retire()
        if (!post(old.owner) { old.relay.clear() }) report("component_clear_dispatch_failed")
    }
    private fun scheduleLocked() {
        val captured = owner ?: return
        val granted = permit ?: return
        if (granted.nonce in used) return
        if (used.size >= 64) { ended = true; report("component_claim_budget_exhausted"); return }
        if (!post(captured) {
            try {
                if (!valid(captured, granted)) return@post
                val next = synchronized(lock) {
                    if (ended || owner !== captured || permit != granted || !used.add(granted.nonce)) return@post
                    Attempt(captured, granted, TidalComponentClaimRelay<T>(onClear = {
                        report("component_clear_api_accepted=$it native_retirement_check=required")
                    }, send)).also { attempt = it }
                }
                val sent = next.relay.attempt(captured, granted.nonce)
                val stillCurrent = synchronized(lock) {
                    if (sent && !ended && attempt === next && owner === captured && permit == granted) {
                        next.accepted = true; accepted(captured, granted); true
                    } else false
                }
                if (stillCurrent) {
                    report("component_claim_api_accepted=true native_binding_check=required pose_authorized=false")
                }
            } catch (_: Exception) {
                end(); report("component_claim_api_accepted=false native_binding_check=required pose_authorized=false")
            }
        }) { ended = true; report("component_claim_dispatch_failed") }
    }
}
