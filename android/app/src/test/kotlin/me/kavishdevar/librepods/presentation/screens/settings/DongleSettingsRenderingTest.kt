package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.intl.LocaleList
import java.io.File
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DongleSettingsRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val editors = mutableListOf<DongleControlTestEditors>()
    @After fun closeEditors() { editors.forEach { it.close() } }

    @Test fun aRememberedHardwareVolumeRequestCanBeCancelledWhileDisconnected() {
        val controller = mock(DongleController::class.java)
        `when`(controller.state).thenReturn(MutableStateFlow(DongleState(selected = true, usb = true, usbVolumeRequested = true)))
        compose.setContent {
            LibrePodsTheme(darkTheme = true, designSystem = DesignSystem.Apple) {
                Column(Modifier.requiredSize(448.dp, 998.dp).verticalScroll(rememberScrollState())) {
                    DongleSettingsSection(controller = controller)
                }
            }
        }
        compose.onNodeWithText("Hardware volume").performScrollTo().assertIsEnabled().performClick()
        org.mockito.Mockito.verify(controller).usbHardwareVolume(false)
    }

    @Test fun settings_remain_readable_without_routine_confirmation_commentary() {
        data class Profile(val theme: DesignSystem, val dark: Boolean, val width: Int, val scale: Float)
        val profiles = listOf(Profile(DesignSystem.Apple, false, 448, 1f), Profile(DesignSystem.Apple, true, 448, 1f),
            Profile(DesignSystem.Material, false, 320, 1.3f))
        val layout = mutableStateOf(profiles.first())
        val nearby = DongleProtocol.Nearby(2, true, -51, 1, 200, 4, 0, 1, -52.5)
        val current = mutableStateOf(DongleState(connected = true,
            status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(91, 92, 70), 3, 0x1f),
            requested = mapOf(0x28 to 1, 0x40 to 50), unconfirmed = setOf(0x28),
            audio = DongleProtocol.AudioProfile(3, 3, 0, 256000, 256000, true, 2, 2, 8, null),
            hearingProfile = HearingAidSettings(FloatArray(8) { 10f }, FloatArray(8) { 20f },
                0f, 0f, 0f, 0f, false, false, 0f, 0f, 0f, 0f, 0f),
            finderOpen = true, nearby = nearby))
        val controller = DongleControlTestEditors(current).also { editors += it }.controller
        compose.setContent {
            val profile = layout.value
            CompositionLocalProvider(LocalDensity provides Density(1f, profile.scale)) {
                LibrePodsTheme(darkTheme = profile.dark, designSystem = profile.theme) {
                    Column(Modifier.requiredSize(profile.width.dp, 998.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp).testTag("dongle-controls").verticalScroll(rememberScrollState())) {
                        DongleControls(current.value, controller)
                    }
                }
            }
        }
        for (profile in profiles) {
            compose.runOnIdle { layout.value = profile; current.value = current.value.copy(finderOpen = true, nearby = nearby) }
            for (label in listOf("Listening", "Preferred microphone", "Charging case sounds", "Tone volume", "Amplification"))
                compose.onAllNodesWithText(label).assertCountEquals(0)
            compose.onAllNodesWithText("Your choice", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Startup priming", substring = true).assertCountEquals(0)
            capture("settings-${profile.theme}-${profile.dark}-${profile.width}")
            compose.onNodeWithText("Game Mode").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Request sent; AirPods confirmation pending").assertDoesNotExist()
            compose.onNodeWithText("Confirmed by a fresh delay report").assertDoesNotExist()
            compose.runOnIdle { current.value = current.value.copy(audio = current.value.audio!!.copy(gameState = 4)) }
            compose.onNodeWithText("Request failed").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { current.value = current.value.copy(audio = current.value.audio!!.copy(gameState = 2)) }
            compose.onNodeWithText("Find nearby").performScrollTo()
            compose.onNodeWithText("Signal: -52.5 dBm · stronger").performScrollTo().assertIsDisplayed()
            capture("finder-${profile.theme}-${profile.dark}-${profile.width}")
            compose.runOnIdle { current.value = current.value.copy(nearby = nearby.copy(smoothedRssi = -64.5, trend = 3, ageMs = 1_500)) }
            compose.onNodeWithText("Signal: -64.5 dBm · weaker").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Signal: -52.5 dBm · stronger").assertDoesNotExist()
            compose.onNodeWithText("Last heard 1 second ago").performScrollTo().assertIsDisplayed()
            capture("finder-updated-${profile.theme}-${profile.dark}-${profile.width}")
            compose.onNodeWithText("Phone").assertDoesNotExist()
            compose.onNodeWithText("This PC").assertDoesNotExist()
            compose.runOnIdle { current.value = current.value.copy(finderOpen = false,
                nearby = DongleProtocol.Nearby(0, false, null, 0, null, 0, 0, 0, null)) }
            capture("finder-setup-${profile.theme}-${profile.dark}-${profile.width}")
            compose.onNodeWithText("Connect the selected AirPods once so the dongle can read their finding identity.")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Start finding").assertIsDisplayed()
        }
        compose.runOnIdle { current.value = current.value.copy(busy = true) }
        compose.onNodeWithText("Game Mode").performScrollTo().assertIsEnabled()
    }

    @Test fun finderNumbersFollowTheAppLocaleAndElapsedTimeUsesPlurals() {
        val context = RuntimeEnvironment.getApplication()
        val locales = mutableStateOf(LocaleList("en-US"))
        val nearby = DongleProtocol.Nearby(2, true, -51, 1, 1_000, 4, 0, 1, -52.5)
        val current = mutableStateOf(DongleState(connected = true,
            status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 3, listOf(91, 92, 70), 3, 0x1f),
            finderOpen = true, nearby = nearby))
        val controller = DongleController(context)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(locales.value)) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    Column(Modifier.requiredSize(448.dp, 998.dp).verticalScroll(rememberScrollState())) {
                        DongleControls(current.value, controller)
                    }
                }
            }
        }
        compose.onNodeWithText("-52.5 dBm", substring = true).performScrollTo().assertIsDisplayed()
        for ((age, expected) in listOf(0L to "Last heard 0 seconds ago", 1_000L to "Last heard 1 second ago",
            2_000L to "Last heard 2 seconds ago")) {
            compose.runOnIdle { current.value = current.value.copy(nearby = nearby.copy(ageMs = age)) }
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { locales.value = LocaleList("fr-FR") }
        compose.onNodeWithText("-52,5 dBm", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("-52.5 dBm", substring = true).assertDoesNotExist()
    }

    @Test fun usbSummarySeparatesControlsFromHeadphonePresenceInBothAppearances() {
        val dark = mutableStateOf(false)
        val state = MutableStateFlow(DongleState(selected = true, usb = true, attached = true, connected = true,
            status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 0, 0, listOf(91, 92, 70), 0, 0)))
        val controller = mock(DongleController::class.java)
        `when`(controller.state).thenReturn(state)
        compose.setContent {
            LibrePodsTheme(darkTheme = dark.value, designSystem = DesignSystem.Apple) {
                Column(Modifier.requiredSize(320.dp, 998.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp).testTag("dongle-controls")) {
                    DongleSettingsSection(onManage = {}, controller = controller)
                }
            }
        }
        for (appearance in listOf(false, true)) {
            compose.runOnIdle { dark.value = appearance; state.value = state.value.copy(usbAudioAvailable = false, status = state.value.status?.copy(flags = 0)) }
            compose.onNodeWithText("Waiting for the dongle’s AirPods connection").assertIsDisplayed()
            compose.onNodeWithText("USB controls connected · headphones unavailable").assertIsDisplayed()
            compose.onNodeWithText("70%", substring = true).assertIsDisplayed()
            capture("usb-controls-only-$appearance")
            compose.runOnIdle { state.value = state.value.copy(usbAudioAvailable = true,
                status = state.value.status?.copy(flags = 3)) }
            compose.onNodeWithText("AirPods controls ready").assertIsDisplayed()
            compose.onNodeWithText("USB controls connected · headphones available").assertIsDisplayed()
            capture("usb-audio-$appearance")
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
        val file = File("build/overlay-previews/dongle-$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use {
            assertTrue(compose.onNodeWithTag("dongle-controls").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
