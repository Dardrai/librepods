/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.media.AudioAttributes
import java.lang.ref.WeakReference

/** A single pending allocation witness; it must not keep a player or codec alive. */
internal class TidalBinauralTrackOwner {
    data class Owner(val renderer: Any, val codec: Any)
    private var sink: WeakReference<Any>? = null
    private var renderer: WeakReference<Any>? = null
    private var codec: WeakReference<Any>? = null

    @Synchronized fun remember(sink: Any, renderer: Any, codec: Any) {
        this.sink = WeakReference(sink)
        this.renderer = WeakReference(renderer)
        this.codec = WeakReference(codec)
    }

    @Synchronized fun forget(sink: Any?) {
        if (this.sink?.get() === sink) {
            this.sink = null; renderer = null; codec = null
        }
    }

    @Synchronized fun get(sink: Any?): Owner? {
        if (sink == null || this.sink?.get() !== sink) return null
        return Owner(renderer?.get() ?: return null, codec?.get() ?: return null)
    }
}

/** Copy the track's attributes, never mutate Media3's shared cached attributes. */
internal fun tidalBinauralAttributes(original: AudioAttributes, scopedProvider: Any?, provider: Any?,
    tunneling: Boolean): AudioAttributes =
    if (scopedProvider == null || scopedProvider !== provider || tunneling ||
        (original.isContentSpatialized && original.spatializationBehavior == AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER))
        original
    else AudioAttributes.Builder(original).setIsContentSpatialized(true)
        .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER).build()
