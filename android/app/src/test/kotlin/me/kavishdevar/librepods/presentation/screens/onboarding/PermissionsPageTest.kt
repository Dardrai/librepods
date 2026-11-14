/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.onboarding

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PermissionsPageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val bluetooth = setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE)
    private val phone = setOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

    private class Requests : ActivityResultRegistry(), ActivityResultRegistryOwner {
        data class Request(val code: Int, val contract: ActivityResultContract<*, *>, val input: Any?)
        override val activityResultRegistry get() = this
        val launched = mutableListOf<Request>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            launched += Request(requestCode, contract, input)
        }
        fun permissions(): Set<String> = when (val value = launched.last().input) {
            is Array<*> -> value.map { it as String }.toSet()
            is String -> setOf(value)
            else -> error("Expected a runtime permission request, received $value")
        }
        fun answerRuntime(granted: Boolean) {
            val request = launched.last()
            val permissions = permissions()
            val app = shadowOf(RuntimeEnvironment.getApplication())
            if (granted) app.grantPermissions(*permissions.toTypedArray()) else app.denyPermissions(*permissions.toTypedArray())
            val result: Any = if (request.contract is ActivityResultContracts.RequestPermission) granted
                else permissions.associateWith { granted }
            assertTrue(dispatchResult(request.code, result))
        }
        fun answerOverlay() {
            assertTrue(launched.last().contract is ActivityResultContracts.StartActivityForResult)
            assertTrue(dispatchResult(launched.last().code, ActivityResult(Activity.RESULT_OK, null)))
        }
    }

    @Before fun denyPermissions() {
        shadowOf(context).denyPermissions(*(bluetooth + phone + Manifest.permission.POST_NOTIFICATIONS +
            Manifest.permission.RECORD_AUDIO).toTypedArray())
        ShadowSettings.setCanDrawOverlays(false)
        if (Build.VERSION.SDK_INT == 33) assertNotEquals(HealthConnectClient.SDK_AVAILABLE, HealthConnectClient.getSdkStatus(context))
    }

    @Test fun grantAllKeepsItsSequenceAcrossRecompositionAndRequestsTheBridgeOnlyAfterOverlayReturns() {
        val requests = Requests()
        val revision = mutableStateOf(0)
        val visible = mutableStateOf(true)
        mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
            `when`(client.state).thenReturn(MutableStateFlow(BridgeState()))
        }.use { clients ->
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides requests) {
                    LibrePodsTheme(designSystem = DesignSystem.Material) {
                        val currentRevision = revision.value
                        if (visible.value) PermissionsPage({}, { assertTrue(currentRevision >= 0) })
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.permission_healthconnect)).assertDoesNotExist()
            assertEquals(0, requests.launched.size)
            val client = clients.constructed().single()
            verify(client, never()).requestPermission()
            compose.onNodeWithText("Grant all").performScrollTo().performClick()
            val expected = listOf(bluetooth, setOf(Manifest.permission.POST_NOTIFICATIONS), phone,
                setOf(Manifest.permission.RECORD_AUDIO))
            expected.forEachIndexed { index, permissions ->
                awaitRequests(requests, index + 1)
                assertEquals(permissions, requests.permissions())
                compose.runOnIdle { revision.value++ }
                compose.waitForIdle()
                assertEquals("A redraw must not launch a second permission dialog", index + 1, requests.launched.size)
                compose.runOnIdle { requests.answerRuntime(true) }
            }
            awaitRequests(requests, 5)
            val overlay = requests.launched.last().input as Intent
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
            assertEquals("package:${context.packageName}", overlay.data.toString())
            verify(client, never()).requestPermission()
            compose.runOnIdle {
                ShadowSettings.setCanDrawOverlays(true)
                requests.answerOverlay()
            }
            compose.waitForIdle()
            verify(client).requestPermission()
            compose.runOnIdle {
                (client.state as MutableStateFlow<BridgeState>).value = BridgeState(availability = "Start Shizuku before connecting")
            }
            compose.onNodeWithText("Start Shizuku before connecting").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("forward").assertIsEnabled()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            verify(client).close()
        }
    }

    @Test fun deniedOptionalPermissionsAdvanceOnceAndDoNotPreventFinishingWithBluetooth() {
        val requests = Requests()
        mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
            `when`(client.state).thenReturn(MutableStateFlow(BridgeState()))
        }.use { clients ->
            show(requests)
            compose.onNodeWithText("Grant all").performScrollTo().performClick()
            awaitRequests(requests, 1)
            compose.runOnIdle { requests.answerRuntime(true) }
            for (count in 2..4) {
                awaitRequests(requests, count)
                compose.runOnIdle { requests.answerRuntime(false) }
            }
            awaitRequests(requests, 5)
            compose.runOnIdle { requests.answerOverlay() }
            compose.waitForIdle()
            assertEquals("Denied optional permissions must not be requested in a loop", 5, requests.launched.size)
            verify(clients.constructed().single()).requestPermission()
            compose.onNodeWithContentDescription("forward").assertIsEnabled()
        }
    }

    @Test fun individualMicrophoneRequestDoesNotRequestOtherPermissionsOrRootAndBlocksOverlappingGrantAll() {
        val requests = Requests()
        mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
            `when`(client.state).thenReturn(MutableStateFlow(BridgeState()))
        }.use { clients ->
            show(requests)
            compose.onNodeWithText(context.getString(R.string.microphone)).performScrollTo().performClick()
            awaitRequests(requests, 1)
            assertEquals(setOf(Manifest.permission.RECORD_AUDIO), requests.permissions())
            compose.onNodeWithText("Grant all").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { requests.answerRuntime(false) }
            compose.waitForIdle()
            assertEquals(1, requests.launched.size)
            verify(clients.constructed().single(), never()).requestPermission()
            compose.onNodeWithText("Grant all").assertIsEnabled()
            compose.onNodeWithContentDescription("forward").assertIsNotEnabled()
        }
    }

    @Test fun returningFromIndividualOverlayRequestRefreshesItsGrantWithoutStartingOtherSetup() {
        val requests = Requests()
        mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
            `when`(client.state).thenReturn(MutableStateFlow(BridgeState()))
        }.use { clients ->
            show(requests)
            val overlay = compose.onNodeWithText(context.getString(R.string.permission_overlay))
            overlay.performScrollTo().performClick()
            awaitRequests(requests, 1)
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, (requests.launched.single().input as Intent).action)
            compose.onNodeWithText("Grant all").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle {
                ShadowSettings.setCanDrawOverlays(true)
                requests.answerOverlay()
            }
            compose.waitForIdle()
            overlay.assertIsNotEnabled()
            assertEquals(1, requests.launched.size)
            verify(clients.constructed().single(), never()).requestPermission()
        }
    }

    @Test fun savedStateRestorationKeepsTheExistingPermissionRequestInsteadOfLaunchingItTwice() {
        val requests = Requests()
        val restoration = StateRestorationTester(compose)
        mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
            `when`(client.state).thenReturn(MutableStateFlow(BridgeState()))
        }.use { clients ->
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides requests) {
                    LibrePodsTheme(designSystem = DesignSystem.Material) { PermissionsPage({}, {}) }
                }
            }
            compose.onNodeWithText("Grant all").performScrollTo().performClick()
            awaitRequests(requests, 1)
            assertEquals(bluetooth, requests.permissions())
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            assertEquals("The restored page must wait for the already launched Android dialog", 1, requests.launched.size)
            verify(clients.constructed().first()).close()
            compose.runOnIdle { requests.answerRuntime(true) }
            awaitRequests(requests, 2)
            assertEquals(setOf(Manifest.permission.POST_NOTIFICATIONS), requests.permissions())
            clients.constructed().forEach { verify(it, never()).requestPermission() }
        }
    }

    @Test @Config(sdk = [34])
    fun availableHealthConnectUsesItsOwnContractAndDenialStillAllowsOptionalSetupToFinish() {
        shadowOf(context).setSystemService("healthconnect", Any())
        assertEquals(HealthConnectClient.SDK_AVAILABLE, HealthConnectClient.getSdkStatus(context))
        shadowOf(context).grantPermissions(*(bluetooth + phone + Manifest.permission.POST_NOTIFICATIONS +
            Manifest.permission.RECORD_AUDIO).toTypedArray())
        ShadowSettings.setCanDrawOverlays(true)
        val permissionController = mock(PermissionController::class.java)
        runBlocking { `when`(permissionController.getGrantedPermissions()).thenReturn(emptySet()) }
        val implementation = Class.forName("androidx.health.connect.client.impl.HealthConnectClientUpsideDownImpl")
            .asSubclass(HealthConnectClient::class.java)
        val requests = Requests()
        mockConstruction(implementation) { client, _ ->
            `when`(client.permissionController).thenReturn(permissionController)
        }.use {
            mockConstruction(ShizukuAudioClient::class.java) { client, _ ->
                `when`(client.state).thenReturn(MutableStateFlow(BridgeState()))
            }.use { clients ->
                show(requests)
                compose.onNodeWithText("Grant all").performScrollTo().performClick()
                awaitRequests(requests, 1)
                val request = requests.launched.single()
                assertTrue("Health Connect needs its provider-aware contract", request.contract.javaClass.name.startsWith("androidx.health.connect."))
                assertEquals(setOf(HealthPermission.getWritePermission(HeartRateRecord::class)), request.input)
                verify(clients.constructed().single(), never()).requestPermission()
                compose.runOnIdle { assertTrue(requests.dispatchResult(request.code, emptySet<String>())) }
                compose.waitForIdle()
                verify(clients.constructed().single()).requestPermission()
                assertEquals(1, requests.launched.size)
                compose.onNodeWithContentDescription("forward").assertIsEnabled()
            }
        }
    }

    private fun show(requests: Requests) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides requests) {
                LibrePodsTheme(designSystem = DesignSystem.Material) { PermissionsPage({}, {}) }
            }
        }
    }

    private fun awaitRequests(requests: Requests, count: Int) {
        compose.waitForIdle()
        try {
            compose.waitUntil(5_000) { requests.launched.size >= count }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected request $count; launched ${requests.launched.map { it.input.let { input -> if (input is Array<*>) input.toList() else input } }}", timeout)
        }
        assertEquals(count, requests.launched.size)
    }
}
