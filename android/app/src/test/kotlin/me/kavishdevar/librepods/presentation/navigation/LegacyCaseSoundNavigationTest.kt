package me.kavishdevar.librepods.presentation.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavBackStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
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

@Suppress("DEPRECATION")
@OptIn(ExperimentalSerializationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyCaseSoundNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun restored(): Screen {
        assertEquals("me.kavishdevar.librepods.presentation.navigation.Screen.CaseSound",
            Screen.CaseSound.serializer().descriptor.serialName)
        val saved = Cbor.encodeToByteArray(Screen.serializer(), Screen.CaseSound)
        return Cbor.decodeFromByteArray(Screen.serializer(), saved)
    }

    @Test fun restoredCaseSoundReturnsToSettingsWithoutRenderingTheExperiment() {
        val screen = restored()
        redirect(screen, NavBackStack<Screen>(Screen.DeviceList, screen))
    }

    @Test fun restoredCaseSoundDoesNotDuplicateAnExistingSettingsDestination() {
        val screen = restored()
        redirect(screen, NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings, screen))
    }

    private fun redirect(screen: Screen, stack: NavBackStack<Screen>) {
        val requests = Channel<CompletableDeferred<Unit>>(Channel.BUFFERED)
        try {
            compose.setContent {
                RenderScreenContent(
                    screen = screen,
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
            compose.onNodeWithText("Case sound experiment").assertDoesNotExist()
            compose.onNodeWithText("Start two-second experiment").assertDoesNotExist()
        } finally {
            requests.close()
        }
    }
}
