package me.kavishdevar.librepods.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

class LiveAccommodationsEditorTest {
    @Test fun identicalFreshReportBeforeSendKeepsTheQueuedChoiceAndItsOwnerRevision() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        })
        try {
            val rig = Rig(scope)
            val revision = rig.editor.state.value.revision
            assertTrue(rig.edit { it.copy(phone = true) })
            rig.replace(initialPacket(), 2)
            assertEquals(revision, rig.editor.state.value.revision)
            assertTrue(rig.editor.state.value.requested!!.selection.phone)
            assertEquals(2L, rig.source.get().report!!.receptionRevision)
            assertTrue(rig.calls.tryReceive().isFailure)
        } finally {
            scope.cancel()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test fun silentSentAndRepeatedFlagsKeepTheReceivedRecordSeparate() = runBlocking {
        fixture { rig ->
            val original = rig.source.get().report!!
            assertTrue(rig.edit { it.copy(phone = true) })
            val first = rig.next()
            assertEquals(0L, first.version)
            assertFalse(first.curves)
            rig.sent(first)
            rig.settled()
            assertSame(original, rig.source.get().report)
            assertFalse(rig.source.get().received!!.phoneEnabled)
            assertTrue(rig.editor.state.value.displayed!!.selection.phone)
            assertTrue(rig.edit { it.copy(phone = false, media = true) })
            val second = rig.next()
            assertEquals(1L, second.version)
            assertSame(original, second.report)
            assertFalse(second.curves)
            assertFalse(second.choice.phone)
            assertTrue(second.choice.media)
            rig.sent(second)
            rig.settled()
            assertArrayEquals(original.packet(), rig.source.get().report!!.packet())
            assertEquals(2L, rig.source.get().request.editRevision)
            val acceptedRevision = rig.editor.state.value.revision
            rig.replace(original.packet(), 2)
            assertTrue(rig.editor.state.value.revision > acceptedRevision)
            assertNull(rig.editor.state.value.requested)
            assertEquals(original.packet().toList(), rig.editor.state.value.displayed!!.packet)
            assertEquals(AccommodationsSelection(false, false, null), rig.editor.state.value.displayed!!.selection)
            assertTrue(rig.calls.tryReceive().isFailure)
        }
    }

    @Test fun curveChoiceAndFlagsCoalesceAndLaterFlagsDoNotReimportTheCurves() = runBlocking {
        fixture { rig ->
            val preset = AccommodationsPreset(AccommodationsTuning.BRIGHTNESS, AccommodationsLevel.STRONG)
            assertTrue(rig.edit(curves = true) { it.copy(preset = preset, audiogram = false) })
            assertTrue(rig.edit { it.copy(phone = true) })
            val first = rig.next()
            assertTrue(first.curves)
            assertEquals(preset, first.choice.preset)
            assertTrue(first.choice.phone)
            assertTrue(rig.edit { it.copy(media = true) })
            rig.sent(first)
            val second = rig.next()
            assertFalse(second.curves)
            assertEquals(preset, second.choice.preset)
            assertTrue(second.choice.phone && second.choice.media)
            val intended = first.report.plan(phone = true, media = true, preset = preset).packet()
                .also { it[9] = first.report.packet()[9] }
            assertEquals(intended.toList(), rig.editor.state.value.displayed!!.packet)
            rig.sent(second)
            rig.settled()
        }
    }

    @Test fun matchingOwnFreshReportKeepsTheNextIntentAndRebasesItsReceivedRevision() = runBlocking {
        fixture { rig ->
            rig.edit { it.copy(phone = true) }
            val first = rig.next()
            rig.edit { it.copy(media = true) }
            val revision = rig.editor.state.value.revision
            val packet = first.report.plan(first.choice.phone, first.choice.media, first.choice.preset).packet()
                .also { it[9] = first.report.packet()[9] }
            rig.replace(packet, 2, pending = true)
            assertEquals(revision, rig.editor.state.value.revision)
            rig.confirmed(first)
            val second = rig.next()
            assertEquals(2L, second.report.receptionRevision)
            assertTrue(second.choice.phone && second.choice.media)
            rig.sent(second)
            rig.settled()
        }
    }

    @Test fun foreignUnknownCurveReportRetiresQueuedIntentAndOldFailureCannotRestoreIt() = runBlocking {
        fixture { rig ->
            rig.edit { it.copy(phone = true) }
            val first = rig.next()
            rig.edit { it.copy(media = true) }
            val packet = initialPacket().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(12, 11f) }
            rig.replace(packet, 2)
            val revision = rig.editor.state.value.revision
            assertNull(rig.editor.state.value.requested)
            assertNull(rig.editor.state.value.displayed!!.selection.preset)
            assertEquals(packet.toList(), rig.editor.state.value.displayed!!.packet)
            first.result.complete(AccommodationsResult.NOT_SENT)
            delay(200)
            assertEquals(revision, rig.editor.state.value.revision)
            assertTrue(rig.calls.tryReceive().isFailure)
        }
    }

    @Test fun hydratedAudiogramClassificationEmitsWithoutChangingAnyPacketAndFlagsKeepIt() = runBlocking {
        fixture { rig ->
            val before = rig.editor.state.value.displayed!!
            assertFalse(before.selection.audiogram)
            val profile = ByteArray(104).also { byteArrayOf(2, 2, 0x60, 0).copyInto(it) }
            val audiogram = requireNotNull(AccommodationsAudiogram.parse(profile, 7))
            rig.source.set(rig.source.get().copy(audiogram = audiogram,
                received = ReceivedAccommodations.parse(initialPacket(), 7, 1, audiogram)))
            rig.editor.observe()
            val after = rig.editor.state.value.displayed!!
            assertEquals(before.packet, after.packet)
            assertTrue(after.selection.audiogram)
            assertNull(after.selection.preset)
            assertTrue(rig.edit { it.copy(phone = true) })
            val call = rig.next()
            assertTrue(call.choice.audiogram)
            assertFalse(call.curves)
            rig.sent(call)
            rig.settled()
        }
    }

    @Test fun failedOldCallbackCannotReleaseAnIdenticalSuccessorInTheSamePhysicalOwner() = runBlocking {
        fixture { rig ->
            rig.edit { it.copy(phone = true) }
            val retired = rig.next()
            rig.replace(initialPacket().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(12, 9f) }, 2)
            rig.replace(initialPacket(), 3)
            val revision = rig.editor.state.value.revision
            assertTrue(rig.edit { it.copy(phone = true) })
            retired.result.complete(AccommodationsResult.NOT_SENT)
            val successor = rig.next()
            assertEquals(revision, rig.editor.state.value.revision)
            assertEquals(3L, successor.report.receptionRevision)
            assertTrue(successor.choice.phone)
            rig.sent(successor)
            rig.settled()
        }
    }

    private class Call(val report: AccommodationsReport, val version: Long,
        val choice: AccommodationsSelection, val curves: Boolean,
        val result: CompletableDeferred<AccommodationsResult> = CompletableDeferred())

    private class Rig(scope: CoroutineScope) {
        val source = AtomicReference(createSource(initialPacket(), 1))
        val calls = Channel<Call>(Channel.UNLIMITED)
        val editor = LiveAccommodationsEditor(scope, source::get) { _, report, version, choice, curves ->
            val call = Call(report, version, choice, curves)
            calls.send(call)
            call.result.await()
        }
        fun edit(curves: Boolean = false, change: (AccommodationsSelection) -> AccommodationsSelection) =
            editor.edit(editor.state.value.revision, curves, change)
        suspend fun next() = withTimeout(2_000) { calls.receive() }
        fun sent(call: Call) {
            val current = source.get()
            source.set(current.copy(request = AccommodationsRequestState(false, AccommodationsResult.SENT,
                call.report.receptionRevision, call.choice, current.request.editRevision + 1)))
            call.result.complete(AccommodationsResult.SENT)
        }
        fun confirmed(call: Call) {
            source.set(source.get().copy(request = AccommodationsRequestState(false, AccommodationsResult.CONFIRMED)))
            call.result.complete(AccommodationsResult.CONFIRMED)
        }
        fun replace(packet: ByteArray, revision: Long, pending: Boolean = false) {
            val current = source.get()
            source.set(createSource(packet, revision).copy(owner = current.owner,
                request = AccommodationsRequestState(pending), audiogram = current.audiogram))
            editor.observe()
        }
        suspend fun settled() = withTimeout(2_000) {
            while (editor.state.value.pending || editor.state.value.requested != null) delay(5)
        }
    }

    private suspend fun fixture(block: suspend (Rig) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(Rig(scope)) } finally { scope.cancel() }
    }

    companion object {
        private fun initialPacket() = ByteArray(140).also {
            byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 0, 2, 2).copyInto(it)
        }
        private fun createSource(packet: ByteArray, revision: Long) = LiveAccommodationsSource(Any(),
            requireNotNull(AccommodationsReport.parse(packet, 7, revision, false, false)),
            requireNotNull(ReceivedAccommodations.parse(packet, 7, revision)), AccommodationsRequestState(), true)
    }
}
