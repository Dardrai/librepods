package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivilegedAccessSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resumeReconnectsSilentlyAndDisposalRetiresOnlyTheOwnedClient() {
        val visible = mutableStateOf(true)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        mockConstruction(ShizukuAudioClient::class.java).use { clients ->
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) rememberPrivilegedAccessClient()
                }
            }
            compose.waitForIdle()
            assertEquals(1, clients.constructed().size)
            val client = clients.constructed().single()
            compose.runOnIdle {
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            verify(client).connect()
            compose.runOnIdle {
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            verify(client, times(2)).connect()
            verify(client, never()).requestPermission()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            verify(client).close()
            compose.runOnIdle {
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            verify(client, times(2)).connect()
        }
    }

    @Test fun callerOwnedClientRequestsAccessOnlyAfterATapAndIsNotClosedByTheRow() {
        val visible = mutableStateOf(true)
        val client = mock(ShizukuAudioClient::class.java)
        val state = MutableStateFlow(BridgeState())
        `when`(client.state).thenReturn(state)
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                if (visible.value) PrivilegedAccessSetting(client)
            }
        }
        compose.onNodeWithText("Use root or Shizuku for seamless pairing and media controls.").assertIsDisplayed()
        verify(client, never()).requestPermission()
        compose.onNodeWithText("Bluetooth access").performClick()
        verify(client).requestPermission()
        compose.runOnIdle {
            state.value = BridgeState(availability = "Connected through root", connected = true, backend = "root")
        }
        compose.onNodeWithText("Connected through root").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        verify(client, never()).close()
    }
}
