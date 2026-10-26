package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
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

    @Test fun gameModeRejectsUnsupportedEnableButAllowsRecoveryOff() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.gameRequested = true }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        gameState().value = controller.state.value.copy(audio = gameProfile(true, false))
        val editor = controller.liveGameModeEditor()
        assertFalse(editor.edit(editor.state.value.revision, true))
        assertTrue(firmware.commands.isEmpty())
        assertTrue(editor.edit(editor.state.value.revision, false))
        awaitGameEdit()
        assertEquals(listOf(false), firmware.gameRequests)
        assertFalse(controller.state.value.audio!!.gameRequested)
        assertNull(controller.state.value.error)
    }

    @Test fun gameModeEnableLosesEligibilityWhileQueuedWithoutSending() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.gameSupported = true }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        gameState().value = controller.state.value.copy(audio = gameProfile(false, true))
        val editor = controller.liveGameModeEditor()
        assertTrue(editor.edit(editor.state.value.revision, true))
        firmware.gameSupported = false
        gameState().value = controller.state.value.copy(audio = gameProfile(false, false))
        awaitGameEdit()
        assertTrue(firmware.commands.isEmpty())
        assertTrue(firmware.gameRequests.isEmpty())
    }

    @Test fun gameModeEnableChecksEligibilityAgainImmediatelyBeforeRpcCommit() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.gameSupported = true }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        gameState().value = controller.state.value.copy(audio = gameProfile(false, true))
        firmware.beforeFeatureCommit = { command -> if (command == 4) {
            firmware.gameSupported = false
            gameState().value = controller.state.value.copy(audio = gameProfile(false, false))
        } }
        val editor = controller.liveGameModeEditor()
        assertTrue(editor.edit(editor.state.value.revision, true))
        awaitGameEdit()
        assertEquals(1, firmware.cancellations)
        assertTrue(firmware.gameRequests.isEmpty())
        assertFalse(controller.state.value.audio!!.gameRequested)
    }

    @Test fun aQueuedGameModeChangeCannotMoveToAnotherPeer() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.gameSupported = true }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        gameState().value = controller.state.value.copy(audio = gameProfile(false, true))
        val editor = controller.liveGameModeEditor()
        val revision = editor.state.value.revision
        assertTrue(editor.edit(revision, true))
        firmware.generation++; firmware.pair = 2
        gameState().value = controller.state.value.copy(status = client.refresh())
        editor.observe()
        assertTrue(editor.state.value.revision > revision)
        assertNull(editor.state.value.requested)
        assertTrue(firmware.gameRequests.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun gameState() = field("mutableState").get(controller) as MutableStateFlow<DongleState>
    private fun gameProfile(requested: Boolean, supported: Boolean) =
        DongleProtocol.AudioProfile(3, 3, 0, 256000, 256000, requested, 0, 2, if (supported) 8 else 0, null)
    private suspend fun awaitGameEdit() = withTimeout(3_000) {
        val editor = controller.liveGameModeEditor()
        while (editor.state.value.pending || editor.state.value.requested != null) delay(5)
    }

    @Test fun aHearingProfileEditExcludesUsbBetweenItsComparisonAndVerifiedWrite() = runBlocking<Unit> {
        val firmware = DongleLeaseFirmware()
        val client = DongleSession(firmware::exchange, closeTransport = firmware::close)
        attach(client, client.refresh())
        var profile = ByteArray(104).also { it[0] = 2; it[1] = 2; it[2] = 0x60 }
        field("profileData").set(controller, profile.copyOf())
        field("profileCharacteristic").set(controller, DongleAtt.Characteristic(0x20, 0x12))
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(controller) as MutableStateFlow<DongleState>
        val hearing = byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 1, 0, 0)
        val gate = byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0)
        val modes = HearingModes.empty().withReport(hearing).withReport(gate)
        firmware.cachedReply = { slot -> if (slot == 4) byteArrayOf() else ByteArray(8).also {
            DongleProtocol.put32(it, 4, 1)
        } + if (slot == 2) hearing else gate }
        state.value = state.value.copy(metadata = AppleMetadata(modelNumber = "A3063"), hearingModes = modes,
            hearingProfile = checkNotNull(parseHearingAidSettingsResponse(profile)))
        var competingWrites = 0
        firmware.beforeRawReply = {
            if (firmware.rawRequests.size == 1) {
                competingWrites++
                assertFalse(firmware.competingEdit {
                    ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN).putFloat(100, 0.5f)
                })
            }
        }
        firmware.rawReply = { operation, request ->
            assertEquals(1, operation)
            when (request[0].toInt()) {
                0x0a -> byteArrayOf(0x0b) + profile
                0x12 -> { profile = request.copyOfRange(3, request.size); byteArrayOf(0x13) }
                else -> error("Unexpected ATT request")
            }
        }
        controller.updateHearing { it.copy(leftTone = 0.25f, rightTone = 0.25f) }
        awaitCommand()
        assertNull(controller.state.value.error)
        assertEquals(1, competingWrites)
        assertEquals(0, firmware.actions.first())
        assertEquals(2, firmware.actions.last())
        assertEquals(1, firmware.actions.count { it == 0 })
        val written = checkNotNull(parseHearingAidSettingsResponse(profile))
        assertEquals(0.25f, written.leftTone, 0f)
        assertEquals(0f, written.ownVoiceAmplification, 0f)
        assertTrue(firmware.competingEdit {
            ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN).putFloat(100, 0.5f)
        })
        assertEquals(0.5f, checkNotNull(parseHearingAidSettingsResponse(profile)).ownVoiceAmplification, 0f)
    }

    @Test fun modeChangeLeasesBeforeCachedBaselinesAndHoldsThroughPmeConfirmation() = runBlocking<Unit> {
        val firmware = DongleLeaseFirmware()
        val client = DongleSession(firmware::exchange, closeTransport = firmware::close)
        attach(client, client.refresh())
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(controller) as MutableStateFlow<DongleState>
        state.value = state.value.copy(metadata = AppleMetadata(modelNumber = "A3063"))
        val hearing = byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 2, 0, 0)
        var gate = byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0)
        var pme = ByteArray(140).also {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1, 1, 1).copyInto(it)
        }
        var revision = 1L
        firmware.cachedReply = { slot ->
            assertNotNull("Mode baselines and confirmations must stay leased", firmware.token)
            val packet = when (slot) { 2 -> hearing; 3 -> gate; 4 -> pme; else -> error("Unexpected cached slot") }
            ByteArray(8).also { DongleProtocol.put32(it, 4, revision) } + packet
        }
        firmware.rawReply = { operation, request ->
            assertEquals(2, operation)
            if (request[4] == 0x53.toByte()) pme = request.copyOf() else gate = request.copyOf()
            revision++
            byteArrayOf()
        }
        controller.hearingMode(HearingMode.MEDIA_ASSIST, false)
        awaitCommand()
        assertNull(controller.state.value.error)
        assertEquals(false, controller.state.value.hearingModes.mediaAssistEnabled)
        assertEquals(2, firmware.rawRequests.size)
        assertTrue(firmware.events.indexOf("lease0") < firmware.events.indexOf("cached2"))
        assertEquals(0, firmware.actions.first())
        assertEquals(2, firmware.actions.last())
        assertEquals(1, firmware.actions.count { it == 0 })
        assertNull(firmware.token)
    }

    @Test fun legacyAccommodationsLeasesFullPmeAndRequiresAnAdvancedReceptionRevision() = runBlocking<Unit> {
        val firmware = DongleLeaseFirmware()
        val client = DongleSession(firmware::exchange, closeTransport = firmware::close)
        attach(client, client.refresh())
        var pme = ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 2, 1, 2).copyInto(it) }
        val original = pme.copyOf()
        val hearing = byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 2, 0, 0, 0)
        val gate = byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0)
        var revision = 1L
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(controller) as MutableStateFlow<DongleState>
        state.value = state.value.copy(controlSession = 99, hearingModes = me.kavishdevar.librepods.core.devices.HearingModes.empty()
            .withReport(hearing).withReport(gate).withReport(pme))
        @Suppress("UNCHECKED_CAST")
        val revisions = field("cachedRevisions").get(controller) as MutableMap<Int, Long>
        revisions.putAll(mapOf(2 to 1L, 3 to 1L, 4 to 1L))
        val expected = state.value.accommodationsReport!!
        firmware.cachedReply = { slot ->
            assertNotNull(firmware.token)
            ByteArray(8).also { DongleProtocol.put32(it, 4, revision) } + when (slot) { 2 -> hearing; 3 -> gate; 4 -> pme; else -> error("Unexpected slot") }
        }
        firmware.rawReply = { operation, packet ->
            assertEquals(2, operation)
            assertTrue(state.value.accommodationsRequest.pending)
            pme = packet.copyOf(); revision++
            byteArrayOf()
        }
        controller.accommodations(expected, false, true, null)
        awaitCommand()
        assertNull(state.value.error)
        assertEquals(me.kavishdevar.librepods.core.devices.AccommodationsResult.CONFIRMED, state.value.accommodationsRequest.result)
        val request = firmware.rawRequests.single().second
        for (index in original.indices) if (index !in 10..11) assertEquals(original[index], request[index])
        assertEquals(2.toByte(), request[10]); assertEquals(1.toByte(), request[11])
        assertTrue(firmware.events.indexOf("lease0") < firmware.events.indexOf("cached2"))
        assertNull(firmware.token)
    }

    @Test fun namedAccommodationsPresetCoordinatesBothRecordsAndPreservesEveryTransparencyScalar() = runBlocking<Unit> {
        val firmware = DongleLeaseFirmware()
        val client = DongleSession(firmware::exchange, closeTransport = firmware::close)
        attach(client, client.refresh())
        val hearing = byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 2, 0, 0, 0)
        val gate = byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 2, 0, 0, 0)
        var pme = ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 2, 1, 2).copyInto(it) }
        var transparency = ByteArray(104).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(36, -0.25f).putFloat(84, 0.6f)
                .putFloat(40, -0.2f).putFloat(88, 0.3f).putFloat(48, 0.1f).putFloat(96, 0.8f)
                .putFloat(44, 1f).putFloat(100, 0.43f)
        }
        val baseline = transparency.copyOf()
        var revision = 1L
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(controller) as MutableStateFlow<DongleState>
        state.value = state.value.copy(controlSession = 99, hearingModes = me.kavishdevar.librepods.core.devices.HearingModes.empty()
            .withReport(hearing).withReport(gate).withReport(pme))
        @Suppress("UNCHECKED_CAST")
        val revisions = field("cachedRevisions").get(controller) as MutableMap<Int, Long>
        revisions.putAll(mapOf(2 to 1L, 3 to 1L, 4 to 1L))
        val expected = state.value.accommodationsReport!!
        firmware.cachedReply = { slot -> ByteArray(8).also { DongleProtocol.put32(it, 4, revision) } +
            when (slot) { 2 -> hearing; 3 -> gate; 4 -> pme; else -> error("Unexpected slot") } }
        val uuid = java.util.UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390")
        val wireUuid = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array().reversedArray()
        firmware.rawReply = { operation, request ->
            assertNotNull(firmware.token)
            if (operation == 2) { pme = request.copyOf(); revision++; byteArrayOf() }
            else when (request[0].toInt()) {
                6 -> byteArrayOf(7, 1, 0, 0x20, 0)
                8 -> if (request[1] == 1.toByte()) byteArrayOf(9, 21, 0x10, 0, 0x0a, 0x11, 0) + wireUuid
                    else byteArrayOf(1, 8, request[1], request[2], 0x0a)
                0x0a -> { assertEquals(0x11.toByte(), request[1]); byteArrayOf(0x0b) + transparency }
                0x12 -> { assertEquals(0x11.toByte(), request[1]); transparency = request.copyOfRange(3, request.size); byteArrayOf(0x13) }
                else -> error("Unexpected request")
            }
        }
        val preset = me.kavishdevar.librepods.core.devices.AccommodationsPreset(
            me.kavishdevar.librepods.core.devices.AccommodationsTuning.BALANCED_TONE,
            me.kavishdevar.librepods.core.devices.AccommodationsLevel.SLIGHT)
        controller.accommodations(expected, null, null, preset)
        awaitCommand()
        assertNull(state.value.error)
        assertEquals(me.kavishdevar.librepods.core.devices.AccommodationsResult.CONFIRMED, state.value.accommodationsRequest.result)
        assertArrayEquals(me.kavishdevar.librepods.core.bluetooth.att.types.accommodationsTransparencyPreset(baseline, preset), transparency)
        for (index in baseline.indices) if (index !in 4..35 && index !in 52..83) assertEquals(baseline[index], transparency[index])
        assertArrayEquals(transparency, state.value.transparencyData)
        assertEquals(1, firmware.rawRequests.count { it.first == 2 })
        assertEquals(1, firmware.rawRequests.count { it.first == 1 && it.second[0] == 0x12.toByte() })
        assertNull(firmware.token)
    }

    private suspend fun awaitCommand() = withTimeout(3_000) {
        delay(200)
        while (controller.state.value.busy) delay(1)
    }

    @Test fun leavingFinderWhileStartIsAwaitingStopsTheLateAcceptedLeaseAndNeverPollsIt() = runBlocking<Unit> {
        val firmware = FinderFirmware()
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()
        assertEquals(listOf(7, 6), firmware.commands)
        assertFalse(controller.state.value.finderOpen)

        controller.stopFinder()
        firmware.startReply.complete(Unit)
        assertEquals("The accepted Start must be followed by one Stop", listOf(7, 6, 8), firmware.commands)
        assertFalse(controller.state.value.finderOpen)
        assertNull(controller.state.value.nearby)
        assertFalse(field("finderWanted").getBoolean(controller))

        firmware.flags = 1
        field("audioUpdated").setLong(controller, Long.MAX_VALUE)
        repeat(3) { refresh(client) }
        assertEquals("Leaving Finder must never renew the late lease", listOf(7, 6, 8), firmware.commands)
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

        assertEquals(listOf(7, 6, 7, 6), firmware.commands)
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
        assertEquals(listOf(7, 6, 7), firmware.commands.filter { it != 9 })
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
        assertEquals(listOf(7, 6), firmware.commands.filter { it != 9 })
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
        assertEquals(listOf(7, 6, 8), firmware.commands)
        assertFalse(controller.state.value.finderOpen)
    }

    @Test fun missingFinderIdentityShowsTheRecoveryStateWithoutStartingALease() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.identityAvailable = false }
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.startFinder()
        assertEquals(listOf(7), firmware.commands)
        assertFalse(controller.state.value.finderOpen)
        assertEquals(false, controller.state.value.nearby?.identityAvailable)
        assertNull(controller.state.value.error)
        assertFalse(field("finderWanted").getBoolean(controller))

        firmware.identityAvailable = true
        firmware.startReply.complete(Unit)
        controller.startFinder()
        assertTrue(controller.state.value.finderOpen)
        assertEquals(listOf(7, 7, 6), firmware.commands)
    }

    @Test fun anObservedUsbOwnerPermanentlyRetiresThePhonePauseEvenAfterReturningToIdle() = runBlocking<Unit> {
        val firmware = FinderFirmware()
        val client = ownPause(firmware)
        assertTrue(checkNotNull(mediaOwner)())
        firmware.input = 1
        field("audioUpdated").setLong(controller, -2_000)
        refresh(client)
        assertFalse(checkNotNull(mediaOwner)())

        firmware.input = 0
        field("audioUpdated").setLong(controller, -2_000)
        firmware.inEars = true; firmware.earRevision++
        refresh(client)
        assertFalse(checkNotNull(mediaOwner)())
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun resumeWaitsForFreshOwnershipAndDoesNotUseTheCachedPhoneOwner() = runBlocking<Unit> {
        val firmware = FinderFirmware()
        val client = ownPause(firmware)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        firmware.beforeAudioReply = { entered.complete(Unit); finish.await() }
        firmware.inEars = true; firmware.earRevision++
        field("audioUpdated").setLong(controller, Long.MAX_VALUE)
        val resuming = launch(Dispatchers.Unconfined) { refresh(client) }
        entered.await()
        assertEquals(2, controller.state.value.audio?.input)
        verify(bridge, never()).finishOwnedMedia(true)
        firmware.input = 1
        finish.complete(Unit)
        resuming.join()
        assertFalse(checkNotNull(mediaOwner)())
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun idleInputPreservesAnOwnedPauseAndCanResumeItsPlayer() = runBlocking<Unit> {
        val firmware = FinderFirmware().also { it.input = 0 }
        val client = ownPause(firmware)
        assertTrue(checkNotNull(mediaOwner)())
        firmware.inEars = true; firmware.earRevision++
        field("audioUpdated").setLong(controller, Long.MAX_VALUE)
        refresh(client)
        verify(bridge).finishOwnedMedia(true)
        assertEquals("The first session reads settings once; pause and resume each check ownership", listOf(2, 9, 9), firmware.commands)
    }

    @Test fun idlePollingSkipsDetailedReportsAndTheViewOrEarPolicyRestoresTheirRequiredWork() = runBlocking<Unit> {
        val firmware = FinderFirmware()
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.automaticPause(false)
        firmware.exchanges = 0
        refresh(client)
        assertTrue("The initial session reads supported cached settings automatically", firmware.exchanges > 1)
        firmware.exchanges = 0
        refresh(client)
        assertEquals("After initialization idle polling needs only status", 1, firmware.exchanges)
        assertEquals(5_000L, pollInterval())

        controller.controlsVisible(true)
        firmware.exchanges = 0
        refresh(client)
        assertTrue("Opening settings restores its cached controls", firmware.exchanges > 1)
        assertTrue(firmware.commands.contains(2))
        assertEquals(600L, pollInterval())

        controller.controlsVisible(false)
        controller.automaticPause(true)
        firmware.commands.clear()
        refresh(client)
        assertFalse("Background ear handling does not poll unrelated settings", firmware.commands.contains(2))
        assertEquals(600L, pollInterval())
        controller.automaticPause(false)
        firmware.startReply.complete(Unit)
        controller.startFinder()
        assertEquals(600L, pollInterval())
    }

    @Test fun anExpiredUnconfirmedChoiceDoesNotKeepBackgroundDetailPollingAlive() = runBlocking<Unit> {
        val firmware = FinderFirmware()
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        controller.automaticPause(false)
        controller.setSetting(0x28, 1)
        awaitCommand()
        refresh(client)
        assertTrue(firmware.commands.contains(2))
        assertEquals(600L, pollInterval())

        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11))
        firmware.commands.clear(); firmware.exchanges = 0
        refresh(client)
        assertEquals(1, firmware.exchanges)
        assertTrue(firmware.commands.isEmpty())
        assertEquals(1L, controller.state.value.requested[0x28])
        assertTrue(controller.state.value.unconfirmed.contains(0x28))
        assertEquals(5_000L, pollInterval())

        firmware.settingValue = 1
        controller.controlsVisible(true)
        refresh(client)
        assertTrue(firmware.commands.contains(2))
        assertFalse(controller.state.value.requested.containsKey(0x28))
        assertFalse(controller.state.value.unconfirmed.contains(0x28))
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
        state.value = state.value.copy(connected = true, status = status,
            controlSession = state.value.controlSession.takeIf { it > 0 } ?: 1)
    }

    private suspend fun ownPause(firmware: FinderFirmware): DongleSession {
        val client = DongleSession(firmware::exchange)
        attach(client, client.refresh())
        field("audioUpdated").setLong(controller, Long.MAX_VALUE)
        refresh(client)
        firmware.inEars = false; firmware.earRevision++
        refresh(client)
        return client
    }

    private fun pollInterval() = DongleController::class.java.getDeclaredMethod("pollInterval")
        .apply { isAccessible = true }.invoke(controller) as Long

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
        var input = 2
        var identityAvailable = true
        var earRevision = 1L
        var inEars = true
        var exchanges = 0
        var settingValue: Long? = null
        var gameRequested = false
        var gameSupported = false
        val gameRequests = mutableListOf<Boolean>()
        var beforeFeatureCommit: ((Int) -> Unit)? = null
        var cancellations = 0
        var beforeAudioReply: (suspend () -> Unit)? = null
        private var operation = 0
        private val received = ByteArrayOutputStream()
        private var report = DongleProtocol.header(3)

        suspend fun exchange(operation: Int, id: Int, bytes: ByteArray): ByteArray {
            exchanges++
            if (id == 2) return DongleProtocol.header(2).also {
                it[7] = flags.toByte(); DongleProtocol.put32(it, 8, generation); it[12] = pair.toByte(); it[42] = 0x10
            }
            if (operation == 1) {
                if (this.operation == 7 && report[21] == 1.toByte())
                    received.toByteArray().firstOrNull()?.let { beforeFeatureCommit?.invoke(it.toInt()) }
                return report.copyOf()
            }
            report = DongleProtocol.header(3).also {
                DongleProtocol.put32(it, 8, generation); DongleProtocol.put32(it, 12, DongleProtocol.u32(bytes, 12))
            }
            when (bytes[7].toInt()) {
                3, 7 -> {
                    this.operation = bytes[7].toInt()
                    if (DongleProtocol.u16(bytes, 18) == 0) received.reset()
                    received.write(bytes, 22, bytes[20].toInt() and 255)
                    report[21] = 1; report[18] = received.size().toByte()
                }
                6 -> {
                    val command = received.toByteArray()[0].toInt()
                    if (this.operation == 7) commands += command
                    if (this.operation == 7 && command == 6) startReply.await()
                    if (this.operation == 7 && command == 9) beforeAudioReply?.invoke()
                    val nearby = if (this.operation == 3) {
                        if (command != 5) byteArrayOf() else ByteArray(8).also { DongleProtocol.put32(it, 4, earRevision) } +
                            byteArrayOf(4, 0, 4, 0, 6, 0, if (inEars) 0 else 1, if (inEars) 0 else 1)
                    } else if (command == 2) settingValue?.let { value -> ByteArray(15).also {
                        it[0] = 1; it[1] = 1; it[2] = 0x28
                        DongleProtocol.put32(it, 7, 2); DongleProtocol.put32(it, 11, value)
                    } } ?: byteArrayOf(1, 0)
                    else if (command == 1 || command == 8) byteArrayOf()
                    else if (command == 4) {
                        gameRequested = received.toByteArray()[1] != 0.toByte()
                        gameRequests += gameRequested
                        byteArrayOf()
                    }
                    else if (command == 9) ByteArray(20).also {
                        it[0] = 1; it[12] = if (gameRequested) 1 else 0
                        it[14] = input.toByte(); it[15] = if (gameSupported) 8 else 0; it[18] = 100
                    } else ByteArray(24).also {
                        it[0] = 1; it[1] = if (identityAvailable) 0x80.toByte() else 0; it[2] = 127
                        DongleProtocol.put32(it, 20, 0x8000_0000)
                    }
                    report[21] = 3; report[16] = nearby.size.toByte(); report[20] = nearby.size.toByte()
                    nearby.copyInto(report, 22)
                }
                5 -> { cancellations++; report[21] = 4; report[22] = 8 }
            }
            return report.copyOf()
        }
    }
}
