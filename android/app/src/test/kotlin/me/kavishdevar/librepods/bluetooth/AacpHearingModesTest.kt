package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.ATTManager
import me.kavishdevar.librepods.core.bluetooth.att.types.accommodationsTransparencyPreset
import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [HearingModeSocketShadow::class])
class AacpHearingModesTest {
    @Test fun receivedZeroEnrollmentRetainsItsMeaningAndRetiredSessionsCannotRepublishIt() = runBlocking<Unit> {
        withFixture { fixture ->
            val incoming = fixture.legacyReport().also { it[9] = 0; it[10] = 2; it[11] = 1 }
            fixture.device.aacp.processPacket(incoming, fixture.socket)
            val current = fixture.device.state.value
            val received = current.hearingModes.receivedAccommodations(current.controlSession)!!
            assertEquals(0, received.rawEnrollment)
            assertFalse(received.phoneEnabled)
            assertTrue(received.mediaEnabled)
            assertNotNull(current.hearingModes.accommodationsReport(current.controlSession))
            assertTrue(current.accommodationsRequest.editable(current.hearingModes.accommodationsReport(current.controlSession)))
            assertTrue(fixture.shadow.output.packets.isEmpty())
            fixture.device.aacp.disconnect()
            val retired = fixture.device.state.value
            assertEquals(0L, retired.controlSession)
            assertFalse(retired.hearingModes.hasReceivedPme)
            assertNull(retired.hearingModes.receivedAccommodations(retired.controlSession))
            fixture.device.aacp.processPacket(incoming, fixture.socket)
            assertNull(fixture.device.state.value.let { it.hearingModes.receivedAccommodations(it.controlSession) })
        }
    }

    @Test fun legacyPhoneMediaUsesFullPmeAndOnlyFreshReceivedBytesConfirm() = runBlocking<Unit> {
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(AccommodationsAtt()::answer)).use { withFixture { fixture ->
            val original = fixture.legacyReport()
            val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
            fixture.shadow.output.onPacket = { packet ->
                assertTrue(fixture.device.state.value.accommodationsRequest.pending)
                assertFalse(fixture.device.aacp.requestMicrophoneStream())
                assertNull(fixture.device.aacp.prepareControlSessionRefresh())
                fixture.device.aacp.processPacket(packet, fixture.socket)
            }
            assertEquals(AccommodationsResult.CONFIRMED, fixture.device.setAccommodations(expected, false, true, null))
            val requested = fixture.shadow.output.packets.single()
            assertEquals(2.toByte(), requested[10]); assertEquals(1.toByte(), requested[11])
            for (index in original.indices) if (index !in 10..11) assertEquals("offset $index", original[index], requested[index])
            assertFalse(fixture.device.state.value.accommodationsRequest.pending)
            assertEquals(AccommodationsResult.CONFIRMED, fixture.device.state.value.accommodationsRequest.result)
            assertEquals(0, fixture.shadow.closes)
        } }
    }

    @Test fun legacyPartialSendStaysUnconfirmedAndCannotReuseItsOldBaseline() = runBlocking<Unit> {
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(AccommodationsAtt()::answer)).use { withFixture { fixture ->
            fixture.legacyReport()
            val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
            fixture.shadow.output.onPacket = { throw IOException("partial transport write") }
            assertEquals(AccommodationsResult.UNCONFIRMED, fixture.device.setAccommodations(expected, false, true, null))
            assertEquals(AccommodationsResult.UNCONFIRMED, fixture.device.state.value.accommodationsRequest.result)
            assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, false, true, null))
            assertEquals(1, fixture.shadow.output.packets.size)
            assertEquals(0, fixture.shadow.closes)
        } }
    }

    @Test fun zeroEnrollmentCommandUsesTwoAndOnlyTheUnchangedReceivedHeaderConfirms() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                val original = fixture.legacyReport(enrollment = 0)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                fixture.shadow.output.onPacket = { packet ->
                    assertEquals(2.toByte(), packet[9])
                    fixture.device.aacp.processPacket(packet.copyOf().also { it[9] = 0 }, fixture.socket)
                }
                assertEquals(AccommodationsResult.CONFIRMED, fixture.device.setAccommodations(expected, false, true, null))
                val command = fixture.shadow.output.packets.single()
                for (offset in original.indices) if (offset !in 9..11) assertEquals(original[offset], command[offset])
                assertEquals(0.toByte(), fixture.device.state.value.hearingModes.receivedAccommodations(
                    fixture.device.state.value.controlSession)!!.rawEnrollment.toByte())
                val received = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                assertEquals(AccommodationsResult.NO_CHANGE, fixture.device.setAccommodations(received, false, true, null))
                assertEquals(1, fixture.shadow.output.packets.size)
                assertTrue(att.writes.isEmpty())
            }
        }
    }

    @Test fun silentAccommodationsWritesStayOnTheSameSocketAndChainFromRequestedBytes() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                val original = fixture.legacyReport(enrollment = 0)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                val session = fixture.device.state.value.controlSession
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, false, true, null))
                val sent = fixture.device.state.value.accommodationsRequest
                assertEquals(AccommodationsSelection(false, true, null), sent.requested)
                assertEquals(1L, sent.editRevision)
                assertTrue(sent.editable(expected))
                assertArrayEquals(original, fixture.device.state.value.hearingModes.accommodationsReport(session)!!.packet())
                assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, true, null, null))
                assertEquals(AccommodationsResult.NO_CHANGE, fixture.device.setAccommodations(expected, false, true, null, 1))
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, true, null, null, 1))
                assertEquals(2, fixture.shadow.output.packets.size)
                val second = fixture.shadow.output.packets.last()
                assertEquals(1.toByte(), second[10]); assertEquals(1.toByte(), second[11])
                assertArrayEquals(original.copyOfRange(12, 140), second.copyOfRange(12, 140))
                assertEquals(2L, fixture.device.state.value.accommodationsRequest.editRevision)
                assertEquals(session, fixture.device.state.value.controlSession)
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                assertEquals(0, fixture.shadow.closes)
                assertTrue(att.writes.isEmpty())
            }
        }
    }

    @Test fun everyFreshPmeReportSupersedesTheOwnedRequestedRecordWithoutInventingConfirmation() = runBlocking<Unit> {
        for (failure in listOf("same", "requested", "partial", "enrollment2", "enrollment1", "curve", "guard")) {
            val att = AccommodationsAtt()
            mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
                withFixture { fixture ->
                    val original = fixture.legacyReport(enrollment = 0)
                    val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                    assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, false, true, null))
                    val received = if (failure == "same") original else fixture.shadow.output.packets.single().copyOf().also {
                        it[9] = when (failure) { "enrollment2" -> 2; "enrollment1" -> 1; else -> 0 }
                        if (failure == "curve") it[12] = (it[12].toInt() xor 1).toByte()
                    }
                    if (failure == "guard") fixture.device.aacp.processPacket(control(0x33, 1), fixture.socket)
                    else fixture.device.aacp.processPacket(if (failure == "partial") received.copyOf(139) else received, fixture.socket)
                    val state = fixture.device.state.value
                    assertNull(failure, state.accommodationsRequest.requested)
                    assertTrue(failure, state.accommodationsRequest.editRevision > 1)
                    if (failure == "guard") assertEquals(AccommodationsResult.UNCONFIRMED, state.accommodationsRequest.result)
                    else assertNull(failure, state.accommodationsRequest.result)
                    assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, true, true, null, 1))
                    assertEquals(1, fixture.shadow.output.packets.size)
                    assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                    assertEquals(0, fixture.shadow.closes)
                }
            }
        }
    }

    @Test fun changedTransparencyOrClinicalProfileAfterSendCannotAuthorizeARequestedChain() = runBlocking<Unit> {
        for (changed in listOf(ATTHandle.TRANSPARENCY, ATTHandle.HEARING_AID)) {
            val att = AccommodationsAtt()
            mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
                withFixture { fixture ->
                    fixture.legacyReport(enrollment = 0)
                    val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                    fixture.shadow.output.onPacket = {
                        val bytes = if (changed == ATTHandle.TRANSPARENCY) att.transparency else att.clinical
                        bytes[4] = (bytes[4].toInt() xor 1).toByte()
                    }
                    assertEquals(AccommodationsResult.UNCONFIRMED, fixture.device.setAccommodations(expected, false, true, null))
                    assertNull(fixture.device.state.value.accommodationsRequest.requested)
                    assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, true, true, null, 1))
                    assertTrue(att.writes.isEmpty())
                    assertFalse(fixture.device.state.value.accommodationsRequest.pending)
                    assertEquals(0, fixture.shadow.closes)
                }
            }
        }
    }

    @Test fun changedClinicalBaselineRetiresTheSentChainBeforeAnotherWrite() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                fixture.legacyReport(enrollment = 0)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, false, true, null))
                att.clinical[4] = 1
                assertEquals(AccommodationsResult.UNCONFIRMED, fixture.device.setAccommodations(expected, true, true, null, 1))
                assertEquals(1, fixture.shadow.output.packets.size)
                assertNull(fixture.device.state.value.accommodationsRequest.requested)
                assertEquals(2L, fixture.device.state.value.accommodationsRequest.editRevision)
                assertFalse(fixture.device.state.value.accommodationsRequest.editable(expected))
                assertEquals(0, fixture.shadow.closes)
            }
        }
    }

    @Test fun unreportedLegacyIntentCannotAuthorizeClinicalEnableFromTheOldPmeCache() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                val original = fixture.legacyReport(enrollment = 0).also { it[10] = 2; it[11] = 2 }
                fixture.device.aacp.processPacket(control(0x2c, 1, 2), fixture.socket)
                fixture.device.aacp.processPacket(original, fixture.socket)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, true, true, null))
                assertFalse(fixture.device.setHearingAidEnabled(true))
                assertFalse(fixture.device.setMediaAssistEnabled(true))
                assertEquals(1, fixture.shadow.output.packets.size)
                assertTrue(att.writes.isEmpty())
                assertEquals(0, fixture.shadow.closes)
            }
        }
    }

    @Test fun explicitAudiogramUsesTheFreshLegacyNormalizedProfileWithoutWritingClinicalDataOrReconnecting() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        val values = ByteBuffer.wrap(att.clinical).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8) { values.putFloat(4 + it * 4, 10f); values.putFloat(52 + it * 4, 20f) }
        val originalClinical = att.clinical.copyOf()
        val originalCt = att.transparency.copyOf()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                val original = fixture.legacyReport(enrollment = 0)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, null, null, null, audiogram = true))
                val sent = fixture.device.state.value.accommodationsRequest
                assertTrue(sent.requested!!.audiogram)
                assertNull(sent.requested!!.preset)
                val command = fixture.shadow.output.packets.single()
                val pme = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN)
                repeat(32) { assertEquals(15f, pme.getFloat(12 + it * 4), 0f) }
                val ct = ByteBuffer.wrap(att.transparency).order(ByteOrder.LITTLE_ENDIAN)
                for (start in listOf(4, 52)) repeat(8) { assertEquals(15f, ct.getFloat(start + it * 4), 0f) }
                for (offset in originalCt.indices) if (offset !in 4..35 && offset !in 52..83)
                    assertEquals(originalCt[offset], att.transparency[offset])
                assertArrayEquals(originalClinical, att.clinical)
                assertArrayEquals(original, fixture.device.state.value.hearingModes.accommodationsReport(expected.session)!!.packet())
                assertEquals(listOf(ATTHandle.TRANSPARENCY), att.writes)
                assertEquals(0, fixture.shadow.closes)
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                fixture.device.aacp.processPacket(command.copyOf().also { it[9] = 0 }, fixture.socket)
                val actual = fixture.device.state.value
                assertNull(actual.accommodationsRequest.requested)
                assertTrue(actual.hearingModes.receivedAccommodations(actual.controlSession, actual.accommodationsAudiogramSource)!!.audiogram)
            }
        }
    }

    @Test fun receivedPersonalTuningKeepsItsSourceAcrossOffOnWithoutAFirstLocalPresetWrite() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        val preset = AccommodationsPreset(AccommodationsTuning.BALANCED_TONE, AccommodationsLevel.SLIGHT)
        val stock = ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 0, 1, 2).copyInto(it) }
        val curves = AccommodationsReport.parse(stock, 7, 1, false, false)!!.plan(preset = preset).packet().copyOfRange(12, 44)
        curves.copyInto(att.clinical, 4); curves.copyInto(att.clinical, 52)
        val originalClinical = att.clinical.copyOf()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                fixture.legacyReport(enrollment = 0)
                val first = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                fixture.device.aacp.processPacket(first.plan(preset = preset).packet().also { it[9] = 0 }, fixture.socket)
                fixture.device.readATTCharacteristic(ATTHandle.HEARING_AID)
                val current = fixture.device.state.value
                val received = current.hearingModes.receivedAccommodations(current.controlSession, current.accommodationsAudiogramSource)!!
                assertTrue(received.audiogram); assertNull(received.preset)
                val expected = current.hearingModes.accommodationsReport(current.controlSession)!!
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, false, false, null))
                assertEquals(AccommodationsSelection(false, false, null, true), fixture.device.state.value.accommodationsRequest.requested)
                assertEquals(AccommodationsResult.SENT, fixture.device.setAccommodations(expected, true, null, null, 1))
                assertEquals(AccommodationsSelection(true, false, null, true), fixture.device.state.value.accommodationsRequest.requested)
                assertArrayEquals(originalClinical, att.clinical)
                assertEquals(2, fixture.shadow.output.packets.size)
                assertTrue(att.writes.isEmpty())
                assertEquals(0, fixture.shadow.closes)
            }
        }
    }

    @Test fun audiogramRequiresAnEnrolledValidSourceAndCannotBeCombinedWithAStockPreset() = runBlocking<Unit> {
        for (failure in listOf("unenrolled", "invalid", "mixed")) {
            val att = AccommodationsAtt()
            if (failure == "unenrolled") att.clinical[1] = 0
            if (failure == "invalid") ByteBuffer.wrap(att.clinical).order(ByteOrder.LITTLE_ENDIAN).putFloat(36, 2f)
            mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
                withFixture { fixture ->
                    fixture.legacyReport(enrollment = 0)
                    val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                    assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, null, null,
                        if (failure == "mixed") AccommodationsPreset(AccommodationsTuning.BRIGHTNESS, AccommodationsLevel.SLIGHT) else null,
                        audiogram = true))
                    assertTrue(fixture.shadow.output.packets.isEmpty())
                    assertTrue(att.writes.isEmpty())
                    assertNull(fixture.device.state.value.accommodationsRequest.requested)
                }
            }
        }
    }

    @Test fun cancellationAfterSendCannotLeaveAReusableRequestedRecord() = runBlocking<Unit> {
        val att = AccommodationsAtt("cancel")
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                fixture.legacyReport(enrollment = 0)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                try { fixture.device.setAccommodations(expected, false, true, null); fail("Expected cancellation") }
                catch (_: java.util.concurrent.CancellationException) { }
                assertFalse(fixture.device.state.value.accommodationsRequest.pending)
                assertEquals(AccommodationsResult.UNCONFIRMED, fixture.device.state.value.accommodationsRequest.result)
                assertNull(fixture.device.state.value.accommodationsRequest.requested)
                assertEquals(1, fixture.shadow.output.packets.size)
                assertEquals(0, fixture.shadow.closes)
            }
        }
    }

    @Test fun staleAccommodationsOperationCannotClearTheReplacementOwnersProfilesOrResult() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                fixture.legacyReport(enrollment = 0)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                val clinical = att.clinical.copyOf()
                val transparency = att.transparency.copyOf()
                fixture.shadow.output.onPacket = {
                    fixture.device.aacp.disconnect()
                    bind(fixture.device.aacp, replacement)
                    fixture.device.loadInitialState(fixture.device.state.value.copy(hearingAidData = clinical, transparencyData = transparency),
                        fixture.device.settings.value, fixture.device.metadata.value)
                    fixture.legacyReport(enrollment = 0, source = replacement)
                }
                try {
                    assertEquals(AccommodationsResult.UNCONFIRMED, fixture.device.setAccommodations(expected, false, true, null))
                    assertSame(replacement, fixture.device.aacp.captureAudioSocket())
                    assertArrayEquals(clinical, fixture.device.state.value.hearingAidData)
                    assertArrayEquals(transparency, fixture.device.state.value.transparencyData)
                    assertNull(fixture.device.state.value.accommodationsRequest.result)
                    assertNull(fixture.device.state.value.accommodationsRequest.requested)
                } finally { replacement.close() }
            }
        }
    }

    @Test fun nativeCallPreventsAccommodationsWritesAndCallStartingDuringSendRetainsFailure() = runBlocking<Unit> {
        for (startsDuringSend in listOf(false, true)) {
            val att = AccommodationsAtt()
            mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
                withFixture { fixture ->
                    fixture.legacyReport(enrollment = 0)
                    val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                    val audio = fixture.device.context.getSystemService(AudioManager::class.java)
                    if (startsDuringSend) fixture.shadow.output.onPacket = { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
                    else audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    try {
                        assertEquals(if (startsDuringSend) AccommodationsResult.UNCONFIRMED else AccommodationsResult.NOT_SENT,
                            fixture.device.setAccommodations(expected, false, true, null))
                        assertEquals(if (startsDuringSend) 1 else 0, fixture.shadow.output.packets.size)
                        assertFalse(fixture.device.state.value.accommodationsRequest.pending)
                        assertNull(fixture.device.state.value.accommodationsRequest.requested)
                        assertTrue(att.writes.isEmpty())
                    } finally { audio.mode = AudioManager.MODE_NORMAL }
                }
            }
        }
    }

    @Test fun nativePresetRequiresRealAttAndStaleOrRawRequestsCannotBypassTheCoordinator() = runBlocking<Unit> {
        withFixture { fixture ->
            val original = fixture.legacyReport()
            val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
            assertFalse(fixture.device.transparencyTransportAvailable)
            assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, null, null,
                AccommodationsPreset(AccommodationsTuning.BALANCED_TONE, AccommodationsLevel.SLIGHT)))
            assertFalse(fixture.device.aacp.sendRawPacket(original))
            fixture.device.aacp.processPacket(original, fixture.socket)
            assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, false, true, null))
            assertTrue(fixture.shadow.output.packets.isEmpty())
        }
    }

    @Test fun currentCompleteReportsOwnHearingStateAndUnknownFlagsInvalidateKnownValues() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            assertEquals(true, fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertEquals(false, fixture.device.state.value.hearingModes.mediaAssistEnabled)
            fixture.device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 0x53, 0, 1, 1), fixture.socket)
            assertEquals(false, fixture.device.state.value.hearingModes.mediaAssistEnabled)
            fixture.device.aacp.processPacket(control(0x2c, 0, 0), fixture.socket)
            assertNull(fixture.device.state.value.hearingModes.enrolled)
            assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertFalse(fixture.device.state.value.hearingModes.customTransparencyAllowed)
        }
    }

    @Test fun genericControlsCannotBypassTheHearingModeTransaction() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = false, media = false)
            assertFalse(fixture.device.setControlCommand(ControlCommandIdentifier.HEARING_AID, byteArrayOf(1, 1)))
            assertFalse(fixture.device.setControlCommand(ControlCommandIdentifier.HEARING_ASSIST_CONFIG, true))
            assertTrue(fixture.shadow.output.packets.isEmpty())
        }
    }

    @Test fun unavailableAndroidFittingReadCannotEnableModesOrWriteFittingData() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = false, media = false)
            for (mode in HearingMode.entries) {
                assertFalse(when (mode) {
                    HearingMode.HEARING_AID -> fixture.device.setHearingAidEnabled(true)
                    HearingMode.MEDIA_ASSIST -> fixture.device.setMediaAssistEnabled(true)
                })
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.state.value.hearingModes.uncertain)
                assertArrayEquals(byteArrayOf(), fixture.device.state.value.hearingAidData)
                assertTrue(fixture.shadow.output.packets.isEmpty())
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
            }
        }
    }

    @Test fun independentOffPreservesTheOtherModeAndEveryPmeCurveWithoutAttTraffic() = runBlocking<Unit> {
        mockConstruction(ATTManager::class.java).use { construction ->
            for (mode in HearingMode.entries) withFixture { fixture ->
                fixture.report(hearing = true, media = true)
                fixture.shadow.output.onPacket = { fixture.report(hearing = mode != HearingMode.HEARING_AID,
                    media = mode != HearingMode.MEDIA_ASSIST) }
                assertTrue(when (mode) {
                    HearingMode.HEARING_AID -> fixture.device.setHearingAidEnabled(false)
                    HearingMode.MEDIA_ASSIST -> fixture.device.setMediaAssistEnabled(false)
                })
                val written = fixture.shadow.output.packets.single()
                if (mode == HearingMode.HEARING_AID) {
                    assertArrayEquals(control(0x2c, 1, 2), written)
                    assertEquals(true, fixture.device.state.value.hearingModes.mediaAssistEnabled)
                } else {
                    assertArrayEquals(pme(enabled = false), written)
                    assertEquals(true, fixture.device.state.value.hearingModes.hearingAidEnabled)
                }
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.state.value.hearingModes.uncertain)
                verifyNoInteractions(construction.constructed().last())
                assertEquals(0, fixture.shadow.closes)
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
            }
        }
    }

    @Test fun unknownOrActiveHearingModesBlockCustomTransparencyBeforeAttWrite() = runBlocking<Unit> {
        val customTransparency = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).array()
        mockConstruction(ATTManager::class.java).use { construction ->
            for (state in listOf("unknown", "hearing", "media")) withFixture { fixture ->
                if (state != "unknown") fixture.report(hearing = state == "hearing", media = state == "media")
                val att = construction.constructed().last()
                assertFalse(fixture.device.writeATTCharacteristic(ATTHandle.TRANSPARENCY, customTransparency))
                verifyNoInteractions(att)
                assertTrue(fixture.shadow.output.packets.isEmpty())
            }
        }
    }

    @Test fun retiringControlSessionClearsHearingKnowledgeAndOldSocketCannotRestoreIt() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = true)
            assertTrue(fixture.device.aacp.disconnect())
            assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertNull(fixture.device.state.value.hearingModes.mediaAssistEnabled)
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
            bind(fixture.device.aacp, replacement)
            try {
                fixture.report(hearing = true, media = true)
                assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
                assertFalse(fixture.device.state.value.hearingModes.canChange(HearingMode.HEARING_AID, true))
                assertFalse(fixture.device.state.value.hearingModes.customTransparencyAllowed)
            } finally { fixture.device.aacp.disconnect(); replacement.close() }
        }
    }

    @Test fun explicitOffDuringMicrophoneCapturePreservesTheTransportAndMicrophoneRequest() = runBlocking<Unit> {
        mockConstruction(ATTManager::class.java).use { construction ->
            withFixture { fixture ->
                fixture.report(hearing = true, media = true)
                fixture.device.updateMicrophoneState { it.copy(isActive = true) }
                assertTrue(fixture.device.aacp.requestMicrophoneStream(fixture.socket))
                fixture.shadow.output.packets.clear()
                fixture.shadow.output.onPacket = { fixture.report(hearing = false, media = true) }
                assertTrue(fixture.device.setHearingAidEnabled(false))
                assertArrayEquals(control(0x2c, 1, 2), fixture.shadow.output.packets.single())
                assertTrue(fixture.device.state.value.microphoneState.isActive)
                assertTrue(fixture.device.aacp.isMicrophoneRequested())
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                assertEquals(0, fixture.shadow.closes)
                verifyNoInteractions(construction.constructed().single())
            }
        }
    }

    @Test fun partialOffFailureRemainsUncertainUntilExplicitOffHasFreshMatchingReports() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            fixture.shadow.output.onPacket = {
                if (fixture.shadow.output.packets.size == 2) throw IOException("Rejected second mode write")
            }
            assertFalse(fixture.device.setHearingAidEnabled(false))
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
            assertNull(fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertFalse(fixture.device.state.value.hearingModes.customTransparencyAllowed)
            fixture.shadow.output.packets.clear()
            fixture.shadow.output.onPacket = { packet ->
                if (packet.getOrNull(6) == 0x2c.toByte()) fixture.report(hearing = false, media = false)
            }
            assertTrue(fixture.device.setHearingAidEnabled(false))
            assertEquals(2, fixture.shadow.output.packets.size)
            assertArrayEquals(control(0x33, 2), fixture.shadow.output.packets[0])
            assertArrayEquals(control(0x2c, 1, 2), fixture.shadow.output.packets[1])
            assertNull(fixture.device.state.value.hearingModes.uncertain)
            assertEquals(false, fixture.device.state.value.hearingModes.hearingAidEnabled)
        }
    }

    @Test fun pendingHearingModeRejectsNewAndPreviouslyPreparedManualRefreshes() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = true)
            val prepared = requireNotNull(fixture.device.aacp.prepareControlSessionRefresh())
            var confirmed: Boolean? = null
            val request = launch(Dispatchers.Unconfined) { confirmed = fixture.device.setHearingAidEnabled(false) }
            try {
                assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.aacp.prepareControlSessionRefresh())
                assertFalse(fixture.device.aacp.refreshControlSession(prepared) { true })
                assertEquals(0, fixture.shadow.closes)
                assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                fixture.report(hearing = false, media = true)
                request.join()
                assertEquals(true, confirmed)
                assertNull(fixture.device.state.value.hearingModes.pending)
            } finally { request.cancelAndJoin() }
        }
    }

    @Test fun cancellingAnUnconfirmedRequestReleasesPendingAndAllowsExplicitOffRecovery() = runBlocking<Unit> {
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            val request = launch(Dispatchers.Unconfined) { fixture.device.setHearingAidEnabled(false) }
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.pending)
            request.cancelAndJoin()
            assertNull(fixture.device.state.value.hearingModes.pending)
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
            fixture.shadow.output.packets.clear()
            fixture.shadow.output.onPacket = { packet ->
                if (packet.getOrNull(6) == 0x2c.toByte()) fixture.report(hearing = false, media = false)
            }
            assertTrue(fixture.device.setHearingAidEnabled(false))
            assertEquals(2, fixture.shadow.output.packets.size)
            assertNull(fixture.device.state.value.hearingModes.pending)
            assertNull(fixture.device.state.value.hearingModes.uncertain)
            assertEquals(false, fixture.device.state.value.hearingModes.hearingAidEnabled)
            assertEquals(0, fixture.shadow.closes)
            assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
        }
    }

    @Test fun silentAcceptedModeWriteConfirmsOnlyFromCompleteReplacementStartupWithoutRepeatingWrites() = runBlocking<Unit> {
        for (enabled in listOf(false, true)) {
            val att = AccommodationsAtt()
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java)
            mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
                withFixture(replacement) { fixture ->
                    fixture.report(hearing = !enabled, media = false)
                    val startup = AtomicBoolean()
                    val next = Shadow.extract<HearingModeSocketShadow>(replacement)
                    next.output.onPacket = {
                        if (it.getOrNull(4) == 0x4d.toByte() && startup.compareAndSet(false, true)) {
                            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
                            fixture.report(hearing = enabled, media = false, source = replacement)
                        }
                    }
                    assertEquals(true, fixture.device.setHearingAidEnabled(enabled))
                    assertSame(replacement, fixture.device.aacp.captureAudioSocket())
                    assertEquals(if (enabled) 3 else 2, fixture.shadow.output.packets.size)
                    assertTrue(next.output.packets.none { (it.getOrNull(6) == 0x2c.toByte() || it.getOrNull(6) == 0x33.toByte()) && it.getOrNull(4) == 9.toByte() })
                    assertEquals(enabled, fixture.device.state.value.hearingModes.hearingAidEnabled)
                    assertNull(fixture.device.state.value.hearingModes.uncertain)
                    assertTrue(att.writes.isEmpty())
                }
            }
        }
    }

    @Test fun incompleteOrChangedReplacementReportsNeverConfirmTheMode() = runBlocking<Unit> {
        for (failure in listOf("partial", "curve", "enrollment")) {
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java)
            withFixture(replacement) { fixture ->
                fixture.report(hearing = true, media = false)
                val started = CompletableDeferred<Unit>()
                val once = AtomicBoolean()
                Shadow.extract<HearingModeSocketShadow>(replacement).output.onPacket = {
                    if (it.getOrNull(4) == 0x4d.toByte() && once.compareAndSet(false, true)) {
                        fixture.device.aacp.processPacket(control(0x2c, 1, 2), replacement)
                        fixture.device.aacp.processPacket(control(0x33, 2), replacement)
                        if (failure != "partial") fixture.device.aacp.processPacket(pme(false).also {
                            val offset = if (failure == "curve") 12 else 9
                            it[offset] = (it[offset].toInt() xor 1).toByte()
                        }, replacement)
                        started.complete(Unit)
                    }
                }
                val request = launch(Dispatchers.Unconfined) { assertFalse(fixture.device.setHearingAidEnabled(false)) }
                try {
                    withTimeout(5_000) { started.await() }
                    assertTrue(request.isActive)
                } finally { request.cancelAndJoin() }
                assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertSame(replacement, fixture.device.aacp.captureAudioSocket())
            }
        }
    }

    @Test fun cancellingABlockedModeRefreshClosesOnlyItsOwnedReplacementAndRetainsUncertainty() = runBlocking<Unit> {
        val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java)
        val next = Shadow.extract<HearingModeSocketShadow>(replacement)
        next.blockConnect()
        withFixture(replacement) { fixture ->
            fixture.report(hearing = true, media = false)
            val request = launch(Dispatchers.Unconfined) { fixture.device.setHearingAidEnabled(false) }
            try {
                assertTrue(next.connectEntered.await(3, TimeUnit.SECONDS))
            } finally { request.cancelAndJoin() }
            assertFalse(replacement.isConnected)
            assertNull(fixture.device.state.value.hearingModes.pending)
            assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
        }
    }

    @Test fun activeNativeCallOrMicrophoneNeverReopensControlForUnconfirmedOff() = runBlocking<Unit> {
        for (busy in listOf("call", "voip", "microphone")) {
            val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java)
            withFixture(replacement) { fixture ->
                fixture.report(hearing = true, media = false)
                val audio = fixture.device.context.getSystemService(AudioManager::class.java)
                if (busy == "microphone") fixture.device.updateMicrophoneState { it.copy(isActive = true) }
                else audio.mode = if (busy == "call") AudioManager.MODE_IN_CALL else AudioManager.MODE_IN_COMMUNICATION
                try {
                    assertFalse(fixture.device.setHearingAidEnabled(false))
                    assertEquals(0, fixture.shadow.closes)
                    assertSame(fixture.socket, fixture.device.aacp.captureAudioSocket())
                    assertEquals(2, fixture.shadow.output.packets.size)
                } finally { audio.mode = AudioManager.MODE_NORMAL }
            }
        }
    }

    @Test fun aReplacementOwnerCannotBeRefreshedOrConfirmedByTheOldModeRequest() = runBlocking<Unit> {
        val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        withFixture { fixture ->
            fixture.report(hearing = true, media = false)
            val request = launch(Dispatchers.Unconfined) { assertFalse(fixture.device.setHearingAidEnabled(false)) }
            try {
                assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.pending)
                fixture.device.aacp.disconnect()
                bind(fixture.device.aacp, replacement)
                fixture.report(hearing = false, media = false, source = replacement)
                request.join()
                assertSame(replacement, fixture.device.aacp.captureAudioSocket())
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertNull(fixture.device.state.value.hearingModes.uncertain)
                assertTrue(Shadow.extract<HearingModeSocketShadow>(replacement).output.packets.isEmpty())
            } finally { request.cancelAndJoin(); replacement.close() }
        }
    }

    @Test fun aNativeCallStartingDuringRefreshCannotPublishReplacementOrEraseUncertainty() = runBlocking<Unit> {
        val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java)
        withFixture(replacement) { fixture ->
            fixture.report(hearing = true, media = false)
            val audio = fixture.device.context.getSystemService(AudioManager::class.java)
            Shadow.extract<HearingModeSocketShadow>(replacement).afterConnect = { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
            try {
                assertFalse(fixture.device.setHearingAidEnabled(false))
                assertNull(fixture.device.aacp.captureAudioSocket())
                assertFalse(replacement.isConnected)
                assertNull(fixture.device.state.value.hearingModes.pending)
                assertEquals(HearingMode.HEARING_AID, fixture.device.state.value.hearingModes.uncertain)
            } finally { audio.mode = AudioManager.MODE_NORMAL }
        }
    }

    @Test fun nativePresetCoordinatesBothTransportsAndPreservesTheWholeClinicalProfile() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                fixture.legacyReport()
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                val clinical = att.clinical.copyOf()
                val originalTransparency = att.transparency.copyOf()
                val preset = AccommodationsPreset(AccommodationsTuning.BRIGHTNESS, AccommodationsLevel.MODERATE)
                fixture.shadow.output.onPacket = { packet ->
                    assertTrue(fixture.device.state.value.accommodationsRequest.pending)
                    assertFalse(fixture.device.aacp.requestMicrophoneStream())
                    fixture.device.aacp.processPacket(packet, fixture.socket)
                }
                assertEquals(AccommodationsResult.CONFIRMED, fixture.device.setAccommodations(expected, false, true, preset))
                assertArrayEquals(expected.plan(false, true, preset).packet(), fixture.shadow.output.packets.single())
                assertArrayEquals(accommodationsTransparencyPreset(originalTransparency, preset), att.transparency)
                assertArrayEquals(clinical, att.clinical)
                assertEquals(listOf(ATTHandle.TRANSPARENCY), att.writes)
                assertArrayEquals(att.transparency, fixture.device.state.value.transparencyData)
                assertArrayEquals(clinical, fixture.device.state.value.hearingAidData)
                assertFalse(fixture.device.state.value.accommodationsRequest.pending)
            }
        }
    }

    @Test fun nativePresetFailureOrRetiredAttCannotBecomeConfirmedAfterThePmeEcho() = runBlocking<Unit> {
        for (failure in listOf("write", "profile", "retired", "reverted", "cancel")) {
            val att = AccommodationsAtt(failure)
            mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
                withFixture { fixture ->
                    val original = fixture.legacyReport()
                    val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                    fixture.shadow.output.onPacket = { fixture.device.aacp.processPacket(it, fixture.socket) }
                    if (failure == "reverted") att.onFinalClinicalRead = { fixture.device.aacp.processPacket(original, fixture.socket) }
                    try {
                        val result = fixture.device.setAccommodations(expected, false, true,
                            AccommodationsPreset(AccommodationsTuning.BALANCED_TONE, AccommodationsLevel.SLIGHT))
                        assertNotEquals("$failure", "cancel", failure)
                        assertEquals("$failure", AccommodationsResult.UNCONFIRMED, result)
                    } catch (cancelled: java.util.concurrent.CancellationException) {
                        assertEquals("cancel", failure)
                    }
                    assertEquals("$failure", AccommodationsResult.UNCONFIRMED,
                        fixture.device.state.value.accommodationsRequest.result)
                    assertFalse(fixture.device.state.value.accommodationsRequest.pending)
                    assertEquals(listOf(ATTHandle.TRANSPARENCY), att.writes)
                    assertEquals(1, fixture.shadow.output.packets.size)
                    assertArrayEquals(byteArrayOf(), fixture.device.state.value.transparencyData)
                    assertEquals(AccommodationsResult.NOT_SENT, fixture.device.setAccommodations(expected, false, true, null))
                }
            }
        }
    }

    @Test fun nativePresetCanRepairOnlyTransparencyWithoutResendingUnchangedPme() = runBlocking<Unit> {
        val att = AccommodationsAtt()
        mockConstruction(ATTManager::class.java, withSettings().defaultAnswer(att::answer)).use {
            withFixture { fixture ->
                fixture.legacyReport()
                val first = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                val preset = AccommodationsPreset(AccommodationsTuning.VOCAL_RANGE, AccommodationsLevel.STRONG)
                fixture.device.aacp.processPacket(first.plan(preset = preset).packet(), fixture.socket)
                val expected = fixture.device.state.value.let { it.hearingModes.accommodationsReport(it.controlSession) }!!
                assertEquals(AccommodationsResult.CONFIRMED, fixture.device.setAccommodations(expected, null, null, preset))
                assertTrue(fixture.shadow.output.packets.isEmpty())
                assertEquals(listOf(ATTHandle.TRANSPARENCY), att.writes)
            }
        }
    }

    private class AccommodationsAtt(private val failure: String = "") {
        var transparency = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(36, 2f); putFloat(40, -0.5f); putFloat(44, 1f); putFloat(48, 0.75f)
            putFloat(84, -0f); putFloat(88, 0.25f); putFloat(96, 0.5f); putFloat(100, 0.5f)
        }.array()
        val clinical = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(2, 2, 0x60, 0)); putFloat(100, 0.5f)
        }.array()
        val writes = mutableListOf<ATTHandle>()
        var onFinalClinicalRead: (() -> Unit)? = null
        private var token = Any()
        private var owns = true
        private var clinicalReads = 0

        fun answer(call: org.mockito.invocation.InvocationOnMock): Any? = when (call.method.name.substringBefore('$')) {
            "getAvailable" -> true
            "sessionToken" -> token
            "ownsSession" -> owns && call.arguments[0] === token
            "disconnect" -> { token = Any(); owns = true; null }
            "readCharacteristic" -> {
                assertTrue((call.arguments[1] as () -> Boolean).invoke())
                (call.arguments[2] as () -> Unit).invoke()
                when (call.arguments[0]) {
                    ATTHandle.TRANSPARENCY -> transparency.copyOf()
                    ATTHandle.HEARING_AID -> {
                        clinicalReads++
                        if (clinicalReads > 1) onFinalClinicalRead?.invoke()
                        if (clinicalReads > 1 && failure == "cancel") throw java.util.concurrent.CancellationException("final profile read")
                        clinical.copyOf().also { if (clinicalReads > 1 && failure == "profile") it[4] = 1 }
                    }
                    else -> null
                }
            }
            "writeCharacteristic" -> {
                assertEquals(ATTHandle.TRANSPARENCY, call.arguments[0])
                assertTrue((call.arguments[3] as () -> Boolean).invoke())
                assertArrayEquals(transparency, call.arguments[2] as ByteArray)
                writes += call.arguments[0] as ATTHandle
                transparency = (call.arguments[1] as ByteArray).copyOf()
                if (failure == "retired") owns = false
                failure != "write"
            }
            else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
        }
    }

    private suspend fun withFixture(replacement: BluetoothSocket? = null, block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.CONNECTED)
        device.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3,
            modelNumber = "A3063", version1 = "9A348"))
        if (replacement != null) AppleDevice::class.java.getDeclaredField("aacp").apply { isAccessible = true }
            .set(device, AACPManager(device) { replacement })
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        bind(device.aacp, socket)
        val fixture = Fixture(device, socket, Shadow.extract(socket))
        try { block(fixture) } finally { device.aacp.disconnect(); socket.close(); replacement?.close() }
    }

    private class Fixture(val device: AppleDevice, val socket: BluetoothSocket, val shadow: HearingModeSocketShadow) {
        fun legacyReport(enrollment: Byte = 2, source: BluetoothSocket = socket): ByteArray {
            val packet = ByteArray(140).also {
                byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, enrollment, 1, 2).copyInto(it)
                val buffer = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                for (index in 0 until 32) buffer.putFloat(12 + index * 4, index / 3f)
            }
            device.aacp.processPacket(control(0x2c, 2, 0), source)
            device.aacp.processPacket(control(0x33, 2), source)
            device.aacp.processPacket(packet, source)
            return packet
        }
        fun report(hearing: Boolean, media: Boolean, source: BluetoothSocket = socket) {
            device.aacp.processPacket(control(0x2c, 1, if (hearing) 1 else 2), source)
            device.aacp.processPacket(control(0x33, if (hearing || media) 1 else 2), source)
            device.aacp.processPacket(pme(media), source)
        }
    }

    companion object {
        private fun control(id: Int, first: Int, second: Int = 0) =
            byteArrayOf(4, 0, 4, 0, 9, 0, id.toByte(), first.toByte(), second.toByte(), 0, 0)
        private fun pme(enabled: Boolean) = ByteArray(140) { (it * 7).toByte() }.apply {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 1,
                (if (enabled) 1 else 2).toByte(), (if (enabled) 1 else 2).toByte()).copyInto(this)
        }
        private fun bind(manager: AACPManager, socket: BluetoothSocket) {
            AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
        }
    }
}

@Implements(BluetoothSocket::class)
class HearingModeSocketShadow : ClosingBluetoothSocketShadow() {
    val output = HearingModeOutput()
    var closes = 0
    val connectEntered = CountDownLatch(1)
    var afterConnect: (() -> Unit)? = null
    init { setOutputStream(output) }
    @Implementation public override fun connect() { connectEntered.countDown(); super.connect(); afterConnect?.invoke() }
    protected override fun onClosing() {
        closes++
    }
}

class HearingModeOutput : PipedOutputStream() {
    val packets = CopyOnWriteArrayList<ByteArray>()
    var onPacket: ((ByteArray) -> Unit)? = null
    override fun write(data: ByteArray, offset: Int, length: Int) {
        val packet = data.copyOfRange(offset, offset + length)
        packets += packet
        onPacket?.invoke(packet)
    }
    override fun flush() = Unit
}
