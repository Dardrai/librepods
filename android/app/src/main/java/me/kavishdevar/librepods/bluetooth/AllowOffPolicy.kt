/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

object AllowOffPolicy {
    fun allowed(requested: Int?, confirmed: Int?, listeningMode: Int?, supportsListening: Boolean,
                supportsAdaptive: Boolean, publicFirmwareMajor: Int?): Boolean {
        when (requested?.takeIf { it in 1..2 } ?: confirmed) {
            1 -> return true
            2 -> return false
        }
        if (listeningMode == 1) return true
        if (!supportsListening) return false
        return !supportsAdaptive || (publicFirmwareMajor != null && publicFirmwareMajor in 1..6)
    }
}
