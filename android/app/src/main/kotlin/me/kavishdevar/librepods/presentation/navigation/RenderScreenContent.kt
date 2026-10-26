package me.kavishdevar.librepods.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavBackStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.data.updates.updates
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.screens.DeviceListScreen
import me.kavishdevar.librepods.presentation.screens.OpenSourceLicensesScreen
import me.kavishdevar.librepods.presentation.screens.ReleaseNotesScreen
import me.kavishdevar.librepods.presentation.screens.apple.HeadphoneAccommodationsScreen
import me.kavishdevar.librepods.presentation.screens.apple.DongleAccommodationsScreen
import me.kavishdevar.librepods.presentation.screens.apple.DongleTransparencyScreen
import me.kavishdevar.librepods.presentation.screens.apple.AccessibilitySettingsScreen
import me.kavishdevar.librepods.presentation.screens.apple.AppleSettingsRoute
import me.kavishdevar.librepods.presentation.screens.apple.CallControlScreen
import me.kavishdevar.librepods.presentation.screens.apple.DebugRoute
import me.kavishdevar.librepods.presentation.screens.apple.EqualizerRoute
import me.kavishdevar.librepods.presentation.screens.apple.HeadTrackingScreen
import me.kavishdevar.librepods.presentation.screens.apple.HearingAidAdjustmentsScreen
import me.kavishdevar.librepods.presentation.screens.apple.HearingAidScreen
import me.kavishdevar.librepods.presentation.screens.apple.HearingProtectionScreen
import me.kavishdevar.librepods.presentation.screens.apple.HeartRateRoute
import me.kavishdevar.librepods.presentation.screens.apple.LongPress
import me.kavishdevar.librepods.presentation.screens.apple.MicrophoneSettingsRoute
import me.kavishdevar.librepods.presentation.screens.apple.RecordingScreenRoute
import me.kavishdevar.librepods.presentation.screens.apple.RenameScreen
import me.kavishdevar.librepods.presentation.screens.apple.TransparencySettingsScreen
import me.kavishdevar.librepods.presentation.screens.apple.UpdateHearingTestRoute
import me.kavishdevar.librepods.presentation.screens.apple.VersionScreen
import me.kavishdevar.librepods.presentation.screens.onboarding.OnboardingScreen
import me.kavishdevar.librepods.presentation.screens.settings.AppAccessibilitySettingsRoute
import me.kavishdevar.librepods.presentation.screens.settings.AppAppearanceSettingsRoute
import me.kavishdevar.librepods.presentation.screens.settings.AppSettingsScreen
import me.kavishdevar.librepods.presentation.screens.settings.DongleSettingsScreen
import me.kavishdevar.librepods.presentation.screens.settings.BLESettingsScreenRoute
import me.kavishdevar.librepods.presentation.screens.settings.IslandSettingsRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandCompactCustomizeRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandCompactSizeAndPositionRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandExpandedCustomizeRoute
import me.kavishdevar.librepods.presentation.screens.settings.island.IslandExpandedSizeAndPositionRoute
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.repository.RecordingRepository


@Composable
private fun createAppleViewModelFactory(
    device: AppleDevice,
    appDataRepository: AppDataRepository,
    recordingRepository: RecordingRepository,
    heartRateRepository: HeartRateRepository
): ViewModelProvider.Factory {
    val application = LocalContext.current.applicationContext as LibrePodsApplication
    return viewModelFactory {
        initializer {
            AppleViewModel(
                device = device,
                appDataRepository = appDataRepository,
                recordingRepository = recordingRepository,
                heartRateRepository = heartRateRepository,
                audioControllerProvider = { application.experimentalAudioControllers[device.macAddress] },
                gameModeControllerProvider = { application.nativeGameModeControllers[device.macAddress] },
            )
        }
    }
}

@Composable
fun RenderScreenContent(
    screen: Screen,
    backStack: NavBackStack<Screen>,
    backRequests: Channel<CompletableDeferred<Unit>>,
    devices: Map<MacAddress, Device<*, *, *>>,
    appDataRepository: AppDataRepository,
    recordingRepository: RecordingRepository,
    heartRateRepository: HeartRateRepository,
    showReleaseNotes: Boolean,
    updatesShown: () -> Unit,
    onboardingComplete: () -> Unit,
    refreshDeviceSettings: suspend (AppleDevice) -> DeviceSettingsRefreshResult
) {
    val navigate: (Screen) -> Unit = { target -> if (target !in backStack) backStack.add(target) } // prevents multiple clicks while transitioning

    val navigateBack: (() -> Unit)? = if (backStack.indexOf(screen) > 0) {
        {
            val completed = CompletableDeferred<Unit>()
            backRequests.trySend(completed)
        }
    } else null

    when (screen) {
        Screen.Onboarding -> {
            OnboardingScreen {
                onboardingComplete()
//                if (showReleaseNotes) navigate(Screen.ReleaseNotes) else
                navigate(Screen.DeviceList)
                backStack.remove(screen)
            }
        }

        Screen.DeviceList -> {
            DeviceListScreen(
                devices = devices,
                navigateToAppSettings = { navigate(Screen.AppSettings) },
                navigateToDongleSettings = { navigate(Screen.DongleSettings) },
                navigateToDevice = { macAddress ->
                    when (devices[macAddress]) {
                        is AppleDevice -> navigate(Screen.AppleScreen(macAddress))
                        else -> {}
                    }
                },
                navigateToHeartRateScreen = { macAddress ->
                    when (devices[macAddress]) {
                        is AppleDevice -> navigate(Screen.HeartRate(macAddress))
                        else -> {}
                    }
                }
            )
        }

        is Screen.AppleScreen -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )

            AppleSettingsRoute(
                viewModel = appleViewModel,
                refreshDeviceSettings = refreshDeviceSettings,
                navigateBack = navigateBack,
                navigateToRename = { navigate(Screen.Rename(screen.macAddress)) },
                navigateToHearingProtection = { navigate(Screen.HearingProtection(screen.macAddress)) },
                navigateToHearingAid = { navigate(Screen.HearingAid(screen.macAddress)) },
                navigateToLeftLongPress = { navigate(Screen.LongPress(screen.macAddress, "left")) },
                navigateToRightLongPress = { navigate(Screen.LongPress(screen.macAddress, "right")) },
                navigateToEqualizer = { navigate(Screen.Equalizer(screen.macAddress)) },
                navigateToHeadTracking = { navigate(Screen.HeadTracking(screen.macAddress)) },
                navigateToAccessibility = { navigate(Screen.Accessibility(screen.macAddress)) },
                navigateToVersion = { navigate(Screen.VersionInfo(screen.macAddress)) },
                navigateToCallControlScreen = { navigate(Screen.CallControl(screen.macAddress, it)) },
                navigateToMicrophoneSettings = { navigate(Screen.MicrophoneSettings(screen.macAddress)) },
                navigateToRecordingScreen = { navigate(Screen.Recording(screen.macAddress)) },
                navigateToHeartRateScreen = { navigate(Screen.HeartRate(screen.macAddress)) },
                navigateToDebugScreen = { navigate(Screen.Debug(screen.macAddress)) }
            )
        }

        is Screen.Rename -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            RenameScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
            )
        }

        Screen.AppSettings -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)

            AppSettingsScreen(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack,
                navigateToOpenSourceLicenses = { navigate(Screen.OpenSourceLicenses) },
                navigateToReleaseNotesScreen = { navigate(Screen.ReleaseNotes) },
                navigateToBleSettingsScreen = { navigate(Screen.BLESettings) },
                navigateToAppAppearanceScreen = { navigate(Screen.AppAppearance) },
                navigateToAppAccessibilityScreen = { navigate(Screen.AppAccessibilitySettings) },
                navigateToIslandSettingsScreen = { navigate(Screen.IslandSettings) },
                navigateToDongleSettings = { navigate(Screen.DongleSettings) }
            )
        }

        Screen.CaseSound -> {
            LaunchedEffect(Unit) {
                backStack.remove(Screen.CaseSound)
                navigate(Screen.AppSettings)
            }
        }

        Screen.DongleSettings -> DongleSettingsScreen(navigateBack, { navigate(Screen.DongleAccommodations) })
        Screen.DongleAccommodations -> DongleAccommodationsScreen(navigateBack) { navigate(Screen.DongleTransparency) }
        Screen.DongleTransparency -> DongleTransparencyScreen(navigateBack)

        is Screen.HeadTracking -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            HeadTrackingScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
            )
        }

        is Screen.Accessibility -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )

            AccessibilitySettingsScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
                navigateToHeadphoneAccommodations = {
                    navigate(Screen.HeadphoneAccommodations(screen.macAddress))
                }
            )
        }

        is Screen.HeadphoneAccommodations -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(key = "${screen.macAddress.value}:${device.connectionNumber}", factory = factory)
            HeadphoneAccommodationsScreen(appleViewModel, navigateBack, refreshDeviceSettings) {
                navigate(Screen.TransparencyCustomization(screen.macAddress))
            }
        }

        is Screen.TransparencyCustomization -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            TransparencySettingsScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
            )
        }

        is Screen.HearingAid -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )

            HearingAidScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
                navigateToHearingAidAdjustments = { navigate(Screen.HearingAidAdjustments(screen.macAddress)) },
                navigateToHearingTest = { navigate(Screen.UpdateHearingTest(screen.macAddress)) }
            )
        }

        is Screen.HearingAidAdjustments -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            HearingAidAdjustmentsScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
            )
        }

        Screen.OpenSourceLicenses -> OpenSourceLicensesScreen(navigateBack)

        is Screen.UpdateHearingTest -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            UpdateHearingTestRoute(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
            )
        }

        is Screen.VersionInfo -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            VersionScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack
            )
        }

        is Screen.HearingProtection -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            HearingProtectionScreen(
                viewModel = appleViewModel,
                navigateBack = navigateBack,
            )
        }

        is Screen.Purchase -> {
            LaunchedEffect(Unit) {
                backStack.remove(Screen.Purchase)
                navigate(Screen.AppSettings)
            }
        }

        is Screen.Equalizer -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            EqualizerRoute(
                viewModel = appleViewModel,
                navigateBack = navigateBack
            )
        }

        is Screen.LongPress -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            LongPress(
                viewModel = appleViewModel,
                name = screen.bud,
                navigateBack = navigateBack,
            )
        }

        is Screen.CallControl -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            CallControlScreen(
                viewModel = appleViewModel,
                action = screen.action,
                navigateBack = navigateBack,
            )
        }

        is Screen.MicrophoneSettings -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            MicrophoneSettingsRoute(
                viewModel = appleViewModel,
                navigateBack = navigateBack
            )
        }

        is Screen.ReleaseNotes -> {
            ReleaseNotesScreen(
                updates = updates,
                releaseNotesShown = {
                    if (showReleaseNotes) {
                        navigate(Screen.DeviceList)
                        backStack.remove(screen)
                        updatesShown()
                    } else {
                        backStack.removeAt(backStack.lastIndex)
                    }
                }
            )
        }

        is Screen.Recording -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            RecordingScreenRoute(
                viewModel = appleViewModel,
                navigateBack = navigateBack
            )
        }

        is Screen.Debug -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            DebugRoute(
                viewModel = appleViewModel,
                navigateBack = navigateBack
            )
        }

        is Screen.BLESettings -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            BLESettingsScreenRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }

        is Screen.HeartRate -> {
            val device = devices[screen.macAddress] as? AppleDevice ?: return
            val factory = createAppleViewModelFactory(device, appDataRepository, recordingRepository, heartRateRepository)
            val appleViewModel: AppleViewModel = viewModel(
                key = "${screen.macAddress.value}:${device.connectionNumber}",
                factory = factory
            )
            HeartRateRoute(
                viewModel = appleViewModel,
                navigateBack = navigateBack
            )
        }

        Screen.AppAccessibilitySettings -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            AppAccessibilitySettingsRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }

        Screen.AppAppearance -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            AppAppearanceSettingsRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }

        Screen.IslandSettings -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            IslandSettingsRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack,

                navigateToExpandedSizeAndPosition = { navigate(Screen.IslandExpandedSizeAndPosition) },
                navigateToCompactSizeAndPosition = { navigate(Screen.IslandCompactSizeAndPosition) },
                navigateToExpandedCustomize = { navigate(Screen.IslandExpandedCustomize) },
                navigateToCompactCustomize = { navigate(Screen.IslandCompactCustomize) }
            )
        }

        Screen.IslandCompactCustomize -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            IslandCompactCustomizeRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }

        Screen.IslandCompactSizeAndPosition -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            IslandCompactSizeAndPositionRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }

        Screen.IslandExpandedSizeAndPosition -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            IslandExpandedSizeAndPositionRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }

        Screen.IslandExpandedCustomize -> {
            val factory = viewModelFactory {
                initializer { AppSettingsViewModel(appDataRepository = appDataRepository) }
            }
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = factory)
            IslandExpandedCustomizeRoute(
                viewModel = appSettingsViewModel,
                navigateBack = navigateBack
            )
        }
    }
}
