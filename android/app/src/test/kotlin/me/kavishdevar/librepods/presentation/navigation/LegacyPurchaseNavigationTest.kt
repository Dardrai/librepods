package me.kavishdevar.librepods.presentation.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavBackStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.repository.RecordingRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyPurchaseNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("DEPRECATION")
    @Test fun legacyPurchaseSubtypeKeepsItsSavedNameAndReturnsToSettings() {
        assertEquals(
            "me.kavishdevar.librepods.presentation.navigation.Screen.Purchase",
            Screen.Purchase.serializer().descriptor.serialName
        )
        val restored: Screen = Screen.Purchase
        val stack = NavBackStack<Screen>(Screen.DeviceList, restored)
        val requests = Channel<CompletableDeferred<Unit>>(Channel.BUFFERED)
        try {
            compose.setContent {
                RenderScreenContent(
                    screen = restored,
                    backStack = stack,
                    backRequests = requests,
                    devices = emptyMap(),
                    appDataRepository = mock(AppDataRepository::class.java),
                    recordingRepository = mock(RecordingRepository::class.java),
                    heartRateRepository = mock(HeartRateRepository::class.java),
                    showReleaseNotes = false,
                    updatesShown = {},
                    onboardingComplete = {},
                    refreshDeviceSettings = { DeviceSettingsRefreshResult.UNAVAILABLE }
                )
            }
            compose.runOnIdle { assertEquals(listOf(Screen.DeviceList, Screen.AppSettings), stack.toList()) }
        } finally {
            requests.close()
        }
    }
}
