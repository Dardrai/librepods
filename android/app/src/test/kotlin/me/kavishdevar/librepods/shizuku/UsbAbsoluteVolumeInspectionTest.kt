/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class UsbAbsoluteVolumeInspectionTest {
    @Test fun systemServerChecksItsOwnRouteAndBoardWithoutReadingRootOnlySysfs() {
        ShadowProcess.setUid(1000)
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.id).thenReturn(7); `when`(device.type).thenReturn(AudioDeviceInfo.TYPE_USB_HEADSET)
        `when`(device.address).thenReturn("card=1;device=0")
        val attrs = Class.forName("android.media.AudioDeviceAttributes")
            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            .newInstance(2, AudioDeviceInfo.TYPE_USB_HEADSET, "card=1;device=0")
        val audio = mock(AudioManager::class.java) { call -> when (call.method.name) {
            "getMode" -> AudioManager.MODE_NORMAL
            "getDevices" -> arrayOf(device)
            "getDevicesForAttributes" -> listOf(attrs)
            else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
        } }
        val board = mock(UsbDevice::class.java)
        `when`(board.vendorId).thenReturn(0xcafe); `when`(board.productId).thenReturn(0x4015)
        `when`(board.serialNumber).thenReturn("BOARD_A"); `when`(board.interfaceCount).thenReturn(1)
        val intf = mock(UsbInterface::class.java)
        `when`(intf.interfaceClass).thenReturn(1); `when`(board.getInterface(0)).thenReturn(intf)
        val usb = mock(UsbManager::class.java)
        `when`(usb.deviceList).thenReturn(hashMapOf("board" to board))
        val context = mock(Context::class.java)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(context.getSystemService(UsbManager::class.java)).thenReturn(usb)
        assertTrue(UsbAbsoluteVolumeInspection.routeEligible(context, 7, "BOARD_A", "card=1;device=0", listOf(device)))
        assertFalse(UsbAbsoluteVolumeInspection.routeEligible(context, 7, "BOARD_A", "card=2;device=0", listOf(device)))
        assertFalse(UsbAbsoluteVolumeInspection.routeEligible(context, 8, "BOARD_A", "card=1;device=0", listOf(device)))
        assertFalse(UsbAbsoluteVolumeInspection.routeEligible(context, 7, "BOARD_B", "card=1;device=0", listOf(device)))
        assertFalse(UsbAbsoluteVolumeInspection.routeEligible(context, 7, "BOARD_A", "card=1;device=0", listOf(device, device)))
        verify(audio, never()).getDevices(anyInt())
        assertThrows(IllegalStateException::class.java) {
            UsbAbsoluteVolumeInspection.verifiedAddress(context, 7, "BOARD_A")
        }
    }
}
