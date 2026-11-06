/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Each obligation is cleared only after its corresponding operation confirms completion. */
internal class UsbVolumePolicyDebt {
    var registration = false
    var mapping = false
    var native = false
    fun clear(registry: Int, removeCache: () -> Unit, disableNative: () -> Boolean, reset: () -> Boolean) {
        require(registry in 0..2)
        if (registration && registry != 1) {
            check(registry == 0 || !native && !mapping) { "USB policy ownership changed before cleanup" }
            registration = false
        }
        if (mapping) { removeCache(); mapping = false }
        if (native) { check(disableNative()) { "Native USB volume policy did not disable" }; native = false }
        if (registration) { check(reset()) { "USB callback registration did not reset" }; registration = false }
    }
}
