package me.kavishdevar.librepods.services

import android.app.Application
import android.content.Context
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class HeadGestureCallTest {
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val device = mock(AppleDevice::class.java)
    private val telecom = mock(TelecomManager::class.java)
    private val settings = MutableStateFlow(AppleSettings(headGesturesEnabled = false))
    private var gesture: ((Boolean) -> Unit)? = null

    init {
        `when`(device.settings).thenReturn(settings)
        `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
        `when`(device.state).thenReturn(MutableStateFlow(AppleState(componentState = setOf(
            DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR)))))
        doAnswer { invocation -> gesture = invocation.getArgument(0); null }
            .`when`(device).detectHeadGestures(anyGesture())
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(MacAddress("00:11:22:33:44:55") to device)
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.TELECOM_SERVICE, telecom)
    }

    @After fun close() { (field("serviceScope").get(service) as CoroutineScope).cancel() }

    @Test fun disabledGesturesNeverStartCallDetectionAndTurningThemOffRetiresItsCallback() {
        ringing()
        verify(device, never()).detectHeadGestures(anyGesture())
        settings.value = settings.value.copy(headGesturesEnabled = true)
        ringing()
        verify(device).detectHeadGestures(anyGesture())
        val callback = checkNotNull(gesture)
        settings.value = settings.value.copy(headGesturesEnabled = false)
        callback(true)
        callback(false)
        verifyNoInteractions(telecom)
        verify(device, never()).stopHeadTracking()
    }

    @Suppress("DEPRECATION")
    @Test fun enabledGesturesCanAnswerAndRejectTheRingingCall() {
        settings.value = settings.value.copy(headGesturesEnabled = true)
        ringing()
        checkNotNull(gesture)(true)
        verify(telecom).acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)
        ringing()
        checkNotNull(gesture)(false)
        verify(telecom).endCall()
        verify(device, times(2)).stopHeadTracking()
    }

    @Test fun endedOrAnsweredCallsRetireQueuedGestureCallbacks() {
        settings.value = settings.value.copy(headGesturesEnabled = true)
        for (state in listOf(TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_OFFHOOK)) {
            ringing()
            val callback = checkNotNull(gesture)
            (field("telephonyCallback").get(service) as TelephonyCallback.CallStateListener).onCallStateChanged(state)
            callback(true)
            callback(false)
        }
        verifyNoInteractions(telecom)
        verify(device, times(2)).stopHeadTracking()
    }

    private fun ringing() = (field("telephonyCallback").get(service) as TelephonyCallback.CallStateListener)
        .onCallStateChanged(TelephonyManager.CALL_STATE_RINGING)

    private fun anyGesture(): (Boolean) -> Unit = any<(Boolean) -> Unit>() ?: {}

    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
