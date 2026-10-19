/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.Looper
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.overlays.ListeningModeSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandListeningIntegrationTest {
    @Test fun failedWritesKeepTheIslandAndPickerOnTheIndependentDeviceReport() = withWindow { window, device ->
        window.showPairedLid(device)
        @Suppress("UNCHECKED_CAST")
        val mode = field(window, "onNoiseControlModeChanged") as (Int) -> Unit
        val deadline = field(window, "autoCloseRunnable")
        mode(4)
        assertTrue("A missing transport cannot create a pending request", device.state.value.requestedControls.isEmpty())
        assertEquals(3, getter(window, "getNoiseControlModeValue"))
        assertEquals(snapshot(device).mode, getter(window, "getNoiseControlModeValue"))
        assertNotSame("A legitimate attempted action still renews dismissal", deadline, field(window, "autoCloseRunnable"))

        device.loadInitialState(AppleState(controlStates = mapOf(
            ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(4),
            ControlCommandIdentifier.AUTO_ANC_STRENGTH to byteArrayOf(25))), device.settings.value, device.metadata.value)
        window.updateAdaptiveStrength(device)
        @Suppress("UNCHECKED_CAST")
        val strength = field(window, "onAdaptiveStrengthChanged") as (Int) -> Unit
        strength(72)
        assertTrue(device.state.value.requestedControls.isEmpty())
        assertEquals(25, getter(window, "getAdaptiveStrength"))
        assertNull(getter(window, "getRequestedAdaptiveStrength"))
    }

    @Test fun acceptedWritesImmediatelySharePendingModeAndStrengthWithoutReplacingReports() = withWindow { window, device ->
        device.loadInitialState(device.state.value.copy(controlStates = device.state.value.controlStates +
            (ControlCommandIdentifier.AUTO_ANC_STRENGTH to byteArrayOf(25))), device.settings.value, device.metadata.value)
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        try {
            window.showPairedLid(device)
            @Suppress("UNCHECKED_CAST")
            val mode = field(window, "onNoiseControlModeChanged") as (Int) -> Unit
            @Suppress("UNCHECKED_CAST")
            val strength = field(window, "onAdaptiveStrengthChanged") as (Int) -> Unit
            mode(4)
            assertEquals(4, getter(window, "getNoiseControlModeValue"))
            assertEquals(snapshot(device).mode, getter(window, "getNoiseControlModeValue"))
            strength(72)
            assertEquals(72, getter(window, "getRequestedAdaptiveStrength"))
            assertEquals(72, snapshot(device).state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]!!.single().toInt())
            assertEquals(3, device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE]!!.single().toInt())
            assertEquals(25, getter(window, "getAdaptiveStrength"))
            assertEquals(25, device.state.value.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]!!.single().toInt())
        } finally {
            device.aacp.disconnect()
            socket.close()
        }
    }

    @Test fun pickerOwnersRemoveTheTouchableOverlayGateQueuedShowsAndNeverReplay() = withWindow { window, device ->
        window.showPairedLid(device)
        val view = field(window, "composeView") as ComposeView
        val lifecycle = requireNotNull(view.findViewTreeLifecycleOwner()).lifecycle
        assertFalse("Exercise priority hiding before the first queued window attachment", view.isAttachedToWindow)
        Thread { window.show(owner = device, name = "Queued mode", statusText = "Adaptive") }.apply { start(); join() }
        data class Token(val value: Int)
        val first = Token(1); val second = Token(1)
        window.setListeningPickerVisible(first, true)
        assertNull("The picker must not wait for a touchable folding animation", view.parent)
        assertFalse("The retired window must already be detached when its lifecycle is destroyed", view.isAttachedToWindow)
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.currentState)
        window.setListeningPickerVisible(second, true)
        window.setListeningPickerVisible(first, false)
        window.setListeningPickerVisible(first, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Draining the queued first frame cannot reattach the destroyed composition", view.isAttachedToWindow)
        window.showPairedLid(device)
        assertFalse(window.isVisible)
        assertNull(getter(window, "getOwnerDevice"))
        window.setListeningPickerVisible(second, false)
        assertFalse("Releasing suppression must not replay any old announcement", window.isVisible)
        window.showPairedLid(device)
        assertTrue(window.isVisible)
    }

    @Test fun confirmedModeUpdatesRetainTheSameOwnersHeaderPresentationConnectionAndDeadline() = withWindow { window, device ->
        window.showPairedLid(device, onConnect = { false }, isAudioConnected = { true })
        window.changeState(IslandState.COMPACT)
        val generation = getter(window, "getTransitionGeneration")
        val connection = field(window, "onConnect")
        val deadline = field(window, "autoCloseRunnable")
        val reading = Battery(BatteryComponent.LEFT, 72, BatteryStatus.NOT_CHARGING)
        device.receiveNearbyBattery(setOf(reading))
        window.show(owner = device, statusText = "Adaptive", noiseControlModeValue = 4,
            adaptiveStrength = 30, requestedAdaptiveStrength = 72)
        assertEquals(IslandState.COMPACT, window.islandState)
        assertEquals(generation, getter(window, "getTransitionGeneration"))
        assertNull("A battery header must not turn into a mode notification", getter(window, "getStatusText"))
        assertSame(connection, field(window, "onConnect"))
        assertSame(deadline, field(window, "autoCloseRunnable"))
        assertEquals(setOf(reading), getter(window, "getBatteries"))
        assertEquals(4, getter(window, "getNoiseControlModeValue"))
        assertEquals(72, getter(window, "getRequestedAdaptiveStrength"))

        val replacement = newDevice(device.macAddress.value)
        window.show(owner = replacement, statusText = "Transparency", keepOpen = true)
        assertSame(replacement, getter(window, "getOwnerDevice"))
        assertNull("A replacement cannot inherit the previous pair's connection action", field(window, "onConnect"))
        assertEquals("Transparency", getter(window, "getStatusText"))
        val replaced = getter(window, "getTransitionGeneration")
        window.show(owner = replacement, statusText = "Noise Cancellation")
        assertEquals("Noise Cancellation", getter(window, "getStatusText"))
        assertEquals(replaced, getter(window, "getTransitionGeneration"))
    }

    @Test fun onlyTheVisibleConnectedCapableOwnerCanSendListeningCommands() = withWindow { window, device ->
        val modes = mutableListOf<Int>(); val strengths = mutableListOf<Int>()
        window.show(owner = device, onNoiseControlModeChanged = { modes += it },
            onAdaptiveStrengthChanged = { strengths += it })
        assertEquals(true, getter(window, "getListeningControlsEnabled"))
        @Suppress("UNCHECKED_CAST")
        val mode = field(window, "onNoiseControlModeChanged") as (Int) -> Unit
        @Suppress("UNCHECKED_CAST")
        val strength = field(window, "onAdaptiveStrengthChanged") as (Int) -> Unit
        mode(2); mode(0); mode(1); strength(50)
        assertEquals(listOf(2), modes)
        assertTrue(strengths.isEmpty())
        device.loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(4))),
            device.settings.value, device.metadata.value)
        window.updateAdaptiveStrength(device)
        strength(72); strength(101)
        assertEquals(listOf(72), strengths)
        @Suppress("UNCHECKED_CAST")
        val connectionState = AppleDevice::class.java.getDeclaredField("_connectionState").apply { isAccessible = true }
            .get(device) as MutableStateFlow<ConnectionState>
        connectionState.value = ConnectionState.DISCONNECTED
        window.updatePairedBattery(device)
        assertEquals(false, getter(window, "getListeningControlsEnabled"))
        mode(3); strength(30)
        connectionState.value = ConnectionState.CONNECTED
        window.show(owner = newDevice(device.macAddress.value), statusText = "Transparency")
        mode(3); strength(30)
        window.close()
        mode(3); strength(30)
        assertEquals(listOf(2), modes)
        assertEquals(listOf(72), strengths)
        val noReport = newDevice().apply { loadInitialState(AppleState(), settings.value, metadata.value) }
        window.show(owner = noReport)
        assertEquals(false, getter(window, "getListeningControlsEnabled"))
        val unsupported = newDevice().apply { loadInitialState(state.value, settings.value,
            metadata.value.copy(model = AirPodsModel.AIRPODS_4)) }
        window.show(owner = unsupported)
        assertEquals(false, getter(window, "getListeningControlsEnabled"))
    }

    private fun newDevice(address: String = "00:11:22:33:44:55"): AppleDevice {
        val context = RuntimeEnvironment.getApplication()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        return AppleDevice(context, adapter, adapter.getRemoteDevice(address), ConnectionState.CONNECTED).apply {
            loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3))),
                settings.value, AppleMetadata(name = "AirPods", model = AirPodsModel.AIRPODS_PRO_3))
        }
    }

    private fun snapshot(device: AppleDevice) = ListeningModeSnapshot(device.metadata.value, device.state.value, device.settings.value)

    private fun withWindow(block: (IslandWindow, AppleDevice) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val window = IslandWindow(context, AppSettingsEntity())
        try { block(window, newDevice()) } finally {
            window.setListeningPickerVisible(Any(), true)
        }
    }
    private fun field(window: IslandWindow, name: String): Any? =
        IslandWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(window)
    private fun getter(window: IslandWindow, name: String): Any? =
        IslandWindow::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(window)
}
