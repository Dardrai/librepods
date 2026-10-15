/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.utils

/** The app may restore volume only while its own last write still owns it. */
class ConversationVolume {
    var speaking = false
        private set
    private var original: Int? = null
    private var expected: Int? = null
    private var target: Int? = null
    private var overridden = false

    fun begin(current: Int, maximum: Int, percent: Int, relative: Boolean, stage: Int) {
        if (!speaking) {
            if (original == null || expected != current) original = current
            expected = current
            overridden = false
        }
        speaking = true
        if (overridden) return
        val cap = (if (relative) checkNotNull(original) else maximum) * percent.coerceIn(0, 100) / 100
        target = minOf(current, if (stage == 2) cap * 60 / 100 else cap).coerceIn(0, maximum)
    }

    fun end(current: Int): Boolean {
        if (!speaking) return false
        speaking = false
        if (overridden || original == null || expected != current) {
            original = null
            target = null
            return false
        }
        target = original
        return true
    }

    fun step(current: Int): Int? {
        if (target == null || original == null) return null
        if (expected != current) {
            overridden = true
            original = null
            target = null
            return null
        }
        if (current == target) {
            if (!speaking) { original = null; target = null }
            return null
        }
        return (current + if (current < checkNotNull(target)) 1 else -1).also { expected = it }
    }
}
