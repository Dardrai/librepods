/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class UsbVolumeHostLoaderTest {
    private val name = "me.kavishdevar.librepods.utils.IsolatedUsbAudioService"
    private val host = object : ClassLoader(null) {
        override fun findClass(name: String): Class<*> {
            if (!name.startsWith(this@UsbVolumeHostLoaderTest.name)) throw ClassNotFoundException(name)
            val bytes = javaClass.getResourceAsStream("/${name.replace('.', '/')}.class")?.use { it.readBytes() }
                ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
    @Test fun callbackTypeComesFromTheResolvedHostMethodWithoutModuleLoaderLookup() {
        val service = host.loadClass(name)
        val info = service.getDeclaredMethod("getAbsoluteVolumeDeviceInfo", Any::class.java)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName(info.returnType.name, false, object : ClassLoader(null) {})
        }
        val field = usbPolicyCallbackField(info, Runnable::class.java)
        assertSame(host, field.declaringClass.classLoader)
        assertSame(info.returnType, field.declaringClass)
        assertEquals("mCallback", field.name)
    }
    @Test fun unknownCallbackLayoutIsRejectedBeforeAnEndpointCanMutateVolume() {
        val info = host.loadClass(name).getDeclaredMethod("getAbsoluteVolumeDeviceInfo", Any::class.java)
        assertThrows(IllegalArgumentException::class.java) { usbPolicyCallbackField(info, String::class.java) }
    }
}
