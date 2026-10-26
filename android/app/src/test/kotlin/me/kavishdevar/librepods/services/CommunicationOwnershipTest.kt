package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.AirPodsMicrophone
import me.kavishdevar.librepods.audio.CallPcmConsumer
import me.kavishdevar.librepods.audio.CommunicationAudioController
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophoneState
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CommunicationOwnershipTest {
    private class Fixture : AutoCloseable {
        val service = spy(Robolectric.buildService(LibrePodsService::class.java).get())
        val app = LibrePodsApplication()
        val address = MacAddress("00:11:22:33:44:55")
        val device = mock(AppleDevice::class.java)
        val aacp = mock(AACPManager::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val bridge = mock(ShizukuAudioClient::class.java)
        val microphone = mock(AirPodsMicrophone::class.java)
        val call = mock(CommunicationAudioController::class.java)
        val consumer = mock(CallPcmConsumer::class.java)
        val callStarts = AtomicInteger()
        val controller = mock(ExperimentalAudioController::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name.startsWith("startLiveForCall")) {
                val current = invocation.getArgument<() -> Boolean>(1)
                if (current()) { callStarts.incrementAndGet(); true } else false
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        @Suppress("UNCHECKED_CAST")
        val armed get() = field("communicationArmed").get(service) as MutableMap<MacAddress, CommunicationAudioController>
        init {
            ReflectionHelpers.setField(service, "mApplication", app)
            doNothing().`when`(service).startForegroundNotification()
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            `when`(device.macAddress).thenReturn(address)
            `when`(device.aacp).thenReturn(aacp)
            `when`(aacp.captureAudioSocket()).thenReturn(socket)
            `when`(aacp.ownsAudioSocket(socket)).thenReturn(true)
            `when`(controller.microphone).thenReturn(microphone)
            `when`(controller.bridge).thenReturn(bridge)
            `when`(bridge.hasRootPrivileges).thenReturn(true)
            `when`(bridge.isMicrophonePrivacyBlocked()).thenReturn(false)
            `when`(microphone.state).thenReturn(MutableStateFlow(MicrophoneState()))
            `when`(call.hasPreparedRoute).thenReturn(true)
            `when`(call.ownsInput(consumer)).thenReturn(true)
            app.experimentalAudioControllers[address] = controller
            app.communicationAudioControllers[address] = call
            @Suppress("UNCHECKED_CAST")
            (field("audioDevices").get(service) as MutableMap<MacAddress, AppleDevice>)[address] = device
            service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        }
        fun arm(owner: CommunicationAudioController, active: Boolean) =
            method("armCommunication", String::class.java, CommunicationAudioController::class.java,
                Boolean::class.javaPrimitiveType!!).invoke(service, address.value, owner, active) as Boolean
        fun start(owner: CommunicationAudioController = call, current: () -> Boolean = { true }) =
            method("startCallMicrophone", AppleDevice::class.java, CommunicationAudioController::class.java,
                CallPcmConsumer::class.java, Function0::class.java).invoke(service, device, owner, consumer, current) as AutoCloseable?
        fun permissionAllowed() = method("microphonePermissionAllowed", String::class.java).invoke(service, address.value) as Boolean
        override fun close() {
            (field("serviceScope").get(service) as CoroutineScope).cancel()
            app.experimentalAudioControllers.clear(); app.communicationAudioControllers.clear(); armed.clear()
        }
        private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
        private fun method(name: String, vararg types: Class<*>) = LibrePodsService::class.java.declaredMethods.single {
            (it.name == name || it.name.startsWith("$name-")) && it.parameterTypes.contentEquals(types)
        }.apply { isAccessible = true }
    }

    @Test fun delayedOldCleanupCannotRemoveTheReplacementForegroundReservation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            val old = mock(CommunicationAudioController::class.java)
            f.armed[f.address] = f.call
            assertTrue(f.arm(old, false))
            assertSame(f.call, f.armed[f.address])
            verify(f.service, never()).startForegroundNotification()
            assertTrue(f.arm(f.call, false))
            assertFalse(f.armed.containsKey(f.address))
            verify(f.service).startForegroundNotification()
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun oldOrUnregisteredControllerCannotAcquireTheReplacementReservation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            val old = mock(CommunicationAudioController::class.java)
            f.armed[f.address] = f.call
            assertFalse(f.arm(old, true))
            assertSame(f.call, f.armed[f.address])
            f.app.communicationAudioControllers.remove(f.address)
            assertFalse(f.arm(f.call, true))
            verify(f.service, never()).startForegroundNotification()
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun activeCallAdmissionNeedsTheExactPreparedControllerConsumerAndSocket() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            assertNull(f.service.startLiveMicrophone(f.device, f.consumer))
            `when`(f.call.hasPreparedRoute).thenReturn(false)
            assertNull(f.start())
            `when`(f.call.hasPreparedRoute).thenReturn(true)
            `when`(f.call.ownsInput(f.consumer)).thenReturn(false)
            assertNull(f.start())
            `when`(f.call.ownsInput(f.consumer)).thenReturn(true)
            assertNull(f.start(current = { false }))
            assertNull(f.start(mock(CommunicationAudioController::class.java)))
            `when`(f.aacp.ownsAudioSocket(f.socket)).thenReturn(false)
            assertNull(f.start())
            assertEquals(0, f.callStarts.get())
            verify(f.consumer, never()).close()
            `when`(f.aacp.ownsAudioSocket(f.socket)).thenReturn(true)
            val handle = checkNotNull(f.start())
            assertEquals(1, f.callStarts.get())
            verify(f.controller, never()).startLive(f.consumer)
            handle.close(); handle.close()
            verify(f.controller, times(1)).stopLive(f.consumer)
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun callMuteExceptionRequiresAReadyRouteOrItsExactClosingCaptureAndNeverBypassesPrivacy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Fixture().use { f ->
            f.service.getSystemService(AudioManager::class.java).isMicrophoneMute = true
            assertTrue(f.permissionAllowed())
            `when`(f.call.hasPreparedRoute).thenReturn(false)
            assertFalse(f.permissionAllowed())
            `when`(f.call.ownsMicrophoneCapture).thenReturn(true)
            assertTrue(f.permissionAllowed())
            `when`(f.bridge.isMicrophonePrivacyBlocked()).thenReturn(true)
            assertFalse(f.permissionAllowed())
            `when`(f.bridge.isMicrophonePrivacyBlocked()).thenReturn(false)
            f.app.communicationAudioControllers.remove(f.address)
            assertFalse(f.permissionAllowed())
        } } finally { Dispatchers.resetMain() }
    }
}
