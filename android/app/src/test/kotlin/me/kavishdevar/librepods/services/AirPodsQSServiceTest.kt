/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.app.Dialog
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.overlays.ListeningModeDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
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
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        device.loadInitialState(device.state.value, device.settings.value, device.metadata.value.copy(name = "Later"))
        idle()
        assertEquals("Studio", tile.qsTile.subtitle)
        tile.onStartListening()
        assertEquals("Rebinding must retain the last published state until it can observe devices", "Studio", tile.qsTile.subtitle)
        idle()
        assertEquals("Later", tile.qsTile.subtitle)
        devices.value = emptyMap()
        idle()
        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
    }

    @Test fun pendingBindingPreservesTheTileAndDefersClickUntilTheCurrentPairIsValidated() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        shadowOf(context).setBindServiceCallsOnServiceConnectedDirectly(false)
        tile.onStartListening(); idle()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        tile.onStopListening()
        val replacement = device(address = "00:11:22:33:44:66")
        devices.value = mapOf(replacement.macAddress to replacement)
        tile.onClick()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertTrue("An unbound tile cannot use its old device reference", created.constructed().isEmpty())

        tile.onStartListening()
        tile.onClick()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertTrue("A pending rebind cannot use its old device reference", created.constructed().isEmpty())
        tile.onStopListening()
        idle()
        assertEquals("The deferred click opens after the new binding resolves, even if the shade stopped listening", 1,
            created.constructed().size)
        assertEquals("The temporary click binding must be released", 0, shadowOf(context).boundServiceConnections.size)
        tile.onDestroy()
        assertEquals("Normal tile destruction must preserve SystemUI's cached state", Tile.STATE_ACTIVE, tile.qsTile.state)
    }

    @Test fun serviceLossCallbacksMakeTheTileUnavailableAndRejectStaleControls() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        val component = ComponentName(context, LibrePodsService::class.java)
        val losses: List<(ServiceConnection) -> Unit> = listOf(
            { it.onServiceDisconnected(component) },
            { it.onBindingDied(component) },
            { it.onNullBinding(component) },
        )
        for (lose in losses) {
            tile.onStartListening(); idle()
            assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
            tile.onStopListening()
            tile.onStartListening()
            tile.onClick()
            lose(shadowOf(context).boundServiceConnections.last())
            assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
            idle()
            assertTrue("A click queued before binding loss must not later open", created.constructed().isEmpty())
            tile.onStopListening()
        }
    }

    @Test fun failedRebindingClearsThePreviouslyPublishedState() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        for (throws in listOf(true, false)) {
            tile.onStartListening(); idle()
            assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
            tile.onStopListening()
            if (throws) shadowOf(context).setThrowInBindService(SecurityException("Test binding denied"))
            else shadowOf(context).declareComponentUnbindable(ComponentName(context, LibrePodsService::class.java))
            tile.onClick(); idle()
            assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
            assertEquals(context.getString(R.string.takeover_disconnected), tile.qsTile.subtitle)
            assertTrue(created.constructed().isEmpty())
            if (throws) shadowOf(context).setThrowInBindService(null)
        }
    }

    @Test fun removingOrDestroyingTheTileCancelsAPendingClick() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening(); idle()
        for (destroy in listOf(false, true)) {
            tile.onStopListening()
            tile.onStartListening()
            tile.onClick()
            if (destroy) tile.onDestroy() else tile.onTileRemoved()
            idle()
            assertTrue("Retiring a tile must cancel its pending click", created.constructed().isEmpty())
            assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
            if (!destroy) tile.onTileAdded()
        }
    }

    @Test fun clickOpensATileDialogForTheSelectedPairWithoutLaunchingAnActivityOrWritingAMode() = withPickerMocks { created ->
        val device = device()
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        sockets += device to socket
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening()
        idle()
        tile.onClick()
        assertSame(created.constructed().single(), tileShadow().dialogs.single())
        assertTrue(tileShadow().pending.isEmpty())
        assertTrue(tileShadow().intents.isEmpty())
        assertEquals(0, shadowOf(socket).outputStreamSink.available())
        assertTrue(device.state.value.requestedControls.isEmpty())
    }

    @Test @Config(sdk = [33]) fun android13UsesTheSameTileDialogWithoutAnActivityTrampoline() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening(); idle(); tile.onClick()
        assertSame(created.constructed().single(), tileShadow().dialogs.single())
        assertTrue(tileShadow().intents.isEmpty())
        assertTrue(tileShadow().pending.isEmpty())
    }

    @Test fun lockedTileWaitsForUnlockAndRevalidatesTheSamePairBeforeOpening() = withPickerMocks {
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening(); idle()
        tileShadow().locked = true
        tile.onClick()
        assertTrue(tileShadow().dialogs.isEmpty())
        val unlocked = requireNotNull(tileShadow().unlock)
        devices.value = emptyMap(); idle()
        tileShadow().locked = false
        unlocked.run()
        assertTrue("A pair removed during unlock must not open", tileShadow().dialogs.isEmpty())

        devices.value = mapOf(device.macAddress to device); idle()
        tileShadow().locked = true; tile.onClick()
        tile.onStopListening()
        tileShadow().locked = false; requireNotNull(tileShadow().unlock).run()
        assertEquals(1, tileShadow().dialogs.size)
    }

    @Test fun repeatedClicksKeepOneDialogAndChangingPairsOrRemovingTheTileDismissesIt() = withPickerMocks { created ->
        val first = device()
        devices.value = mapOf(first.macAddress to first)
        tile.onStartListening(); idle(); tile.onClick(); tile.onClick()
        val original = created.constructed().single()
        assertEquals("Every valid repeat click must ask SystemUI to collapse the shade", 2, tileShadow().dialogs.size)
        assertTrue(tileShadow().dialogs.all { it === original })
        verify(original, times(2)).show()
        tile.onStopListening()
        verify(original, never()).dismiss()
        val replacement = device(address = "00:11:22:33:44:66")
        devices.value = mapOf(replacement.macAddress to replacement)
        tile.onStartListening(); idle(); tile.onClick()
        verify(original).dismiss()
        val current = created.constructed().last()
        assertEquals(3, tileShadow().dialogs.size)
        tile.onTileRemoved()
        verify(current).dismiss()
    }

    @Test fun serviceDestructionDismissesTheDialogAndRetiresAPendingUnlock() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening(); idle(); tile.onClick()
        val dialog = created.constructed().single()
        tileShadow().locked = true
        tile.onClick()
        val unlock = requireNotNull(tileShadow().unlock)
        tile.onDestroy()
        verify(dialog).dismiss()
        tileShadow().locked = false
        unlock.run()
        assertEquals("A retired tile cannot show another window", 1, created.constructed().size)
    }

    @Test fun removingTheTileRetiresAnUnlockAndFailedWindowAttachmentCanBeRetried() = withPickerMocks { created ->
        val device = device()
        devices.value = mapOf(device.macAddress to device)
        tile.onStartListening(); idle()
        tileShadow().locked = true
        tile.onClick()
        val unlock = requireNotNull(tileShadow().unlock)
        tile.onTileRemoved()
        tileShadow().locked = false
        unlock.run()
        assertTrue(created.constructed().isEmpty())
        tile.onTileAdded()
        tile.onStartListening(); idle()
        tileShadow().rejectDialogs = true
        tile.onClick()
        verify(created.constructed().single()).dismiss()
        tileShadow().rejectDialogs = false
        tile.onClick()
        assertEquals("A failed attachment cannot retain the picker slot", 2, created.constructed().size)
        verify(created.constructed().last()).show()
    }

    @Test fun pickerThemeHasNoAppPreviewGlobalDimOrFullscreenBackdrop() {
        context.obtainStyledAttributes(R.style.Theme_LibrePods_ListeningMode, intArrayOf(
            android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent,
            android.R.attr.windowDisablePreview, android.R.attr.backgroundDimEnabled,
            android.R.attr.windowBlurBehindEnabled, android.R.attr.windowAnimationStyle)).let { attributes ->
            try {
                assertTrue(attributes.getBoolean(0, false))
                assertTrue(attributes.getBoolean(1, false))
                assertTrue(attributes.getBoolean(2, false))
                assertFalse(attributes.getBoolean(3, true))
                assertFalse(attributes.getBoolean(4, true))
                assertEquals(R.style.Animation_LibrePods_ListeningMode, attributes.getResourceId(5, 0))
            } finally { attributes.recycle() }
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

    private fun tileShadow() = Shadow.extract<TileLifecycleShadow>(tile)

    private fun withPickerMocks(test: (MockedConstruction<ListeningModeDialog>) -> Unit) {
        mockConstruction(ListeningModeDialog::class.java) { dialog, construction ->
            assertSame(tile, construction.arguments()[0])
            assertEquals(devices.value.keys.single().value, construction.arguments()[1])
            `when`(dialog.isShowing).thenReturn(true)
        }.use { test(it) }
    }

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
    val intents = mutableListOf<Intent>()
    val pending = mutableListOf<PendingIntent>()
    val dialogs = mutableListOf<Dialog>()
    var locked = false
    var unlock: Runnable? = null
    var rejectDialogs = false
    @Implementation fun getQsTile(): Tile = tile
    @Implementation fun isLocked(): Boolean = locked
    @Implementation fun unlockAndRun(action: Runnable) { unlock = action }
    @Implementation fun showDialog(dialog: Dialog) {
        dialogs += dialog
        if (rejectDialogs) throw WindowManager.BadTokenException("Test invalid tile token")
        dialog.show()
    }
    @Implementation fun startActivityAndCollapse(intent: Intent) { intents += intent }
    @Implementation fun startActivityAndCollapse(intent: PendingIntent) { pending += intent }
}
