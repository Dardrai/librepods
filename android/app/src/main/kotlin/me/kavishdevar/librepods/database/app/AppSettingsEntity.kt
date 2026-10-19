package me.kavishdevar.librepods.database.app

import android.bluetooth.le.ScanSettings
import androidx.compose.ui.graphics.Color
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import me.kavishdevar.librepods.data.app.AccessibilitySettings
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.NightTheme

@Entity
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = 0,

    val nightMode: NightTheme = NightTheme.System,
    val designSystem: DesignSystem = DesignSystem.Apple,
    val overrideMaterialColor: Color? = null,
    val fontSettings: FontSettings = FontSettings(),

    val useHighestRefreshRate: Boolean = false,

    val debugMode: Boolean = false,

    val bleScanMode: Int = ScanSettings.SCAN_MODE_BALANCED,
    val bleReportDelay: Long = 0,

    val swipeAnywhereForBack: Boolean = true,

    val accessibilitySettings: AccessibilitySettings = AccessibilitySettings(),
    val islandSettings: IslandSettings = IslandSettings()
)
