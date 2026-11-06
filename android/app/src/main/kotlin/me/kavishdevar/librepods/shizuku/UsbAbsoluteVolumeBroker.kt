/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

/** Native USB volume policy is type-wide on SDK37, so its owner must live in system_server. */
internal class UsbAbsoluteVolumeBroker(
    private val policy: Policy,
    private val now: () -> Long,
    private val firmwareContractVersion: Int? = null,
) : AutoCloseable {
    data class Target(val deviceId: Int, val type: Int, val address: String, val boardSerial: String,
        val connection: Long)
    data class Route(val target: Target, val soleUsbOutput: Boolean, val selected: Boolean,
        val normalMode: Boolean, val variableVolume: Boolean)
    data class Authority(val contractVersion: Int, val advertised: Boolean, val connection: Long,
        val expiresAt: Long, val physicalLevelKnown: Boolean, val relaySuppressed: Boolean)
    data class Result(val active: Boolean, val reason: String)
    interface OwnedPolicy : AutoCloseable {
        /** Includes the registration, native driving stream and selected USB device. */
        fun stillOwned(): Boolean
    }
    interface Policy {
        val lifecycleSafe: Boolean
        fun route(target: Target): Route?
        /** Must acquire registration and native unity policy together, with Binder-death cleanup. */
        fun acquire(target: Target, deadline: Long): OwnedPolicy?
    }
    private data class Held(val owner: Any, val target: Target, var deadline: Long, val policy: OwnedPolicy)
    private var held: Held? = null
    private var failed = false
    private var closed = false

    @Synchronized fun acquire(owner: Any, target: Target, authority: Authority,
        explicitlyEnabled: Boolean, sdk: Int, helperUid: Int): Result {
        if (held != null) return Result(false, if (failed) "cleanup_failed" else "busy")
        val error = admission(target, authority, explicitlyEnabled, sdk, helperUid)
        if (error != null) return Result(false, error)
        val owned = runCatching { policy.acquire(target, authority.expiresAt) }.getOrNull()
            ?: return Result(false, "native_policy_unavailable")
        held = Held(owner, target, authority.expiresAt, owned)
        // Losing the device while acquiring must not leave a type-wide unity policy behind.
        if (!runCatching { owned.stillOwned() }.getOrDefault(false) || !routeCurrent(target, false) || now() >= authority.expiresAt) {
            release(owner)
            return Result(false, if (failed) "cleanup_failed" else "stale")
        }
        return Result(true, "active")
    }

    private fun admission(target: Target, authority: Authority, enabled: Boolean, sdk: Int, uid: Int): String? = when {
        failed -> "cleanup_failed"
        closed -> "closed"
        !enabled -> "disabled"
        uid != 0 -> "root_required"
        sdk != 37 -> "platform_unavailable"
        firmwareContractVersion == null -> "firmware_contract_unavailable"
        authority.contractVersion != firmwareContractVersion || !authority.advertised -> "firmware_unavailable"
        target.deviceId <= 0 || target.type !in USB_TYPES || !target.boardSerial.matches(SERIAL) ||
            target.connection <= 0 || target.connection != authority.connection -> "stale"
        !authority.physicalLevelKnown || !authority.relaySuppressed -> "physical_volume_unconfirmed"
        authority.expiresAt <= now() || authority.expiresAt - now() > MAX_LEASE_MS -> "stale"
        !routeCurrent(target) -> "route_unavailable"
        !policy.lifecycleSafe -> "native_policy_unavailable"
        else -> null
    }

    private fun routeCurrent(target: Target, requireVariable: Boolean = true): Boolean = runCatching {
        policy.route(target)?.let { it.target == target && it.soleUsbOutput && it.selected &&
            it.normalMode && (!requireVariable || it.variableVolume) } == true
    }.getOrDefault(false)

    /** An expired firmware lease cannot be renewed after the fact. */
    @Synchronized fun renew(owner: Any, target: Target, authority: Authority): Result {
        val current = held ?: return Result(false, "inactive")
        if (current.owner !== owner) return Result(false, "wrong_owner")
        if (failed) return Result(false, "cleanup_failed")
        if (now() >= current.deadline || target != current.target || !runCatching { current.policy.stillOwned() }.getOrDefault(false)) {
            release(owner)
            return Result(false, if (failed) "cleanup_failed" else "stale")
        }
        // The already acquired registration is no longer variable-volume.
        val route = runCatching { policy.route(target) }.getOrNull()
        val valid = firmwareContractVersion != null && authority.contractVersion == firmwareContractVersion &&
            authority.advertised && authority.connection == target.connection &&
            authority.relaySuppressed && authority.expiresAt > now() && authority.expiresAt - now() <= MAX_LEASE_MS &&
            route?.target == target && route.soleUsbOutput && route.selected && route.normalMode
        if (!valid) {
            release(owner)
            return Result(false, if (failed) "cleanup_failed" else "stale")
        }
        current.deadline = authority.expiresAt
        return Result(true, "active")
    }

    /** Called on Binder death, device/session loss, audio-server restart and lease expiry. */
    @Synchronized fun release(owner: Any): Boolean {
        val current = held?.takeIf { it.owner === owner } ?: return false
        failed = runCatching { current.policy.close() }.isFailure
        if (!failed) held = null
        return !failed
    }

    @Synchronized fun poll(): Result {
        if (failed) return Result(false, "cleanup_failed")
        val current = held ?: return Result(false, if (failed) "cleanup_failed" else "inactive")
        val route = runCatching { policy.route(current.target) }.getOrNull()
        if (now() >= current.deadline || !runCatching { current.policy.stillOwned() }.getOrDefault(false) || route?.target != current.target ||
            !route.soleUsbOutput || !route.selected || !route.normalMode) {
            release(current.owner)
            return Result(false, if (failed) "cleanup_failed" else "stale")
        }
        return Result(true, "active")
    }

    @Synchronized override fun close() { closed = true; held?.owner?.let(::release) }

    companion object {
        const val MAX_LEASE_MS = 5_000L
        private val SERIAL = Regex("[A-Za-z0-9_-]{1,64}")
        // AudioDeviceInfo.TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_USB_HEADSET.
        val USB_TYPES = setOf(11, 12, 22)
    }
}
