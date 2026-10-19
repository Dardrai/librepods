/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.LibrePodsApplication
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
import me.kavishdevar.librepods.services.LibrePodsService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.mockito.ArgumentMatchers.any
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.Executor
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ListeningModeDialogTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: LibrePodsService
    private lateinit var devices: MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
    private val sockets = mutableListOf<Pair<AppleDevice, BluetoothSocket>>()
    private var dialog: ListeningModeDialog? = null
    private lateinit var dialogContext: Context
    private lateinit var app: LibrePodsApplication
    private lateinit var island: IslandWindow

    @Before fun setUp() {
        app = LibrePodsApplication()
        // Dialog content needs real resources and the service-owned application state.
        ReflectionHelpers.callInstanceMethod<Void>(app, "attach", ClassParameter.from(Context::class.java, context.baseContext))
        island = mock(IslandWindow::class.java)
        ReflectionHelpers.setField(app, "islandWindow", island)
        dialogContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = app
        }
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        @Suppress("UNCHECKED_CAST")
        devices = LibrePodsService::class.java.getDeclaredField("_devices").apply { isAccessible = true }
            .get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(context, LibrePodsService::class.java), service.LocalBinder())
        shadowOf(context).setUnbindServiceCallsOnServiceDisconnected(false)
    }

    @After fun tearDown() {
        dialog?.dismiss()
        idle()
        sockets.forEach { (device, socket) -> device.aacp.disconnect(); socket.close() }
        (LibrePodsService::class.java.getDeclaredField("serviceScope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
    }

    @Test @Config(qualifiers = "w700dp-h1000dp")
    fun popupOpensWideAtTheTopWithoutCommandsAndUserSelectionsWriteOnlyToItsPair() {
        val target = device()
        val other = device("00:11:22:33:44:66")
        devices.value = mapOf(target.macAddress to target, other.macAddress to other)
        val popup = open(target)
        val layout = popup.window!!.attributes
        assertEquals(Gravity.TOP, layout.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertTrue("The tile panel should use the available wide display", layout.width >= (550 * context.resources.displayMetrics.density).toInt())
        assertTrue("The blur field must remain limited to the rail", layout.width <= (600 * context.resources.displayMetrics.density).toInt())
        assertEquals("The rail sits close below the native safe area", (8 * context.resources.displayMetrics.density).toInt(), layout.y)
        assertEquals(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(), layout.fitInsetsTypes)
        assertEquals("Window elevation must reserve a buffer for the pressed bubble's overflow",
            8 * context.resources.displayMetrics.density, popup.window!!.decorView.elevation, 0.01f)
        assertFalse("The pressed bubble must not be clipped to the rail outline", popup.window!!.decorView.clipToOutline)
        assertEquals("Background blur must not blur the entire screen", 0, layout.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        assertEquals("The rest of the screen must remain undimmed", 0, layout.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(0, output(target).available())
        assertTrue(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 4))
        assertEquals(4, readCommand(target).value.single().toInt())
        assertEquals(3, target.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE]!!.single().toInt())
        assertEquals(4, ListeningModeSnapshot(target.metadata.value, target.state.value, target.settings.value).mode)
        assertTrue(popup.writeControl(ControlCommandIdentifier.AUTO_ANC_STRENGTH, 72))
        val strength = readCommand(target)
        assertEquals(ControlCommandIdentifier.AUTO_ANC_STRENGTH, strength.identifier)
        assertEquals(72, strength.value.single().toInt())
        assertEquals(0, output(other).available())
    }

    @Test @Config(qualifiers = "notnight")
    fun lightBackdropTracksBlurAvailabilityAndRetiresItsListener() = assertBlurLifecycle(160, 245)

    @Test @Config(qualifiers = "night")
    fun darkBackdropTracksBlurAvailabilityAndRetiresItsListener() = assertBlurLifecycle(148, 242)

    @Test fun failedBlurRegistrationKeepsTheBackdropReadableAndStillReleasesDeviceResources() {
        useBlurManager(onAdd = { throw IllegalStateException("Blur registration unavailable") })
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = open(target)
        assertTrue(popup.isShowing)
        assertTrue("An unavailable blur service needs an opaque fallback", backgroundAlpha(popup) >= 240)
        popup.dismiss()
        idle()
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
        assertFalse(islandOwnership().last().second)
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        assertEquals(0, output(target).available())
    }

    private fun assertBlurLifecycle(blurredAlpha: Int, fallbackAlpha: Int) {
        val added = mutableListOf<Consumer<Boolean>>()
        val removed = mutableListOf<Consumer<Boolean>>()
        useBlurManager(onAdd = { added += it }, onRemove = { removed += it })
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = open(target)
        val callback = added.single()
        assertEquals(blurredAlpha, backgroundAlpha(popup))
        val background = shadowOf(popup.window!!).backgroundDrawable as GradientDrawable
        assertEquals("The backdrop outline must also clip native blur to its rounded corners",
            32 * context.resources.displayMetrics.density, background.cornerRadius, 0.01f)

        callback.accept(false)
        idle()
        assertEquals("Disabling native blur must preserve legibility", fallbackAlpha, backgroundAlpha(popup))
        callback.accept(true)
        idle()
        assertEquals(blurredAlpha, backgroundAlpha(popup))

        popup.dismiss()
        idle()
        assertEquals(1, removed.size)
        assertSame("Only this dialog's blur listener may be removed", callback, removed.single())
        val retiredBackground = shadowOf(popup.window!!).backgroundDrawable
        callback.accept(true)
        idle()
        assertSame("A queued availability callback cannot alter a closed window's backdrop",
            retiredBackground, shadowOf(popup.window!!).backgroundDrawable)
        popup.dismiss()
        idle()
        assertEquals("Repeated dismissal must not remove another listener", 1, removed.size)
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
        assertFalse(islandOwnership().last().second)
        assertEquals(0, output(target).available())
    }

    private fun useBlurManager(onAdd: (Consumer<Boolean>) -> Unit,
        onRemove: (Consumer<Boolean>) -> Unit = {}) {
        val manager = spy(context.getSystemService(WindowManager::class.java))
        doReturn(true).`when`(manager).isCrossWindowBlurEnabled
        doAnswer { call ->
            val callback = call.getArgument<Consumer<Boolean>>(1)
            // DecorView also registers its own listener; leave its unsupported compositor disabled.
            if (callback === dialog?.let { ReflectionHelpers.getField<Consumer<Boolean>>(it, "blurListener") }) {
                onAdd(callback)
                call.getArgument<Executor>(0).execute { callback.accept(true) }
            }
            null
        }.`when`(manager).addCrossWindowBlurEnabledListener(any(Executor::class.java), any<Consumer<Boolean>>())
        doAnswer { call ->
            val callback = call.getArgument<Consumer<Boolean>>(0)
            if (callback === dialog?.let { ReflectionHelpers.getField<Consumer<Boolean>>(it, "blurListener") }) onRemove(callback)
            null
        }
            .`when`(manager).removeCrossWindowBlurEnabledListener(any<Consumer<Boolean>>())
        dialogContext = object : ContextWrapper(dialogContext) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
    }

    private fun backgroundAlpha(popup: ListeningModeDialog): Int = Color.alpha(
        (shadowOf(popup.window!!).backgroundDrawable as GradientDrawable).color!!.defaultColor)

    @Test fun supportedModesAndAllowOffAreCheckedAtDispatchRatherThanOnlyInTheRenderedButtons() {
        val target = device(model = AirPodsModel.AIRPODS_PRO_1)
        devices.value = mapOf(target.macAddress to target)
        val popup = open(target)
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 4))
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 1))
        assertFalse(popup.writeControl(ControlCommandIdentifier.AUTO_ANC_STRENGTH, 30))
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 0))
        assertFalse(popup.writeControl(ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG, 1))
        assertEquals(0, output(target).available())
        target.loadInitialState(target.state.value, AppleSettings(allowOffChoice = true), target.metadata.value)
        assertTrue(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 1))
        assertEquals(1, readCommand(target).value.single().toInt())
        target.loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(4))),
            AppleSettings(), target.metadata.value.copy(model = AirPodsModel.AIRPODS_PRO_3))
        assertFalse(popup.writeControl(ControlCommandIdentifier.AUTO_ANC_STRENGTH, 101))
        assertFalse(popup.writeControl(ControlCommandIdentifier.AUTO_ANC_STRENGTH, -1))
    }

    @Test fun noReportMeansNoInventedSelectionOrCommandsAndPendingIntentDoesNotOverwriteTheReport() {
        val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3)
        assertNull(ListeningModeSnapshot(metadata, AppleState(), AppleSettings()).mode)
        val state = AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(2)),
            requestedControls = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3)))
        assertEquals(3, ListeningModeSnapshot(metadata, state, AppleSettings()).mode)
        assertTrue(ListeningModeSnapshot(metadata, state, AppleSettings()).pending)
        assertEquals(2, state.controlStates[ControlCommandIdentifier.LISTENING_MODE]!!.single().toInt())
        val target = device()
        target.loadInitialState(AppleState(), target.settings.value, target.metadata.value)
        devices.value = mapOf(target.macAddress to target)
        assertFalse(open(target).writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        assertEquals(0, output(target).available())
    }

    @Test fun replacementAtTheSameAddressCannotReceiveAStaleActionEvenBeforeCollectorsRun() {
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = open(target)
        val replacement = device()
        devices.value = mapOf(replacement.macAddress to replacement)
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        idle()
        assertFalse(popup.isShowing)
        assertEquals(0, output(target).available())
        assertEquals(0, output(replacement).available())
    }

    @Test fun disconnectOrRemovalDismissesInsteadOfFallingBackToADifferentConnectedPair() {
        val target = device()
        val other = device("00:11:22:33:44:66")
        devices.value = mapOf(target.macAddress to target, other.macAddress to other)
        val popup = open(target)
        @Suppress("UNCHECKED_CAST")
        val state = AppleDevice::class.java.getDeclaredField("_connectionState").apply { isAccessible = true }
            .get(target) as MutableStateFlow<ConnectionState>
        state.value = ConnectionState.DISCONNECTED
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        idle()
        assertFalse(popup.isShowing)
        assertEquals(0, output(other).available())
    }

    @Test fun closingReleasesBindingAndCollectorsAndRetiresActions() {
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = open(target)
        val token = islandOwnership().single().first
        assertTrue(islandOwnership().single().second)
        assertEquals(1, shadowOf(context).boundServiceConnections.size)
        popup.dismiss()
        idle()
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
        assertNull(ListeningModeDialog::class.java.getDeclaredField("observation").apply { isAccessible = true }.get(popup))
        val scope = ListeningModeDialog::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(popup) as CoroutineScope
        assertFalse(scope.coroutineContext[Job]!!.isActive)
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        assertEquals(0, output(target).available())
        val ownership = islandOwnership()
        assertEquals(2, ownership.size)
        assertSame("The same dialog token must release its own suppression", token, ownership.last().first)
        assertFalse(ownership.last().second)
        popup.dismiss()
        idle()
        assertEquals("Repeated dismissal cannot release another popup's suppression", ownership, islandOwnership())
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
    }

    @Test fun aLockedPhoneCannotOpenControls() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = open(target)
        assertFalse(popup.isShowing)
        assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
        assertTrue(islandOwnership().isEmpty())
    }

    @Test fun failedWindowAttachmentStillReleasesResourcesAcquiredByOnStart() {
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = ListeningModeDialog(dialogContext, target.macAddress).also { dialog = it }
        popup.create()
        ReflectionHelpers.callInstanceMethod<Void>(popup, "onStart")
        idle()
        assertFalse(popup.isShowing)
        assertEquals(1, shadowOf(context).boundServiceConnections.size)
        val token = islandOwnership().single().first
        assertTrue(islandOwnership().single().second)

        popup.dismiss()
        idle()
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
        assertNull(ListeningModeDialog::class.java.getDeclaredField("observation").apply { isAccessible = true }.get(popup))
        val scope = ListeningModeDialog::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(popup) as CoroutineScope
        assertFalse(scope.coroutineContext[Job]!!.isActive)
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        assertEquals(0, output(target).available())
        val ownership = islandOwnership()
        assertEquals(2, ownership.size)
        assertSame(token, ownership.last().first)
        assertFalse(ownership.last().second)

        popup.dismiss()
        idle()
        assertEquals(ownership, islandOwnership())
        assertEquals(1, shadowOf(context).unboundServiceConnections.size)
    }

    @Test fun lockingBetweenConstructionAndShowCannotAcquireAnIslandLeaseOrBind() {
        val target = device()
        devices.value = mapOf(target.macAddress to target)
        val popup = ListeningModeDialog(dialogContext, target.macAddress).also { dialog = it }
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        popup.show()
        idle()
        assertFalse(popup.isShowing)
        assertTrue(islandOwnership().isEmpty())
        assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
    }

    @Test fun missingOrUnsupportedPairClosesWithoutSubstitutingAnotherDevice() {
        val target = device(model = AirPodsModel.AIRPODS_4)
        val other = device("00:11:22:33:44:66")
        devices.value = mapOf(other.macAddress to other)
        val missing = open(target)
        assertFalse(missing.isShowing)
        assertFalse(missing.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        devices.value = mapOf(target.macAddress to target, other.macAddress to other)
        val popup = open(target)
        assertFalse(popup.isShowing)
        assertFalse(popup.writeControl(ControlCommandIdentifier.LISTENING_MODE, 2))
        assertEquals(0, output(target).available())
        assertEquals(0, output(other).available())
    }

    private fun open(target: AppleDevice): ListeningModeDialog {
        val popup = ListeningModeDialog(dialogContext, target.macAddress).also { dialog = it }
        popup.show()
        idle()
        return popup
    }

    private fun islandOwnership() = mockingDetails(island).invocations
        .filter { it.method.name == "setListeningPickerVisible" }
        .map { it.arguments[0] to (it.arguments[1] as Boolean) }

    private fun device(address: String = "00:11:22:33:44:55", model: AirPodsModel = AirPodsModel.AIRPODS_PRO_3): AppleDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice(address), ConnectionState.CONNECTED).apply {
            loadInitialState(AppleState(controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3))),
                AppleSettings(allowOffChoice = false), AppleMetadata(name = "My AirPods", model = model, modelNumber = "A3063"))
        }
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        sockets += device to socket
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        return device
    }

    private fun output(device: AppleDevice) = shadowOf(sockets.first { it.first === device }.second).outputStreamSink
    private fun readCommand(device: AppleDevice) = output(device).let { input ->
        val bytes = ByteArray(input.available())
        assertTrue(bytes.isNotEmpty())
        assertEquals(bytes.size, input.read(bytes))
        ControlCommandPacket.parse(bytes).controlCommand
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
