/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Calls run serially in the Bluetooth process; implementations must return without waiting for HCI. */
internal interface GameNativeGateway {
    fun query(): LongArray
    fun submit(operation: Int, ownerSerial: Long, expectedPeer: ByteArray,
        expectedMediaGeneration: Long, arguments: LongArray): LongArray
    fun poll(ownerSerial: Long, nativeSerial: Long): LongArray
    fun restore(ownerSerial: Long, holdSerial: Long): LongArray
    fun beginClock(ownerSerial: Long, holdSerial: Long, expectedPeer: ByteArray,
        expectedMediaGeneration: Long, arguments: LongArray): LongArray
    fun cancelClock(ownerSerial: Long, holdSerial: Long): LongArray
    fun cancelOwner(ownerSerial: Long)
}

internal object NativeGameTransportRules {
    const val APP = "me.kavishdevar.librepods"
    val BLUETOOTH_PACKAGES = listOf("com.google.android.bluetooth", "com.android.bluetooth")
    const val PERMISSION = "$APP.permission.NATIVE_GAME_CONTROL"
    const val REQUEST = "$APP.action.NATIVE_GAME_REQUEST"
    const val REPLY = "$APP.action.NATIVE_GAME_REPLY"
    const val VERSION = 1
    const val QUERY = 0
    const val PROBE_FLUSH = 1
    const val POLL = 2
    const val CANCEL = 3
    const val RELEASE = 4
    const val READ_FLUSH = 5
    const val PREPARE = 6
    const val RESTORE = 7
    const val BEGIN_CLOCK = 8
    const val CANCEL_CLOCK = 9
    const val OK = 0
    const val STALE = 1
    const val BUSY = 2
    const val UNAVAILABLE = 3
    const val INVALID = 4
    const val CLOSED = 5
    const val MAX_VALUES = 128
    const val MAX_PENDING = 8

    fun requestSender(uid: Int, packageName: String?, expectedUid: Int) =
        expectedUid >= 10000 && uid == expectedUid && packageName == APP
    fun replySender(uid: Int, packageName: String?, expectedPackage: String) = uid == 1002 &&
        expectedPackage in BLUETOOTH_PACKAGES && packageName == expectedPackage
    fun validNonce(value: String?) = value != null && value.length == 36 &&
        value.indices.all { index -> if (index in intArrayOf(8, 13, 18, 23)) value[index] == '-'
            else value[index] in '0'..'9' || value[index] in 'a'..'f' }
    fun validPeer(peer: ByteArray?) = peer?.size == 6 && peer.any { it.toInt() != 0 } &&
        peer.any { it.toInt() != -1 }
    fun timeoutRestoreSerial(operation: Int, nativeSerial: Long, holdSerial: Long): Long = when (operation) {
        POLL -> nativeSerial.takeIf { it > 0 } ?: 0
        RESTORE, BEGIN_CLOCK, CANCEL_CLOCK -> holdSerial.takeIf { it > 0 } ?: 0
        else -> 0 // No receipt means no authority over an existing shared-app hold.
    }
    fun validPayload(operation: Int, peer: ByteArray?, generation: Long, nativeSerial: Long,
        encoderGeneration: Long = 0, aclHandle: Int = 0, holdSerial: Long = 0,
        interval: Int = 0, drift: Int = 0) =
        when (operation) {
            QUERY, CANCEL, RELEASE -> peer == null && generation == 0L && nativeSerial == 0L &&
                encoderGeneration == 0L && aclHandle == 0 && holdSerial == 0L && interval == 0 && drift == 0
            PROBE_FLUSH, READ_FLUSH, PREPARE -> validPeer(peer) && generation > 0 && nativeSerial == 0L &&
                encoderGeneration > 0 && aclHandle in 0..0xeff && holdSerial == 0L && interval == 0 && drift == 0
            POLL -> peer == null && generation == 0L && nativeSerial > 0 &&
                encoderGeneration == 0L && aclHandle == 0 && holdSerial == 0L && interval == 0 && drift == 0
            RESTORE, CANCEL_CLOCK -> peer == null && generation == 0L && nativeSerial == 0L &&
                encoderGeneration == 0L && aclHandle == 0 && holdSerial > 0 && interval == 0 && drift == 0
            BEGIN_CLOCK -> validPeer(peer) && generation > 0 && nativeSerial == 0L &&
                encoderGeneration > 0 && aclHandle in 0..0xeff && holdSerial > 0 &&
                interval in setOf(9, 11) && drift in 1..500
            else -> false
        }
}

/** Replay/ownership checks are independent of Android's permission and shared-sender checks. */
internal class NativeGameSession(private val processNonce: String) {
    private var owner: Any? = null
    private var clientNonce: String? = null
    private var lastRequest = 0L

    fun accept(token: Any, nonce: String, request: Long, operation: Int, serverNonce: String?): Int {
        if (!NativeGameTransportRules.validNonce(nonce) || request <= 0) return NativeGameTransportRules.INVALID
        if (owner == null && operation != NativeGameTransportRules.QUERY) return NativeGameTransportRules.STALE
        if (owner != null && (owner != token || clientNonce != nonce)) return NativeGameTransportRules.BUSY
        if (request <= lastRequest) return NativeGameTransportRules.STALE
        if (operation != NativeGameTransportRules.QUERY && serverNonce != processNonce)
            return NativeGameTransportRules.STALE
        if (owner == null) {
            owner = token
            clientNonce = nonce
        }
        lastRequest = request
        return NativeGameTransportRules.OK
    }

    fun release(token: Any): Boolean {
        if (owner != token) return false
        owner = null
        clientNonce = null
        lastRequest = 0
        return true
    }
}
