/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [TileLifecycleShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AirPodsQSServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val tileController = Robolectric.buildService(AirPodsQSService::class.java)
    private val tile get() = tileController.get()
    private lateinit var devices: MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
    private val sockets = mutableListOf<Pair<AppleDevice, BluetoothSocket>>()

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        @Suppress("UNCHECKED_CAST")
        devices = LibrePodsService::class.java.getDeclaredField("_devices").apply { isAccessible = true }
            .get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(context, LibrePodsService::class.java),
            service.LocalBinder())
        shadowOf(context).setUnbindServiceCallsOnServiceDisconnected(false)
        tileController.create()
    }

    @After fun tearDown() {
        tileController.destroy()
        sockets.forEach { (device, socket) -> device.aacp.disconnect(); socket.close() }
    }

    @Test fun bindingObservesTheCurrentPairAndReleasesEveryCollectorWhenListeningStops() {
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening()
        idle()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertEquals(context.getString(R.string.transparency), tile.qsTile.label)
        assertEquals("My AirPods", tile.qsTile.subtitle)
        assertEquals(1, shadowOf(context).boundServiceConnections.size)

        device.loadInitialState(device.state.value.copy(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(2))),
            device.settings.value, device.metadata.value.copy(name = "Studio"))
        idle()
        assertEquals(context.getString(R.string.noise_cancellation), tile.qsTile.label)
        assertEquals("Studio", tile.qsTile.subtitle)

        tile.onStopListening()
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
        device.loadInitialState(device.state.value, device.settings.value, device.metadata.value.copy(name = "Later"))
        idle()
        assertEquals(context.getString(R.string.takeover_disconnected), tile.qsTile.subtitle)
        tile.onStartListening()
        idle()
        assertEquals("Later", tile.qsTile.subtitle)
        devices.value = emptyMap()
        idle()
        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
    }

    @Test fun clicksWriteOnlySupportedModesAndRespectTheSelectedPairsAllowOffChoice() {
        val device = device()
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        sockets += device to socket
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening()
        idle()
        val output = shadowOf(socket).outputStreamSink
        for ((model, current, allowOff, next) in listOf(
            Cycle(AirPodsModel.AIRPODS_PRO_1, 3, null, 2),
            Cycle(AirPodsModel.AIRPODS_PRO_1, 2, null, 3),
            Cycle(AirPodsModel.AIRPODS_PRO_2_LIGHTNING, 3, null, 4),
            Cycle(AirPodsModel.AIRPODS_PRO_2_LIGHTNING, 4, null, 2),
            Cycle(AirPodsModel.AIRPODS_PRO_3, 2, true, 1),
            Cycle(AirPodsModel.AIRPODS_PRO_3, 1, false, 3),
            Cycle(AirPodsModel.AIRPODS_PRO_3, 2, false, 3)
        )) {
            device.loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(current.toByte()))),
                AppleSettings(allowOffChoice = allowOff), AppleMetadata(name = "My AirPods", model = model, modelNumber = "A3063"))
            idle()
            tile.onClick()
            val bytes = ByteArray(output.available())
            assertTrue("Click must write a real AACP control packet", bytes.isNotEmpty())
            assertEquals(bytes.size, output.read(bytes))
            val command = ControlCommandPacket.parse(bytes).controlCommand
            assertEquals(ControlCommandIdentifier.LISTENING_MODE, command.identifier)
            assertArrayEquals(byteArrayOf(next.toByte()), command.value)
        }
    }

    @Test fun disconnectedOrUnsupportedPairsCannotMakeTheTileAvailableOrReceiveCommands() {
        val unsupported = device(model = AirPodsModel.AIRPODS_4)
        val supported = device(address = "00:11:22:33:44:66", connection = ConnectionState.DISCONNECTED)
        devices.value = linkedMapOf(unsupported.macAddress to unsupported, supported.macAddress to supported)
        tile.onStartListening()
        idle()
        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
        tile.onClick()
        assertTrue(unsupported.state.value.requestedControls.isEmpty())
        assertTrue(supported.state.value.requestedControls.isEmpty())

        @Suppress("UNCHECKED_CAST")
        val connection = AppleDevice::class.java.getDeclaredField("_connectionState").apply { isAccessible = true }
            .get(supported) as MutableStateFlow<ConnectionState>
        connection.value = ConnectionState.CONNECTED
        idle()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        connection.value = ConnectionState.DISCONNECTED
        idle()
        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
    }

    private data class Cycle(val model: AirPodsModel, val current: Int, val allowOff: Boolean?, val next: Int)

    private fun device(address: String = "00:11:22:33:44:55", model: AirPodsModel = AirPodsModel.AIRPODS_PRO_3,
                       connection: ConnectionState = ConnectionState.CONNECTED): AppleDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        return AppleDevice(context, adapter, adapter.getRemoteDevice(address), connection).apply {
            loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3))),
                AppleSettings(), AppleMetadata(name = "My AirPods", model = model, modelNumber = "A3063"))
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

/** Robolectric's tile shadow omits ShadowService, which breaks the real Service.onDestroy call. */
@Implements(TileService::class)
class TileLifecycleShadow : ShadowService() {
    private val tile = Shadow.newInstanceOf(Tile::class.java)
    @Implementation fun getQsTile(): Tile = tile
}
