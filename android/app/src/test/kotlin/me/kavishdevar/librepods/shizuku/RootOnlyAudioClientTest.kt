/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.topjohnwu.superuser.Shell
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RootOnlyAudioClientTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun rootOnlyConnectWithNoPriorAuthorizationCannotPromptOrUseShizuku() {
        context.getSharedPreferences("privileged_audio", Context.MODE_PRIVATE).edit().clear().commit()
        mockStatic(Shell::class.java).use { root ->
            root.`when`<Boolean?> { Shell.isAppGrantedRoot() }.thenReturn(null)
            mockStatic(Shizuku::class.java).use { shizuku ->
                mockConstruction(RootAudioConnection::class.java).use { connections ->
                    val client = ShizukuAudioClient(context, requireRoot = true)
                    try {
                        client.connect(); client.connect()
                        assertEquals("Root access required", client.state.value.availability)
                        assertFalse(client.state.value.connected)
                        verify(connections.constructed().single(), never()).start()
                        shizuku.verifyNoInteractions()
                    } finally { client.close() }
                    shizuku.verifyNoInteractions()
                }
            }
        }
    }

    @Test fun explicitRootOnlyRequestAttemptsAndCanRetryWithoutAnyShizukuFallback() {
        context.getSharedPreferences("privileged_audio", Context.MODE_PRIVATE).edit().clear().commit()
        mockStatic(Shell::class.java).use { root ->
            root.`when`<Boolean?> { Shell.isAppGrantedRoot() }.thenReturn(false)
            mockStatic(Shizuku::class.java).use { shizuku ->
                lateinit var unavailable: () -> Unit
                mockConstruction(RootAudioConnection::class.java) { _, call ->
                    @Suppress("UNCHECKED_CAST")
                    unavailable = call.arguments()[3] as () -> Unit
                }.use { connections ->
                    val client = ShizukuAudioClient(context, requireRoot = true)
                    try {
                        client.connect()
                        verify(connections.constructed().single(), never()).start()
                        client.requestPermission()
                        assertEquals("Requesting root access", client.state.value.availability)
                        verify(connections.constructed().single()).start()
                        unavailable()
                        assertEquals("Root access required", client.state.value.availability)
                        assertFalse(client.state.value.connected)
                        client.requestPermission()
                        verify(connections.constructed().single(), times(2)).start()
                        shizuku.verifyNoInteractions()
                    } finally { client.close() }
                    shizuku.verifyNoInteractions()
                }
            }
        }
    }

    @Test fun rootOnlyConnectCanReuseAlreadyGrantedRootWithoutShizuku() {
        mockStatic(Shell::class.java).use { root ->
            root.`when`<Boolean?> { Shell.isAppGrantedRoot() }.thenReturn(true)
            mockStatic(Shizuku::class.java).use { shizuku ->
                mockConstruction(RootAudioConnection::class.java).use { connections ->
                    val client = ShizukuAudioClient(context, requireRoot = true)
                    try {
                        client.connect()
                        assertEquals("Starting root audio service", client.state.value.availability)
                        verify(connections.constructed().single()).start()
                        shizuku.verifyNoInteractions()
                    } finally { client.close() }
                }
            }
        }
    }

    @Test fun originalContextConstructorStillFallsBackToAlreadyGrantedShizukuAfterRootFailure() {
        assertNotNull(ShizukuAudioClient::class.java.getConstructor(Context::class.java))
        mockStatic(Shell::class.java).use { root ->
            root.`when`<Boolean?> { Shell.isAppGrantedRoot() }.thenReturn(true)
            mockStatic(Shizuku::class.java).use { shizuku ->
                shizuku.`when`<Boolean> { Shizuku.pingBinder() }.thenReturn(true)
                shizuku.`when`<Int> { Shizuku.checkSelfPermission() }.thenReturn(PackageManager.PERMISSION_GRANTED)
                shizuku.`when`<Int> { Shizuku.getVersion() }.thenReturn(13)
                lateinit var unavailable: () -> Unit
                mockConstruction(RootAudioConnection::class.java) { _, call ->
                    @Suppress("UNCHECKED_CAST")
                    unavailable = call.arguments()[3] as () -> Unit
                }.use {
                    val client = ShizukuAudioClient(context)
                    try {
                        client.connect(); unavailable()
                        assertEquals("Starting audio service", client.state.value.availability)
                        val args = ShizukuAudioClient::class.java.getDeclaredField("args").apply { isAccessible = true }
                            .get(client) as Shizuku.UserServiceArgs
                        val connection = ShizukuAudioClient::class.java.getDeclaredField("connection").apply { isAccessible = true }
                            .get(client) as android.content.ServiceConnection
                        shizuku.verify { Shizuku.bindUserService(args, connection) }
                        shizuku.verify({ Shizuku.requestPermission(anyInt()) }, never())
                    } finally { client.close() }
                }
            }
        }
    }
}
