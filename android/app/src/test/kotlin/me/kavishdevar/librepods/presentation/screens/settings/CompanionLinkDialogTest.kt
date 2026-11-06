package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.companion.CompanionPairingState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionLinkDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun showForm(content: @Composable () -> Unit) {
        compose.setContent { Column { content() } }
    }

    @Test fun linkingCodeOptsOutOfAutofillAndDoneDismissesKeyboardWithoutLinking() {
        val keyboard = mock(SoftwareKeyboardController::class.java)
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                LibrePodsTheme {
                    var value by remember { mutableStateOf("") }
                    Column {
                        CompanionLinkCodeField(value, false) { value = it }
                        TextButton(onClick = {}) { Text("Cancel") }
                    }
                }
            }
        }
        val code = compose.onNodeWithText("Linking code")
        code.performClick()
        code.performTextInput("a".repeat(64)) // Synthetic test value; never a provisioned linking code.
        code.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None))
        code.assertIsFocused()
        code.performImeAction()
        code.assertIsNotFocused()
        verify(keyboard).hide()
    }

    @Test fun pairingRequiresBothSelectionsAndCompleteCodeAndRefreshNeverLinks() {
        val pc = CompanionDeviceOption("02:00:00:00:00:11", "Office PC")
        val pod = CompanionDeviceOption("02:00:00:00:00:22", "Test AirPods")
        val paired = mutableStateOf(CompanionPairingDevices(CompanionPairingAccess.READY, listOf(pc)))
        val pods = mutableStateOf(listOf(pod))
        var links = 0
        var bluetooth = 0
        showForm {
            LibrePodsTheme {
                CompanionLinkForm(paired.value, pods.value, onOpenBluetooth = { bluetooth++ },
                    onPermission = {}, onDismiss = {}) { peer, device, token ->
                    assertEquals(pc.address, peer); assertEquals(pod.address, device)
                    assertEquals("a".repeat(64), token); links++
                }
            }
        }
        val link = compose.onNode(hasText("Link PC") and hasClickAction())
        link.assertIsNotEnabled()
        compose.onNodeWithText("Open Bluetooth settings").performScrollTo().performClick()
        assertEquals(1, bluetooth); assertEquals(0, links)
        compose.onNodeWithText("Office PC").performScrollTo().performClick()
        compose.onNodeWithText("Test AirPods").performScrollTo().performClick()
        compose.onNodeWithText("Linking code").performScrollTo().performTextInput("a".repeat(63))
        link.assertIsNotEnabled()
        compose.onNodeWithText("Linking code").performTextInput("a")
        link.assertIsEnabled()
        compose.runOnIdle { paired.value = paired.value.copy(devices = emptyList()) }
        link.assertIsNotEnabled()
        compose.runOnIdle { paired.value = paired.value.copy(devices = listOf(pc)); pods.value = emptyList() }
        link.assertIsNotEnabled()
        compose.runOnIdle { pods.value = listOf(pod) }
        link.assertIsEnabled()
        assertEquals(0, links)
        link.performClick()
        assertEquals(1, links)
    }

    @Test fun missingPermissionBluetoothAndEmptyStatesOfferRecoveryWithoutAuthorizing() {
        val access = mutableStateOf(CompanionPairingAccess.PERMISSION_REQUIRED)
        var permissions = 0
        var links = 0
        showForm {
            LibrePodsTheme {
                CompanionLinkForm(CompanionPairingDevices(access.value), emptyList(),
                    onOpenBluetooth = {}, onPermission = { permissions++ }, onDismiss = {}) { _, _, _ -> links++ }
            }
        }
        compose.onNodeWithText("Allow Nearby devices").performScrollTo().performClick()
        assertEquals(1, permissions)
        compose.onNodeWithText("Allow Nearby devices access to see your paired PC and AirPods.").assertExists()
        compose.runOnIdle { access.value = CompanionPairingAccess.BLUETOOTH_OFF }
        compose.onNodeWithText("Bluetooth is off. Turn it on in Bluetooth settings, then return here.").assertExists()
        compose.onNodeWithText("Allow Nearby devices").assertDoesNotExist()
        compose.runOnIdle { access.value = CompanionPairingAccess.UNAVAILABLE }
        compose.onNodeWithText("Bluetooth devices could not be read. Check Bluetooth settings and return here to retry.").assertExists()
        compose.runOnIdle { access.value = CompanionPairingAccess.READY }
        compose.onNodeWithText("No paired PC found. Pair this phone with your PC in Bluetooth settings; the USB AirPods dongle is not the PC connection.").assertExists()
        compose.onNodeWithText("No paired AirPods found. Pair them in Bluetooth settings and open their device card in LibrePods.").assertExists()
        compose.onNode(hasText("Link PC") and hasClickAction()).assertIsNotEnabled()
        assertEquals(0, links)
    }

    @Test fun failedLinkClearsCodeAndKeepsRecoveryInDialog() {
        val pc = CompanionDeviceOption("02:00:00:00:00:11", "Office PC")
        val pod = CompanionDeviceOption("02:00:00:00:00:22", "Test AirPods")
        showForm {
            LibrePodsTheme {
                CompanionLinkForm(CompanionPairingDevices(CompanionPairingAccess.READY, listOf(pc)), listOf(pod),
                    onOpenBluetooth = {}, onPermission = {}, onDismiss = {}) { _, _, _ -> error("Synthetic failure") }
            }
        }
        compose.onNodeWithText("Office PC").performScrollTo().performClick()
        compose.onNodeWithText("Test AirPods").performScrollTo().performClick()
        compose.onNodeWithText("Linking code").performScrollTo().performTextInput("a".repeat(64))
        compose.onNode(hasText("Link PC") and hasClickAction()).performClick().assertIsNotEnabled()
        compose.onNodeWithText("Linking code").assertIsNotFocused()
        compose.onNodeWithText("Could not link these devices. Check pairing and paste the linking code again.").assertExists()
    }

    @Test fun groupedShortCodeShowsProgressAndRecoveryRequiresExplicitRetry() {
        val pc = CompanionDeviceOption("02:00:00:00:00:11", "Office PC")
        val pod = CompanionDeviceOption("02:00:00:00:00:22", "Test AirPods")
        val state = mutableStateOf(CompanionPairingState())
        var links = 0; var retries = 0; var cancels = 0
        showForm {
            LibrePodsTheme {
                CompanionLinkForm(CompanionPairingDevices(CompanionPairingAccess.READY, listOf(pc)), listOf(pod),
                    onOpenBluetooth = {}, onPermission = {}, onDismiss = { cancels++ },
                    pairingState = state.value, retryReady = true, onRetryPending = { retries++ }) { peer, device, code ->
                    assertEquals(pc.address, peer); assertEquals(pod.address, device); assertEquals("00001234", code)
                    links++
                    state.value = CompanionPairingState(busy = true, pendingPeer = pc.address, pendingDevice = pod.address)
                }
            }
        }
        compose.onNodeWithText("Office PC").performScrollTo().performClick()
        compose.onNodeWithText("Test AirPods").performScrollTo().performClick()
        compose.onNodeWithText("Linking code").performScrollTo().performTextInput("0000 12-34")
        compose.onNode(hasText("Link PC") and hasClickAction()).performClick().assertIsNotEnabled()
        compose.onNodeWithText("Linking apps…").assertExists()
        compose.onNodeWithText("Office PC").assertIsNotEnabled()
        compose.onNodeWithText("Linking code").assertIsNotEnabled()
        compose.onNodeWithText("Retry saved link").assertIsNotEnabled()
        assertEquals(1, links); assertEquals(0, retries)
        compose.runOnIdle { state.value = state.value.copy(busy = false, failed = true) }
        compose.onNodeWithText("Couldn’t finish linking. Check the code in NTPods or retry your saved attempt.").assertExists()
        assertEquals(0, retries)
        compose.onNodeWithText("Retry saved link").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test fun systemBondChangesAndReturnFromSettingsRefreshOnlyWhileVisible() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val visible = mutableStateOf(true)
        var refreshes = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) ObserveCompanionPairing { refreshes++ }
            }
        }
        compose.runOnIdle {
            val initial = refreshes
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(initial + 1, refreshes)
            val context = RuntimeEnvironment.getApplication()
            context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(initial + 3, refreshes)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val stopped = refreshes
            RuntimeEnvironment.getApplication().sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(stopped, refreshes)
        }
    }

    @Test fun pairedDeviceInspectionDistinguishesDeniedOffAndUnavailable() {
        val context = mock(Context::class.java)
        `when`(context.checkPermission(eq(Manifest.permission.BLUETOOTH_CONNECT), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals(CompanionPairingAccess.PERMISSION_REQUIRED, companionPairingDevices(context).access)
        verify(context, never()).getSystemService(BluetoothManager::class.java)
        `when`(context.checkPermission(eq(Manifest.permission.BLUETOOTH_CONNECT), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED)
        `when`(context.checkPermission(eq(Manifest.permission.BLUETOOTH_SCAN), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals(CompanionPairingAccess.PERMISSION_REQUIRED, companionPairingDevices(context).access)
        `when`(context.checkPermission(eq(Manifest.permission.BLUETOOTH_SCAN), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED)
        assertEquals(CompanionPairingAccess.UNAVAILABLE, companionPairingDevices(context).access)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(false)
        assertEquals(CompanionPairingAccess.BLUETOOTH_OFF, companionPairingDevices(context).access)
        `when`(adapter.isEnabled).thenReturn(true)
        `when`(adapter.bondedDevices).thenReturn(emptySet())
        assertEquals(CompanionPairingDevices(CompanionPairingAccess.READY), companionPairingDevices(context))
    }

    @Test fun pairedPcChoicesExcludeAudioDonglesAndOtherKnownClassesButKeepUnknownComputers() {
        val context = mock(Context::class.java)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        fun device(suffix: Int, name: String, major: Int?): BluetoothDevice {
            val device = mock(BluetoothDevice::class.java)
            `when`(device.address).thenReturn("02:00:00:00:00:${suffix.toString(16).padStart(2, '0')}")
            `when`(device.name).thenReturn(name)
            if (major != null) {
                val type = mock(BluetoothClass::class.java)
                `when`(type.majorDeviceClass).thenReturn(major)
                `when`(device.bluetoothClass).thenReturn(type)
            }
            return device
        }
        val computer = device(1, "Office PC", BluetoothClass.Device.Major.COMPUTER)
        val dongle = device(2, "AirPods Dongle", BluetoothClass.Device.Major.AUDIO_VIDEO)
        val phone = device(3, "Other phone", BluetoothClass.Device.Major.PHONE)
        val peripheral = device(4, "Keyboard", BluetoothClass.Device.Major.PERIPHERAL)
        val uncategorized = device(5, "Uncategorized PC", BluetoothClass.Device.Major.UNCATEGORIZED)
        val noClass = device(6, "PC without class", null)
        val unknownClass = device(7, "Future PC", 0x1e00)
        val airpods = device(8, "Test AirPods", BluetoothClass.Device.Major.AUDIO_VIDEO)
        val bonded = setOf(computer, dongle, phone, peripheral, uncategorized, noClass, unknownClass, airpods)
        `when`(adapter.bondedDevices).thenReturn(bonded)
        val actual = companionPairingDevices(context)
        assertEquals(CompanionPairingAccess.READY, actual.access)
        assertEquals(setOf(computer, uncategorized, noClass, unknownClass).map { it.address }.toSet(), actual.devices.map { it.address }.toSet())
        // AirPods still need their own bond check even though audio devices are not PC choices.
        assertEquals(bonded.map { it.address }.toSet(), actual.bondedAddresses)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sharedAppleFormRendersInBothAppearancesAndAtLargerFontScale() {
        data class Profile(val width: Int, val scale: Float, val dark: Boolean)
        val profiles = listOf(Profile(448, 1f, false), Profile(448, 1f, true), Profile(320, 1.3f, true))
        val profile = mutableStateOf(profiles.first())
        compose.setContent {
            val current = profile.value
            CompositionLocalProvider(LocalDensity provides Density(1f, current.scale)) {
                LibrePodsTheme(darkTheme = current.dark) {
                    Column(Modifier.requiredSize(current.width.dp, 998.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                        .testTag("pc-link-form")) {
                        androidx.compose.runtime.key(current) {
                            CompanionLinkForm(CompanionPairingDevices(CompanionPairingAccess.READY,
                                listOf(CompanionDeviceOption("02:00:00:00:00:11", "Office PC"))),
                                listOf(CompanionDeviceOption("02:00:00:00:00:22", "Test AirPods")),
                                onOpenBluetooth = {}, onPermission = {}, onDismiss = {}) { _, _, _ -> }
                        }
                    }
                }
            }
        }
        for (current in profiles) {
            compose.runOnIdle { profile.value = current }
            for (position in listOf("top", "code")) {
                if (position == "code") compose.onNodeWithText("For audio switching, allow Bluetooth access in LibrePods Settings using root or Shizuku. Bluetooth pairing alone does not link the apps.").performScrollTo()
                compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
                val file = File("build/overlay-previews/companion-link-${current.width}-${current.scale}-${current.dark}-$position.png")
                file.parentFile?.mkdirs()
                file.outputStream().use {
                    assertTrue(compose.onNodeWithTag("pc-link-form").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                compose.onNode(hasText("Link PC") and hasClickAction()).assertIsNotEnabled()
            }
        }
    }

}
