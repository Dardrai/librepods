/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.database.Converters
import org.junit.Assert.*
import org.junit.Test

class NativeGameModePreferencesTest {
    @Test fun absentLegacyPreferenceKeepsGameModeOff() {
        assertFalse(Converters.bytesToAppleSettings(byteArrayOf(0xa0.toByte())).nativeGameModeEnabled)
    }

    @Test fun gameModeRoundTripPreservesOtherLocalChoices() {
        val settings = AppleSettings(hostAutoPause = false, earDetectionEnabled = false,
            nativeGameModeEnabled = true, callTakeoverEnabled = false)
        assertEquals(settings, Converters.bytesToAppleSettings(Converters.appleSettingsToBytes(settings)))
    }
}
