/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

internal class IslandListeningMode {
    private var previous: Int? = null

    fun observe(mode: Int): Boolean {
        if (mode !in 1..4) return false
        val changed = previous != null && previous != mode
        previous = mode
        return changed
    }

    fun reset() { previous = null }
}
