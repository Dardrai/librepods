package me.kavishdevar.librepods.services

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.database.apple.AppleDao
import me.kavishdevar.librepods.database.apple.AppleEntity
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.repository.AppleRepository
import me.kavishdevar.librepods.utils.ConversationVolume
import me.kavishdevar.librepods.utils.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConversationAwarenessServiceTest {
    private lateinit var service: LibrePodsService
    private val device = mock(AppleDevice::class.java)
    private val aacp = mock(AACPManager::class.java)
    private val socket = mock(BluetoothSocket::class.java)
    private val currentSocket = AtomicReference<BluetoothSocket?>(socket)
    private val address = MacAddress("00:11:22:33:44:55")
    private val id = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
    private val states = MutableStateFlow(AppleState(controlStates = mapOf(id to byteArrayOf(2)),
        requestedControls = mapOf(id to byteArrayOf(1))))
    private val connections = MutableStateFlow(ConnectionState.CONNECTED)
    private val events = MutableSharedFlow<AppleEvent>()
    private val audio = mock(AudioManager::class.java)
    private val currentVolume = AtomicInteger(10)
    private val jobs = mutableListOf<Job>()
    private lateinit var devices: MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
    private val mediaAudio = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
    private val volume = MediaController::class.java.getDeclaredField("conversationVolume").apply { isAccessible = true }
        .get(null) as ConversationVolume
    private var previousAudio: Any? = null

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        val app = LibrePodsApplication()
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(app, "islandWindow", mock(IslandWindow::class.java))
        `when`(device.macAddress).thenReturn(address)
        `when`(device.state).thenReturn(states)
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata()))
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(conversationAwarenessPauseMusicEnabled = false)))
        `when`(device.connectionState).thenReturn(connections)
        `when`(device.events).thenReturn(events)
        `when`(device.aacp).thenReturn(aacp)
        `when`(aacp.captureAudioSocket()).thenAnswer { currentSocket.get() }
        `when`(aacp.ownsAudioSocket(any(BluetoothSocket::class.java) ?: socket)).thenAnswer {
            it.getArgument<BluetoothSocket>(0) === currentSocket.get()
        }
        val output = mock(AudioDeviceInfo::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(output.address).thenReturn(address.value)
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(output))
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { currentVolume.get() }
        `when`(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(20)
        doAnswer { currentVolume.set(it.getArgument(1)); null }.`when`(audio)
            .setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(), anyInt())
        @Suppress("UNCHECKED_CAST")
        val registered = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices = registered
        devices.value = mapOf(address to device)
        previousAudio = mediaAudio.get(null)
        mediaAudio.set(null, audio)
    }

    @After fun close() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        synchronized(MediaController) { volume.owner?.let { MediaController.cancelConversation(it) } }
        mediaAudio.set(null, previousAudio)
        Dispatchers.resetMain()
    }

    @Test fun silentOnIntentAllowsSpeechAndOffIntentCancelsItBeforeAnEcho() = runBlocking {
        observe()
        stage(1)
        awaitSpeaking()
        assertTrue(volume.owns(socket))
        states.value = states.value.copy(requestedControls = mapOf(id to byteArrayOf(2)))
        await { !volume.owns(socket) }
        states.value = states.value.copy(requestedControls = mapOf(id to byteArrayOf(1)))
        stage(2)
        stage(3)
        assertFalse(synchronized(MediaController) { volume.speaking })
        stage(1)
        awaitSpeaking()
        assertTrue(volume.owns(socket))
    }

    @Test fun orderedStagesDuckAndRestoreWhileTheStateRepositoryIsBlocked() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = AppleRepository(object : AppleDao {
            override suspend fun get(macAddress: MacAddress): AppleEntity? = null
            override suspend fun getAll(): List<AppleEntity> = emptyList()
            override suspend fun upsert(device: AppleEntity) { entered.complete(Unit); release.await() }
        })
        field("appleRepository\$delegate").set(service, lazyOf(repository))
        observe()
        val stateObserver = service.observeAppleState(device).also(jobs::add)
        withTimeout(2_000) { states.subscriptionCount.first { it >= 2 } }
        states.value = states.value.copy(controlStates = states.value.controlStates +
            (ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(2)))
        withTimeout(2_000) { entered.await() }
        try {
            stage(1)
            stage(2)
            awaitSpeaking()
            awaitVolume(2)
            stage(4)
            await { !volume.speaking }
            awaitVolume(10)
            assertFalse(release.isCompleted)
            assertTrue(stateObserver.isActive)
        } finally {
            stateObserver.cancelAndJoin()
            release.complete(Unit)
        }
    }

    @Test fun cachedSpeechIsNotReplayedAndARepeatedLiveStartAfterAnEndStillWorks() = runBlocking {
        states.value = states.value.copy(conversationAwarenessState = 1)
        connections.value = ConnectionState.CONNECTING
        observe()
        assertFalse(synchronized(MediaController) { volume.speaking })
        stage(1)
        stage(1)
        stage(2)
        awaitSpeaking()
        awaitVolume(2)
        connections.value = ConnectionState.CONNECTED
        stage(4)
        await { !volume.speaking }
        awaitVolume(10)
        stage(2)
        stage(3)
        stage(1)
        awaitSpeaking()
        awaitVolume(4)
        stage(4)
        await { !volume.speaking }
        awaitVolume(10)
    }

    @Test fun anOldTransportCannotStartOrStopItsReplacementAndRetirementCancelsPromptly() = runBlocking {
        states.value = states.value.copy(conversationAwarenessState = 1)
        observe()
        stage(1)
        awaitSpeaking()
        awaitVolume(4)
        val replacement = mock(BluetoothSocket::class.java)
        currentSocket.set(replacement)
        stage(1, socket)
        await { !volume.owns(socket) }
        stage(2, replacement)
        stage(1, replacement)
        await { volume.owns(replacement) && volume.speaking }
        stage(4, socket)
        stage(2, replacement)
        awaitVolume(2)
        assertTrue(volume.speaking)
        currentSocket.set(null)
        states.value = states.value.copy(conversationAwarenessState = 0)
        await { !volume.owns(replacement) }
        assertEquals(10, currentVolume.get())
    }

    @Test fun disconnectAndDeviceReplacementCancelWithoutARepositoryOrActivity() = runBlocking {
        observe()
        stage(1)
        awaitSpeaking()
        connections.value = ConnectionState.DISCONNECTED
        await { !volume.owns(socket) }
        connections.value = ConnectionState.CONNECTED
        stage(1)
        awaitSpeaking()
        devices.value = mapOf(address to mock(AppleDevice::class.java))
        await { !volume.owns(socket) }
        stage(1)
        stage(2)
        assertFalse(synchronized(MediaController) { volume.speaking })
    }

    @Test fun aVolumeApiFailureDoesNotKillTheObserverOrDiscardTheNextLiveStart() = runBlocking {
        val observer = observe()
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .thenThrow(IllegalStateException("Audio service temporarily unavailable"))
            .thenAnswer { currentVolume.get() }
        stage(1)
        stage(1)
        awaitSpeaking()
        assertTrue(observer.isActive)
        assertTrue(volume.owns(socket))
    }

    private fun observe(): Job = service.observeConversationAwareness(device).also {
        jobs += it
        assertEquals("Subscribe before the first native packet can arrive", 1, events.subscriptionCount.value)
    }

    private suspend fun stage(stage: Int, source: BluetoothSocket = socket) {
        // Activity state is deliberately unchanged: events must carry repeated and conflated stages.
        events.emit(AppleEvent.ConversationAwareness(stage, source))
    }

    private suspend fun awaitSpeaking() = await { volume.speaking }
    private suspend fun await(condition: () -> Boolean) = withTimeout(2_000) {
        while (!synchronized(MediaController) { condition() }) delay(10)
    }
    private suspend fun awaitVolume(expected: Int) = withTimeout(2_000) {
        while (currentVolume.get() != expected) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            delay(10)
        }
    }
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
