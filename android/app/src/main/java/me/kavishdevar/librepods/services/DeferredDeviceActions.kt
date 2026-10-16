/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.services

/** A queued gesture from a previous call cannot answer a later call. */
internal class RingingCallSession {
    private var ringing = false
    var token: Any? = null
        private set

    fun update(isRinging: Boolean) {
        if (isRinging && !ringing) token = Any()
        if (!isRinging) token = null
        ringing = isRinging
    }

    fun consume(expected: Any): Boolean {
        if (token !== expected) return false
        token = null
        return true
    }
}

/** A delayed media resume belongs to one insertion and its original transport. */
internal class DevicePlaybackRequest(val address: String, val connection: Any, now: Long) {
    private val deadline = now + 15_000
    private var pending = true

    fun cancel() { pending = false }

    fun current(address: String?, connection: Any?, now: Long): Boolean =
        pending && this.address == address && this.connection === connection && now < deadline

    fun consume(address: String?, connection: Any?, now: Long): Boolean {
        if (!current(address, connection, now)) return false
        pending = false
        return true
    }
}

internal fun awaitingDevicePlayback(startedWaiting: Boolean, pending: DevicePlaybackRequest?,
    address: String?, connection: Any?, now: Long): Boolean =
    startedWaiting || pending?.current(address, connection, now) == true
