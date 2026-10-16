/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.NearbyAirPods
import me.kavishdevar.librepods.data.AirPodsModels

/** Presentation fallbacks do not determine device identity or capabilities. */
internal object OverlayArtwork {
    fun isHeadphones(modelNumber: String?) = modelNumber in setOf("A2096", "A3184", "A3454")
    fun island(modelNumber: String?) = if (isHeadphones(modelNumber)) R.drawable.overlay_airpods_max else R.drawable.overlay_airpods_buds
    // shortcut: invitation models share the bundled case art; replace when model-specific art is available.
    fun pairing(modelId: Int) = AirPodsModels.models.firstOrNull { it.name == NearbyAirPods.modelNames[modelId] }
        ?.budCaseRes ?: R.drawable.airpods_pro_2
}
