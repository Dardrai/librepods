package me.kavishdevar.librepods.presentation.screens.apple

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.audio.MicrophoneState
import me.kavishdevar.librepods.audio.AirPodsMicrophone
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.data.recording.RecordingState
import me.kavishdevar.librepods.core.data.audio.MicrophoneState as DeviceMicrophoneState
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.util.TimeZone
import kotlin.time.Instant
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ExperimentalAudioController>()
    private val sockets = mutableListOf<BluetoothSocket>()
    private val files = mutableListOf<File>()

    private class Permissions : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var requestCode = -1
        var permission: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
            permission = input
        }
        fun answer(granted: Boolean) { assertTrue(dispatchResult(requestCode, granted)) }
    }

    @After fun close() {
        controllers.forEach { it.close() }
        sockets.forEach { it.close() }
        files.forEach { it.delete() }
    }

    @Test fun startRequestsPermissionAndReportsDenialBeforeItCanRecord() {
        val controller = controller()
        bridge(controller).value = BridgeState(connected = true)
        val permissions = Permissions()
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, controller)
                }
            }
        }
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(Manifest.permission.RECORD_AUDIO, permissions.permission)
            permissions.answer(false)
        }
        compose.onNodeWithText(text(R.string.experimental_audio_permission)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recorder_start)).performClick()
        compose.runOnIdle {
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            permissions.answer(true)
        }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun recorderShowsShizukuSetupAndCaptureFailuresWithoutStartingAnything() {
        val controller = controller()
        var starts = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, controller)
            }
        }
        compose.onNodeWithText(text(R.string.experimental_shizuku_connect)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, starts)
            bridge(controller).value = BridgeState(connected = true)
            microphone(controller).value = MicrophoneState(MicrophonePhase.ERROR, message = "Decoder unavailable")
        }
        compose.onNodeWithText("Decoder unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsEnabled()
        compose.runOnIdle { microphone(controller).value = MicrophoneState(MicrophonePhase.STARTING) }
        compose.onNodeWithText(text(R.string.recorder_starting)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsNotEnabled()
        assertEquals(0, starts)
        compose.runOnIdle { microphone(controller).value = MicrophoneState() }
        compose.onNodeWithText(text(R.string.recorder_starting)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsEnabled()
    }

    @Test fun permissionResultCannotStartAReplacementDevicesRecording() {
        val first = controller()
        val replacement = controller()
        bridge(first).value = BridgeState(connected = true)
        bridge(replacement).value = BridgeState(connected = true)
        val selected = mutableStateOf(first)
        val permissions = Permissions()
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, selected.value)
                }
            }
        }
        compose.onNodeWithText(text(R.string.recorder_start)).performClick()
        compose.runOnIdle { selected.value = replacement }
        compose.waitForIdle()
        compose.runOnIdle { permissions.answer(true) }
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun materialIdleRecorderHasAnEmptyStateAndNoConnectionSuccessChatter() = emptyRecorder(DesignSystem.Material)

    @Test fun appleIdleRecorderHasAnEmptyStateAndNoConnectionSuccessChatter() = emptyRecorder(DesignSystem.Apple)

    private fun emptyRecorder(theme: DesignSystem) {
        val controller = controller()
        bridge(controller).value = BridgeState(connected = true, availability = "Connected through Shizuku")
        var starts = 0
        compose.setContent {
            LibrePodsTheme(darkTheme = true, designSystem = theme) {
                RecordingScreen(AppleUiState(), null, emptyList(), { starts++ }, {}, controller)
            }
        }
        compose.onNodeWithText(text(R.string.recorder_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recorder_empty_description)).assertIsDisplayed()
        compose.onNodeWithText("Connected through Shizuku").assertDoesNotExist()
        compose.onNodeWithTag("recording-waveform").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsDisplayed().assertIsEnabled()
        assertEquals("Opening Recorder never starts capture", 0, starts)
        if (theme == DesignSystem.Material) capture("recorder-empty-material-dark")
        compose.runOnIdle { bridge(controller).value = bridge(controller).value.copy(detail = "Reconnect your AirPods to restore playback.") }
        compose.onNodeWithText("Reconnect your AirPods to restore playback.").assertIsDisplayed()
        assertEquals(0, starts)
    }

    @Config(qualifiers = "w320dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun savedRecordingsFitNarrowMaterialAtDoubleTextAndOpenThroughTheExistingChooser() = savedRecordings(DesignSystem.Material)

    // Native SDK35 supports Apple glass and real font metrics; behavior tests above still cover SDK33.
    @Config(sdk = [35], qualifiers = "w320dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun savedRecordingsFitNarrowAppleAtDoubleTextAndOpenThroughTheExistingChooser() = savedRecordings(DesignSystem.Apple)

    @Config(qualifiers = "en-rUS-w448dp-h998dp-mdpi")
    @Test fun recordingTimesHonorExplicit24HourPreferenceIn12HourLocale() = recordingTime("24")

    @Config(qualifiers = "en-rUS-w448dp-h998dp-mdpi")
    @Test fun recordingTimesHonorExplicit12HourPreference() = recordingTime("12")

    private fun recordingTime(preference: String) {
        val originalPreference = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        val originalTimeZone = TimeZone.getDefault()
        try {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, preference)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val recording = saved("12345678-1234-5678-1234-567812345678", "2026-09-30T15:45:30Z")
            compose.setContent {
                LibrePodsTheme(designSystem = DesignSystem.Material) {
                    RecordingScreen(AppleUiState(), null, listOf(recording), {}, {}, null)
                }
            }
            if (preference == "24") {
                compose.onNodeWithText("15:45:30").assertIsDisplayed()
                compose.onNodeWithText("PM", substring = true).assertDoesNotExist()
            } else {
                compose.onNodeWithText("3:45:30", substring = true).assertIsDisplayed()
                compose.onNodeWithText("PM", substring = true).assertIsDisplayed()
                compose.onNodeWithText("15:45:30").assertDoesNotExist()
            }
        } finally {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, originalPreference)
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun savedRecordings(theme: DesignSystem) {
        val controller = controller()
        bridge(controller).value = BridgeState(connected = true)
        val saved = listOf(saved("12345678-1234-5678-1234-567812345678", "2026-09-30T15:45:30Z"),
            saved("87654321-4321-6789-4321-678987654321", "2026-09-29T11:20:00Z"))
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LibrePodsTheme(darkTheme = false, designSystem = theme) {
                    RecordingScreen(AppleUiState(), null, saved, { starts++ }, {}, controller)
                }
            }
        }
        compose.onNodeWithText(text(R.string.recorder_empty)).assertDoesNotExist()
        for (recording in saved) compose.onNodeWithText(recording.uuid.toString()).assertDoesNotExist()
        val dates = compose.onAllNodesWithText("2026", substring = true, useUnmergedTree = true).assertCountEquals(2)
        for (index in saved.indices) {
            val date = dates[index].performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            date.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val fits = layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }
            val measured = layouts.joinToString { layout ->
                "text=${layout.layoutInput.text.text}, size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                    "maxLines=${layout.layoutInput.maxLines}, wrap=${layout.layoutInput.softWrap}, lines=${layout.lineCount}, " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}"
            }
            assertTrue("The complete localized date must fit at 2x text ($theme/$index: $measured)", fits)
        }
        compose.onNodeWithText(text(R.string.recorder_start)).assertIsDisplayed().assertIsEnabled()
        capture("recorder-saved-${theme.name.lowercase()}-narrow-2x")
        compose.onAllNodesWithText("2026", substring = true)[0].performScrollTo().performClick()
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val opened = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("audio/wav", opened.type)
        assertEquals("content", opened.data?.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.provider", opened.data?.authority)
        assertTrue(opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, starts)
    }

    @Config(qualifiers = "w320dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun activeWaveformResetsOnSameLevelRestartAndStopSurvivesLostPrerequisites() {
        val controller = controller()
        bridge(controller).value = BridgeState(connected = true)
        val state = mutableStateOf(AppleState(recordingState = RecordingState(isRecording = true),
            microphoneState = DeviceMicrophoneState(isActive = true, level = 0.9f)))
        var starts = 0
        var stops = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LibrePodsTheme(darkTheme = false, designSystem = DesignSystem.Material) {
                    RecordingScreen(AppleUiState(state = state.value), null, emptyList(), { starts++ }, { stops++ }, controller)
                }
            }
        }
        compose.onNodeWithText(text(R.string.recorder_recording)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.recorder_waveform)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(microphoneState = state.value.microphoneState.copy(level = 0.1f)) }
        val before = redPixels(compose.onNodeWithTag("recording-waveform").captureToImage().asAndroidBitmap())
        compose.runOnIdle { state.value = state.value.copy(recordingState = RecordingState()) }
        compose.onNodeWithTag("recording-waveform").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(recordingState = RecordingState(isRecording = true)) }
        val after = redPixels(compose.onNodeWithTag("recording-waveform").captureToImage().asAndroidBitmap())
        assertTrue("A new recording must not retain the previous louder waveform", before > after)
        compose.runOnIdle { bridge(controller).value = BridgeState(connected = false, session = "failed", detail = "Shizuku disconnected") }
        compose.onNodeWithText(text(R.string.recorder_stop)).assertIsDisplayed().assertIsEnabled()
        capture("recorder-active-material-narrow-2x")
        compose.onNodeWithText(text(R.string.recorder_stop)).performClick()
        assertEquals(1, stops)
        assertEquals(0, starts)
    }

    @Test fun activeRecordingKeepsStopAvailableWithoutAController() {
        var stops = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                RecordingScreen(AppleUiState(state = AppleState(recordingState = RecordingState(isRecording = true))),
                    null, emptyList(), {}, { stops++ }, null)
            }
        }
        compose.onNodeWithText(text(R.string.recorder_stop)).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, stops)
    }

    @Test fun anUnavailableRecordingShowsAnOpenErrorWithoutStartingCapture() {
        val invalid = Recording(Uuid.parse("12345678-1234-5678-1234-567812345678"),
            File(context.cacheDir, "outside-the-recordings-provider.wav"), Instant.parse("2026-09-30T15:45:30Z"))
        var starts = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                RecordingScreen(AppleUiState(), null, listOf(invalid), { starts++ }, {}, null)
            }
        }
        compose.onNodeWithText("2026", substring = true).performClick()
        compose.onNodeWithText(text(R.string.recorder_open_failed)).assertIsDisplayed()
        assertEquals(0, starts)
    }

    private fun saved(id: String, timestamp: String): Recording {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS), "$id.wav")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(44))
        files += file
        return Recording(Uuid.parse(id), file, Instant.parse(timestamp))
    }

    private fun redPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            if (red > android.graphics.Color.green(pixel) * 1.2 && red > android.graphics.Color.blue(pixel) * 1.2) count++
        }
        return count
    }

    private fun capture(name: String) {
        val output = File("build/overlay-previews/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun controller(): ExperimentalAudioController {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(modelNumber = "A3063"))
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        sockets += socket
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        return ExperimentalAudioController(context, device) {}.also { controllers += it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bridge(controller: ExperimentalAudioController) = ShizukuAudioClient::class.java
        .getDeclaredField("mutableState").apply { isAccessible = true }.get(controller.bridge) as MutableStateFlow<BridgeState>

    @Suppress("UNCHECKED_CAST")
    private fun microphone(controller: ExperimentalAudioController) = AirPodsMicrophone::class.java
        .getDeclaredField("mutableState").apply { isAccessible = true }.get(controller.microphone) as MutableStateFlow<MicrophoneState>

    private fun text(id: Int) = context.getString(id)
}
