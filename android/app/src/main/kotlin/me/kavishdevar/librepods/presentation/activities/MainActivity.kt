/*
    LibrePods - AirPods liberated from Appleâ€™s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.activities

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.bluetooth.nearbyDevicesPermissions
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.NightTheme
import me.kavishdevar.librepods.presentation.navigation.NavigationRoot
import me.kavishdevar.librepods.presentation.screens.onboarding.OnboardingScreen
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.services.LibrePodsService
import me.kavishdevar.librepods.utils.XposedState

class MainActivity: ComponentActivity() {
    companion object {
        init {
            if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                System.loadLibrary("fluoride_hooks")
            }
        }
    }

    val appDataRepository by lazy { (application as LibrePodsApplication).appDataRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by appDataRepository.settings.collectAsState()

            val systemDarkTheme = isSystemInDarkTheme()

            val darkTheme = when (settings.nightMode) {
                NightTheme.Dark -> true
                NightTheme.Light -> false
                NightTheme.System -> systemDarkTheme
            }

            val view = LocalView.current
            val window = (view.context as Activity).window

            LaunchedEffect(darkTheme) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }

            RefreshRatePreference(settings.useHighestRefreshRate)

            CompositionLocalProvider(
                LocalDebugMode provides settings.debugMode
            ) {
                LibrePodsTheme(
                    designSystem = settings.designSystem,
                    overrideMaterialColor = settings.overrideMaterialColor,
                    accessibilitySettings = settings.accessibilitySettings,
                    fontSettings = settings.fontSettings,
                    darkTheme = darkTheme
                ) {
//                For demo screenshots
//                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
//                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

                    Main()
                }
            }
        }
    }

}

@Composable
internal fun RefreshRatePreference(enabled: Boolean) {
    val context = LocalContext.current
    val window = (LocalView.current.context as Activity).window
    LaunchedEffect(enabled, window) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) display.supportedModes.maxOfOrNull { it.refreshRate } ?: 0f else 0f
        }
    }
}

@Composable
fun Main(
    appDataRepository: AppDataRepository = (LocalContext.current.applicationContext as LibrePodsApplication).appDataRepository,
) {
    val context = LocalContext.current
    val librepodsService = remember { mutableStateOf<LibrePodsService?>(null) }

    val appState by appDataRepository.state.collectAsState()


    val onboardingComplete = appState.hasCompletedOnboarding
    val bluetoothPermissions = rememberMultiplePermissionsState(nearbyDevicesPermissions)
    val bluetoothGranted = bluetoothPermissions.allPermissionsGranted

    val currentVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug")
    val lastVersionShown = appState.lastVersionShown

    val releaseNotesShown = lastVersionShown == currentVersion

    val devicesState = remember(librepodsService.value) {
        librepodsService.value?.devices ?: MutableStateFlow(emptyMap())
    }.collectAsState()

    DisposableEffect(onboardingComplete, bluetoothGranted) {
        if (!onboardingComplete || !bluetoothGranted) {
            librepodsService.value = null
            onDispose { }
        } else {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as LibrePodsService.LocalBinder
                    librepodsService.value = binder.getService()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    librepodsService.value = null
                }
            }

            var bound = false
            try {
                context.startForegroundService(Intent(context, LibrePodsService::class.java))
                bound = context.bindService(Intent(context, LibrePodsService::class.java), connection, Context.BIND_AUTO_CREATE)
            } catch (denied: SecurityException) {
                Log.w("Main", "Bluetooth permission changed before service startup", denied)
            }

            onDispose {
                try {
                    if (bound) context.unbindService(connection)
                    librepodsService.value = null
                } catch(e: Exception) {
                    Log.w("Main", "Error while unbinding service: $e")
                }
            }
        }
    }

    if (onboardingComplete && !bluetoothGranted) {
        OnboardingScreen(initialItem = 3) { }
        return
    }

    NavigationRoot(
        showReleaseNotes = !releaseNotesShown,
        updatesShown = {
            appDataRepository.updateState {
                it.copy(lastVersionShown = currentVersion)
            }
        },
        showOnboarding = !onboardingComplete,
        onboardingComplete = {
            appDataRepository.updateState {
                it.copy(hasCompletedOnboarding = true)
            }
        },
        devicesState = devicesState,
        refreshDeviceSettings = { device ->
            librepodsService.value?.refreshDeviceSettings(device) ?: DeviceSettingsRefreshResult.UNAVAILABLE
        }
    )
}
