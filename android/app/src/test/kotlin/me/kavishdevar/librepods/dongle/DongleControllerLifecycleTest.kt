package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.io.ByteArrayOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import me.kavishdevar.librepods.services.LibrePodsService
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleControllerLifecycleTest {
    private val address = "00:11:22:33:44:55"
    private val context = RuntimeEnvironment.getApplication()
    private val bridge = mock(ShizukuAudioClient::class.java)
    private val controller: DongleController
    private val mediaAudio = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
    private val previousAudio = mediaAudio.get(null)
    private var service: LibrePodsService? = null
    private var mediaOwner: (() -> Boolean)? = null

    init {
        context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE).edit().clear()
            .putString("address", address).putBoolean("automatic_pause", true).commit()
        controller = DongleController(context)
        (field("scope").get(controller) as CoroutineScope).cancel()
        field("scope").set(controller, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        field("bridge\$delegate").set(controller, lazyOf(bridge))
        `when`(bridge.state).thenReturn(MutableStateFlow(BridgeState(connected = true)))
        runBlocking {
            `when`(bridge.pauseOwnedMedia(eq(address) ?: address, any<() -> Boolean>() ?: { false })).thenAnswer { invocation ->
                mediaOwner = invocation.getArgument(1)
                checkNotNull(mediaOwner)()
            }
        }
    }

    @After fun close() {
        controller.close()
        service?.let { (serviceField("serviceScope").get(it) as CoroutineScope).cancel() }
        mediaAudio.set(null, previousAudio)
    }

    @Test fun leavingFinderWhileStartIsAwaitingStopsTheLateAcceptedLeaseAndNeverPollsIt() = runBlocking<Unit> {
        val firmware = FinderFirmware()
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()
        assertEquals(listOf(6), firmware.commands)
        assertFalse(controller.state.value.finderOpen)

        controller.stopFinder()
        firmware.startReply.complete(Unit)
        assertEquals("The accepted Start must be followed by one Stop", listOf(6, 8), firmware.commands)
        assertFalse(controller.state.value.finderOpen)
        assertNull(controller.state.value.nearby)
        assertFalse(field("finderWanted").getBoolean(controller))

        firmware.flags = 1
        field("audioUpdated").setLong(controller, Long.MAX_VALUE)
        repeat(3) { refresh(client) }
        assertEquals("Leaving Finder must never renew the late lease", listOf(6, 8), firmware.commands)
    }

    @Test fun aQueuedStopCannotCancelANewerFinderStart() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.startReply.complete(Unit) }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()
        assertTrue(controller.state.value.finderOpen)

        val io = field("io").get(controller) as Mutex
        io.lock()
        controller.stopFinder()
        controller.startFinder()
        io.unlock()

        assertEquals(listOf(6, 6), firmware.commands)
        assertTrue(controller.state.value.finderOpen)
    }

    @Test fun finderSurvivesTheSamePairsAacpReconnectAndKeepsPolling() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.startReply.complete(Unit) }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()

        firmware.generation++
        firmware.flags = 1
        refresh(client)
        assertTrue(controller.state.value.finderOpen)
        assertTrue(field("finderWanted").getBoolean(controller))
        assertEquals(listOf(6, 7), firmware.commands.filter { it != 9 })
    }

    @Test fun aDifferentSelectedPairClosesFinderWithoutPollingThatPair() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.startReply.complete(Unit) }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()

        firmware.generation++
        firmware.pair++
        firmware.flags = 1
        refresh(client)
        assertFalse(controller.state.value.finderOpen)
        assertNull(controller.state.value.nearby)
        assertFalse(field("finderWanted").getBoolean(controller))
        assertEquals(listOf(6), firmware.commands.filter { it != 9 })
    }

    @Test fun aQueuedStopStillStopsItsOwnPairAfterAnAacpReconnect() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.startReply.complete(Unit) }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()
        val io = field("io").get(controller) as Mutex
        io.lock()
        controller.stopFinder()
        firmware.generation++
        io.unlock()
        assertEquals(listOf(6, 8), firmware.commands)
        assertFalse(controller.state.value.finderOpen)
    }

    @Test fun theServiceRouteAwayAndBackRetiresBothThePauseAndItsRemoteOwner() = runBlocking<Unit> {
        val audio = mock(AudioManager::class.java)
        mediaAudio.set(null, audio)
        val client = mediaClient()
        val service = attachService()
        route(audio, address)
        wear(client, true, 1)
        wear(client, false, 2)
        assertTrue(checkNotNull(mediaOwner)())

        clearInvocations(bridge)
        observedRoute(service)
        assertTrue("An unchanged eligible route keeps its pause", checkNotNull(mediaOwner)())
        verify(bridge, never()).cancelOwnedMedia()

        route(audio, "AA:BB:CC:DD:EE:FF")
        observedRoute(service)
        assertFalse(checkNotNull(mediaOwner)())
        route(audio, address)
        observedRoute(service)
        assertFalse("Returning to the dongle cannot revive the old owner", checkNotNull(mediaOwner)())
        wear(client, true, 3)
        verify(bridge).cancelOwnedMedia()
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun theServiceCallCallbackPermanentlyRetiresThePreviousPause() = runBlocking<Unit> {
        val client = mediaClient()
        val service = attachService()
        wear(client, true, 1)
        wear(client, false, 2)
        assertTrue(checkNotNull(mediaOwner)())
        clearInvocations(bridge)

        val calls = serviceField("telephonyCallback").get(service) as TelephonyCallback.CallStateListener
        calls.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK)
        assertFalse(checkNotNull(mediaOwner)())
        calls.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE)
        assertFalse(checkNotNull(mediaOwner)())
        wear(client, true, 3)
        verify(bridge).cancelOwnedMedia()
        verify(bridge, never()).finishOwnedMedia(true)
    }

    private fun attach(client: DongleSession, status: DongleProtocol.Status) {
        field("session").set(controller, client)
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(controller) as MutableStateFlow<DongleState>
        state.value = state.value.copy(connected = true, status = status)
    }

    private suspend fun mediaClient(): DongleSession {
        val client = mock(DongleSession::class.java)
        val status = DongleProtocol.Status(3, listOf(1, 2, 3, 4, 5, 6), 3, 0, listOf(null, null, null), 0, 0)
        `when`(client.refresh()).thenReturn(status)
        `when`(client.settings()).thenReturn(emptyMap())
        attach(client, status)
        return client
    }

    private suspend fun wear(client: DongleSession, inEars: Boolean, revision: Long) {
        val value = if (inEars) 0 else 1
        `when`(client.cached(5)).thenReturn(DongleProtocol.Cached(0, revision,
            byteArrayOf(4, 0, 4, 0, 6, 0, value.toByte(), value.toByte())))
        refresh(client)
    }

    private fun attachService(): LibrePodsService = Robolectric.buildService(LibrePodsService::class.java).get().also {
        service = it
        serviceField("dongleControls\$delegate").set(it, lazyOf(controller))
        serviceField("companionBridge\$delegate").set(it, lazyOf(mock(ShizukuAudioClient::class.java)))
    }

    private fun observedRoute(service: LibrePodsService) {
        LibrePodsService::class.java.getDeclaredMethod("retireWearMediaForOtherRoute")
            .apply { isAccessible = true }.invoke(service)
    }

    private fun route(audio: AudioManager, address: String) {
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.address).thenReturn(address)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(device))
    }

    private suspend fun refresh(client: DongleSession): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        DongleController::class.java.getDeclaredMethod("refresh", DongleSession::class.java, Continuation::class.java)
            .apply { isAccessible = true }.invoke(controller, client, continuation)
    }

    private fun field(name: String) = DongleController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun serviceField(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private class FinderFirmware {
        val startReply = CompletableDeferred<Unit>()
        val commands = mutableListOf<Int>()
        var flags = 3
        var generation = 3L
        var pair = 1
        private val received = ByteArrayOutputStream()
        private var report = DongleProtocol.header(3)

        suspend fun exchange(operation: Int, id: Int, bytes: ByteArray): ByteArray {
            if (id == 2) return DongleProtocol.header(2).also {
                it[7] = flags.toByte(); DongleProtocol.put32(it, 8, generation); it[12] = pair.toByte(); it[42] = 0x10
            }
            if (operation == 1) return report.copyOf()
            report = DongleProtocol.header(3).also {
                DongleProtocol.put32(it, 8, generation); DongleProtocol.put32(it, 12, DongleProtocol.u32(bytes, 12))
            }
            when (bytes[7].toInt()) {
                7 -> {
                    if (DongleProtocol.u16(bytes, 18) == 0) received.reset()
                    received.write(bytes, 22, bytes[20].toInt() and 255)
                    report[21] = 1; report[18] = received.size().toByte()
                }
                6 -> {
                    val command = received.toByteArray()[0].toInt()
                    commands += command
                    if (command == 6) startReply.await()
                    val nearby = if (command == 8) byteArrayOf() else if (command == 9) ByteArray(20).also {
                        it[0] = 1; it[18] = 100
                    } else ByteArray(24).also {
                        it[0] = 1; it[2] = 127; DongleProtocol.put32(it, 20, 0x8000_0000)
                    }
                    report[21] = 3; report[16] = nearby.size.toByte(); report[20] = nearby.size.toByte()
                    nearby.copyInto(report, 22)
                }
            }
            return report.copyOf()
        }
    }
}
