/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.bluetooth.BluetoothDevice
import java.lang.reflect.Method
import java.util.Locale

internal data class HfpBatteryHookMethods(val exchange: Method, val response: Method) {
    companion object {
        fun discover(state: Class<*>, native: Class<*>) = HfpBatteryHookMethods(
            exactEldMethod(state, "processAtXapl", Void.TYPE,
                listOf(Array<Any>::class.java, BluetoothDevice::class.java)),
            exactEldMethod(native, "atResponseString", Boolean::class.javaPrimitiveType!!,
                listOf(BluetoothDevice::class.java, String::class.java)))
    }
}

/** Keep the legacy battery exchange local to the identified Pro 3's synchronous XAPL response. */
internal class HfpBatteryNegotiation {
    private val activePeer = ThreadLocal<String>()

    private fun supported(arguments: Array<*>?): Boolean {
        if (arguments?.size != 2 || arguments[1] !is Int || (arguments[1] as Int) < 0 ||
            (arguments[1] as Int) and 2 == 0) return false
        val identity = arguments[0] as? String ?: return false
        if (identity.length > 32) return false
        val parts = identity.trim().split('-')
        return parts.size == 3 && parts[0].matches(Regex("[0-9a-fA-F]{1,4}")) &&
            parts[1].matches(Regex("[0-9a-fA-F]{1,4}")) && parts[2].matches(Regex("[0-9a-fA-F]{1,8}")) &&
            parts[0].toIntOrNull(16) == 0x4c && parts[1].toIntOrNull(16) == 0x2027
    }

    fun <T> withXapl(arguments: Array<*>?, peer: String?, block: () -> T): T {
        val previous = activePeer.get()
        val address = peer?.uppercase(Locale.ROOT)
        if (address != null && HfpCallSuppression.validPeer(address) && supported(arguments)) activePeer.set(address)
        else activePeer.remove()
        return try { block() } finally {
            if (previous == null) activePeer.remove() else activePeer.set(previous)
        }
    }

    fun response(peer: String?, value: String?): String? =
        if (value == "+XAPL=iPhone,2" && peer != null && activePeer.get()?.equals(peer, ignoreCase = true) == true)
            "+XAPL=iPhone,0" else value
}
