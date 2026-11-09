/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.bluetooth.HearingModeSocketShadow
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HearingModeSocketShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeQuickControlsTest {
    private val awareness = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
    private val mode = ControlCommandIdentifier.LISTENING_MODE
    private val strength = ControlCommandIdentifier.AUTO_ANC_STRENGTH
    private val ear = ControlCommandIdentifier.EAR_DETECTION_CONFIG

    @Test fun blockedBluetoothWritesLeaveEditsImmediateAndCoalesceOnlyTheUnsentTail() = runBlocking<Unit> {
        fixture { f ->
            assertTrue(f.writes().isEmpty())
            f.blockNextWrite()
            assertTrue(f.controls.edit(awareness, 1))
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            assertEquals(1.toByte(), f.controls.displayState(f.state.value).requestedControls[awareness]!!.first())
            val started = System.nanoTime()
            assertTrue(f.controls.edit(awareness, 2))
            assertTrue(f.controls.edit(awareness, 1))
            assertTrue(f.controls.edit(awareness, 2))
            assertTrue("Main must not wait for the blocked Bluetooth writer", (System.nanoTime() - started) / 1_000_000 < 250)
            assertEquals(2.toByte(), f.controls.displayState(f.state.value).requestedControls[awareness]!!.first())
            assertEquals(listOf(awareness to 1), f.writes())
            f.release.countDown()
            f.await { f.writes().size == 2 }
            f.awaitSettled()
            assertEquals(listOf(awareness to 1, awareness to 2), f.writes())
            assertTrue(f.failures.isEmpty())
        }
    }

    @Test fun queuedWritesCannotMoveToAReplacementOwnerOrSurviveDismissal() = runBlocking<Unit> {
        for (replacement in listOf("socket", "session", "connection", "admission", "dismiss")) fixture { f ->
            f.blockNextWrite()
            assertTrue(f.controls.edit(awareness, 1))
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            assertTrue(f.controls.edit(awareness, 2))
            assertTrue(f.controls.edit(ear, 1))
            when (replacement) {
                "socket" -> f.replaceSocket()
                "session" -> f.state.value = f.state.value.copy(controlSession = 2)
                "connection" -> f.connection.value++
                "admission" -> f.admitted.set(false)
                else -> f.scope.cancel()
            }
            f.controls.observe()
            f.release.countDown()
            delay(250)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("No queued write may cross $replacement", listOf(awareness to 1), f.writes())
            assertTrue("An unaccepted queued ear choice cannot persist", f.device.settings.value.earDetectionEnabled)
            if (replacement == "dismiss") assertFalse(f.controls.edit(ear, 1))
        }
    }

    @Test fun adaptiveModePrecedesItsStrengthAndAChangedModeRetiresQueuedStrength() = runBlocking<Unit> {
        fixture { f ->
            f.blockNextWrite()
            assertTrue(f.controls.edit(mode, 4))
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            assertTrue(f.controls.edit(strength, 73))
            f.release.countDown()
            f.await { f.writes().size == 2 }
            assertEquals(listOf(mode to 4, strength to 73), f.writes())
        }
        fixture { f ->
            f.blockNextWrite()
            assertTrue(f.controls.edit(mode, 4))
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            assertTrue(f.controls.edit(strength, 73))
            assertTrue(f.controls.edit(mode, 2))
            f.release.countDown()
            f.await { f.writes().size == 2 }
            delay(200)
            assertEquals(listOf(mode to 4, mode to 2), f.writes())
            assertFalse(f.controls.edit(strength, 51))
            assertTrue(f.controls.edit(mode, 4))
            f.await { f.writes().size == 3 }
            delay(200)
            assertEquals("Returning to Adaptive must not replay an abandoned strength gesture",
                listOf(mode to 4, mode to 2, mode to 4), f.writes())
        }
        fixture { f ->
            f.blockNextWrite()
            assertTrue(f.controls.edit(mode, 4))
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            assertTrue(f.controls.edit(strength, 73))
            withTimeout(2_000) { f.controls.changes.first { values -> values.count { it.pending } == 2 } }
            assertTrue(f.controls.edit(mode, 2))
            assertTrue(f.controls.edit(mode, 4))
            f.release.countDown()
            f.awaitSettled()
            assertEquals("A queued strength cannot survive leaving and returning to Adaptive", listOf(mode to 4), f.writes())
            assertTrue(f.controls.edit(strength, 51))
            f.await { f.writes().size == 2 }
            assertEquals(listOf(mode to 4, strength to 51), f.writes())
        }
    }

    @Test fun earPreferenceChangesOnlyAfterAnAcceptedOwnedWrite() = runBlocking<Unit> {
        fixture { f ->
            f.blockNextWrite()
            assertTrue(f.controls.edit(ear, 1))
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            assertTrue(f.device.settings.value.earDetectionEnabled)
            f.release.countDown()
            f.await { !f.device.settings.value.earDetectionEnabled }
            assertEquals("off", f.device.settings.value.savedUnconfirmedChoices["desired_ear_detection"])
        }
        fixture { f ->
            f.shadow.output.onPacket = { throw IOException("Rejected synthetic write") }
            assertTrue(f.controls.edit(ear, 1))
            f.await { f.failures.isNotEmpty() }
            assertTrue(f.device.settings.value.earDetectionEnabled)
            assertNull(f.device.settings.value.savedUnconfirmedChoices["desired_ear_detection"])
            assertEquals(listOf(ear), f.failures)
        }
    }

    @Test fun ordinarySettingsWaitForTheCurrentSettingsStartupWithoutInventingReadiness() = runBlocking<Unit> {
        fixture(ready = false) { f ->
            assertFalse(f.controls.canWrite(awareness))
            assertFalse(f.controls.edit(awareness, 1))
            assertTrue(f.writes().isEmpty())
            f.settingsReady()
            assertTrue(f.controls.canWrite(awareness))
            assertTrue(f.controls.edit(awareness, 1))
            f.await { f.writes().isNotEmpty() }
            assertEquals(listOf(awareness to 1), f.writes())
        }
    }

    @Test fun acceptedOffChoiceSurvivesAControlRefreshAndFreshReportsWinWithoutAReplay() = runBlocking<Unit> {
        fixture { f ->
            f.state.value = f.state.value.copy(controlStates = f.state.value.controlStates + (awareness to byteArrayOf(1)))
            f.controls.observe()
            assertTrue(f.controls.edit(awareness, 2))
            f.await { f.writes().size == 1 && f.state.value.requestedControls[awareness]?.firstOrNull() == 2.toByte() }
            f.awaitSettled()
            f.replaceSocket()
            f.state.value = f.state.value.copy(controlSession = 2, controlStates = emptyMap(),
                requestedControls = emptyMap(), liveControlReports = emptySet())
            f.settingsReady()
            f.controls.observe()
            assertEquals(2.toByte(), f.controls.displayState(f.state.value).requestedControls[awareness]!!.first())
            assertFalse(awareness in f.state.value.liveControlReports)
            assertNull(f.state.value.controlStates[awareness])
            delay(200)
            assertEquals("An owned refresh retains presentation, not an automatic settings write", listOf(awareness to 2), f.writes())
            f.state.value = f.state.value.copy(controlStates = mapOf(awareness to byteArrayOf(1)),
                liveControlReports = setOf(awareness))
            f.controls.observe()
            assertNull(f.controls.displayState(f.state.value).requestedControls[awareness])
            assertEquals(1.toByte(), f.controls.displayState(f.state.value).controlStates[awareness]!!.first())
            f.state.value = f.state.value.copy(controlSession = 3, controlStates = emptyMap(),
                requestedControls = emptyMap(), liveControlReports = emptySet())
            f.settingsReady()
            f.controls.observe()
            assertNull("A later reload cannot resurrect a preference superseded by a fresh report",
                f.controls.displayState(f.state.value).requestedControls[awareness])
            f.connection.value++
            f.state.value = f.state.value.copy(controlStates = emptyMap(), liveControlReports = emptySet())
            f.controls.observe()
            assertNull(f.controls.displayState(f.state.value).requestedControls[awareness])
            assertEquals(1, f.writes().size)
        }
    }

    private suspend fun fixture(ready: Boolean = true, block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        device.loadInitialState(AppleState(), AppleSettings(earDetectionEnabled = true),
            AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063"))
        val f = Fixture(device)
        if (ready) f.settingsReady()
        f.controls = NativeQuickControls(device, f.scope, idle = { f.admitted.get() }, failed = { f.failures += it })
        try { block(f) } finally {
            f.release.countDown()
            f.scope.cancel()
            withTimeout(2_000) {
                while (f.scope.coroutineContext[Job]?.isCompleted != true) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            }
            device.aacp.disconnect()
            f.sockets.forEach { it.close() }
        }
    }

    private class Fixture(val device: AppleDevice) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var controls: NativeQuickControls
        val failures = CopyOnWriteArrayList<ControlCommandIdentifier>()
        val admitted = AtomicBoolean(true)
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        val sockets = mutableListOf(socket)
        val shadow = Shadow.extract<HearingModeSocketShadow>(socket)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = ReflectionHelpers.getField<MutableStateFlow<AppleState>>(device, "_state")
        val connection = ReflectionHelpers.getField<MutableStateFlow<Int>>(device, "_connectionNumber")
        init {
            ReflectionHelpers.setField(device.aacp, "socket", socket)
            val reports = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(3),
                ControlCommandIdentifier.AUTO_ANC_STRENGTH to byteArrayOf(50),
                ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(2),
                ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG to byteArrayOf(2),
                ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(2))
            state.value = state.value.copy(controlSession = 1, controlStates = reports, liveControlReports = reports.keys)
        }
        fun settingsReady() {
            val type = Class.forName(AACPManager::class.java.name + "\$SettingsStartup")
            val startup = type.getDeclaredConstructor(BluetoothSocket::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(checkNotNull(device.aacp.captureAudioSocket()), state.value.controlSession)
            ReflectionHelpers.setField(startup, "sent", true)
            ReflectionHelpers.setField(startup, "received", true)
            ReflectionHelpers.setField(device.aacp, "settingsStartup", startup)
        }
        fun blockNextWrite() {
            shadow.output.onPacket = {
                shadow.output.onPacket = null
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Synthetic writer deadline exceeded" }
            }
        }
        fun replaceSocket() {
            val next = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            sockets += next
            ReflectionHelpers.setField(device.aacp, "socket", next)
        }
        fun writes() = sockets.flatMap { Shadow.extract<HearingModeSocketShadow>(it).output.packets }.map { packet ->
            val command = ControlCommandPacket.parse(packet).controlCommand
            command.identifier to (command.value.first().toInt() and 255)
        }
        suspend fun await(condition: () -> Boolean) = withTimeout(2_000) {
            while (!condition()) { controls.observe(); shadowOf(Looper.getMainLooper()).idle(); delay(5) }
        }
        suspend fun awaitSettled() {
            val settled = scope.async { controls.changes.first { values -> values.all { !it.pending && it.requested == null } } }
            withTimeout(2_000) {
                while (!settled.isCompleted) { controls.observe(); shadowOf(Looper.getMainLooper()).idle(); delay(5) }
                settled.await()
            }
        }
    }
}
