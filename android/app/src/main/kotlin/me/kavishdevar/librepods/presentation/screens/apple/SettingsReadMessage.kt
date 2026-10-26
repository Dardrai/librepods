/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import androidx.annotation.StringRes
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.SettingsReadStatus

@StringRes
internal fun settingsReadMessage(status: SettingsReadStatus, connected: Boolean): Int = when {
    !connected -> R.string.settings_connect_airpods
    status == SettingsReadStatus.UNAVAILABLE || status == SettingsReadStatus.AVAILABLE -> R.string.accommodations_unavailable
    status == SettingsReadStatus.READING -> R.string.hearing_modes_unknown
    else -> R.string.settings_waiting
}
